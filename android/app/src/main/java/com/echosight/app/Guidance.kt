package com.echosight.app

import kotlin.math.roundToInt

/** 把检测结果转成对盲人友好的、以身体为参照的中文指引。 */
object Guidance {

    const val STEP_METERS = 0.7f      // 成人一步约 0.7 米

    data class Hit(
        val box: DetBox,
        val dist: Float,             // 米
        val angleDeg: Float,         // 负=左，正=右
        val direction: String,       // 正前方 / 左前方 / 你的左侧 …
        val vertical: String,        // 高 / 平视 / 低
        val verticalTip: String     // 完整说法
    )

    /** 按相对身体的角度分级：不使用钟表方向。 */
    private fun directionWords(angle: Float): String = when {
        angle <= -35 -> "你的左侧"
        angle <= -10 -> "左前方"
        angle < 10 -> "正前方"
        angle <= 35 -> "右前方"
        else -> "你的右侧"
    }

    fun buildHit(box: DetBox, frameW: Float, frameH: Float, targetId: Int): Hit {
        val fx = frameW * 0.85f
        val hPx = (box.y2 - box.y1).coerceAtLeast(1f)
        val realH = Labels.REAL_HEIGHT[targetId] ?: Labels.DEFAULT_HEIGHT
        val dist = realH * fx / hPx

        val cx = (box.x1 + box.x2) / 2
        val angle = Math.toDegrees(
            kotlin.math.atan2((cx - frameW / 2).toDouble(),
                              fx.toDouble())).toFloat()

        // 高低位置：以框的竖向中心判断
        val cy = ((box.y1 + box.y2) / 2 / frameH).coerceIn(0f, 1f)
        val verticalTip = when {
            cy < 0.30f -> "位置偏高，大约在你头部以上"
            cy > 0.66f -> "位置偏低，大约在腰部以下，可能在桌面或地面上"
            else -> "和你的视线差不多高"
        }
        val vertical = when {
            cy < 0.30f -> "高处"
            cy > 0.66f -> "低处"
            else -> "平视"
        }
        return Hit(box, dist, angle, directionWords(angle), vertical, verticalTip)
    }

    /** 距离的口语说法。 */
    fun distanceWords(dist: Float): String {
        if (dist < 0.5f) return "不到半米，伸手就可以摸到"
        if (dist < 0.9f) return "大约一步远，${"%.1f".format(dist)}米"
        val steps = (dist / STEP_METERS).roundToInt().coerceAtLeast(1)
        return "大约${steps}步，${"%.1f".format(dist)}米"
    }

    /** 根据方位和距离给出的行动指引；转动幅度也说清楚。 */
    fun actionTip(hit: Hit): String {
        val parts = ArrayList<String>()
        when {
            hit.angleDeg <= -35 -> parts.add("请向左多转一些身体")
            hit.angleDeg <= -10 -> parts.add("请把身体或手机向左转一点")
            hit.angleDeg >= 35 -> parts.add("请向右多转一些身体")
            hit.angleDeg >= 10 -> parts.add("请把身体或手机向右转一点")
        }
        when {
            hit.dist < 0.5f -> parts.add("就在面前，可以伸手摸了")
            hit.dist < 1.2f -> parts.add("再往前走一两步就到了")
            hit.dist < 3f -> parts.add("请朝着这个方向往前走")
        }
        return if (parts.isEmpty()) "目标在正前方，保持现在的方向"
        else parts.joinToString("，")
    }

    /** 第一次/隔较久看到目标：完整描述。 */
    fun fullReport(name: String, hit: Hit, count: Int): String {
        val countPart = if (count > 1) "看到${count}个${name}，最近的一个"
                        else "看到${name}"
        return "$countPart在${hit.direction}，${hit.verticalTip}，" +
               "距离${distanceWords(hit.dist)}。${actionTip(hit)}"
    }

    /** 持续看到时的简短播报：告诉用户进展。 */
    fun briefReport(name: String, hit: Hit, distDelta: Float): String {
        val move = when {
            distDelta < -0.2f -> "你在靠近，继续走"
            distDelta > 0.2f -> "注意，你在远离目标"
            else -> "方向保持得很好"
        }
        return "${name}在${hit.direction}，${distanceWords(hit.dist)}，$move。"
    }

    /** 目标丢失时的提示；last 为最近一次看到的目标。 */
    fun lostReport(name: String, last: Hit?, ageMs: Long): String {
        if (last != null && ageMs < 10_000) {
            val turn = when {
                last.angleDeg <= -35 -> "请拿着手机向左多转一些去找"
                last.angleDeg <= -10 -> "请把手机向左转一点去找"
                last.angleDeg >= 35 -> "请拿着手机向右多转一些去找"
                last.angleDeg >= 10 -> "请把手机向右转一点去找"
                else -> "目标可能被挡住了，请原地慢慢转一圈找"
            }
            return "${name}刚刚还在${last.direction}，$turn。"
        }
        return "暂时看不到${name}，请拿着手机慢慢转动身体，上下左右都扫一遍。"
    }
}
