package com.naoh.whalechat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话持久化：整个列表写成一份 JSON。
 *
 * 手表上会话数量是几十条量级，单文件足够；写盘走「临时文件 + rename」，
 * 断电时不会留下半截 JSON 把历史全毁掉。
 */
class ConversationStore(private val file: File) {

    fun load(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(KEY) ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::toConversation)
            }
        }.getOrElse { emptyList() }
    }

    fun save(conversations: List<Conversation>) {
        val array = JSONArray()
        conversations.forEach { array.put(fromConversation(it)) }
        val text = JSONObject()
            .put(FORMAT_VERSION, 1)
            .put(KEY, array)
            .toString()

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 某些文件系统上 rename 到已存在的目标会失败。绝不能用
            // 「先 delete 原文件再 rename」——两次 rename 都失败时历史就全没了。
            // 改成原地覆盖写，再顺手清掉临时文件。
            runCatching { tmp.copyTo(file, overwrite = true) }
            tmp.delete()
        }
    }

    private fun fromConversation(c: Conversation) = JSONObject()
        .put("id", c.id)
        .put("title", c.title)
        .put("createdAt", c.createdAt)
        .put("updatedAt", c.updatedAt)
        .put("titleGenerated", c.titleGenerated)
        // 普通对话写空串，不写 JSON null：读回来的判据统一是「空串 = 不是角色会话」，
        // 少一个 null 分支就少一处漏判。
        .put("personaId", c.personaId.orEmpty())
        .put("promptTokens", c.promptTokens)
        .put("completionTokens", c.completionTokens)
        // 顺序无所谓，但**必须跟下面 readonly 那边成对**：
        // 只写不读 = 界面永远拿不到数据（「解析对了、发射漏了」同一类）。
        .put("cacheHitTokens", c.cacheHitTokens)
        .put("cacheMissTokens", c.cacheMissTokens)
        .put(
            "messages",
            JSONArray().apply {
                c.messages.forEach { m ->
                    put(
                        JSONObject()
                            .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                            .put("content", m.content)
                            .put("reasoning", m.reasoning)
                            .put("timestamp", m.timestamp)
                            .put("error", m.error),
                    )
                }
            },
        )

    private fun toConversation(o: JSONObject): Conversation {
        val messages = o.optJSONArray("messages") ?: JSONArray()
        return Conversation(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            title = o.optString("title"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            titleGenerated = o.optBoolean("titleGenerated", false),
            // 向后兼容：老文件里根本没有这个键，optString 会给出空串 → null。
            // 反过来说，**绝不能**拿 `has("personaId")` 当判据，那对老文件恒为 false。
            personaId = o.optString("personaId").takeIf { it.isNotBlank() },
            promptTokens = o.optLong("promptTokens", 0L),
            completionTokens = o.optLong("completionTokens", 0L),
            // ⚠️ 默认 **0L，不是 -1**：判据统一是「hit + miss == 0 ⇒ 没数据」，
            // 引入 -1 会多出一个要到处单独处理的哨兵值。老文件里没有这两个键
            // → 都读成 0 → [Conversation.cacheHitRate] 给 null → 命中率不显示。
            // **这正是要的行为，不要为此写迁移**。
            cacheHitTokens = o.optLong("cacheHitTokens", 0L),
            cacheMissTokens = o.optLong("cacheMissTokens", 0L),
            messages = (0 until messages.length()).mapNotNull { i ->
                val m = messages.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    role = if (m.optString("role") == "user") ChatRole.USER else ChatRole.ASSISTANT,
                    content = m.optString("content"),
                    reasoning = m.optString("reasoning"),
                    timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    error = m.optBoolean("error", false),
                )
            },
        )
    }

    private companion object {
        const val KEY = "conversations"
        const val FORMAT_VERSION = "version"
    }
}
