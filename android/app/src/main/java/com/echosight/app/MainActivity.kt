package com.echosight.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var pushButton: MaterialButton

    private lateinit var detector: YoloDetector
    private val api = VoiceApi(BuildConfig.SENSEAUDIO_KEY)
    private val vision = SiliconFlowApi(BuildConfig.SILICONFLOW_KEY)
    private val ptt = PushToTalk()
    private val ttsExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // ---------------- 目标状态 ----------------
    @Volatile private var targetId: Int? = null
    @Volatile private var standby = false
    private var searchStart = 0L
    private var lastLosePrompt = 0L
    private var lastFoundReport = 0L
    private var ttsCounter = 0

    // 用于进展播报与丢失记忆
    private var lastSpokenDist: Float? = null
    @Volatile private var lastSeenHit: Guidance.Hit? = null
    private var lastSeenTime = 0L

    // 识图兜底（YOLO 找不到时问一次视觉大模型）
    private var lastVisionCheck = 0L
    @Volatile private var visionBusy = false
    @Volatile private var visionTargetId: Int? = null
    @Volatile private var visionTargetCn: String? = null

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true &&
                result[Manifest.permission.RECORD_AUDIO] == true) {
                startEverything()
            } else {
                setStatus("需要相机和麦克风权限才能使用，点这里重新授权",
                    R.color.status_icon_error)
                statusCard.setOnClickListener { requestPermissions() }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        statusIcon = findViewById(R.id.statusIcon)
        pushButton = findViewById(R.id.pushButton)
        applyWindowInsets()

        if (BuildConfig.SENSEAUDIO_KEY.isBlank()) {
            statusText.text = "未配置语音接口 Key，APK 无法使用云端语音，请联系开发者"
            statusIcon.setColorFilter(getColor(R.color.status_icon_error))
        }

        if (hasPermissions()) startEverything() else requestPermissions()
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    /** Android 15 起全面屏强制生效：把系统栏高度补进卡片和按钮的边距。 */
    private fun applyWindowInsets() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            (statusCard.layoutParams as FrameLayout.LayoutParams).topMargin =
                (16 * density).toInt() + bars.top
            (pushButton.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                (44 * density).toInt() + bars.bottom
            statusCard.requestLayout()
            pushButton.requestLayout()
            insets
        }
    }

    private fun startEverything() {
        detector = YoloDetector(this)
        detector.onSnapshot = { jpeg -> handleSnapshot(jpeg) }
        bindCamera()
        bindPushButton()
        speak("回声视见已启动。请问你要寻找什么物品？请按住屏幕下方的大按钮，对着手机说话，说完松手。")
    }

    // ---------------- 相机 ----------------
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    processFrame(image)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val boxes = detector.detect(image)
        overlay.frameWidth = detector.frameWidth
        overlay.frameHeight = detector.frameHeight

        val tid = targetId
        val now = System.currentTimeMillis()

        if (tid == null) {
            overlay.drawBoxes = emptyList()
            setStatus("等待指令：按住下方大按钮说话", R.color.status_card_text)
            return
        }
        val frameW = detector.frameWidth.toFloat()
        val frameH = detector.frameHeight.toFloat()

        val hits = boxes.filter { it.cls == tid }.map {
            Guidance.buildHit(it, frameW, frameH, tid)
        }
        val cn = Labels.CLASS_CN[tid]

        // 待机：只画不播
        if (standby) {
            overlay.drawBoxes = hits.map {
                DrawBox(it.box, "${cn} ${it.direction}")
            }
            setStatus("已找到，安静待命中。要换东西就按住按钮说：找某某",
                R.color.status_icon_found)
            return
        }

        if (hits.isNotEmpty()) {
            searchStart = now
            val nearest = hits.maxBy {
                (it.box.y2 - it.box.y1) * (it.box.x2 - it.box.x1) }
            lastSeenHit = nearest
            lastSeenTime = now

            overlay.drawBoxes = hits.map {
                DrawBox(it.box,
                    "${cn} ${it.direction} ${"%.1f".format(it.dist)}米")
            }
            setStatus("找到${hits.size.let { if (it > 1) "${it}个" else "" }}$cn：" +
                    "${nearest.direction}·${nearest.vertical} " +
                    "${"%.1f".format(nearest.dist)}米",
                R.color.status_icon_found)

            if (now - lastFoundReport > FOUND_REPORT_INTERVAL) {
                val prev = lastSpokenDist
                val phrase = if (prev == null || now - lastFoundReport >
                        FOUND_REPORT_INTERVAL * 1.6f) {
                    Guidance.fullReport(cn, nearest, hits.size)
                } else {
                    Guidance.briefReport(cn, nearest, nearest.dist - prev)
                }
                lastSpokenDist = nearest.dist
                speak(phrase)
                lastFoundReport = now
            }
        } else {
            overlay.drawBoxes = emptyList()
            val age = now - lastSeenTime
            setStatus("寻找$cn 中…", R.color.status_card_text)
            if (now - searchStart > 4000 &&
                now - lastLosePrompt > LOSE_PROMPT_INTERVAL) {
                speak(Guidance.lostReport(cn, lastSeenHit, age))
                lastLosePrompt = now
            }
            maybeAskVision(tid, cn, now)
        }
    }

    // ---------------- 按住说话 ----------------
    private fun bindPushButton() {
        pushButton.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    stopSpeaking()
                    ptt.start()
                    pushButton.backgroundTintList = ColorStateList.valueOf(
                        getColor(R.color.mic_button_recording))
                    pushButton.text = "松开\n识别"
                    setStatus("我在听，说完请松开手指", R.color.status_icon_active)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val wav = ptt.stop()
                    pushButton.backgroundTintList = ColorStateList.valueOf(
                        getColor(R.color.mic_button))
                    pushButton.text = "按住\n说话"
                    if (event.action == MotionEvent.ACTION_UP && wav.size > 1000) {
                        sendForAsr(wav)
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        speak("说话时间太短了，请按住按钮多说一会儿。")
                        setStatus("说话时间太短，请按住按钮多说一会儿",
                            R.color.status_icon_error)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun sendForAsr(wav: ByteArray) {
        setStatus("正在识别，请稍等…", R.color.status_icon_active)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { api.transcribe(wav) }
            when (val cmd = Labels.parseCommand(text)) {
                is Labels.Command.Target -> switchTarget(cmd.classId)
                Labels.Command.Found -> {
                    standby = true
                    speak("好的，我先安静待命。需要找别的东西时，按住按钮说，找，加上物品名字。")
                }
                null -> {
                    if (text.isBlank()) {
                        speak("没有听清，请按住按钮，靠近手机再说一次。")
                        setStatus("没听到内容，请靠近手机再说一次",
                            R.color.status_icon_error)
                    } else {
                        speak("没有听懂“$text”。请按住按钮说，找，加上物品名字，比如找杯子。")
                        setStatus("没听懂“$text”", R.color.status_icon_error)
                    }
                }
            }
        }
    }

    /** 完整的目标切换处理。 */
    private fun switchTarget(newId: Int) {
        val cn = Labels.CLASS_CN[newId]
        val now = System.currentTimeMillis()
        if (newId == targetId && !standby) {
            speak("已经在帮你找$cn 了。")
            return
        }
        targetId = newId
        standby = false
        searchStart = now
        lastLosePrompt = now
        lastFoundReport = now
        lastSpokenDist = null
        lastSeenHit = null
        lastSeenTime = 0L
        setStatus("当前目标：$cn")
        speak("好的，现在帮你寻找$cn。请把手机摄像头对准前方，慢慢转动身体，我会告诉你它在哪里。")
    }

    // ---------------- 识图兜底 ----------------
    /** YOLO 找了几秒还没找到：下一帧抓张快照，问一次视觉大模型（25 秒限流控成本）。 */
    private fun maybeAskVision(tid: Int, cn: String, now: Long) {
        if (BuildConfig.SILICONFLOW_KEY.isBlank() || visionBusy) return
        if (now - searchStart < 6000) return
        if (now - lastVisionCheck < VISION_CHECK_INTERVAL) return
        lastVisionCheck = now
        visionTargetId = tid
        visionTargetCn = cn
        detector.snapshotRequest = true
    }

    /** 在分析线程收到快照：异步问模型，回来播报。期间换目标/进入待命则丢弃。 */
    private fun handleSnapshot(jpeg: ByteArray) {
        val tid = visionTargetId ?: return
        val cn = visionTargetCn ?: return
        visionTargetId = null
        visionTargetCn = null
        visionBusy = true
        lifecycleScope.launch {
            setStatus("YOLO 没找到$cn，正在请 AI 帮忙看画面…",
                R.color.status_icon_active)
            val answer = withContext(Dispatchers.IO) {
                vision.askAboutFrame(jpeg, cn)
            }
            visionBusy = false
            if (answer == null) {
                Log.w("MainActivity", "识图兜底失败")
                return@launch
            }
            if (targetId != tid || standby) return@launch
            val found = answer.startsWith("有") || answer.contains(cn)
            speak("我请AI仔细看了画面：$answer")
            setStatus("AI帮看：$answer",
                if (found) R.color.status_icon_found else R.color.status_card_text)
        }
    }

    // ---------------- 语音播放 ----------------
    @Volatile private var currentPlayer: MediaPlayer? = null

    private fun stopSpeaking() = ttsExecutor.execute {
        currentPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        currentPlayer = null
    }

    private fun speak(text: String) {
        ttsExecutor.execute {
            val wav = api.synthesize(text) ?: return@execute
            if (ptt.isRecording) return@execute
            try {
                currentPlayer?.runCatching {
                    if (isPlaying) stop()
                    release()
                }
                val file = File(cacheDir, "tts_${ttsCounter++}.wav")
                file.writeBytes(wav)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        file.delete()
                        if (currentPlayer === it) currentPlayer = null
                    }
                    prepare()
                }
                currentPlayer = player
                player.start()
            } catch (e: Exception) {
                // 播放失败不影响主流程
            }
        }
    }

    /** iconColorRes 传状态语义色（R.color.status_icon_*），0 表示保持不变。 */
    private fun setStatus(text: String, iconColorRes: Int = 0) = runOnUiThread {
        statusText.text = text
        statusCard.setOnClickListener(null)
        if (iconColorRes != 0) {
            statusIcon.setColorFilter(getColor(iconColorRes))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
    }

    companion object {
        private const val LOSE_PROMPT_INTERVAL = 7000L
        private const val FOUND_REPORT_INTERVAL = 5000L
        private const val VISION_CHECK_INTERVAL = 25000L
    }
}
