package com.naoh.whalechat.data

import android.content.Context
import com.naoh.whalechat.BuildSecrets
import com.naoh.whalechat.net.XfyunConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 回答档位。
 *
 * 注意这**不是两个模型**：都跑 DeepSeek-V4.1-Flash，
 * 区别只是「思考模式」这个开关的开与关。
 */
enum class ChatMode(val key: String, val label: String) {
    /** 关掉思考，拿到就答，适合日常闲聊与简单查询 */
    FAST("fast", "快答"),

    /** 先推理再作答，回答里可以展开思考过程 */
    THINKING("thinking", "深度思考"),
    ;

    companion object {
        /**
         * 旧版本这里存的是模型名（deepseek-chat / deepseek-reasoner）。
         * 顺手迁移一下，免得老用户升级后档位莫名掉回快答。
         *
         * ⚠️ 传 null 落 [FAST]，所以**读盘时不能把 null 直接丢进来** ——
         * 必须 `?: legacy.key` 先兜住老键（见 [SettingsStore.read]）。
         */
        fun fromKey(key: String?): ChatMode = when (key) {
            THINKING.key, "deepseek-reasoner" -> THINKING
            else -> FAST
        }
    }
}

/**
 * 语音识别的引擎。
 *
 * - [SYSTEM] 设备自带的识别服务。跑在本地还是联网**由厂商决定，App 没有任何开关能指定**，
 *   所以在装了 Google 那套服务的设备上它经常就是连不上 —— 这正是要加后一条路的原因。
 * - [XFYUN] 讯飞语音听写。边录边发，**边说边出字**，等待感最小。
 */
enum class AsrEngine(val key: String, val label: String) {
    SYSTEM("system", "系统"),
    XFYUN("xfyun", "讯飞听写"),
    ;

    companion object {
        fun fromKey(key: String): AsrEngine = entries.firstOrNull { it.key == key } ?: SYSTEM

        /**
         * 老版本存的键可能是已经删掉的 `generic`（自建 OpenAI 兼容接口），
         * 也可能更老、只有一个 `asr_enabled` 布尔。两种情况一律落到 [SYSTEM] ——
         * 不去猜用户想用哪档，让他在设置页重新选一次。
         */
        fun migrate(engine: String?): AsrEngine =
            if (engine == null) SYSTEM else fromKey(engine)
    }
}

data class Settings(
    val apiKey: String = "",
    val mode: ChatMode = ChatMode.FAST,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

    // ------------------------------------------------------------ 语音识别
    val asrEngine: AsrEngine = AsrEngine.SYSTEM,

    /** 讯飞：三样缺一不可。产品固定是「语音听写（流式版）」，详见 XfyunIat 的注释 */
    val xfyAppId: String = "",
    val xfyApiKey: String = "",
    val xfyApiSecret: String = "",
) {
    /**
     * 当前的语音识别配置能不能直接用。
     *
     * 系统识别不用配置（也用不了 [speechReady] 判断 —— 它由设备决定，
     * 面板能不能起来只有调用时才知道）；讯飞则必须三样齐全。
     */
    val speechReady: Boolean
        get() = when (asrEngine) {
            AsrEngine.SYSTEM -> false
            AsrEngine.XFYUN ->
                xfyAppId.isNotBlank() && xfyApiKey.isNotBlank() && xfyApiSecret.isNotBlank()
        }

    /** 讯飞的语种跟着系统走：中文环境用中文模型，其余按中英混合的英文档走 */
    fun xfyunConfig(): XfyunConfig = XfyunConfig(
        appId = xfyAppId.trim(),
        apiKey = xfyApiKey.trim(),
        apiSecret = xfyApiSecret.trim(),
        language = if (Locale.getDefault().language == "zh") "zh_cn" else "en_us",
    )

    companion object {
        /**
         * 手表屏幕就这么点大，默认让模型收着点说。
         * 这条可以在设置里改或清空。
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "你在智能手表上回答用户，屏幕很小。回答力求简短直接，优先给结论；" +
                "除非用户明确要求，不要输出长代码块和长篇列表。"
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("whalechat_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val value: Settings get() = _state.value

    init {
        applyPresetsIfNeeded()
    }

    /**
     * 出包时烤进来的凭证写进设置。
     *
     * **按指纹判断，而不是「只写一次」的布尔标记。** 起先用的是布尔：写完就置位，
     * 永远不再碰。问题是重新出包换一批凭证再覆盖安装时，标记还是 true，
     * 于是新凭证静默不生效 —— 调凭证的阶段天天踩。
     *
     * 指纹 = 本次包里预置的全部内容拼起来。和上次落盘的一致就什么都不做：
     * 用户之后在设置里改过或删掉，不会被下次启动悄悄覆盖回去（因为指纹没变）。
     * 只有包里的预置内容真变了，才重写一次。
     */
    private fun applyPresetsIfNeeded() {
        val fingerprint = listOf(
            BuildSecrets.PRESET_API_KEY,
            BuildSecrets.PRESET_XFY_APPID,
            BuildSecrets.PRESET_XFY_APIKEY,
            BuildSecrets.PRESET_XFY_APISECRET,
            BuildSecrets.PRESET_ASR_ENGINE,
        ).joinToString("\u0000")

        if (prefs.getString(KEY_PRESET_FINGERPRINT, null) == fingerprint) return

        var next = _state.value
        var dirty = false

        BuildSecrets.PRESET_API_KEY.trim().takeIf { it.isNotEmpty() }?.let {
            next = next.copy(apiKey = it)
            dirty = true
        }

        if (BuildSecrets.hasPresetXfyun) {
            next = next.copy(
                xfyAppId = BuildSecrets.PRESET_XFY_APPID.trim(),
                xfyApiKey = BuildSecrets.PRESET_XFY_APIKEY.trim(),
                xfyApiSecret = BuildSecrets.PRESET_XFY_APISECRET.trim(),
                // 只预置凭证不切引擎的话，用户装完还得自己去设置里改一档 ——
                // 那预置就没意义了。没显式指定引擎时，给了讯飞凭证就切讯飞。
                asrEngine = BuildSecrets.PRESET_ASR_ENGINE
                    .takeIf { it.isNotBlank() }
                    ?.let { AsrEngine.fromKey(it) }
                    ?: AsrEngine.XFYUN,
            )
            dirty = true
        }

        prefs.edit().putString(KEY_PRESET_FINGERPRINT, fingerprint).apply()
        if (dirty) write { next }
    }

    fun setApiKey(key: String) = write { it.copy(apiKey = key.trim()) }

    fun setMode(mode: ChatMode) = write { it.copy(mode = mode) }

    fun setSystemPrompt(prompt: String) = write { it.copy(systemPrompt = prompt) }

    fun setAsrEngine(engine: AsrEngine) = write { it.copy(asrEngine = engine) }

    fun setXfyAppId(value: String) = write { it.copy(xfyAppId = value.trim()) }

    fun setXfyApiKey(value: String) = write { it.copy(xfyApiKey = value.trim()) }

    fun setXfyApiSecret(value: String) = write { it.copy(xfyApiSecret = value.trim()) }

    /**
     * 手机扫码保存：把网页上那一屏**原样**存进来。
     *
     * 语义是「整屏覆盖」，不是「留空表示不动」。**这个改动是被表单自己逼出来的**：
     * 页面现在会把已有的值预填出来（见 KeyImportServer.formHtml），用户看到的是一张
     * 真值表 —— 那么「清空某一格再保存」就该等于删掉那一项。旧语义下这一格会被当成
     * 「没填」而保留原值，结果是**已经写进去的凭证永远删不掉**。
     *
     * 所以空串在这里是有意义的输入，不做任何 `ifEmpty { 原值 }`。
     * 引擎档位的自动切换在服务端就已经定好（KeyImportServer.withAutoEngine），
     * 这里只负责把拿到的值落盘，不再自己判断一遍。
     *
     * 落盘用同步写（`sync = true`）：这一刻手机页面上已经写了「已保存到手表」，
     * 值就必须真的在磁盘上。理由见 [write] 的注释。
     */
    fun importFromPhone(c: ImportedCredentials) = write(sync = true) { current ->
        current.copy(
            apiKey = c.apiKey.trim(),
            asrEngine = AsrEngine.fromKey(c.asrEngine),
            xfyAppId = c.xfyAppId.trim(),
            xfyApiKey = c.xfyApiKey.trim(),
            xfyApiSecret = c.xfyApiSecret.trim(),
        )
    }

    /**
     * 落盘。
     *
     * 默认走 `apply()`（异步写，不占调用线程）；[sync] = true 时改用 `commit()`。
     *
     * 为什么值得为扫码导入那一处破例：`apply()` 只是把写盘丢给后台线程，
     * 进程若在那之前被干掉，这次改动就没了 —— 页面已经显示了「已保存到手表」，
     * 值却没进磁盘，是这个功能最不能出的错。这条路径本来就跑在 IO 协程里
     * （KeyImportServer 的 handle），同步写一下没有代价。
     *
     * 这不是纸上推演：提交成功页刚回来就 `am force-stop`，重开页面读到的还是旧值；等待 6 秒后再看就对了。
     *
     * 参数顺序刻意让 [transform] 收尾：绝大多数调用点写的是 `write { it.copy(...) }`，
     * 尾部 lambda 必须落在最后一个形参上，否则那几十处全得改成显式传参。
     */
    private fun write(sync: Boolean = false, transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        val editor = prefs.edit()
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_MODE, next.mode.key)
            .putString(KEY_PROMPT, next.systemPrompt)
            .putString(KEY_ASR_ENGINE, next.asrEngine.key)
            .putString(KEY_XFY_APPID, next.xfyAppId)
            .putString(KEY_XFY_APIKEY, next.xfyApiKey)
            .putString(KEY_XFY_APISECRET, next.xfyApiSecret)
        if (sync) editor.commit() else editor.apply()
        _state.value = next
    }

    private fun read(): Settings {
        return Settings(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            mode = ChatMode.fromKey(
                prefs.getString(KEY_MODE, null) ?: prefs.getString(KEY_MODE_LEGACY, null),
            ),
            systemPrompt = prefs.getString(KEY_PROMPT, Settings.DEFAULT_SYSTEM_PROMPT)
                ?: Settings.DEFAULT_SYSTEM_PROMPT,
            asrEngine = AsrEngine.migrate(prefs.getString(KEY_ASR_ENGINE, null)),
            xfyAppId = prefs.getString(KEY_XFY_APPID, "").orEmpty(),
            xfyApiKey = prefs.getString(KEY_XFY_APIKEY, "").orEmpty(),
            xfyApiSecret = prefs.getString(KEY_XFY_APISECRET, "").orEmpty(),
        )
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_MODE = "chat_mode"

        /** 旧版本用它存 deepseek-chat / deepseek-reasoner */
        const val KEY_MODE_LEGACY = "model"
        const val KEY_PROMPT = "system_prompt"

        const val KEY_ASR_ENGINE = "asr_engine"

        const val KEY_XFY_APPID = "xfy_app_id"
        const val KEY_XFY_APIKEY = "xfy_api_key"
        const val KEY_XFY_APISECRET = "xfy_api_secret"

        const val KEY_PRESET_FINGERPRINT = "preset_fingerprint"
    }
}
