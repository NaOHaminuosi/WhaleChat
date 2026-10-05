package com.naoh.whalechat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 角色的持久化：整个列表写成一份 JSON（`filesDir/personas.json`）。
 *
 * 写盘套路**照抄 `ConversationStore`**，一个字都不自创：
 *
 *  * 临时文件 + rename，断电不会留下半截 JSON 把角色全毁掉；
 *  * rename 失败时**用 copyTo 覆盖，绝不先 delete 原文件** ——
 *    「先删再 rename」两次都失败就全没了；
 *  * 手写 `org.json`，**不引入 Gson / kotlinx.serialization** ——
 *    那会带进一整类 R8 keep 规则问题，而 release 包开着 `isMinifyEnabled = true`。
 *
 * 与 `ConversationStore` 唯一的差别是 [save] 里多了一次 `fd.sync()`，
 * 理由见那个方法上的注释。
 */
class PersonaStore(private val file: File) {

    fun load(): List<Persona> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(KEY) ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::toPersona)
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 落盘。**这个方法返回时，内容已经在磁盘上了。**
     *
     * 为什么这里比 `ConversationStore` 多一次 `fd.sync()`：手机端那一页会向用户
     * 明确宣称「已保存到手表」，而它是**同步等这次写盘返回之后才回 HTTP 响应**的。
     * 只 writeText + rename 的话，数据还在页缓存里，用户此刻拔电/杀进程
     * （手机上看到「已保存」之后的第一个动作往往就是切走）就可能丢掉刚填的六块。
     * `Conversation` 那边没这个承诺，所以不动它。
     *
     * 目录本身的 fsync 在纯 Java 里做不到，这里只保证**文件内容**已经落地；
     * rename 的元数据由 ext4 的默认挂载选项兜着，够用。
     */
    fun save(personas: List<Persona>) {
        val array = JSONArray()
        personas.forEach { array.put(fromPersona(it)) }
        val text = JSONObject()
            .put(VERSION_KEY, FORMAT_VERSION)
            .put(KEY, array)
            .toString()

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true) }
            tmp.delete()
        }
    }

    private fun fromPersona(p: Persona) = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("tagline", p.tagline)
        // 人设六块。**存的就是这六块本身，不存拼好的那份正文** ——
        // 存两份就会出现「手机关了 soul、拼好的那份还是旧的」这类不一致。
        .put("soul", p.soul)
        .put("style", p.style)
        .put("rules", p.rules)
        .put("scenario", p.scenario)
        .put("examples", p.examples)
        .put("greeting", p.greeting)
        .put("sourceDescription", p.sourceDescription)
        .put("source", p.source.name)
        .put("createdAt", p.createdAt)
        .put("lastChatAt", p.lastChatAt)

    private fun toPersona(o: JSONObject): Persona {
        // 缺 id 的角色没法跟会话对上，直接丢掉比留一个空壳好
        val id = o.optString("id")
        if (id.isBlank()) throw IllegalArgumentException("persona 缺少 id")
        return Persona(
            id = id,
            name = o.optString("name").take(PERSONA_NAME_MAX),
            tagline = o.optString("tagline").take(PERSONA_TAGLINE_MAX),
            // 老的 `systemPrompt` 键**故意不读**：项目从未公开发布，没有需要照顾的
            // 存量数据，为一个只存在于开发机上的旧格式写迁移得不偿失。读不到就是六块
            // 全空 —— 那样的角色聊起来等于裸 prompt，删掉重造即可。
            soul = o.optString("soul"),
            style = o.optString("style"),
            rules = o.optString("rules"),
            scenario = o.optString("scenario"),
            examples = o.optString("examples"),
            greeting = o.optString("greeting"),
            sourceDescription = o.optString("sourceDescription"),
            // 认不出来的来源一律当「用户自己写的」—— 预设只是个文案来源，不影响行为
            source = runCatching { PersonaSource.valueOf(o.optString("source")) }
                .getOrDefault(PersonaSource.CUSTOM),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastChatAt = o.optLong("lastChatAt", 0L),
        )
    }

    private companion object {
        const val KEY = "personas"

        /** 版本号那个键名。 */
        const val VERSION_KEY = "version"

        /**
         * 文件版本。
         *
         * 字段结构换过一次（`systemPrompt` 一段话 → 六块），所以往前挪了一格。
         * **没有任何代码读它**，也刻意没写迁移（理由见 [toPersona]）——
         * 留着它只是让以后有人直接看文件时能知道这是哪一版的结构。
         */
        const val FORMAT_VERSION = 2
    }
}
