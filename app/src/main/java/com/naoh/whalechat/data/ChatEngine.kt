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
    private val client = DeepSeekClient()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    lateinit var settings: SettingsStore
        private set

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

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
     * 规则是「有 DS 回复才算新对话」。第 2 类之所以要单独拎出来
     * 正是因为它最容易被漏掉：点「＋」会真的往列表里塞一条壳，
     * 万一它在别的路径上被清掉（比如从对话页绕去设置里点了「清空所有对话」），
     * 光看「被删过」是拦不住它的。拿「DS 回过话没有」当闸门，它就被挡在外面了 ——
     * 而且这条规则不依赖任何内存记账，消息本身就是证据。
     */
    private val deleted = mutableSetOf<String>()

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = ConversationStore(File(context.filesDir, "conversations.json"))
        settings = SettingsStore(context)
        _conversations.value = store.load().sortedByDescending { it.sortKey }
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
        val systemPrompt = current.systemPrompt

        // 上下文不包含最后那条空的助手占位消息；再按「最近 N 条 + 总字数预算」截一刀。
        // 手表上的会话很容易越滚越长，整段历史照发早晚会撞上模型的上下文上限（400），
        // 而且每一轮都在为很早以前的内容重复付费。
        val history = trimToBudget(snapshot.messages.dropLast(1))
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
                    thinking = current.mode == ChatMode.THINKING,
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

    private inline fun update(conversationId: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) transform(conversation) else conversation
        }
    }

    /**
     * 会话落盘的实体部分，**调用方必须已经持有 [writeLock]**。
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

    /** 带上文的字符预算。约等于 5k token 量级，离上下文上限还很远，不会触发 400。 */
    private const val HISTORY_MAX_CHARS = 8_000

    /**
     * 从最新往回留，直到用满条数或字数预算。
     *
     * 至少保留一条：哪怕单条就超预算（比如粘了一大段代码），
     * 也得把这句话发出去，否则用户按了发送却永远得不到回答。
     */
    private fun trimToBudget(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val maxMessages = HISTORY_MAX_MESSAGES
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
