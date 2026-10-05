package com.naoh.whalechat.data

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** 思考模式下的思维链；没开思考时恒为空 */
    val reasoning: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** 本地的失败提示，不参与上下文、也不回传给模型 */
    val error: Boolean = false,
)

data class Conversation(
    val id: String,
    /** 空串表示还没起标题，列表里显示成「新对话」 */
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    /** 标题是否已由模型总结生成；失败兜底的标题也置为 true，避免反复重试 */
    val titleGenerated: Boolean = false,
    /**
     * 这条会话属于哪个角色；null = ask 模式的普通对话。
     *
     * 这是**会话指向人的外键**，方向只有这一个。角色本身活在 `PersonaStore` 里，
     * 它的存亡跟会话没有关系（见 `Persona` 的注释）。
     *
     * 复用现有 [Conversation] 而不是给 chat 模式另起一套会话引擎，是为了白拿：
     * 流式、中断、rewrite、retry、标题、上下文预算裁剪、删除名单、排序、破窗保护。
     * 另起一套意味着这些全写第二遍。
     */
    val personaId: String? = null,

    // ------------------------------------------------------------ token 用量
    /**
     * 这条会话累计烧掉的 token，**从最后那个 usage chunk 累加而来**。
     *
     * 两个字段分开记（输入 / 生成）不是为了分开显示 —— 列表和底部那行都只看总量。
     * 分开是因为它们本来就是两回事：输入里有缓存命中的部分（下面两个字段），
     * 而缓存未命中的输入与原始 prompt 也不是一回事。
     * [cacheHitTokens] / [cacheMissTokens] 已经接上来了，命中率看 [cacheHitRate]。
     *
     * 默认 0 是**正确语义**，不是「还没拿到」：累加型数据从 0 开始，老数据读回来
     * 没有这两个键 → optLong 给 0，正好落在「还没用过」上。**不要**为此写迁移。
     */
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,

    /**
     * 缓存命中 / 未命中的输入 token。
     *
     * 见 [cacheHitRate] 的注释：**0 不等于「没统计过」**，判「有没有数据」
     * 要看两者之和。老会话读回来这两个键不存在 ⇒ optLong 给 0 ⇒ 落在
     * 「没统计过」上，这正是要的行为，**不要**为此写迁移。
     */
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
) {
    val displayTitle: String get() = title.ifBlank { "新对话" }

    /**
     * 累计总量。列表和底部那行显示用的都是它。
     *
     * ⚠️ **不要往这里加 [cacheHitTokens] / [cacheMissTokens]**：
     * 那两个是**输入侧的子集**（`prompt_tokens == hit + miss`），不是新烧的 token。
     * 加进来会让「本对话已用 xx tokens」凭空翻倍，语义漂移成「输入 token 记了两遍」。
     * 命中率跟总量是两件独立的事，各有各的口径。
     */
    val totalTokens: Long get() = promptTokens + completionTokens

    /**
     * 缓存命中率，**没有数据时是 null，不是 0**。
     *
     * null 与 0 是两件完全不同的事，混起来会把「这条会话从来没统计过」
     * 显示成「这条会话一个字都没命中」，看起来就是个 bug：
     *   - null：hit + miss == 0 ⇒ 在缓存字段接入之前攒的老对话（落盘里根本没这两个键），
     *     或者服务端这次压根没带 cache 字段 ⇒ **命中率整段不显示**；
     *   - 0f  ：真的统计到了，且命中数为 0 ⇒ **显示「命中 0%」**。
     *
     * 分母用 `hit + miss`，**不用 [promptTokens]**：前者是这一档数据自己的总量，
     * 即使将来服务端对 `prompt_tokens` 的口径变了（比如不把缓存部分算进去），
     * 这里还是一个自洽的比例；后者则会跟着口径漂移，算出 >100% 的数。
     */
    val cacheHitRate: Float? get() {
        val total = cacheHitTokens + cacheMissTokens
        return if (total == 0L) null else cacheHitTokens.toFloat() / total
    }

    /**
     * DS 回过话没有。**这是「这条会话到底算不算一条真正的对话」的判据。**
     *
     * 规则：**有 DS 回复才算新对话**。
     *
     * 为什么必须有这么一条：会话是从点「＋」那一刻在内存里出现的，但那时候它什么都不
     * 是 —— 用户在打字页一个字没打就退出，它就该安静消失，不该在屏幕上留下任何痕迹。
     * 真正让它变成「一条对话」的是 DS 回过话（[ChatEngine.send] 里用户消息和助手占位
     * 是一起落下的，所以实际等价于「在这个会话里说过话」，和首页列表、落盘过滤用的
     * 是**同一条线**，全应用一个口径）。
     *
     * 反过来说：只要 [hasReply] 为 false，那条会话在任何地方都只能被当成「还没有内容」，
     * 不能当成「本来有、现在没了」—— 后者会弹一句「这个对话已经不在了」，而它压根
     * 没存在过。
     */
    val hasReply: Boolean get() = messages.any { it.role == ChatRole.ASSISTANT }

    /** 列表里用来排序的时间：有内容就按最后一条消息算 */
    val sortKey: Long get() = messages.lastOrNull()?.timestamp ?: updatedAt
}
