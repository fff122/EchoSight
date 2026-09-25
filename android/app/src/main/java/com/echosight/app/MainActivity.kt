package com.echosight.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.ByteArrayOutputStream
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
    @Volatile private var pendingSnapshotUse = ""   // "find"=找目标 / "scan"=扫描
    private var lastVisionFoundPos: String? = null
    private var lastVisionFoundTime = 0L

    // ---------------- 方案A：引导扫描 + 记忆 ----------------
    // 0=空闲 1=问房间 2=扫描中 3=收尾打框
    @Volatile private var scanStage = 0
    @Volatile private var scanRoom: String? = null
    @Volatile private var currentRoom: String? = null
    private val scanItems = LinkedHashSet<String>()
    @Volatile private var lastScanCapture = 0L
    @Volatile private var lastRoomSnapshot: ByteArray? = null

    // 帮助对话状态：0=不在帮助中，1=已列功能等"是否播教程"，2=等"哪个功能"
    @Volatile private var helpStage = 0

    private val tutorials = linkedMapOf(
        "找东西" to "按住屏幕下方的大按钮，说，找，加物品名字，比如，找杯子。" +
                "常见物品像椅子、电视、手机，我会实时告诉你方位和距离。" +
                "它不认识的物品，比如耳机、药盒，我也会接单，请AI定期帮你看画面；" +
                "如果它登记过照片，我会按照片帮你认。找到后说，找到了，我就安静待命。",
        "扫描房间" to "按住按钮说，扫描。我先问这是哪个房间，你回答后，" +
                "拿着手机慢慢转一圈，我会把看到的东西都登记进记忆，" +
                "它们在哪个房间也记住了。扫完说，扫完了。" +
                "以后找它，我会先告诉你它在哪个房间。",
        "待命" to "找到东西以后，说，找到了，我就不播报了。" +
                "想找别的东西，再按住按钮说，找，加新名字。",
        "AI帮看" to "遇到我不认识的物品，或者目标一时找不到，我会自动抓拍画面，" +
                "请AI帮忙看：你走动时几秒一次，停下来就慢一些。" +
                "看到就告诉你它在画面里的位置；如果刚看到又找不到了，" +
                "我会提醒你放慢脚步往回转。",
        "记忆" to "你可以问我东西在哪，说，耳机在哪；也可以自己登记，说，" +
                "记一下，遥控器在茶几上；不要了就说，忘掉，加名字；" +
                "换了房间就说，我在客厅。",
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
        MemoryStore.init(this)
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

        // 扫描期间接管画面，不做找目标播报
        if (scanStage >= 1) {
            overlay.drawBoxes = emptyList()
            maybeCaptureForScan(now)
            return
        }

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
                MemoryStore.touch(cn, currentRoom)
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
            // 多轮对话进行中：帮助 > 扫描 > 正常指令
            if (helpStage != 0) {
                handleHelpReply(text)
                return@launch
            }
            if (scanStage != 0 && handleScanReply(text)) return@launch
            executeCommand(Labels.parseCommand(text), text)
        }
    }

    private fun executeCommand(cmd: Labels.Command?, rawText: String) {
        when (cmd) {
            Labels.Command.Help -> showHelpMenu()
            Labels.Command.ScanStart -> startScan()
            Labels.Command.ScanDone ->
                if (scanStage == 2) finishScan()
                else speak("现在没有在扫描。要开始就说，扫描。")
            Labels.Command.ScanCancel ->
                if (scanStage != 0) cancelScan()
                else speak("现在没有在扫描。")
            is Labels.Command.MemoryWhere -> answerWhere(cmd.name)
            is Labels.Command.MemoryNote -> rememberNote(cmd.item, cmd.spot)
            is Labels.Command.MemoryForget -> forgetItem(cmd.name)
            is Labels.Command.RoomHere -> {
                currentRoom = cmd.room
                speak("好的，现在在${cmd.room}。")
                setStatus("所在房间：${cmd.room}", R.color.status_card_text)
            }
            is Labels.Command.Target -> switchTarget(cmd.classId)
            is Labels.Command.FreeTarget -> switchFreeTarget(cmd.name)
            Labels.Command.Found -> {
                standby = true
                speak("好的，我先安静待命。需要找别的东西时，按住按钮说，找，加上物品名字。")
            }
            null -> {
                if (rawText.isBlank()) {
                    speak("没有听清，请按住按钮，靠近手机再说一次。")
                    setStatus("没听到内容，请靠近手机再说一次",
                        R.color.status_icon_error)
                } else {
                    speak("没有听懂“$rawText”。请按住按钮说，找，加上物品名字，比如找杯子。" +
                            "想登记家里物品就说，扫描。")
                    setStatus("没听懂“$rawText”", R.color.status_icon_error)
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
        val rec = MemoryStore.find(cn)
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
        val hint = when {
            rec != null && rec.spot.isNotBlank() -> "记忆里它在${rec.room}的${rec.spot}。"
            rec != null -> "记忆里它上次出现在${rec.room}。"
            else -> ""
        }
        speak("${hint}好的，现在帮你寻找$cn。请把手机摄像头对准前方，慢慢转动身体，我会告诉你它在哪里。")
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
        val rec = MemoryStore.find(name)
        val hint = when {
            rec != null && rec.spot.isNotBlank() -> "记忆里它在${rec.room}的${rec.spot}。"
            rec != null -> "记忆里它上次出现在${rec.room}。"
            MemoryStore.refPhoto(name) != null -> "它有登记照片，我会按照片帮你认。"
            else -> "记忆里还没有它，我会定期请AI帮你看画面。"
        }
        setStatus("当前目标：$name（AI帮看）")
        speak("好的，帮你寻找$name。$hint 找到后告诉你位置。拿到后说，找到了。")
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

    // ---------------- 方案A：引导扫描 + 记忆 ----------------
    /** 说"扫描"开始：先取消找目标，问房间名。 */
    private fun startScan() {
        targetId = null
        freeTarget = null
        standby = false
        helpStage = 0
        scanStage = 1
        scanRoom = null
        scanItems.clear()
        lastRoomSnapshot = null
        lastScanCapture = 0L
        speak("好的，开始扫描。这是哪个房间？比如客厅、卧室、厨房。说取消就不扫了。")
        setStatus("扫描：请告诉我这是哪个房间", R.color.status_icon_active)
    }

    private fun cancelScan(silent: Boolean = false) {
        scanStage = 0
        scanRoom = null
        scanItems.clear()
        lastRoomSnapshot = null
        if (!silent) speak("好，扫描已取消。")
        setStatus("等待指令：按住下方大按钮说话", R.color.status_card_text)
    }

    /** 扫描多轮对话（问房间 / 扫描中）。返回 true 表示这句话已消费。 */
    private fun handleScanReply(text: String): Boolean {
        val t = text.replace(" ", "")
        when (scanStage) {
            1 -> {
                if (t.contains("取消") || t == "不") {
                    cancelScan()
                    return true
                }
                val room = Labels.matchRoom(t) ?: Labels.plausibleItemName(t)
                if (room == null) {
                    speak("没听清房间名。这是哪个房间？比如客厅、卧室、厨房。说取消就不扫了。")
                } else {
                    scanRoom = room
                    currentRoom = room
                    scanStage = 2
                    lastScanCapture = 0L
                    speak("好，开始扫描$room。请拿着手机从左往右慢慢转，桌面和地面都扫到。扫完就说，扫完了。")
                    setStatus("正在扫描$room…", R.color.status_icon_active)
                }
                return true
            }
            2 -> {
                val done = t.contains("完") || t.contains("结束") ||
                        t.contains("好了") || t.contains("行了")
                if (t.contains("取消") || (t.contains("不") && !done)) {
                    cancelScan()
                    return true
                }
                if (done) {
                    finishScan()
                    return true
                }
                // 扫描中说了别的明确指令：先取消扫描再执行
                val cmd = Labels.parseCommand(text)
                if (cmd != null && cmd !is Labels.Command.ScanStart) {
                    cancelScan(silent = true)
                    executeCommand(cmd, text)
                    return true
                }
                return false
            }
        }
        return false
    }

    /** 扫描中的采样节流：每 8 秒最多一次，且画面要有变化才花钱问 AI。 */
    private fun maybeCaptureForScan(now: Long) {
        if (scanStage != 2 || visionBusy) return
        if (now - lastScanCapture < 8000) return
        if (detector.sceneChangeScore < 0.05f) return
        lastScanCapture = now
        pendingSnapshotUse = "scan"
        detector.snapshotRequest = true
    }

    /** 扫描快照：短清单登记本房间物品，逐批口播。 */
    private fun processScanSnapshot(jpeg: ByteArray) {
        val room = scanRoom ?: return
        visionBusy = true
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { vision.listItems(jpeg) }
            visionBusy = false
            if (scanStage != 2) return@launch
            val fresh = list.filter { it !in scanItems }
            if (fresh.isEmpty()) return@launch
            fresh.forEach { scanItems.add(it) }
            speak("看到${fresh.joinToString("、")}")
            setStatus("扫描$room：已看到${scanItems.size}样",
                R.color.status_icon_active)
        }
    }

    /** 房间收尾：打框 + 裁参考照片 + 写入记忆库。 */
    private fun finishScan() {
        val room = scanRoom
        val snap = lastRoomSnapshot
        if (room == null) {
            cancelScan()
            return
        }
        if (scanItems.isEmpty() || snap == null) {
            speak("$room 还没看清东西，请再慢慢转一圈，或者说，取消扫描。")
            return   // 留在扫描中
        }
        scanStage = 3
        visionBusy = true
        setStatus("正在给$room 的物品打框登记，请稍等…", R.color.status_icon_active)
        lifecycleScope.launch {
            val grounded = withContext(Dispatchers.IO) {
                vision.groundItems(snap)
            }.filter { it.item.length <= 6 }
            visionBusy = false
            if (scanStage != 3) return@launch   // 期间被取消

            // 参考照片：按框裁剪保存——盲人的东西就那几样，登记一次，
            // 以后找它按照片比对（两图对比），比开放词汇准；也是将来微调 YOLO 的数据
            val refSaved = saveReferenceCrops(snap, grounded)

            // 登记：以打框结果为准（带方位词），短清单里没打上框的也补上
            val now = System.currentTimeMillis()
            val names = LinkedHashSet<String>()
            grounded.forEach { names.add(it.item) }
            scanItems.forEach { names.add(it) }
            names.forEach { n ->
                val g = grounded.firstOrNull { it.item == n }
                MemoryStore.upsert(ItemRec(n, room, "", g?.pos ?: "", now, "scan"))
            }
            val annotated = annotateSnapshot(room, snap, grounded)
            Log.i("MainActivity",
                "扫描完成 $room：${names.size}样，参考照片${refSaved}张")

            speak("$room 登记了${names.size}样东西：" +
                    names.take(8).joinToString("、") +
                    (if (names.size > 8) "等${names.size}样" else "") +
                    "。要去掉哪个就说，去掉，加名字。继续扫下一个房间就说，扫描。")
            scanStage = 0
            scanRoom = null
            scanItems.clear()
            setStatus("$room 已登记${names.size}样" +
                    (if (annotated != null) "，快照已存" else ""),
                R.color.status_icon_found)
        }
    }

    /** 打框结果按 0~1000 坐标裁剪成参考照片。返回保存张数。 */
    private fun saveReferenceCrops(jpeg: ByteArray,
                                   grounded: List<SiliconFlowApi.Grounded>): Int {
        if (grounded.isEmpty()) return 0
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return 0
            var saved = 0
            for (g in grounded) {
                if (MemoryStore.refPhoto(g.item) != null) continue   // 已有照片不覆盖
                val x1 = (g.box[0] / 1000f * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val y1 = (g.box[1] / 1000f * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                val x2 = (g.box[2] / 1000f * bmp.width).toInt().coerceIn(x1 + 1, bmp.width)
                val y2 = (g.box[3] / 1000f * bmp.height).toInt().coerceIn(y1 + 1, bmp.height)
                val crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
                val out = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 85, out)
                crop.recycle()
                File(MemoryStore.refsDir(), "${g.item}.jpg").writeBytes(out.toByteArray())
                saved++
            }
            bmp.recycle()
            saved
        }.getOrDefault(0)
    }

    /** 把打框画到快照上存档，给陪行的人核对。 */
    private fun annotateSnapshot(room: String, jpeg: ByteArray,
                                 grounded: List<SiliconFlowApi.Grounded>): File? {
        if (grounded.isEmpty()) return null
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val canvas = Canvas(bmp)
            val p = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.GREEN
            }
            val tp = Paint().apply { color = Color.WHITE; textSize = 36f }
            val bp = Paint().apply { color = Color.argb(160, 0, 110, 0) }
            for (g in grounded) {
                val r = android.graphics.RectF(
                    g.box[0] / 1000f * bmp.width, g.box[1] / 1000f * bmp.height,
                    g.box[2] / 1000f * bmp.width, g.box[3] / 1000f * bmp.height)
                canvas.drawRect(r, p)
                val top = (r.top - 42f).coerceAtLeast(0f)
                canvas.drawRect(r.left, top,
                    r.left + tp.measureText(g.item) + 12f, top + 40f, bp)
                canvas.drawText(g.item, r.left + 6f, top + 32f, tp)
            }
            val f = File(MemoryStore.snapshotsDir(),
                "snap_${room}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            f
        }.getOrNull()
    }

    // ---- 记忆查询与登记 ----

    private fun answerWhere(name: String) {
        val rec = MemoryStore.find(name)
        if (rec == null) {
            speak("记忆里还没有$name。可以说，扫描，先登记一遍；或者直接说，找$name。")
            setStatus("记忆里没有$name", R.color.status_card_text)
        } else {
            val spot = if (rec.spot.isNotBlank()) "的${rec.spot}" else ""
            speak("记忆里，${name}在${rec.room}$spot，${timeAgo(rec.lastSeen)}记录的。")
            setStatus("记忆：$name 在${rec.room}$spot", R.color.status_icon_found)
        }
    }

    private fun timeAgo(ts: Long): String {
        if (ts <= 0) return "很久以前"
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 90 -> "刚刚"
            s < 3600 -> "${s / 60}分钟前"
            s < 86400 -> "${s / 3600}小时前"
            else -> "${s / 86400}天前"
        }
    }

    private fun rememberNote(item: String, spot: String) {
        val r = Labels.matchRoom(spot)
        val room = r ?: currentRoom ?: "未分区"
        val s = if (r != null) spot.replace(r, "").trim() else spot
        MemoryStore.upsert(
            ItemRec(item, room, s, "", System.currentTimeMillis(), "manual"))
        speak("记下了：${item}在$room${if (s.isNotBlank()) "的$s" else ""}。")
        setStatus("已登记：$item 在$room", R.color.status_icon_found)
    }

    private fun forgetItem(name: String) {
        if (MemoryStore.remove(name)) {
            speak("好，已经忘掉$name 了。")
            setStatus("已忘掉$name", R.color.status_card_text)
        } else {
            speak("记忆里没有$name。")
        }
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
        val free = freeTarget != null
        if (now - searchStart < if (free) VISION_FIRST_FREE else VISION_FIRST_COCO) {
            return
        }
        // 画面在变 = 用户在走动/转动：加快节奏，免得走过头了才报位置
        val interval = if (detector.sceneChangeScore > 0.04f) VISION_WALKING
                       else VISION_IDLE
        if (now - lastVisionCheck < interval) return
        val tid = targetId
        val name = freeTarget ?: tid?.let { Labels.CLASS_CN[it] } ?: return
        lastVisionCheck = now
        visionSignature = sig
        visionName = name
        pendingSnapshotUse = "find"
        detector.snapshotRequest = true
    }

    /** 在分析线程收到快照：按用途分发（找目标 / 扫描登记）。 */
    private fun handleSnapshot(jpeg: ByteArray) {
        val use = pendingSnapshotUse
        pendingSnapshotUse = ""
        if (use == "scan") {
            lastRoomSnapshot = jpeg
            processScanSnapshot(jpeg)
        } else {
            processFindSnapshot(jpeg)
        }
    }

    /** 找目标：异步问模型，回来播报。期间换目标/进入待命则丢弃。 */
    private fun processFindSnapshot(jpeg: ByteArray) {
        val sig = visionSignature ?: return
        val name = visionName ?: return
        visionSignature = null
        visionName = null
        visionBusy = true
        lifecycleScope.launch {
            val ref = MemoryStore.refPhoto(name)
            setStatus(if (ref != null) "正在按照片比对，找$name…"
                      else "正在请 AI 帮忙看画面，找$name…",
                R.color.status_icon_active)
            val answer = withContext(Dispatchers.IO) {
                if (ref != null) vision.findReference(ref.readBytes(), jpeg, name)
                else vision.askAboutFrame(jpeg, name)
            }
            visionBusy = false
            if (answer == null) {
                Log.w("MainActivity", "识图兜底失败")
                return@launch
            }
            if (standby || currentSignature() != sig) return@launch
            // 注意"没有看到耳机"也包含物品名，只认"有"开头的回答
            val found = answer.startsWith("有")
            if (found) {
                lastVisionFoundPos = POS_WORDS.firstOrNull { answer.contains(it) }
                lastVisionFoundTime = System.currentTimeMillis()
                MemoryStore.touch(name, currentRoom)
            } else if (lastVisionFoundPos != null &&
                System.currentTimeMillis() - lastVisionFoundTime < 25000) {
                // 刚看到过现在又说没有：多半是走过头了，往回带
                val dir = when (lastVisionFoundPos) {
                    "左上", "左中", "左下" -> "往左"
                    "右上", "右中", "右下" -> "往右"
                    else -> "原地"
                }
                speak("$name 刚才还在画面里，请放慢脚步，$dir 慢慢转回去找。")
            } else {
                lastVisionFoundPos = null
            }
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

        // 识图兜底节奏：走动时快、停着时省；自由目标几乎立刻先看一次
        private const val VISION_FIRST_FREE = 800L
        private const val VISION_FIRST_COCO = 3000L
        private const val VISION_WALKING = 5000L
        private const val VISION_IDLE = 12000L

        private val POS_WORDS = listOf("左上", "右上", "左下", "右下",
                                       "左中", "右中", "中间")
    }
}
