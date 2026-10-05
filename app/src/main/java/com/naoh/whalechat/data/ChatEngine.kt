package com.naoh.whalechat.data

import android.content.Context
import com.naoh.whalechat.net.DeepSeekClient
import com.naoh.whalechat.net.DeepSeekException
import com.naoh.whalechat.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 应用级会话引擎。
 *
 * 放在 Application 作用域而不是 ViewModel 里，是为了让「生成」这件事能跨页面活着：
 * 在聊天页发出问题后可以直接返回首页，回答照样流式写进会话，回来就是完整的。
 *
 * 线程约定：所有 [_conversations] 的读写都发生在主线程（UI 调用 + 主线程作用域），
 * 所以不需要额外加锁；真正的磁盘写才丢给 IO 作用域。
 */
object ChatEngine {

    private lateinit var store: ConversationStore
    private lateinit var personaStore: PersonaStore
    private val client = DeepSeekClient()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val personaWriteLock = Mutex()

    lateinit var settings: SettingsStore
        private set

    /** 余额缓存。独立于 [settings]（独立 prefs），见 [UsageStore] 的注释。 */
    lateinit var usage: UsageStore
        private set

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** 通讯录（chat 模式的人）。**它的存亡与会话无关**，见 [Persona] 的注释。 */
    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    /**
     * 「基于预设创建」时正在编辑的那四个字段。
     *
     * 为什么放在这里而不是 `rememberSaveable`：预设详情页 → 文字输入页 → 返回，中间
     * 隔了一次导航，值得跨页面活着；而它又是个**纯页面级草稿**，不进文件、不进历史。
     * 所以按 `notice` 那种「一次性状态」的路子放引擎里，用完由页面清掉。
     *
     * 它跟 `deleted` 那种内存簿记完全是两回事：这里存的是用户正在打字的界面状态，
     * 没有它只是要重敲一遍，不会让任何判定出错。
     */
    private val _presetDraft = MutableStateFlow<PresetDraft?>(null)
    val presetDraft: StateFlow<PresetDraft?> = _presetDraft.asStateFlow()

    /**
     * 「自己说一句」那条路上，用户在输入页敲下的那句描述。
     *
     * 为什么不让输入页直接把文字带进等待页：`InputScreen` 的规矩是「**离开 = 提交**」，
     * 而它离开时唯一的动作通道是 `onDispose`，那里**只能写数据、不能做导航**
     * （onDispose 阶段再 popBackStack / navigate 是未定义行为，代码里早写着这条）。
     * 所以文字先落到这儿，由**新建页**观察到它再往前导航 —— 这也顺带把
     * 「划走输入页 = 提交」和「划走等待页 = 取消」这两条相反的规矩分得干干净净。
     *
     * 和 [presetDraft] 一样是页面级草稿，不落盘。
     */
    private val _personaDraft = MutableStateFlow<String?>(null)
    val personaDraft: StateFlow<String?> = _personaDraft.asStateFlow()

    /** 正在流式输出的会话 id；null 表示空闲 */
    private val _streamingId = MutableStateFlow<String?>(null)
    val streamingId: StateFlow<String?> = _streamingId.asStateFlow()

    /** 一次性提示（缺 Key、没有可重试内容等），UI 展示后调 consumeNotice() 清掉 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var streamJob: Job? = null

    /**
     * 被**明确删掉、并且当时已经有过 DS 回复**的会话 id（[deleteConversation] / [clearAll]）。
     *
     * 这是「这个对话已经不在了」这句话唯一站得住的依据。对话页以前是拿
     * 「在当前列表里找不到」当依据的，那条判据太粗 —— 「找不到」的原因至少有三条，
     * 只有一条真的该提示：
     *  1. （本集合）被删除、被清空 —— 该提示、该退回去；
     *  2. 刚点「＋」进来、DS 还没回过话的空壳 —— 用户只是进出了一下，
     *     在他心里**根本不存在「一个对话」**，没有任何东西「消失」过；
     *  3. **重建后的空窗期** —— 这条最阴：Wear 的边缘滑动（SwipeDismissableNavHost）
     *     会让这个页面短暂重建，重建那一瞬间 `conversations` 还没读回来、列表是空的，
     *     于是「找不到」成立、弹一句「已经不在了」，而实际上用户只是正常返回了一次。
     *     屏幕上的表现就是**返回首页时中间劈下来一句红卡片**。
     *
     * 所以判据从「找不到」换成「**被删过**」：要正面证据，不要缺席证据。
     *
     * 入册还多一道闸：**只有 [Conversation.hasReply] 的会话才登记** ——
     * 规则是「有 ds 回复才算新对话」。第 2 类之所以要单独拎出来
     * 正是因为它最容易被漏掉：点「＋」会真的往列表里塞一条壳，
     * 万一它在别的路径上被清掉（比如从对话页绕去设置里点了「清空所有对话」），
     * 光看「被删过」是拦不住它的。拿「DS 回过话没有」当闸门，它就被挡在外面了 ——
     * 而且这条规则不依赖任何内存记账，消息本身就是证据。
     */
    private val deleted = mutableSetOf<String>()

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = ConversationStore(File(context.filesDir, "conversations.json"))
        personaStore = PersonaStore(File(context.filesDir, "personas.json"))
        settings = SettingsStore(context)
        usage = UsageStore(context)
        _conversations.value = store.load().sortedByDescending { it.sortKey }
        _personas.value = personaStore.load().sortedByDescending { it.sortKey }
    }

    fun find(id: String?): Conversation? =
        id?.let { target -> _conversations.value.firstOrNull { it.id == target } }

    /**
     * 这条会话是不是被**明确删掉了**？这是「已经不在了」唯一站得住的依据。
     *
     * 注意两件事：
     *  * 它刻意不看当前列表里有没有 —— 「列表里没有」至少有三条原因，
     *    只有「被删过」这一条该提示用户（理由见 [deleted]）；
     *  * 它也不可能对一条**从没被 DS 回复过**的会话为真 —— 那种会话压根不会进
     *    这个集合（见 [deleteConversation]）。
     *
     * 被删过的会话不会复活（id 是 UUID，不会重用），所以这个集合只增不减，
     * 不清理也不会误伤。进程重启后这个集合本来就空了，导航栈里恢复出来的那条 id
     * 就落回「不在集合里」的一侧，[isGone] 为 false。
     */
    fun wasDeleted(id: String?): Boolean = id != null && id in deleted

    /**
     * 对话页该不该为「看不到内容」而自己退回去？
     *
     * 只有**被明确删掉、且那时已经有过 DS 回复**才算数。其余情况一律静默 ——
     * 点「＋」进来的空壳、页面重建的空窗期，用户都什么都没做错，
     * 不该被一句「已经不在了」吓一下。
     *
     * 代价说清楚：进程被回收、导航栈恢复出一个从没落过盘的 id 时，这一页会停在空白
     * 而不是自动退回。这是刻意接受的一侧 —— 那种情况下屏幕上的东西和刚点「＋」
     * 进来长得一模一样，返回一下就走了；比给每个正常返回的人都劈一张红卡片好得多。
     */
    fun isGone(id: String?): Boolean = wasDeleted(id)

    val isBusy: Boolean get() = _streamingId.value != null

    fun hasApiKey(): Boolean = settings.value.apiKey.isNotBlank()

    // ------------------------------------------------------------------ 会话

    /**
     * 给首页那个「＋」一个会话 id，**刻意不落盘**。
     *
     * 会话真正的诞生点是第一句话（见 [send]）。如果在这里就写进历史，用户在空白
     * 会话页什么都没问就返回，列表里就会多出一条「新对话」—— 点几次＋就攒几条，
     * 久而久之历史列表被一串空壳占满。所以这里只在内存里放一个占位，
     * 让对话页有个能渲染的对象；没说话的话，[discardEmpty] 会把内存里这份也收走。
     *
     * 这个空壳**还不算一条对话**：[Conversation.hasReply] 为 false。
     * 它不出现在首页列表里，也不该在对话页上被当成「已经不在了」的对象 ——
     * 这两件事都是靠 `hasReply` 这一条线判的，不需要额外记账。
     */
    fun createConversation(): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        _conversations.value = listOf(
            Conversation(id = id, createdAt = now, updatedAt = now)
        ) + _conversations.value
        return id
    }

    /**
     * 丢掉所有还没说过话的会话（内存里的空壳）。
     *
     * 首页在每次进入时调用一次：从空白会话返回首页，那条占位就该消失，
     * 而不是留在内存里等下一次「＋」再叠一层。
     *
     * 对正在流式输出的会话是安全的 —— 它至少已经有一条用户消息，
     * `messages.isEmpty()` 不成立，不会被误伤。
     */
    fun discardEmpty() {
        _conversations.value = _conversations.value.filterNot { it.messages.isEmpty() }
    }

    fun deleteConversation(id: String) {
        if (_streamingId.value == id) stop()
        // 只有 DS 回过话的会话才登记进「删除名单」—— 见 [deleted] 的注释。
        // 「点＋进来、一个字没打就被清掉」这种壳不该让对话页弹提示，因为它从没存在过。
        if (find(id)?.hasReply == true) deleted += id
        _conversations.value = _conversations.value.filterNot { it.id == id }
        commit()
    }

    fun rename(id: String, title: String) {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return
        update(id) { it.copy(title = cleaned.take(TITLE_MAX), titleGenerated = true) }
        commit()
    }

    fun clearAll() {
        stop()
        // 同上：没被 DS 回复过的空壳不入册。清空列表里躺着的壳不该让谁收到「已经不在了」。
        deleted += _conversations.value.filter { it.hasReply }.map { it.id }
        _conversations.value = emptyList()
        commit()
    }

    // ------------------------------------------------------------------ 角色

    fun findPersona(id: String?): Persona? =
        id?.let { target -> _personas.value.firstOrNull { it.id == target } }

    /** 这个角色名下的那条会话。一个角色最多一条会话 —— 通讯录点进去永远是「和 TA 的那个聊天」。 */
    fun personaConversation(personaId: String): Conversation? =
        _conversations.value.firstOrNull { it.personaId == personaId }

    /**
     * 造完人之后落地：**存角色 + 建会话（greeting 当场上架）**，返回会话 id。
     *
     * greeting 必须在这一刻写进去，**不能等用户开口**。理由有两条，都是硬的：
     *
     *  1. `discardEmpty()` 会把「一句消息都没有」的会话从内存里收走。角色会话要是空着
     *     从这一页走过去，可能还没进聊天页就被收掉了；
     *  2. 成功之后是**直接进聊天页**的，用户最想看到的就是 TA 开口说话。
     *     要是进了页面还是空的，那这一步就白走了。
     *
     * 注意这里**不碰** `hasReply` 的判据 —— greeting 是第一条 assistant 消息，
     * 它让会话天然非空，但这是结果，不是用来判「角色还在不在」的依据。
     * 角色在不在只有一个来源：[findPersona]。
     */
    fun createPersonaChat(persona: Persona): String {
        _personas.value = _personas.value + persona
        commitPersonas()
        return openPersonaChat(persona.id) ?: run {
            // openPersonaChat 只在角色不存在时返回 null，这里刚写完，理论上到不了。
            // 真到了也不能返回一个假 id 让页面去撞空白页 —— 直接把刚放进来的角色撤掉。
            _personas.value = _personas.value.filterNot { it.id == persona.id }
            commitPersonas()
            ""
        }
    }

    /**
     * 进某个角色的会话：已有就复用，没有就现建一条并把 greeting 上架。
     *
     * 返回 null 表示角色已经不在了（被删/进程重启后导航栈恢复出的假 id）——
     * 调用方什么都不该做，静静待着就行。
     */
    fun openPersonaChat(personaId: String): String? {
        val persona = findPersona(personaId) ?: return null
        personaConversation(personaId)?.let { return it.id }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val conversation = Conversation(
            id = id,
            // 角色会话的标题恒等于角色名，所以 titleGenerated 直接置位：
            // 谁都不该再去叫模型总结一遍
            title = persona.name,
            createdAt = now,
            updatedAt = now,
            titleGenerated = true,
            messages = listOf(ChatMessage(ChatRole.ASSISTANT, persona.openingLine)),
            personaId = personaId,
        )
        _conversations.value = listOf(conversation) + _conversations.value
        // 带着 greeting 建出来就是非空的，commit 的「空会话不落盘」过滤不会收走它
        commit()
        return id
    }

    /**
     * 「重新开始」：**人设不动，清空这个角色的全部消息，把存的 greeting 再上架一次。**
     *
     * 不联网、不重新生成人设，所以是瞬间完成的。TA 说的第一句和上次一字不差，
     * 因为 [Persona.greeting] 是固定值 —— 要每次换一句就得让模型现编，那要联网、会失败。
     *
     * ## 为什么必须先判「会话还在不在」
     *
     * 角色的存活性**不依赖会话**：一个角色完全可能没有会话。最常见的一条路是
     * [createPersonaChat] 分两次落盘（先 `commitPersonas()` 再 `commit()`），进程正好死在
     * 中间就是这个状态。更根本地说，`personaId` 是**会话指向人**的外键，箭头不能反过来，
     * 所以这里不能假定「有角色就一定有会话」。
     *
     * 没有会话时**现建一条**，而不是 `?: return` 静默退出：静默退出会让用户长按 →
     * 点「重新开始」→ 确认之后**屏幕上什么都没发生**，那是个死胡同。而「重新开始」的
     * 语义本来就是「回到那句开场白」——没有会话时把它建出来，正好就是这句话的实现。
     */
    fun restartPersona(personaId: String) {
        val persona = findPersona(personaId) ?: return
        val conversation = personaConversation(personaId)
        if (conversation == null) {
            openPersonaChat(personaId)
            return
        }
        if (_streamingId.value == conversation.id) stop()
        update(conversation.id) {
            it.copy(
                messages = listOf(ChatMessage(ChatRole.ASSISTANT, persona.openingLine)),
                updatedAt = System.currentTimeMillis(),
            )
        }
        commit()
    }

    /**
     * 给角色改名。会话标题跟着一起改 —— 角色会话的标题就是角色名，两处必须同步。
     *
     * 只改 [Persona.name]，**不顺手去重写人设六块**：里面提到名字的地方（比如示例对话
     * 每行的说话人）是模型生成时写死的，静态替换会留下改不干净的残留，让模型重写一遍
     * 又要联网。名字是用户在界面和气泡标签上看到的那一层，改它立刻生效 ——
     * 要连人设一起换，那是「重新描述一个人再生成」的事。
     */
    fun renamePersona(personaId: String, name: String) {
        val cleaned = name.trim().take(PERSONA_NAME_MAX)
        if (cleaned.isEmpty()) return
        _personas.value = _personas.value.map {
            if (it.id == personaId) it.copy(name = cleaned) else it
        }
        personaConversation(personaId)?.let { conversation ->
            update(conversation.id) { it.copy(title = cleaned, titleGenerated = true) }
        }
        commitPersonas()
        commit()
    }

    /**
     * 手机端网页提交的角色，**整对象覆盖**，并且**返回时已经在磁盘上**。
     *
     * 和手表上那几条路的区别只有两点，都是网页那边的承诺逼出来的：
     *
     *  1. **同步落盘。** `commitPersonas()` 是把写盘丢进 IO 作用域就返回的，
     *     而网页上写完就显示「已保存到手表」—— 用户看到这四个字的下一个动作往往就是
     *     切走或者杀掉应用。必须等 `PersonaStore.save` 真的返回（里面有 `fd.sync()`）
     *     才让 HTTP 响应出去，那句「已保存」才是真的。
     *  2. **整对象覆盖，不是增量合并。** 网页给的是六块的真值表，留空就是清空。
     *
     * 返回 null 表示成功；非 null 是人话错误，直接显示在网页上。
     *
     * 名改了的话顺带把会话标题也改掉（角色会话的标题恒等于角色名），并且一起同步落盘 ——
     * 否则会出现「角色名是新的、聊天页标题还是旧的」，而且要等下次 renamePersona 才自愈。
     *
     * @param persona 已由调用方补好 `id` / `createdAt` / `lastChatAt` / `source`；
     *   这里只做裁剪和落盘，不猜任何身份信息。
     */
    suspend fun savePersonaFromPhone(persona: Persona): String? {
        val cleaned = persona.copy(
            name = persona.name.trim().take(PERSONA_NAME_MAX),
            tagline = persona.tagline.trim().take(PERSONA_TAGLINE_MAX),
            soul = persona.soul.trim(),
            style = persona.style.trim(),
            rules = persona.rules.trim(),
            scenario = persona.scenario.trim(),
            examples = persona.examples.trim(),
            greeting = persona.greeting.trim(),
        )
        if (cleaned.name.isEmpty()) return "角色名不能空着"

        val nameChanged = findPersona(cleaned.id)?.name != cleaned.name

        _personas.value = (_personas.value.filterNot { it.id == cleaned.id } + cleaned)
            .sortedByDescending { it.sortKey }

        if (nameChanged && personaConversation(cleaned.id) != null) {
            // `_conversations` 的读写约定在主线程（见类注释里的线程约定），
            // 而这里是从 HTTP 的 IO 协程调进来的，所以这一步必须回主线程做。
            // `update` 是「读一遍再整体写回」，跨线程并发调用会丢更新。
            withContext(Dispatchers.Main.immediate) {
                personaConversation(cleaned.id)?.let { conversation ->
                    update(conversation.id) { it.copy(title = cleaned.name, titleGenerated = true) }
                    _conversations.value = _conversations.value.sortedByDescending { it.sortKey }
                }
            }
        }

        return runCatching {
            personaWriteLock.withLock { personaStore.save(_personas.value) }
            if (nameChanged) writeLock.withLock { saveConversationsNow() }
        }.exceptionOrNull()?.let { "写盘失败：${it.message ?: "未知错误"}" }
    }

    /**
     * 改角色的一个**短字段**（名字 / 简介 / 开场白）。
     *
     * 这三格是手表上唯一能改的：六块里其余五块都是大段文字，在这块表上敲不现实。
     * 名字那条路另有讲究（会话标题要跟着改），所以直接转给 [renamePersona] ——
     * 一处裁剪、一处同步，不在这里再写一遍。
     */
    fun updatePersonaField(personaId: String, field: PersonaField, value: String) {
        if (field == PersonaField.NAME) {
            renamePersona(personaId, value)
            return
        }

        val persona = findPersona(personaId) ?: return
        val text = value.trim()
        val updated = when (field) {
            PersonaField.TAGLINE -> persona.copy(tagline = text.take(PERSONA_TAGLINE_MAX))
            PersonaField.GREETING -> persona.copy(greeting = text)
            // 上面已经分流走了；写在这里是为了让 when 是全穷尽的
            PersonaField.NAME -> return
        }
        _personas.value = _personas.value.map { if (it.id == personaId) updated else it }

        // 改了开场白，**那个还没聊过的会话**里上架的那句要跟着换 ——
        // 否则用户改完回到聊天页，看到的还是旧的那句，只会以为没生效。
        //
        // 判据用 `lastChatAt == 0`，也就是「从没在 TA 的会话里说过话」。
        // 那种会话里除了一句开场白什么都没有，换掉它不是改历史 —— 而真的聊过之后
        // 这就是历史，历史不该被一次编辑改掉（要换就得点「重新开始」）。
        if (field == PersonaField.GREETING && persona.lastChatAt == 0L) {
            personaConversation(personaId)?.let { conversation ->
                if (_streamingId.value == conversation.id) stop()
                update(conversation.id) {
                    it.copy(messages = listOf(ChatMessage(ChatRole.ASSISTANT, updated.openingLine)))
                }
                commit()
            }
        }
        commitPersonas()
    }

    /**
     * 删角色 —— **连带它的会话一起删**。
     *
     * 会话也照 [deleted] 的老规矩登记：如果用户当时正停在这条角色会话的聊天页上
     * （比如从设置里绕了一圈），聊天页会靠 `isGone()` 自己退回去。
     * 角色会话的 `hasReply` 必然为 true（greeting 就是 assistant 消息），所以在册。
     */
    fun deletePersona(personaId: String) {
        val conversation = personaConversation(personaId)
        if (conversation != null) {
            if (_streamingId.value == conversation.id) stop()
            if (conversation.hasReply) deleted += conversation.id
            _conversations.value = _conversations.value.filterNot { it.id == conversation.id }
        }
        _personas.value = _personas.value.filterNot { it.id == personaId }
        commitPersonas()
        commit()
    }

    /** 在 TA 的会话里说过话 → 刷新「最后聊天时间」，通讯录按它排序。 */
    private fun touchPersona(personaId: String, at: Long) {
        var changed = false
        val next = _personas.value.map {
            if (it.id == personaId && it.lastChatAt != at) {
                changed = true
                it.copy(lastChatAt = at)
            } else {
                it
            }
        }
        if (!changed) return
        _personas.value = next
        commitPersonas()
    }

    private fun commitPersonas() {
        _personas.value = _personas.value.sortedByDescending { it.sortKey }
        val snapshot = _personas.value
        io.launch {
            personaWriteLock.withLock { runCatching { personaStore.save(snapshot) } }
        }
    }

    // -------------------------------------------------------- 预设草稿（页面级）

    /**
     * 进预设详情页时铺一份草稿。
     *
     * **同一个预设已经有草稿就不重铺**：这一页去一趟文字输入页再回来，中间会被销毁重建，
     * 无条件重铺会把用户刚改的那几个字段全冲回默认值 —— 而他只是去改了其中一行。
     */
    fun beginPresetDraft(presetIndex: Int) {
        val preset = personaPresets().getOrNull(presetIndex) ?: return
        if (_presetDraft.value?.presetIndex == presetIndex) return
        _presetDraft.value = PresetDraft.from(preset, presetIndex)
    }

    fun setPersonaDraft(text: String) {
        _personaDraft.value = text.trim().takeIf { it.isNotEmpty() }
    }

    fun consumePersonaDraft() {
        _personaDraft.value = null
    }

    fun updatePresetDraft(transform: (PresetDraft) -> PresetDraft) {
        _presetDraft.value = _presetDraft.value?.let(transform)
    }

    fun clearPresetDraft() {
        _presetDraft.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * 让页面给用户留一句话（首页那块的提示卡会显示它）。
     *
     * 场景是「动作发生在这个页面、但用户马上会被送到另一个页面」：对话页发现
     * 会话已经不存在、自己退回首页时，得让首页有句话解释他为什么被弹回来了。
     */
    fun postNotice(text: String) {
        _notice.value = text
    }

    // ------------------------------------------------------------------ 发送

    fun send(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }

        val now = System.currentTimeMillis()
        // 会话从第一句话开始存在。首页「＋」拿到的只是一个还没落地的 id，
        // 走到这里就该把它补进列表 —— 用户从空白会话页发出的第一条消息就是这条路。
        //
        // 两种情况都要能落地：会话**完全不在了**（进程重启后恢复的假 id），
        // 以及它还在内存里、只是还没有消息（刚点的＋）。前者的 id 从没进过列表，
        // 后者正在列表里。分支写好，别让「补一条空的」把后者那条覆盖掉。
        if (find(conversationId) == null) {
            _conversations.value = _conversations.value +
                Conversation(id = conversationId, createdAt = now, updatedAt = now)
        }
        // 说过话的这一刻，它才算一条真正的对话（见 [Conversation.hasReply]）——
        // 下面那条助手占位消息就是「DS 回过话」的证据，判据全靠它，不再需要别的记账。
        update(conversationId) { conversation ->
            conversation.copy(
                // 占位消息，流式内容直接往最后这条上灌
                messages = conversation.messages + ChatMessage(ChatRole.USER, body) +
                    ChatMessage(ChatRole.ASSISTANT, ""),
                // 先给个临时标题，首页列表不至于一排「新对话」；真正的标题随后由模型补上
                title = conversation.title.ifBlank { provisionalTitle(body) },
                updatedAt = now,
            )
        }
        // 在 TA 的会话里说过话 → 刷新通讯录的排序时间。放在这里而不是 commit() 里：
        // commit 在流式的每个消息边界都会被调，放那儿等于每次回复都重排一遍列表。
        find(conversationId)?.personaId?.let { touchPersona(it, now) }
        commit()
        startStream(conversationId)
    }

    /**
     * 编辑重发：改掉第 [index] 条消息（必须是用户消息），用它重新问一遍。
     *
     * 这是「截断再发」，不是「插一条新的」—— 它和它之后的一切（原来那条回答，
     * 以及更往后的所有追问）全部作废。理由：问句换了，后面那些回答回答的是**另一个**
     * 问题，留在上下文里只会让模型自相矛盾。
     *
     * **只允许改最新那一问**，也就是「它后面再没有别的用户消息」。更早的提问一律拒绝：
     * 作废的就不只是它自己那条回答了，而是它后面**已经发生过的整段对话** ——
     * 用户要的边界就是这个（见下面的第二道闸）。
     *
     * 所有前置检查都在截断之前完成。顺序反了就会出现「对话少了一半、新消息却没发出去」
     * 这种最坏的中间态 —— send 内部还会再查一遍，这里提前拦是为了不让截断白做。
     */
    fun rewrite(conversationId: String, index: Int, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }
        val conversation = find(conversationId) ?: return
        val target = conversation.messages.getOrNull(index) ?: return
        if (target.role != ChatRole.USER) return

        // 第二道闸：UI 已经不给更早那些长按了（ChatScreen 里按「最新一问」判据过滤），
        // 这里再拦一次。真冒出一个「改中间那句」的调用，宁可什么都不做并说明原因，
        // 也不能悄悄把后面几轮问答删掉 —— 那是用户明确不要的行为。
        if (conversation.messages.drop(index + 1).any { it.role == ChatRole.USER }) {
            _notice.value = "只能改最新那条提问"
            return
        }

        val kept = conversation.messages.take(index)
        // 被改掉的正好是这个会话的第一句：标题本来就是照着它起的，留着就成了一个
        // 和内容对不上的标题。清空并允许重新生成，列表里的标题才会跟着问题走。
        val firstQuestionGone = kept.none { it.role == ChatRole.USER }
        update(conversationId) {
            it.copy(
                messages = kept,
                title = if (firstQuestionGone) "" else it.title,
                titleGenerated = if (firstQuestionGone) false else it.titleGenerated,
            )
        }
        send(conversationId, body)
    }

    /** 把最后一条出错的回答抹掉重来。 */
    fun retry(conversationId: String) {
        if (isBusy) return
        val conversation = find(conversationId) ?: return
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }

        val kept = conversation.messages.dropLastWhile {
            it.role == ChatRole.ASSISTANT && (it.error || it.content.isBlank())
        }
        if (kept.lastOrNull()?.role != ChatRole.USER) {
            _notice.value = "没有可以重试的问题"
            return
        }
        update(conversationId) {
            it.copy(messages = kept + ChatMessage(ChatRole.ASSISTANT, ""))
        }
        startStream(conversationId)
    }

    fun stop() {
        client.cancel()
        streamJob?.cancel()
        streamJob = null
        _streamingId.value = null
    }

    // ------------------------------------------------------------ 流式实现

    private fun startStream(conversationId: String) {
        val snapshot = find(conversationId) ?: return
        val current = settings.value
        // 这条会话是不是角色会话？是的话，system prompt 换成「人设六块 + 硬编码输出规则」，
        // **全局设置里那套不再参与**。两段缺一不可：
        // 前面的 [Persona.buildCorePrompt] 随角色变，后面的 CHAT_OUTPUT_RULES 谁也改不动。
        //
        // 每次发送都现拼，**不缓存也不落库**：手机端改完 `soul` 回到手表，
        // 下一次发送必须直接用新的。存一份拼好的结果就会出现「改了没效果」，
        // 而那是最难查的一类不一致（理由也写在 buildCorePrompt 上）。
        val persona = snapshot.personaId?.let(::findPersona)
        val systemPrompt = persona?.let {
            listOf(it.buildCorePrompt(), CHAT_OUTPUT_RULES)
                .filter { part -> part.isNotBlank() }
                .joinToString("\n\n")
        } ?: current.systemPrompt

        // 上下文不包含最后那条空的助手占位消息；再按「最近 N 条 + 总字数预算」截一刀。
        // 手表上的会话很容易越滚越长，整段历史照发早晚会撞上模型的上下文上限（400），
        // 而且每一轮都在为很早以前的内容重复付费。
        val history = trimToBudget(snapshot.messages.dropLast(1), persona = persona != null)
        if (history.isEmpty()) return

        _streamingId.value = conversationId
        streamJob = scope.launch {
            val reply = StringBuilder()
            val reason = StringBuilder()
            var failure: String? = null
            var stopped = false

            try {
                client.stream(
                    apiKey = current.apiKey,
                    history = history,
                    systemPrompt = systemPrompt,
                    // 思考模式**按会话类型取档**：角色会话用 `modeChat`，普通会话用 `modeAsk`。
                    // 两档在设置页**分开存、且一次只显示一组**（设置页按来源分叉，
                    // 从哪一页进来就调哪一档），改一个不动另一个 —— 判据就是上面那个
                    // `persona`（`snapshot.personaId != null` 即角色会话），不另造一个。
                    //
                    // ⚠️ 这里读的是**两个字段**，与设置页摆几组按钮**无关**：
                    // UI 藏掉一组只是不给入口，判据仍然是这个 `persona`。
                    // 把取档也改成「看当前 UI 选中哪组」就成了隐藏功能改行为的路子，没那回事。
                    //
                    // **仍然不强制给角色会话关掉思考。** 曾经想过「角色会话一律关思考」，
                    // 理由是「思维链打到屏幕上破坏沉浸」—— 那条是错的：MessageCard 把
                    // reasoning 做成默认收起的折叠区（「思考过程 · 点击展开」），用户不点就看不到。
                    // 剩下的「首包慢」是真的，但那是用户自己在设置里给 chat 选的档位，
                    // 他愿意等就让他等。深度思考本来就是接入这个模型的价值所在，别替他决定。
                    thinking = (if (persona != null) current.modeChat else current.modeAsk) ==
                        ChatMode.THINKING,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            reply.append(event.text)
                            patchAssistant(conversationId, reply.toString(), reason.toString())
                        }

                        is StreamEvent.Reasoning -> {
                            reason.append(event.text)
                            patchAssistant(conversationId, reply.toString(), reason.toString())
                        }

                        is StreamEvent.Usage -> {
                            // 累加进会话，不覆盖。usage 只在最后一个 chunk 出现一次，
                            // 所以这里一定是「加」，不会是「重复加」。
                            //
                            // 用户中途按「停止」时**没有 usage chunk**，这个分支根本不会
                            // 走到 —— 于是会话的 token 数停在打断前的旧值，而不是被记成 0。
                            // 这正是要的行为：没拿到不等于用了 0 个。
                            addUsage(
                                conversationId,
                                event.promptTokens,
                                event.completionTokens,
                                event.cacheHitTokens,
                                event.cacheMissTokens,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 用户点了「停止」或切走了会话。这里故意不再抛出：
                // 主线程作用域永远不会被整体取消，吞掉它才能把已收到的内容落盘。
                stopped = true
            } catch (e: DeepSeekException) {
                failure = e.hint
            } catch (e: Exception) {
                failure = "出错了：${e.message ?: "未知错误"}"
            }

            val text = reply.toString()
            val problem = failure

            when {
                problem != null && text.isEmpty() -> {
                    replaceLastAssistant(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, problem, error = true),
                    )
                }

                problem != null -> {
                    // 已经收到一部分，保留正文，把错误另起一条
                    patchAssistant(conversationId, text, reason.toString(), done = true)
                    appendMessage(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, problem, error = true),
                    )
                }

                // 一个字都没收到就被停掉：把空占位悄悄删掉，别留下一条像报错的东西
                stopped && text.isEmpty() -> dropTrailingEmptyAssistant(conversationId)

                text.isEmpty() -> {
                    replaceLastAssistant(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, "模型没有返回内容，可以重试", error = true),
                    )
                }

                else -> {
                    patchAssistant(conversationId, text, reason.toString(), done = true)
                }
            }

            streamJob = null
            _streamingId.value = null
            commit()
            generateTitleIfNeeded(conversationId)
        }
    }

    /**
     * 模型总结标题。用便宜的非推理模型 + 低温度，失败了就用首条问题的截断兜底。
     * 整个流程刻意「静默」：标题拿不到不该影响聊天。
     */
    private fun generateTitleIfNeeded(conversationId: String) {
        val conversation = find(conversationId) ?: return
        if (conversation.titleGenerated) return
        // 角色会话**直接跳过**：它的标题恒等于角色名（建会话时就置位了），
        // 叫模型去总结一遍纯属白烧钱，而且总结出来的标题还会把角色名冲掉。
        if (conversation.personaId != null) return

        val question = conversation.messages.firstOrNull { it.role == ChatRole.USER }?.content
            ?: return
        val answer = conversation.messages
            .firstOrNull { it.role == ChatRole.ASSISTANT && !it.error && it.content.isNotBlank() }
            ?.content
            ?: return

        val apiKey = settings.value.apiKey
        scope.launch {
            val generated = runCatching {
                client.complete(
                    apiKey = apiKey,
                    systemPrompt = TITLE_PROMPT,
                    userContent = "用户：${question.take(300)}\n助手：${answer.take(600)}",
                )
            }.getOrNull()

            val title = cleanTitle(generated) ?: provisionalTitle(question)
            update(conversationId) { it.copy(title = title, titleGenerated = true) }
            commit()
        }
    }

    // ------------------------------------------------------------- 状态修改

    private fun patchAssistant(
        conversationId: String,
        content: String,
        reasoning: String,
        done: Boolean = false,
    ) {
        update(conversationId) { conversation ->
            val index = conversation.messages.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (index < 0) return@update conversation
            val patched = conversation.messages.toMutableList()
            patched[index] = patched[index].copy(content = content, reasoning = reasoning)
            conversation.copy(
                messages = patched,
                updatedAt = if (done) System.currentTimeMillis() else conversation.updatedAt,
            )
        }
    }

    private fun replaceLastAssistant(conversationId: String, message: ChatMessage) {
        update(conversationId) { conversation ->
            val index = conversation.messages.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (index < 0) return@update conversation
            val patched = conversation.messages.toMutableList()
            patched[index] = message
            conversation.copy(messages = patched, updatedAt = System.currentTimeMillis())
        }
    }

    private fun appendMessage(conversationId: String, message: ChatMessage) {
        update(conversationId) { conversation ->
            conversation.copy(
                messages = conversation.messages + message,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun dropTrailingEmptyAssistant(conversationId: String) {
        update(conversationId) { conversation ->
            val last = conversation.messages.lastOrNull()
            if (last == null || last.role != ChatRole.ASSISTANT || last.content.isNotEmpty()) {
                conversation
            } else {
                conversation.copy(messages = conversation.messages.dropLast(1))
            }
        }
    }

    /**
     * 把一条 usage 累加进会话。
     *
     * 这里**只做累加、绝不覆盖**：覆盖的话，一条流里万一服务端发两次 usage
     * （某些实现会在中间也带一次），第二次就把第一次冲掉了；累加天然幂等——
     * 一次加到一次的量，两次加到两次的量。
     *
     * cache 两个字段同理：它们只在带真数据的那个 chunk 上有值，
     * 中间的全零占位在 `parseDelta` 那道 `prompt > 0 || completion > 0` 上
     * 就已经被挡掉了，所以这里加到的永远是「这一轮真实命中/未命中」的量。
     *
     * 注意它**不主动 commit**：usage 是在流式收尾时到的，紧跟着外面那套
     * 「结束时统一 commit」走，这里改内存态就够了，别在流中间触发写盘。
     */
    private fun addUsage(
        conversationId: String,
        prompt: Long,
        completion: Long,
        cacheHit: Long,
        cacheMiss: Long,
    ) {
        update(conversationId) { conversation ->
            conversation.copy(
                promptTokens = conversation.promptTokens + prompt,
                completionTokens = conversation.completionTokens + completion,
                cacheHitTokens = conversation.cacheHitTokens + cacheHit,
                cacheMissTokens = conversation.cacheMissTokens + cacheMiss,
            )
        }
    }

    private inline fun update(conversationId: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) transform(conversation) else conversation
        }
    }

    /**
     * 会话落盘的实体部分，**调用方必须已经持有 [writeLock]**。
     *
     * 抽出来是因为除了 [commit] 这条「异步写」的路，还有一条**同步写**的路：
     * [savePersonaFromPhone] 改了角色名之后要连着会话标题一起在返回 HTTP 响应之前
     * 落盘。两边共用同一个过滤和同一把锁，不然「空会话不写进文件」这条规则
     * 迟早只在其中一边生效。
     */
    private fun saveConversationsNow() {
        // 空会话不写进文件：它是内存里的过渡态，不是历史记录。
        // 不在这过滤的话，别的会话随便触发一次 commit，就会顺手把首页点过＋
        // 但没说话的占位一起写进 conversations.json —— 下次启动它们又回来了。
        runCatching { store.save(_conversations.value.filter { it.messages.isNotEmpty() }) }
    }

    /** 排序 + 落盘，只在消息边界调用，流式过程中不写盘。 */
    private fun commit() {
        _conversations.value = _conversations.value.sortedByDescending { it.sortKey }
        io.launch {
            writeLock.withLock { saveConversationsNow() }
        }
    }

    // ------------------------------------------------------------------ 杂项

    private fun provisionalTitle(question: String) = question
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(TITLE_MAX)

    /** 把模型可能带上来的引号、序号、句末标点都削掉。 */
    private fun cleanTitle(raw: String?): String? {
        val firstLine = raw?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
        val stripped = firstLine
            .replace(Regex("^(标题|主题)\\s*[:：]\\s*"), "")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '《', '》', '。', '，', '、', '.', ',', ':', '：')
            .trim()
        return stripped.takeIf { it.isNotEmpty() }?.take(TITLE_MAX)
    }

    /** 手表列表宽度有限，标题超过这个字数就会被截断成省略号。 */
    private const val TITLE_MAX = 12

    /**
     * 带上文的条数上限（一问一答算两条）。
     *
     * 取 20 条 ≈ 最近十轮对话，对手机表上的追问场景够用；
     * 再往上加只会让首包更慢、更贵，而手表屏幕也回看不了那么多。
     */
    private const val HISTORY_MAX_MESSAGES = 20

    /**
     * 角色会话的条数上限，比 ask 放宽到 30 条。
     *
     * 情感陪伴比问答更依赖连贯的记忆：TA 该记得住你上周说过什么，而 ask 模式
     * 只关心眼下这一问。字数预算不动 —— 真正的上限是它，条数只是顺手放的闸。
     */
    private const val PERSONA_HISTORY_MAX_MESSAGES = 30

    /** 角色名在列表标题位和气泡标签上的长度上限，跟 [Persona.name] 的约定一致。 */
    private const val PERSONA_NAME_MAX = 6

    /** 带上文的字符预算。约等于 5k token 量级，离上下文上限还很远，不会触发 400。 */
    private const val HISTORY_MAX_CHARS = 8_000

    /**
     * 从最新往回留，直到用满条数或字数预算。
     *
     * 至少保留一条：哪怕单条就超预算（比如粘了一大段代码），
     * 也得把这句话发出去，否则用户按了发送却永远得不到回答。
     *
     * @param persona 角色会话放宽条数到 [PERSONA_HISTORY_MAX_MESSAGES]、字数预算不变。
     */
    private fun trimToBudget(messages: List<ChatMessage>, persona: Boolean = false): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val maxMessages =
            if (persona) PERSONA_HISTORY_MAX_MESSAGES else HISTORY_MAX_MESSAGES
        var budget = HISTORY_MAX_CHARS
        val kept = ArrayDeque<ChatMessage>()
        for (message in messages.asReversed()) {
            if (kept.size >= maxMessages) break
            val size = message.content.length
            if (kept.isNotEmpty() && budget - size < 0) break
            budget -= size
            kept.addFirst(message)
        }
        return kept.toList()
    }

    private const val TITLE_PROMPT =
        "你是会话标题生成器。读一段对话，输出一个概括其主题的中文短标题。" +
            "要求：不超过 12 个字；不要引号、书名号、标点或句号；不要任何解释、前缀或序号；只输出标题本身。"
}
