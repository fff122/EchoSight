package com.echosight.app

import android.graphics.Bitmap

/**
 * 视觉提示模板跟踪器：Qwen 确认目标并给出框后，把框内区域存成固定尺寸
 * （长边 48）的灰度模板；之后每一帧在上一位置附近、按三档尺度把"搜索区域
 * 缩放到模板尺寸"做归一化互相关（NCC），让框实时跟着物品走。
 *
 * 设计要点（前两版的教训）：
 *  - 模板必须固定小尺寸：随框变大 → NCC 计算量爆炸（数亿次运算卡死线程）；
 *  - 尺度变化靠"缩放搜索区域"实现，而不是缩放模板 → 尺度一致性与计算量兼得；
 *  - 坐标全程 0~1 归一化；NCC < 0.42 视为跟丢，自动停用交回 Qwen——不乱报。
 */
class VisionTracker {

    private var tmpl: FloatArray? = null      // 灰度模板（固定尺寸）
    private var tmplW = 0
    private var tmplH = 0
    private var tmplMean = 0f
    private var tmplStd = 1f
    private var baseWN = 0f                   // 学习时框的归一化宽/高
    private var baseHN = 0f

    /** 最近一次跟踪到的框（归一化 x1,y1,x2,y2）。 */
    @Volatile var lastBoxN: FloatArray? = null
        private set

    /** 是否处于有效跟踪中。 */
    @Volatile var active = false
        private set

    private var startedAt = 0L

    /** 用 Qwen 确认帧启动跟踪。box 为该帧上的像素坐标。 */
    fun start(frame: Bitmap, box: DetBox) {
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()
        lastBoxN = floatArrayOf(box.x1 / fw, box.y1 / fh, box.x2 / fw, box.y2 / fh)
        baseWN = lastBoxN!![2] - lastBoxN!![0]
        baseHN = lastBoxN!![3] - lastBoxN!![1]

        // 模板：框内内容缩放到长边 48 的灰度图
        val cw = (lastBoxN!![2] - lastBoxN!![0]).toInt().coerceIn(8, frame.width)
        val ch = (lastBoxN!![3] - lastBoxN!![1]).toInt().coerceIn(8, frame.height)
        val crop = Bitmap.createBitmap(frame,
            (lastBoxN!![0] * fw).toInt().coerceIn(0, frame.width - cw),
            (lastBoxN!![1] * fh).toInt().coerceIn(0, frame.height - ch),
            cw, ch)
        val longSide = maxOf(crop.width, crop.height)
        val s = if (longSide > 48) 48f / longSide else 1f
        val tw = maxOf(8, (crop.width * s).toInt())
        val th = maxOf(8, (crop.height * s).toInt())
        val scaled = Bitmap.createScaledBitmap(crop, tw, th, true)
        tmpl = toGray(scaled, tw, th).also { g ->
            var sum = 0f
            for (v in g) sum += v
            tmplMean = sum / g.size
            var acc = 0f
            for (v in g) acc += (v - tmplMean) * (v - tmplMean)
            tmplStd = maxOf(1e-3f, kotlin.math.sqrt(acc / g.size))
        }
        tmplW = tw
        tmplH = th
        scaled.recycle()
        crop.recycle()
        startedAt = System.currentTimeMillis()
        active = true
    }

    /** 每帧更新：返回跟踪到的框（帧像素坐标），跟丢返回 null 并自动停用。 */
    fun update(frame: Bitmap): DetBox? {
        val t = tmpl ?: return null
        if (!active) return null

        val workW = 320
        val workH = maxOf(64, (320f * frame.height / frame.width).toInt())
        val work = Bitmap.createScaledBitmap(frame, workW, workH, true)
        val gray = toGray(work, workW, workH)
        work.recycle()

        val nb = lastBoxN ?: return null
        val cx = (nb[0] + nb[2]) / 2f * workW
        val cy = (nb[1] + nb[3]) / 2f * workH

        var best = -1f
        var bestS = 1f
        var bestCx = cx
        var bestCy = cy
        for (s in floatArrayOf(1f, 1.25f, 0.8f)) {
            // 搜索区域（工作坐标）：学习框 × 尺度 + 边距
            val regionW = (baseWN * workW * s).toInt() + 24
            val regionH = (baseHN * workH * s).toInt() + 24
            if (regionW < tmplW || regionH < tmplH) continue
            val rx0 = (cx - regionW / 2f).toInt()
                .coerceIn(0, maxOf(0, workW - regionW))
            val ry0 = (cy - regionH / 2f).toInt()
                .coerceIn(0, maxOf(0, workH - regionH))
            val rw = minOf(regionW, workW - rx0)
            val rh = minOf(regionH, workH - ry0)
            if (rw < tmplW || rh < tmplH) continue

            // 关键：把区域缩放到固定匹配尺寸（目标在区域内恢复到模板大小）
            val region = Bitmap.createBitmap(work, rx0, ry0, rw, rh)
            val mw = tmplW + 24
            val mh = tmplH + 24
            val scaled = Bitmap.createScaledBitmap(region, mw, mh, true)
            val g = toGray(scaled, mw, mh)
            scaled.recycle()
            region.recycle()

            val k = rw.toFloat() / mw               // 匹配空间 → 工作坐标的缩放比
            for (oy in 0..mh - tmplH step 2) {
                for (ox in 0..mw - tmplW step 2) {
                    val score = ncc(g, mw, ox, oy, t)
                    if (score > best) {
                        best = score
                        bestS = s
                        // 匹配空间中心 → 工作坐标中心
                        bestCx = rx0 + (ox + tmplW / 2f) * k
                        bestCy = ry0 + (oy + tmplH / 2f) * k
                    }
                }
            }
        }

        if (best < 0.42f || System.currentTimeMillis() - startedAt > 12000) {
            active = false                                     // 跟丢或超时，交回 Qwen
            return null
        }

        // 框尺寸 = 学习框 × 当前尺度（工作坐标）→ 归一化
        val halfW = (baseWN * workW * bestS / 2f) / workW
        val halfH = (baseHN * workH * bestS / 2f) / workH
        val nx1 = (bestCx / workW - halfW).coerceIn(0f, 1f)
        val ny1 = (bestCy / workH - halfH).coerceIn(0f, 1f)
        val nx2 = (bestCx / workW + halfW).coerceIn(0f, 1f)
        val ny2 = (bestCy / workH + halfH).coerceIn(0f, 1f)
        lastBoxN = floatArrayOf(nx1, ny1, nx2, ny2)
        return DetBox(nx1 * frame.width, ny1 * frame.height,
            nx2 * frame.width, ny2 * frame.height, -1, best)
    }

    fun stop() {
        active = false
        tmpl = null
        lastBoxN = null
    }

    // ---- 内部 ----

    /** 归一化互相关：模板在匹配区域内滑动。 */
    private fun ncc(img: FloatArray, iw: Int, ox: Int, oy: Int, t: FloatArray): Float {
        var sum = 0f
        var iy = oy
        val yEnd = oy + tmplH
        val xEnd = ox + tmplW
        while (iy < yEnd) {
            val row = iy * iw
            var ix = ox
            var ti = (iy - oy) * tmplW
            while (ix < xEnd) {
                sum += img[row + ix]
                ix++
                ti++
            }
            iy++
        }
        val n = tmplW * tmplH
        val mean = sum / n
        var acc = 0f
        var dot = 0f
        iy = oy
        while (iy < yEnd) {
            val row = iy * iw
            var ix = ox
            var ti = (iy - oy) * tmplW
            while (ix < xEnd) {
                val d = img[row + ix] - mean
                acc += d * d
                dot += d * t[ti]
                ix++
                ti++
            }
            iy++
        }
        val std = maxOf(1e-3f, kotlin.math.sqrt(acc / n))
        return dot / (n * tmplStd * std)
    }

    private fun toGray(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val safe = if (bmp.width == w && bmp.height == h) bmp
                   else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        safe.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            out[i] = ((c shr 16 and 0xFF) * 3 +
                      (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 2550f
        }
        if (safe !== bmp) safe.recycle()
        return out
    }
}
