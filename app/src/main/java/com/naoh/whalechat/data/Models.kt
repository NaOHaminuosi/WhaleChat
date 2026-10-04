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
) {
    val displayTitle: String get() = title.ifBlank { "新对话" }

    /**
     * DS 回过话没有。**这是「这条会话到底算不算一条真正的对话」的判据。**
     *
     * 规则是：**有 DS 回复才算新对话**。
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
