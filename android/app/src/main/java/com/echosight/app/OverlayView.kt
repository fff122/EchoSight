package com.echosight.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 屏幕上要画的一个目标：检测框 + 标注文字。 */
data class DrawBox(val box: DetBox, val title: String)

/** 在相机预览上画框。相机画面按 fitCenter 居中显示，坐标映射保持同样规则。 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var drawBoxes: List<DrawBox> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }
    var frameWidth = 480
    var frameHeight = 640

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.GREEN }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameWidth <= 0 || frameHeight <= 0) return

        val scale = minOf(width.toFloat() / frameWidth,
                          height.toFloat() / frameHeight)
        val contentW = frameWidth * scale
        val contentH = frameHeight * scale
        val offX = (width - contentW) / 2f
        val offY = (height - contentH) / 2f

        for (d in drawBoxes) {
            val b = d.box
            rect.set(
                offX + b.x1 * scale, offY + b.y1 * scale,
                offX + b.x2 * scale, offY + b.y2 * scale)
            canvas.drawRect(rect, boxPaint)

            // 文字底色 + 文字
            val textW = textPaint.measureText(d.title)
            val top = (rect.top - 48f).coerceAtLeast(0f)
            canvas.drawRect(rect.left, top, rect.left + textW + 16f,
                top + 44f, bgPaint)
            canvas.drawText(d.title, rect.left + 8f, top + 35f, textPaint)
        }
    }
}
