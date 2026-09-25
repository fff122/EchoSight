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
    @Volatile private var freeTarget: String? = null   // COCO 之外的物品名
    @Volatile private var standby = false
    private var searchStart = 0L
    private var lastLosePrompt = 0L
    private var lastFoundReport = 0L
    private var ttsCounter = 0

    // 用于进展播报与丢失记忆
    private var lastSpokenDist: Float? = null
    @Volatile private var lastSeenHit: Guidance.Hit? = null
    private var lastSeenTime = 0L

    // 识图兜底（YOLO 找不到或 COCO 外目标时，问一次视觉大模型）
    private var lastVisionCheck = 0L
    @Volatile private var visionBusy = false
    @Volatile private var visionSignature: String? = null
    @Volatile private var visionName: String? = null

    // 帮助对话状态：0=不在帮助中，1=已列功能等"是否播教程"，2=等"哪个功能"
    @Volatile private var helpStage = 0

    private val tutorials = linkedMapOf(
        "找东西" to "按住屏幕下方的大按钮，说，找，加物品名字，比如，找杯子。" +
                "常见物品像椅子、电视、手机，我会实时告诉你方位和距离。" +
                "它不认识的物品，比如耳机、药盒，我也会接单，请AI定期帮你看画面。" +
                "找到后说，找到了，我就安静待命。",
        "待命" to "找到东西以后，说，找到了，我就不播报了。" +
                "想找别的东西，再按住按钮说，找，加新名字。",
        "AI帮看" to "遇到我不认识的物品，或者目标一时找不到，我会自动抓拍画面，" +
                "请AI帮忙看，大约每十五秒一次，看到就告诉你它在画面里的位置。",
        "帮助" to "任何时候按住按钮说，帮助，我会列出所有功能，还能一个个教你用法。"
    )

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

        val ft = freeTarget
        val frameW = detector.frameWidth.toFloat()
        val frameH = detector.frameHeight.toFloat()

        // 待机：只画不播
        if (standby) {
            overlay.drawBoxes = if (tid != null) boxes.filter { it.cls == tid }.map {
                val h = Guidance.buildHit(it, frameW, frameH, tid)
                DrawBox(h.box, "${Labels.CLASS_CN[tid]} ${h.direction}")
            } else emptyList()
            setStatus("已找到，安静待命中。要换东西就按住按钮说：找某某",
                R.color.status_icon_found)
            return
        }

        if (tid == null && ft == null) {
            overlay.drawBoxes = emptyList()
            setStatus("等待指令：按住下方大按钮说话", R.color.status_card_text)
            return
        }

        // COCO 外的自由目标：YOLO 无能为力，定期请 AI 看画面
        if (tid == null) {
            overlay.drawBoxes = emptyList()
            setStatus("寻找$ft 中…（AI 每隔一会儿帮你看一眼画面）",
                R.color.status_icon_active)
            maybeAskVision(now)
            return
        }

        val hits = boxes.filter { it.cls == tid }.map {
            Guidance.buildHit(it, frameW, frameH, tid)
        }
        val cn = Labels.CLASS_CN[tid]

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
            maybeAskVision(now)
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
            // 帮助多轮对话进行中：本轮优先按帮助流程处理
            if (helpStage != 0) {
                handleHelpReply(text)
                return@launch
            }
            when (val cmd = Labels.parseCommand(text)) {
                Labels.Command.Help -> showHelpMenu()
                is Labels.Command.Target -> switchTarget(cmd.classId)
                is Labels.Command.FreeTarget -> switchFreeTarget(cmd.name)
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
        if (newId == targetId && freeTarget == null && !standby) {
            speak("已经在帮你找$cn 了。")
            return
        }
        targetId = newId
        freeTarget = null
        standby = false
        searchStart = now
        lastLosePrompt = now
        lastFoundReport = now
        lastSpokenDist = null
        lastSeenHit = null
        lastSeenTime = 0L
        lastVisionCheck = 0L
        setStatus("当前目标：$cn")
        speak("好的，现在帮你寻找$cn。请把手机摄像头对准前方，慢慢转动身体，我会告诉你它在哪里。")
    }

    /** COCO 之外的目标（耳机、药盒…）：YOLO 不认识，交给识图兜底定期看画面。 */
    private fun switchFreeTarget(name: String) {
        if (BuildConfig.SILICONFLOW_KEY.isBlank()) {
            speak("识图功能没有配置，暂时帮不了你找$name。")
            return
        }
        val now = System.currentTimeMillis()
        if (name == freeTarget && !standby) {
            speak("已经在帮你找$name 了。")
            return
        }
        targetId = null
        standby = false
        freeTarget = name
        searchStart = now
        lastLosePrompt = now
        lastFoundReport = now
        lastSpokenDist = null
        lastSeenHit = null
        lastSeenTime = 0L
        lastVisionCheck = 0L
        setStatus("当前目标：$name（AI帮看）")
        speak("好的，帮你寻找$name。它不在常见物品清单里，我会每隔一会儿请AI帮你看画面，找到后告诉你位置。拿到后说，找到了。")
    }

    // ---------------- 帮助 / 使用教程 ----------------
    /** 列出全部功能，并问是否播报教程。 */
    private fun showHelpMenu() {
        helpStage = 1
        val names = tutorials.keys.toList()
        val list = names.mapIndexed { i, n -> "${'一' + i}，$n" }.joinToString("；")
        speak("本应用共有${names.size}个功能：$list。需要我播报使用教程吗？" +
                "需要就说，是；不需要就说，不用。")
        setStatus("帮助：已列功能，等你说是否要教程", R.color.status_icon_active)
    }

    /** 帮助对话中的一轮回答。说"找XX"等正常指令可直接跳出帮助去执行。 */
    private fun handleHelpReply(text: String) {
        val t = text.replace(" ", "")
        val no = t.contains("不") || t.contains("取消") || t.contains("退出") ||
                t.contains("算了")
        val yes = t.contains("是") || t.contains("要") || t == "好" ||
                t.contains("需要") || t.contains("可以") || t == "行"
        val all = t.contains("全部") || t.contains("挨个") || t.contains("都讲") ||
                t.contains("都听") || t == "都"
        when (helpStage) {
            1 -> when {
                no -> exitHelp()
                yes -> {
                    helpStage = 2
                    speak("你要学哪个功能？${tutorials.keys.joinToString("，")}。" +
                            "说全部，我就从第一个开始挨个讲。")
                }
                else -> if (!executeIfNormalCommand(text)) {
                    speak("没听懂。需要播报使用教程就说，是；不需要就说，不用。")
                }
            }
            2 -> {
                val key = tutorials.keys.firstOrNull { t.contains(it) }
                when {
                    all -> {
                        helpStage = 0
                        speak("好的，下面从第一个功能开始挨个讲，请听。")
                        tutorials.values.forEach { speak(it) }
                        setStatus("帮助：正在播放全部教程", R.color.status_icon_active)
                    }
                    key != null -> {
                        helpStage = 0
                        speak(tutorials[key]!!)
                        setStatus("帮助：${key}教程已播报", R.color.status_icon_active)
                    }
                    no -> exitHelp()
                    else -> if (!executeIfNormalCommand(text)) {
                        speak("没听懂。可以说：${tutorials.keys.joinToString("，")}，" +
                                "或者，全部，或者，退出。")
                    }
                }
            }
        }
    }

    /** 帮助对话里用户直接说了正常指令：退出帮助并执行，返回是否执行了。 */
    private fun executeIfNormalCommand(text: String): Boolean {
        return when (val cmd = Labels.parseCommand(text)) {
            is Labels.Command.Target -> {
                helpStage = 0
                switchTarget(cmd.classId)
                true
            }
            is Labels.Command.FreeTarget -> {
                helpStage = 0
                switchFreeTarget(cmd.name)
                true
            }
            Labels.Command.Found -> {
                helpStage = 0
                standby = true
                speak("好的，我先安静待命。要找别的东西时，按住按钮说，找，加上物品名字。")
                true
            }
            else -> false
        }
    }

    private fun exitHelp() {
        helpStage = 0
        speak("好的，已退出帮助。需要时再按住按钮说，帮助。")
        setStatus("等待指令：按住下方大按钮说话", R.color.status_card_text)
    }

    // ---------------- 识图兜底 ----------------
    /** 当前目标的唯一标识：COCO 目标用类别 id，自由目标用名字；无目标返回 null。 */
    private fun currentSignature(): String? = when {
        targetId != null -> "id:${targetId}"
        freeTarget != null -> "free:${freeTarget}"
        else -> null
    }

    /** 找了几秒还没找到：下一帧抓张快照，问一次视觉大模型（限流控成本）。 */
    private fun maybeAskVision(now: Long) {
        val sig = currentSignature() ?: return
        if (BuildConfig.SILICONFLOW_KEY.isBlank() || visionBusy) return
        if (now - searchStart < 6000) return
        val interval = if (freeTarget != null) FREE_VISION_INTERVAL
                       else VISION_CHECK_INTERVAL
        if (now - lastVisionCheck < interval) return
        val tid = targetId
        val name = freeTarget ?: tid?.let { Labels.CLASS_CN[it] } ?: return
        lastVisionCheck = now
        visionSignature = sig
        visionName = name
        detector.snapshotRequest = true
    }

    /** 在分析线程收到快照：异步问模型，回来播报。期间换目标/进入待命则丢弃。 */
    private fun handleSnapshot(jpeg: ByteArray) {
        val sig = visionSignature ?: return
        val name = visionName ?: return
        visionSignature = null
        visionName = null
        visionBusy = true
        lifecycleScope.launch {
            setStatus("正在请 AI 帮忙看画面，找$name…",
                R.color.status_icon_active)
            val answer = withContext(Dispatchers.IO) {
                vision.askAboutFrame(jpeg, name)
            }
            visionBusy = false
            if (answer == null) {
                Log.w("MainActivity", "识图兜底失败")
                return@launch
            }
            if (standby || currentSignature() != sig) return@launch
            // 注意"没有看到耳机"也包含物品名，只认"有"开头的回答
            val found = answer.startsWith("有")
            val tip = if (found) "拿到后说，找到了。" else ""
            speak("我请AI仔细看了画面：$answer。$tip")
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
        private const val FREE_VISION_INTERVAL = 15000L
    }
}
