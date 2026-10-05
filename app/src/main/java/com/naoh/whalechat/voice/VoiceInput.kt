package com.naoh.whalechat.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.naoh.whalechat.data.AsrEngine
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.Settings
import com.naoh.whalechat.net.SpeechException
import com.naoh.whalechat.net.XfyunIat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.Locale

/** 语音输入的三个阶段，界面靠它决定按钮长什么样、提示说什么。 */
enum class VoicePhase {
    /** 空闲，点麦克风就是开始 */
    IDLE,

    /** 录着呢，再点一下就是结束 */
    RECORDING,

    /** 录完了，正在等服务端把文字返回来 */
    TRANSCRIBING,
}

/** 语音输入入口的句柄。 */
data class VoiceInputHandle(
    val start: () -> Unit,
    val stop: () -> Unit,

    /**
     * 结束录音，但**不发送** —— 识别结果交给 `onEditResult`。
     *
     * 和 [stop] 走的是同一条「录音 → 识别」链路，只把最后交付那一步换了个去处。
     * 只有正在录的时候有意义，其余阶段是空操作。
     */
    val stopToEdit: () -> Unit,
    val phase: VoicePhase,

    /** 0..1 的实时音量，只有 [VoicePhase.RECORDING] 期间有意义 */
    val level: Float,

    /** 流式识别的中间结果，用来做「边说边出字」；批量接口这条恒为空 */
    val partial: String,

    /** 有任何一条路正在工作（本地录音、上传识别、或系统识别在听） */
    val listening: Boolean,
)

/**
 * 语音输入。**讯飞识别优先，系统识别兜底。**
 *
 * 两条路，按设置里选的那个走：
 *
 * 1. **讯飞语音听写**（`AsrEngine.XFYUN`）—— 自己拿麦克风录音，按 1280 字节
 *    切片推给讯飞，**边说边出字**；说完等最后一片结果就行。
 * 2. **系统识别**（`AsrEngine.SYSTEM`）—— 拉系统面板，面板不可用就直连识别服务。
 *
 * 前一条存在的理由：系统那套 API 只把音频交给设备上装的 `RecognitionService`，
 * 跑在本地还是联网由厂商决定，**App 没有任何开关能指定**。在国内手机上这个服务
 * 往往就是 Google 的，于是表现为「经常报错 / 提示没有可用的识别 / 识别不准」——
 * 根因是它连不上，不是用户说得不清楚。
 *
 * 两条都不通就明确告诉用户，而不是静默失败。
 *
 * @param onPartial 中间结果。讯飞那条是真流式，系统直连那条靠 `onPartialResults`。
 *   把它显示出来，用户能当场看出识别得对不对。
 * @param onEditResult 「录完先别发、拿去改字」这条出口（[VoiceInputHandle.stopToEdit]）。
 *   和 [onResult] 是同一趟识别的两个落点 —— 识别错字时，「重新说一遍」在手表上
 *   远不如「把这一句带去编辑页改一个字」划算。
 */
@Composable
fun rememberVoiceInput(
    onResult: (String) -> Unit,
    onMessage: (String) -> Unit,
    onPartial: (String) -> Unit = {},
    onEditResult: (String) -> Unit = {},
): VoiceInputHandle {
    val context = LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    val latestMessage by rememberUpdatedState(onMessage)
    val latestPartial by rememberUpdatedState(onPartial)
    val latestEdit by rememberUpdatedState(onEditResult)

    val scope = rememberCoroutineScope()
    val recorder = remember { AudioRecorder() }
    val xfyun = remember { XfyunIat() }

    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var liveText by remember { mutableStateOf("") }
    var systemListening by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    /**
     * 这一趟录制的识别结果往哪儿去。
     *
     * false = 说完即发（对话页左边那支麦克风）；
     * true = 先别发，把文字交回界面（[onEditResult] 那条出口）。
     *
     * 做成标记而不是两条独立流程，是因为「录音 → 边传边识别 → 收敛成一句」
     * 这一整套对两个出口完全一样 —— 分的只是最后一脚往哪儿踢。
     */
    var editIntent by remember { mutableStateOf(false) }

    val level by recorder.level.collectAsState()

    val direct = remember {
        DirectSpeechRecognizer(
            context = context,
            onResult = { latestResult(it) },
            onMessage = { latestMessage(it) },
            onPartial = { latestPartial(it) },
            onListeningChanged = { systemListening = it },
        )
    }

    /**
     * 交付识别结果 —— **整条链路的唯一出口**，「发送」和「转编辑」只在这里分叉。
     *
     * 必须定义在 [runXfyun] 前面：Kotlin 的局部函数只能向后引用，
     * 写在后面会在编译期直接报 unresolved reference。
     */
    fun deliver(text: String) {
        if (text.isBlank()) {
            latestMessage("没听清，再说一次")
            return
        }
        if (editIntent) latestEdit(text) else latestResult(text)
    }

    /**
     * 边录边发。讯飞那条，录音和识别是并发的。
     *
     * 外面套一层 `supervisorScope`：这是个局部 suspend 函数，本身没有
     * `CoroutineScope` 接收者，`async` 在这里是解析不到的。
     *
     * **必须是 supervisor 而不是普通 `coroutineScope`**，这一点踩过坑：
     * 普通 `coroutineScope` 里，`async` 体一抛异常就会**立刻连坐取消整个作用域**，
     * 而且即便调用方在 `await()` 处 catch 住了，`coroutineScope` 收尾时照样会把这个
     * 失败重新抛出去 → 冒到 `rememberCoroutineScope()` 里那个 `launch` → 那个协程
     * 没有异常处理器 → **未捕获异常，进程直接挂掉**（用户看到的就是"闪退"）。
     * 实测复现：断网后按麦克风，讯飞域名解析失败，App 崩在 main 上。
     * supervisorScope 里子协程的失败不会外溢，异常只在 `await()` 处交给调用方处理。
     *
     * 双保险：`recognition` 里的失败另外还包成了 `Result`，绝不往外抛（取消除外）。
     */
    suspend fun runXfyun(config: Settings) = supervisorScope {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)

        // 录音每攒够 1280 字节就丢进通道，识别那边立刻推给讯飞 —— 两边并发跑，
        // 而不是等录完再上传，这是「边说边出字」的来源。
        val recognition = async {
            try {
                Result.success(
                    xfyun.transcribe(config.xfyunConfig(), frames) { text -> liveText = text },
                )
            } catch (e: CancellationException) {
                // 取消要照常往上传，否则录音停不下来、连接也关不掉
                throw e
            } catch (e: Throwable) {
                // 识别失败是**预期内**的结果（没网、凭证错、额度用完），
                // 当成结果交回主流程，绝不让它去炸父作用域
                Result.failure(e)
            }
        }

        // 识别侧一失败就立刻叫停录音。
        //
        // 不这么做的话：连接其实已经断了（手表没网、讯飞拒了凭证），用户却还停在
        // 「正在聆听」上，得等他先按一下「结束录制」，错误才浮出来 —— 中间那几十秒
        // 界面一个字都不说，看起来就是卡死/无响应。用户报的「无响应」基本都出在这。
        val abortRecorder = launch {
            runCatching { recognition.await() }
                .onSuccess { result -> if (result.isFailure) recorder.requestStop() }
        }

        val recorded: Result<ByteArray> = try {
            Result.success(recorder.record(onFrame = { frames.trySend(it) }))
        } catch (e: CancellationException) {
            recognition.cancel()
            abortRecorder.cancel()
            frames.close()
            phase = VoicePhase.IDLE
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

        abortRecorder.cancel()
        // 关掉帧通道 = 告诉讯飞「说完了」。它会补上最后一片结果，然后我们才动。
        frames.close()

        // 已经包成 Result，这里不会再抛（取消除外，那属于被取消的收尾）
        val speech = recognition.await()

        if (recorded.isFailure) {
            // 录音侧自己失败（麦克风被占 / 太短）。但识别侧先失败时它的原因才是真原因：
            // 上面刚把录音掐掉，录到的往往真的不足 0.4 秒，于是这里必然是"说得太短了" ——
            // 直接报出去就把"连不上讯飞"盖掉了。有识别原因就报识别原因。
            phase = VoicePhase.IDLE
            // 例外：用户本来就是收工去改字的，只要已经出过一部分字，就把这半句带走 ——
            // 让他对着空输入框把刚说的一整句重打一遍，比报个错还难受。
            if (editIntent && liveText.isNotBlank()) {
                latestEdit(liveText)
            } else {
                latestMessage(readable(speech.exceptionOrNull() ?: recorded.exceptionOrNull()))
            }
            return@supervisorScope
        }

        phase = VoicePhase.TRANSCRIBING
        try {
            val error = speech.exceptionOrNull()
            when {
                error is CancellationException -> throw error
                // 同上：识别失败但屏幕上已经出了半句，编辑去向就把这半句带走
                error != null ->
                    if (editIntent && liveText.isNotBlank()) latestEdit(liveText)
                    else latestMessage(readable(error))
                else -> deliver(speech.getOrThrow())
            }
        } finally {
            phase = VoicePhase.IDLE
        }
    }

    /** 录一段 → 识别 → 出文字。整条链路收在这里，界面只需要看 [phase]。 */
    fun beginRecording() {
        if (phase != VoicePhase.IDLE) return
        phase = VoicePhase.RECORDING
        liveText = ""
        // 每一趟开录都重置去向。漏掉这行的话，上一趟点了「去编辑」但没走成，
        // 这一趟按「结束录制」就会莫名其妙地不发送。
        editIntent = false

        job = scope.launch {
            // 开录之后才读配置：用户完全可能刚在设置里改完就回来按麦克风。
            // beginRecording() 只在 speechReady 为真时才会被调到，而那一档只可能是
            // 讯飞 —— 但引擎字段本身是可变的，真出了意外也不能让协程悄悄什么都不做。
            val config = ChatEngine.settings.value
            if (config.asrEngine == AsrEngine.XFYUN) {
                runXfyun(config)
            } else {
                latestMessage("识别引擎没配好，回设置里选一档")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            latestMessage("没有麦克风权限，无法语音输入")
        } else if (ChatEngine.settings.value.speechReady) {
            beginRecording()
        } else {
            direct.start()
        }
    }

    val systemLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            latestMessage("已取消语音输入")
            return@rememberLauncherForActivityResult
        }
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) latestMessage("没听清，再说一次") else latestResult(text)
    }

    val systemAvailable = remember(context) { hasSystemRecognizer(context) }
    val serviceAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }

    fun start() {
        if (phase != VoicePhase.IDLE) return
        when {
            // 自建识别排在第一位，这就是这套改造的全部意义
            ChatEngine.settings.value.speechReady ->
                if (hasRecordPermission(context)) beginRecording()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

            systemAvailable -> systemLauncher.launch(recognizerIntent())
            !serviceAvailable -> latestMessage("这台设备没有可用的语音识别")
            hasRecordPermission(context) -> direct.start()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stop() {
        if (phase == VoicePhase.RECORDING) recorder.requestStop()
    }

    /**
     * 结束录音，但把结果**留着不发**，交给 `onEditResult`。
     *
     * 为什么必须先老老实实把这一趟录完、等最后一片结果回来，而不是当场带着屏幕上
     * 那半句 partial 跳走 —— 跳页会让这个 composable 被销毁，`DisposableEffect`
     * 里那两下（掐录音、取消协程）会跟着执行，服务端随后补发的最后一片就再也
     * 收不到了，用户看到的是半句话。
     *
     * 等这一下的代价和按「结束录制」发送时完全一样（反正都要等这次识别），
     * 不会多花时间；期间界面就是「正在识别…」，用户看得见自己那一下点到了。
     */
    fun stopToEdit() {
        if (phase == VoicePhase.RECORDING) {
            editIntent = true
            recorder.requestStop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 页面被划走时必须主动掐录音：record() 里那次 read 是阻塞调用，
            // 光取消协程它不会立刻返回，麦克风会一直热着。
            recorder.requestStop()
            job?.cancel()
            direct.release()
        }
    }

    return VoiceInputHandle(
        start = ::start,
        stop = ::stop,
        stopToEdit = ::stopToEdit,
        phase = phase,
        level = level,
        partial = liveText,
        listening = phase != VoicePhase.IDLE || systemListening,
    )
}

/**
 * 识别语言跟随系统。
 *
 * 之前这里写死 zh-CN：系统语言是英文的用户，说什么都会被当成中文去识别。
 * 语言标签拿不到时才退回中文。
 *
 * 注意这一项只对「系统识别」有效 —— 讯飞那档是整片音频上传，
 * 语种由它自己判断（按系统语言在 zh_cn / en_us 里挑一个）。
 */
private fun speechLanguage(): String =
    Locale.getDefault().toLanguageTag().takeIf { !it.isNullOrBlank() } ?: "zh-CN"

private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage())
    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    // 官方默认就是 false，显式写出来是为了说明意图：我们要的是联网识别。
    // 但这只是个「倾向」，服务完全可以无视 —— 这也是为什么真正想控制
    // 「本地还是联网」只能靠自己录 + 自己传（见上面讯飞那条路）。
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
}

private fun hasSystemRecognizer(context: Context): Boolean =
    recognizerIntent().resolveActivity(context.packageManager) != null

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 给用户看的一句话。
 *
 * `SpeechException` 的 `hint` 就是专门写给用户看的人话（"连不上讯飞（域名解析失败）"），
 * 优先用它；其余异常退到 message，实在没有再说"识别失败"。
 */
private fun readable(error: Throwable?): String = when {
    error == null -> "识别失败"
    error is SpeechException -> error.hint
    else -> error.message ?: "识别失败"
}

/** `SpeechRecognizer` 必须在主线程创建，这里假定只从 Compose（主线程）调用。 */
private class DirectSpeechRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onListeningChanged(true)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = onListeningChanged(false)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        /** 边说边出结果，这是识别体验里最有信息量的一环，别丢 */
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            onListeningChanged(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) onMessage("没听清，再说一次") else onResult(text)
        }

        override fun onError(error: Int) {
            onListeningChanged(false)
            onMessage(errorText(error))
        }
    }

    fun start() {
        val instance = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        runCatching { instance.startListening(recognizerIntent()) }
            .onFailure { onMessage("语音识别启动失败") }
    }

    fun release() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        // 国内网络下这两个是最高频的：识别服务在 Google 那边，连不上
        SpeechRecognizer.ERROR_NETWORK -> "识别服务连不上（可在设置里换成自建识别）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务超时（可在设置里换成自建识别）"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错（可在设置里换成自建识别）"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        else -> "识别失败"
    }
}
