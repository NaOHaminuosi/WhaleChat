package com.naoh.whalechat.data

import android.content.Context
import android.content.SharedPreferences
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
 *
 * ⚠️ **它是两档枚举，但存两份** —— ask 第一页一份（[Settings.modeAsk]）、
 * chat 第二页一份（[Settings.modeChat]），各自独立、改一个不动另一个。
 * 别把它改成一个布尔开关：`thinking.type` 只有 enabled/disabled 两种取值，
 * 拆成「两个独立开关」语义会塌回二选一。
 *
 * 存两份之后，**设置页按来源一次只显示一组**（`SettingsScreen` 的 `isChatOrigin`）：
 * 并排摆两组会让人以为改一个两边都变。要改的是「用户在哪儿调它」，
 * 不是这两个字段、也不是 [SettingsStore.read] 的迁移初值。
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
    /**
     * ask 第一页（一次性提问）用哪一档。
     *
     * 与 [modeChat] **分开存**：在设置页改一个不动另一个。
     * 判据是「这条会话是不是角色会话」（`Conversation.personaId`），见 `ChatEngine.startStream`。
     */
    val modeAsk: ChatMode = ChatMode.FAST,
    /**
     * chat 第二页（角色对话）用哪一档。
     *
     * ⚠️ 默认也是 [ChatMode.FAST]：改成 THINKING 会让新用户的角色对话一上来就多花钱、
     * 多等首字，不是用户要的。
     */
    val modeChat: ChatMode = ChatMode.FAST,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

    // ------------------------------------------------------------ 语音识别
    val asrEngine: AsrEngine = AsrEngine.SYSTEM,

    /** 讯飞：三样缺一不可。产品固定是「语音听写（流式版）」，详见 XfyunIat 的注释 */
    val xfyAppId: String = "",
    val xfyApiKey: String = "",
    val xfyApiSecret: String = "",

    // ------------------------------------------------------------ 显示设置
    /**
     * 消息气泡上要不要写时间。
     *
     * 管且只管一处：对话页里每张消息气泡**右上角那个时间**（`ChatScreen.MessageCard`）。
     * 跟下面两个用量开关完全独立 —— 一个是「这条消息什么时候说的」，两个是「花了多少」，
     * 想只留时间不留数字、或反过来，都是合理的选择，所以拆成三个而不是一个。
     *
     * 默认开：默认关掉等于把刚做出来的东西藏起来。
     */
    val showBubbleTime: Boolean = true,

    /**
     * 对话页底部那行「已用 xx tokens · 命中 yy%」显不显示。
     *
     * 这是原来那个「一个开关管两处」的 `showTokenUsage` 拆出来的两半之一。拆的理由：上一版一个开关管两处，
     * 而这两处的使用场景差得很远 —— 对话页底部是「这条聊天花了多少」，
     * 角色列表是「这个人跟我聊了多少」。所以要能分开开关。
     *
     * ⚠️ **默认关**。**关掉 = 整行不存在**，不是变灰、也不是显示 0。
     * **ask 第一页的会话列表从来不受它管**（那一页是问完就走的一次性问答，
     * 堆数字只会分心）—— 老规矩没变。
     */
    val showUsageInChat: Boolean = false,

    /**
     * chat 第二页角色卡片上那个用量显不显示。
     *
     * 和 [showUsageInChat] 是同一件事的另一半，拆开的理由见那边。
     *
     * 默认开。注意它管的是**副标题行左端**那串 `12.4k tokens`；
     * 副标题行右端那个时间（`HH:mm`）属于 [showBubbleTime] 吗？**不属于** ——
     * 角色卡上的时间没有单独开关，跟着卡片一起显示（它跟「这条消息什么时候说的」
     * 不是一码事，关了气泡时间不该把通讯录里的时间也带走）。
     */
    val showUsageOnPersona: Boolean = true,
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
        .also { applyDisplayDefaults(it) }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val value: Settings get() = _state.value

    init {
        applyPresetsIfNeeded()
    }

    /**
     * 显示开关的一次性覆盖。
     *
     * 默认组合：**对话页用量（含缓存命中率）关、气泡时间开、角色列表用量开**，
     * 并且「显示设置」二级页入口**暂时隐藏**（先不删）。
     *
     * ⚠️ **为什么必须覆盖、而不是只改 `read()` 的兜底值**：
     * [write] 是**全量写** —— 用户只要改过任何一项设置（哪怕只是换个档位、填个 Key），
     * `show_usage_chat` / `show_usage_persona` / `show_bubble_time` 三个键就都已经
     * 以**当时的默认值（true）落盘**了。只改兜底值只对「盘上没有这个键」的全新安装生效，
     * 老用户升上来**界面一点都不会变**；而入口又隐藏了，用户自己也没法改回来。
     * 所以这里直接改写盘上的值。
     *
     * ⚠️ 必须挂在 `also` 链上、排在 `_state = read()` **之前**：
     * 这样 `read()` 读到的就是覆盖后的值。若写进 `init` 块，界面会先闪一下旧值
     * （属性初始化器与 `init` 都按书写顺序执行，写错顺序就会读到没被覆盖的旧值）。
     *
     * ⚠️ 标记位只在本函数里写、**绝不能进 [write]** ——
     * 否则用户每改一次设置都会把自己重置回默认值。
     *
     * @param target 目标 prefs。
     */
    private fun applyDisplayDefaults(target: SharedPreferences) {
        if (target.getBoolean(KEY_DISPLAY_DEFAULTS_1911, false)) return
        target.edit()
            .putBoolean(KEY_SHOW_USAGE_CHAT, false)
            .putBoolean(KEY_SHOW_USAGE_PERSONA, true)
            .putBoolean(KEY_SHOW_BUBBLE_TIME, true)
            .putBoolean(KEY_DISPLAY_DEFAULTS_1911, true)
            .apply()
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

    fun setModeAsk(mode: ChatMode) = write { it.copy(modeAsk = mode) }

    fun setModeChat(mode: ChatMode) = write { it.copy(modeChat = mode) }

    fun setSystemPrompt(prompt: String) = write { it.copy(systemPrompt = prompt) }

    fun setAsrEngine(engine: AsrEngine) = write { it.copy(asrEngine = engine) }

    fun setXfyAppId(value: String) = write { it.copy(xfyAppId = value.trim()) }

    fun setXfyApiKey(value: String) = write { it.copy(xfyApiKey = value.trim()) }

    fun setXfyApiSecret(value: String) = write { it.copy(xfyApiSecret = value.trim()) }

    fun setShowBubbleTime(value: Boolean) = write { it.copy(showBubbleTime = value) }

    fun setShowUsageInChat(value: Boolean) = write { it.copy(showUsageInChat = value) }

    fun setShowUsageOnPersona(value: Boolean) = write { it.copy(showUsageOnPersona = value) }

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
     * 这不是纸上推演：当时的 release 验证脚本复现过一次 ——
     * 提交成功页刚回来就 `am force-stop`，重开页面读到的还是旧值；等待 6 秒后再看就对了。
     *
     * 参数顺序刻意让 [transform] 收尾：绝大多数调用点写的是 `write { it.copy(...) }`，
     * 尾部 lambda 必须落在最后一个形参上，否则那几十处全得改成显式传参。
     */
    private fun write(sync: Boolean = false, transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        val editor = prefs.edit()
            .putString(KEY_API_KEY, next.apiKey)
            // 两个档位各写各的键。**不再写 KEY_MODE** —— 那个老键降级为「只读不写」，
            // 留作升级时的迁移初值，留在老用户盘上不清理（沿用既有「不主动清理」的约定）。
            .putString(KEY_MODE_ASK, next.modeAsk.key)
            .putString(KEY_MODE_CHAT, next.modeChat.key)
            .putString(KEY_PROMPT, next.systemPrompt)
            .putString(KEY_ASR_ENGINE, next.asrEngine.key)
            .putString(KEY_XFY_APPID, next.xfyAppId)
            .putString(KEY_XFY_APIKEY, next.xfyApiKey)
            .putString(KEY_XFY_APISECRET, next.xfyApiSecret)
            // 三个显示开关各写各的键。**不再写 KEY_SHOW_TOKEN_USAGE** ——
            // 那个老键和 `chat_mode` 一样降级为「只读不写」，留作升级时的迁移初值，
            // 不主动清理。
            .putBoolean(KEY_SHOW_BUBBLE_TIME, next.showBubbleTime)
            .putBoolean(KEY_SHOW_USAGE_CHAT, next.showUsageInChat)
            .putBoolean(KEY_SHOW_USAGE_PERSONA, next.showUsageOnPersona)
        if (sync) editor.commit() else editor.apply()
        _state.value = next
    }

    private fun read(): Settings {
        // 档位现在是 ask / chat 各存一份（KEY_MODE_ASK / KEY_MODE_CHAT）。
        // 老键 KEY_MODE（全局一份）仍作为两者的迁移初值被读：它存在的键优先，
        // 读不到再回退到更老的「存模型名」那个键。两边都没有时 legacy 是 FAST —— 新装用户。
        //
        // ⚠️ 必须走 `?: legacy.key`，不能把 null 直接丢给 ChatMode.fromKey：
        // 那样会落 FAST，老用户升级后**档位静默掉回快答**，而他在设置页看到的
        // 还是「我明明选了深度思考」—— 这类「升级后悄悄变了」最难查。
        val legacy = ChatMode.fromKey(
            prefs.getString(KEY_MODE, null) ?: prefs.getString(KEY_MODE_LEGACY, null),
        )

        // 显示开关现在是三个独立的键（show_bubble_time / show_usage_chat / show_usage_persona）。
        //
        // 更早只有一个 `show_token_usage` 管两处用量，当时拆成两个新键、拿老键当迁移初值，
        // 为的是别让「明确关过」的老用户静默变回 true —— 那和 `chat_mode` 是同一类错，
        // 症状都是「升级后悄悄变了」，最难查。
        //
        // ⚠️ **这条迁移已作废，`show_token_usage` 不再被读**：
        // 现在改了新的默认组合（对话用量关 / 角色列表开 / 气泡时间开），
        // 并且明确要求**连盘上已有的值一起覆盖**（只改兜底值对老用户无效，
        // 因为 `write()` 是全量写、这三个键早就以 true 落盘了）⇒ 改由
        // `applyDisplayDefaults` 在 `read()` 之前一次性改写，老键的初值已没有任何作用。
        //
        // ⚠️ 老键**仍然不删**（沿用既有「不主动清理」约定），只是彻底不读了。
        return Settings(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            modeAsk = ChatMode.fromKey(prefs.getString(KEY_MODE_ASK, null) ?: legacy.key),
            modeChat = ChatMode.fromKey(prefs.getString(KEY_MODE_CHAT, null) ?: legacy.key),
            systemPrompt = prefs.getString(KEY_PROMPT, Settings.DEFAULT_SYSTEM_PROMPT)
                ?: Settings.DEFAULT_SYSTEM_PROMPT,
            asrEngine = AsrEngine.migrate(prefs.getString(KEY_ASR_ENGINE, null)),
            xfyAppId = prefs.getString(KEY_XFY_APPID, "").orEmpty(),
            xfyApiKey = prefs.getString(KEY_XFY_APIKEY, "").orEmpty(),
            xfyApiSecret = prefs.getString(KEY_XFY_APISECRET, "").orEmpty(),
            // 气泡时间是**全新**的功能，老用户盘上没这个键。
            // 现在由 `applyDisplayDefaults` 统一覆盖，这里的兜底值只在
            // 覆盖函数因某种原因没跑时用得上。
            showBubbleTime = prefs.getBoolean(KEY_SHOW_BUBBLE_TIME, true),
            // ⚠️ 兜底值就是当前的新默认（关 / 开）。盘上原有的值一律被
            // `applyDisplayDefaults` 覆盖，所以这两个兜底值实际只对新装用户生效。
            showUsageInChat = prefs.getBoolean(KEY_SHOW_USAGE_CHAT, false),
            showUsageOnPersona = prefs.getBoolean(KEY_SHOW_USAGE_PERSONA, true),
        )
    }

    private companion object {
        const val KEY_API_KEY = "api_key"

        /** ask 第一页的档位。 */
        const val KEY_MODE_ASK = "mode_ask"

        /** chat 第二页的档位。 */
        const val KEY_MODE_CHAT = "mode_chat"

        /**
         * 更早只有**全局一份**档位，ask / chat 共用。
         * 现在降级为**只读不写** —— 只在 [read] 里充当两档的迁移初值，写盘时不再碰它。
         */
        const val KEY_MODE = "chat_mode"

        /** 旧版本用它存 deepseek-chat / deepseek-reasoner */
        const val KEY_MODE_LEGACY = "model"
        const val KEY_PROMPT = "system_prompt"

        const val KEY_ASR_ENGINE = "asr_engine"

        const val KEY_XFY_APPID = "xfy_app_id"
        const val KEY_XFY_APIKEY = "xfy_api_key"
        const val KEY_XFY_APISECRET = "xfy_api_secret"

        /**
         * 更早：**一个开关管两处用量**。
         * 现在降级为**只读不写**，在 [read] 里充当两个新用量开关的迁移初值。
         *
         * ⚠️ **现在连读都不读了**：[applyDisplayDefaults] 直接覆盖盘上那三个显示键，
         * 迁移初值已无意义。键留着只是为了不主动清理老用户的盘 —— 当前**没有任何代码路径
         * 读它**。别因为「没人用了」就删，删了等于主动清理，与既有约定冲突。
         */
        const val KEY_SHOW_TOKEN_USAGE = "show_token_usage"

        /**
         * 气泡时间。当时是全新功能、无迁移；现在由 [applyDisplayDefaults] 覆盖成「开」。
         */
        const val KEY_SHOW_BUBBLE_TIME = "show_bubble_time"

        /**
         * 对话页底部的用量。
         *
         * ⚠️ 现在**不再拿 [KEY_SHOW_TOKEN_USAGE] 当迁移初值** ——
         * 盘上原有的值一律被 [applyDisplayDefaults] 覆盖，老键已不再被读。
         */
        const val KEY_SHOW_USAGE_CHAT = "show_usage_chat"

        /** 角色卡片上的用量。覆盖逻辑同 [KEY_SHOW_USAGE_CHAT]。 */
        const val KEY_SHOW_USAGE_PERSONA = "show_usage_persona"

        /**
         * [applyDisplayDefaults] 的「已覆盖过」标记位。
         *
         * ⚠️ 只在那个函数里写，**绝不能进 [write]**：它是「一次性覆盖已完成」的凭据，
         * 跟着设置一起写就等于每次改设置都把三个显示开关重置回默认值。
         */
        const val KEY_DISPLAY_DEFAULTS_1911 = "display_defaults_1911"

        /**
         * 已废弃、不再读也不再写的键：`asr_url` / `asr_key` / `asr_model`（已删掉的
         * 通用接口档位）、`asr_enabled`（更早的布尔开关）、`voice_edit_before_send`
         * （识别出字后先编辑还是直接发）、以及 `xfy_protocol` / `xfy_v2_*`（讯飞产品档位）。
         * 留在老用户的 prefs 里不动，不主动清理。
         *
         * 注意 `chat_mode`（[KEY_MODE]）**不在**这一串里：它仍然**读**（作两档的迁移初值），
         * 只是不再写。别把它删掉 —— 删了就断了老用户的升级路径。
         *
         * ⚠️ `show_token_usage`（[KEY_SHOW_TOKEN_USAGE]）**现在也归到「不再读」**：
         * 它给两个用量开关当迁移初值的作用，已被 [applyDisplayDefaults] 的覆盖取代。
         * 键本身仍然留着、不主动清理，只是没有任何代码再读它。
         */
        const val KEY_PRESET_FINGERPRINT = "preset_fingerprint"
    }
}
