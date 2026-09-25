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

/** SiliconFlow 图像问答：YOLO 不认识的物品，兜底问一次视觉大模型。 */
class SiliconFlowApi(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

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
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put(
                    "url", "data:image/jpeg;base64,$b64"))
            })
            .put(JSONObject().apply {
                put("type", "text")
                put("text", "这是一部手机摄像头看到的画面。" +
                        "请仔细看画面里有没有「$itemCn」。" +
                        "如果有：先回答“有，在画面的左上/右上/左下/右下/中间”，" +
                        "再用一句话说明依据。" +
                        "如果没有：先回答“没有看到$itemCn”，" +
                        "再用一句话说明画面里有什么相近或相关的东西。" +
                        "总共不超过两句话，不要猜测距离和运动，不要输出其他内容。")
            })
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
            put("stream", false)
            put("max_tokens", 150)
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
