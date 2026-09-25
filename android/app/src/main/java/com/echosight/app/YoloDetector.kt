package com.echosight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.Collections

/** 一个检测框，坐标为转正后画面的像素坐标。 */
data class DetBox(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val cls: Int, val conf: Float
)

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputSize = 320
    private val confThreshold = 0.3f
    private val iouThreshold = 0.45f

    // 复用的中间缓冲
    private val inputData = FloatArray(3 * inputSize * inputSize)
    private val letterboxBitmap =
        Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val tmpTransform = Matrix()

    init {
        val bytes = context.assets.open("yolo26n.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    /** 置 true 时，下一次 detect() 把当帧压成 JPEG 并回调 [onSnapshot]（供识图兜底用）。 */
    @Volatile var snapshotRequest = false
    var onSnapshot: ((ByteArray) -> Unit)? = null

    /** 相机帧（YUV）→ 转正 Bitmap → 推理；返回框坐标基于转正后画面。 */
    fun detect(image: ImageProxy): List<DetBox> {
        val raw = yuvToBitmap(image)
        val rotated = Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },
            true)
        if (rotated != raw) raw.recycle()

        val boxes = infer(rotated)

        if (snapshotRequest) {
            snapshotRequest = false
            onSnapshot?.invoke(SiliconFlowApi.compressSnapshot(rotated))
        }
        rotated.recycle()
        return boxes
    }

    val frameWidth get() = _frameWidth
    val frameHeight get() = _frameHeight
    private var _frameWidth = 480
    private var _frameHeight = 640

    private fun infer(bitmap: Bitmap): List<DetBox> {
        val w = bitmap.width
        val h = bitmap.height
        _frameWidth = w
        _frameHeight = h

        // letterbox 到 320×320
        val scale = minOf(inputSize.toFloat() / w, inputSize.toFloat() / h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        letterboxCanvas.drawPaint(blackPaint)
        tmpTransform.reset()
        tmpTransform.setScale(scale, scale)
        tmpTransform.postTranslate(padX, padY)
        letterboxCanvas.drawBitmap(bitmap, tmpTransform, null)

        // Bitmap(ARGB) → CHW float, /255
        val pixels = IntArray(inputSize * inputSize)
        letterboxBitmap.getPixels(pixels, 0, inputSize, 0, 0,
            inputSize, inputSize)
        val plane = inputSize * inputSize
        var idx = 0
        for (i in 0 until plane) {
            val c = pixels[i]
            inputData[i] = ((c shr 16) and 0xFF) / 255f
            inputData[plane + i] = ((c shr 8) and 0xFF) / 255f
            inputData[2 * plane + i] = (c and 0xFF) / 255f
            idx++
        }

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(inputData), shape)
        val output = session.run(Collections.singletonMap(inputName, tensor))
        tensor.close()

        // 输出 [1, 84, 2100]：直接从 OnnxTensor 的 FloatBuffer 按索引读取
        //（布局为 CHW，索引 = c*2100+i），避免转成 Java 多维数组
        val outTensor = output.get(0) as OnnxTensor
        val fb = outTensor.floatBuffer
        val anchors = fb.capacity() / 84      // 2100
        val raw = ArrayList<DetBox>()
        for (i in 0 until anchors) {
            var bestScore = confThreshold
            var bestCls = -1
            for (c in 0 until 80) {
                val s = fb.get(4 * anchors + c * anchors + i)
                if (s > bestScore) {
                    bestScore = s
                    bestCls = c
                }
            }
            if (bestCls < 0) continue
            val cx = fb.get(i)
            val cy = fb.get(anchors + i)
            val bw = fb.get(2 * anchors + i)
            val bh = fb.get(3 * anchors + i)

            // 反 letterbox 到原画面坐标
            val x1 = (cx - bw / 2 - padX) / scale
            val y1 = (cy - bh / 2 - padY) / scale
            val x2 = (cx + bw / 2 - padX) / scale
            val y2 = (cy + bh / 2 - padY) / scale
            raw.add(DetBox(
                x1.coerceIn(0f, w.toFloat()),
                y1.coerceIn(0f, h.toFloat()),
                x2.coerceIn(0f, w.toFloat()),
                y2.coerceIn(0f, h.toFloat()),
                bestCls, bestScore))
        }
        outTensor.close()
        output.close()
        return nms(raw)
    }

    private fun nms(boxes: List<DetBox>): List<DetBox> {
        val sorted = boxes.sortedByDescending { it.conf }.toMutableList()
        val keep = ArrayList<DetBox>()
        while (sorted.isNotEmpty()) {
            val cur = sorted.removeAt(0)
            keep.add(cur)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.cls != cur.cls) continue
                if (iou(cur, b) > iouThreshold) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: DetBox, b: DetBox): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        val inter = (ix2 - ix1).coerceAtLeast(0f) *
                (iy2 - iy1).coerceAtLeast(0f)
        val ua = (a.x2 - a.x1) * (a.y2 - a.y1) +
                (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    /** ImageProxy(YUV_420_888) → Bitmap，处理行跨度/UV 交错。 */
    private fun yuvToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val argb = IntArray(width * height)
        for (row in 0 until height) {
            val top = row * width
            val uvRowPos = uvRowStride * (row / 2)
            for (col in 0 until width) {
                val yPos = yRowStride * row + col
                var y = (yBuffer.get(yPos).toInt() and 0xFF) - 16
                if (y < 0) y = 0
                val uvIndex = uvRowPos + uvPixelStride * (col / 2)
                val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v)
                var g = (y1192 - 833 * v - 400 * u)
                var b = (y1192 + 2066 * u)
                r = if (r < 0) 0 else if (r > 262143) 262143 else r
                g = if (g < 0) 0 else if (g > 262143) 262143 else g
                b = if (b < 0) 0 else if (b > 262143) 262143 else b
                argb[top + col] =
                    0xFF000000.toInt() or ((r shl 6) and 0xFF0000) or
                    ((g shr 2) and 0x00FF00) or ((b shr 10) and 0x0000FF)
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }
}
