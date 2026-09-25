package com.echosight.app

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * SiliconFlow 图像问答：YOLO 不认识的物品，兜底问一次视觉大模型；
 * 也是方案A扫描的"眼睛"（短清单 + 打框 + 参考照片比对）。
 */
class SiliconFlowApi(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ---------------- 公共请求 ----------------

    /** 所有视觉调用的公共部分：发图 + 提示词，返回模型文本；失败 null。 */
    private fun chat(content: JSONArray, maxTokens: Int): String? {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
            put("stream", false)
            put("max_tokens", maxTokens)
        }
        val req = Request.Builder()
            .url("$BASE/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "识图失败 ${r.code}")
                    return null
                }
                val text = JSONObject(r.body!!.string())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (text.isEmpty()) null else text
            }
        } catch (e: Exception) {
            Log.w(TAG, "识图异常 $e")
            null
        }
    }

    private fun singleImageContent(jpeg: ByteArray, prompt: String): JSONArray =
        JSONArray()
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put(
                    "url", "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}"))
            })
            .put(JSONObject().apply { put("type", "text"); put("text", prompt) })

    // ---------------- 找东西：开放词汇 ----------------

    /**
     * 问画面里有没有 [itemCn]。返回一句可以直接念出来的短话；失败返回 null。
     * prompt 刻意让模型只描述这一帧：不猜距离、不猜运动，避免和 YOLO 播报打架。
     *
     * 模型选型（2026-09 实测 bus.jpg，3 次平均）：
     *   Qwen3-VL-8B-Instruct      ~35s（排队严重，弃用）
     *   Qwen3-VL-30B-A3B-Instruct ~1.4s，MoE 激活参数仅 3B，速度精度兼顾 ← 用这个
     *   Qwen3-VL-32B-Instruct     ~1.8s，略慢，无精度优势
     */
    fun askAboutFrame(jpeg: ByteArray, itemCn: String): String? {
        val prompt = "这是一部手机摄像头看到的画面。" +
                "请仔细看画面里有没有「$itemCn」。" +
                "如果有：先回答“有，在画面的左上/右上/中间/左下/右下”，" +
                "再用一句话说明依据。" +
                "如果没有：先回答“没有看到$itemCn”，" +
                "再用一句话说明画面里有什么相近或相关的东西。" +
                "总共不超过两句话，不要猜测距离和运动，不要输出其他内容。"
        return chat(singleImageContent(jpeg, prompt), 150)
    }

    /**
     * 两图对比找登记过的物品：第一张参考照片，第二张当前画面。
     * 有参考照片时优先走这里，比开放词汇准得多——盲人的东西就那几样，
     * 登记一次，以后按照片认，不用担心长得不一样的同款。
     */
    fun findReference(refJpeg: ByteArray, frameJpeg: ByteArray, itemCn: String): String? {
        val content = JSONArray()
        for (b in arrayOf(refJpeg, frameJpeg)) {
            val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", "第一张图是登记过的「$itemCn」的照片。第二张是现在的画面。" +
                    "画面里有没有和第一张相同或同款的$itemCn？" +
                    "有：先回答“有，在画面的左上/右上/中间/左下/右下”，再一句话说明。" +
                    "没有：先回答“没有看到”。不超过两句话，不要猜距离和运动。")
        })
        return chat(content, 150)
    }

    // ---------------- 扫描：短清单 + 打框 ----------------

    /** 扫描途中的短清单：只要物品名。输出短所以快（约 5-8 秒）。 */
    fun listItems(jpeg: ByteArray): List<String> {
        val text = chat(singleImageContent(jpeg,
            "列出画面里所有看得清的物品名，只用中文顿号分隔，最多6个，" +
            "不要数量词，不要解释，不要坐标，不要JSON。"), 100)
            ?: return emptyList()
        return text.replace("```", "")
            .split('、', '，', ',', ' ', '\n')
            .map { it.trim().trimEnd('。', '.') }
            .filter { it.isNotEmpty() && it.length <= 6 }
    }

    /** 打框结果：物品名 + 0~1000 归一化坐标 + 方位词。 */
    data class Grounded(val item: String, val box: List<Float>, val pos: String)

    /**
     * 房间收尾打框：物品名 + 坐标框。输出长所以慢（10-70 秒），
     * 只在每个房间扫完时调用一次。注意实测模型不一定守"最多N个"的约束，
     * max_tokens 给足并容忍 JSON 被截断。
     */
    fun groundItems(jpeg: ByteArray): List<Grounded> {
        val text = chat(singleImageContent(jpeg,
            "仔细看图，找出最多8个清晰的物品。只输出JSON数组，不要其他文字：" +
            "[{\"item\":\"中文名\",\"box\":[x1,y1,x2,y2],\"pos\":\"左上/右上/中间/左下/右下\"}]。" +
            "坐标0到1000归一化，x1y1是左上角，x2y2是右下角。"), 700)
            ?: return emptyList()
        return runCatching {
            val clean = text.replace("```json", "").replace("```", "").trim()
            val s = clean.indexOf('[')
            val e = clean.lastIndexOf(']')
            if (s < 0 || e <= s) return emptyList()
            val arr = JSONArray(clean.substring(s, e + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("box").let { ba ->
                    (0 until ba.length()).map { ba.getDouble(it).toFloat() } }
                if (b.size < 4) null
                else Grounded(o.getString("item").trim(), b, o.optString("pos", ""))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "SiliconFlowApi"
        private const val BASE = "https://api.siliconflow.cn"
        private const val MODEL = "Qwen/Qwen3-VL-30B-A3B-Instruct"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 快照压到最长边 640 的 JPEG，识别够用且体积小（几十 KB）。 */
        fun compressSnapshot(src: Bitmap, quality: Int = 70): ByteArray {
            val maxSide = maxOf(src.width, src.height)
            val scale = if (maxSide > 640) 640f / maxSide else 1f
            val small = if (scale < 1f) Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1), true) else src
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (small !== src) small.recycle()
            return bytes
        }
    }
}
