package com.naoh.whalechat.data

import com.naoh.whalechat.net.DeepSeekClient
import com.naoh.whalechat.net.DeepSeekException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 造人流程的结果。
 *
 * 三种结局分开是有原因的：它们各自的后续动作完全不同。
 *  * [Done] → 存角色、建会话、**直接进聊天页**；
 *  * [Abandoned] → 用户已经划走了，结果作废，**什么都不做**；
 *  * [Failed] → 退回上一页并说明原因，**不产生角色**。
 */
sealed interface PersonaResult {
    data class Done(val persona: Persona) : PersonaResult
    data object Abandoned : PersonaResult
    data class Failed(val hint: String) : PersonaResult
}

/**
 * 把「用户那句描述」捏成一个能对话的角色。**全应用只有这一条捏人管道。**
 *
 * 预设改完之后也是把字段拼成一句话送进来，跟「自己说一句」走的是同一个函数。
 * 手机端网页上那个「用 AI 生成一份」按钮走的也是这里（见 `KeyImportServer`）——
 * 三条入口共用一套提示词、一套字段、一套兜底，**不存在「手表简易版 / 手机完整版」
 * 两个质量档**。预设**绝不能自成一套生成逻辑** —— 兜底、超时、取消的规则一旦写两份，
 * 迟早有一份忘了改。
 *
 * ## 取消：只作废结果，不掐 HTTP
 *
 * 等待页的规矩是「**离开 = 取消**」，而 [DeepSeekClient.complete] 是非流式的 suspend，
 * 不走 `activeCall` 那条取消通道。为捏人这一个调用引入第二套取消机制不值，所以做法是：
 * 本次捏人带一个**令牌**，结果回来时令牌对不上就丢掉。
 *
 * 代价是一次浪费的调用（钱已经花了），换来的是行为正确：用户划走之后，
 * 那个角色**不会**在他以为已经取消之后凭空冒出来。
 *
 * 令牌是 [`String`] 而不是一个布尔「已取消」旗：旗要手动清，中途再开一次捏人就会
 * 互相踩；令牌天然区分「哪一次」。
 */
object PersonaFactory {

    private val client = DeepSeekClient()

    /** 当前有效的那次捏人的令牌；null 表示没有正在跑的。 */
    private val token = AtomicReference<String?>(null)

    /**
     * 捏人的读超时，**单独设**。
     *
     * `complete()` 默认吃的是 OkHttp 的 180 秒读超时 —— 那是给思考模式的首包留的，
     * 拿它捏人太长（手表上盯着转圈超过一分钟，用户就认为它死了）。
     *
     * 但也不能太短：人设拆成六块之后要吐的东西翻了一倍（其中 `examples` 是好几轮对话），
     * `maxTokens` 从 700 提到 1400，服务端**生成时间本身就翻了倍**。
     * 原来的 30 秒是按「一段 systemPrompt」估的，现在会在正常的慢响应上误判超时 ——
     * 那不是「失败」，是钱已经花了却没拿到结果。
     *
     * 90 秒是「够慢响应跑完」和「用户还愿意等」之间取的数：它比默认的 180 秒短一半，
     * 而且等待页有取消（返回键），真嫌久随时能退。
     */
    const val PERSONA_READ_TIMEOUT_SECONDS = 90L

    /** 开一次新的捏人，返回它的令牌。上一次没跑完的会被顶掉（它的结果自然作废）。 */
    fun begin(): String {
        val fresh = UUID.randomUUID().toString()
        token.set(fresh)
        return fresh
    }

    /**
     * 宣布这次捏人作废。
     *
     * 只在令牌还是自己那个的时候清空 —— 万一用户已经退了又开了新的一次，
     * 旧页面迟到的 onDispose 不能把新的那次一起废掉。
     */
    fun abandon(own: String) {
        token.compareAndSet(own, null)
    }

    /**
     * 造一个人。
     *
     * 失败分两种，处理**必须不同**：
     *
     *  * **拿到字但 JSON 坏了** → 静默兜底：人格内核取用户原话、名字取前四字，照常进聊天页。
     *    模型偶尔吐一坨不能解析的东西不值得打断用户。
     *  * **网络失败 / Key 无效 / 内容为空** → **不兜底**，返回 [PersonaResult.Failed]
     *    让页面说明原因。为什么不兜底：造出来的是拿用户原话当人设的假角色，体验落差大，
     *    而且用户就发现不了自己的 Key 有问题 —— 他下次还会照这么用。
     */
    suspend fun generate(
        apiKey: String,
        own: String,
        description: String,
        source: PersonaSource = PersonaSource.CUSTOM,
    ): PersonaResult {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return PersonaResult.Failed("先说说你想要什么样的人")

        if (!isAlive(own)) return PersonaResult.Abandoned

        val raw = try {
            client.complete(
                apiKey = apiKey,
                systemPrompt = META_PROMPT,
                userContent = trimmed,
                // 六块里光 examples 就是好几轮对话，700 会被从中间截断成半个 JSON ——
                // 那种情况连宽容解析都救不回来（截断点上既没有闭合括号也没有结尾）。
                maxTokens = 1400,
                // 捏人是要「有想法」的活，温度必须比起标题那种抽取型任务高
                temperature = 0.9,
                jsonObject = true,
                readTimeoutSeconds = PERSONA_READ_TIMEOUT_SECONDS,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户划走等待页 → 这一页被销毁 → 这个协程被取消。**必须原样抛出**：
            // CancellationException 是 Exception 的子类，被下面那条 catch 收走的话，
            // 取消会变成一条「生成失败」，然后页面还会拿着它往一个已经不在的页面上贴提示。
            throw e
        } catch (e: DeepSeekException) {
            return if (isAlive(own)) PersonaResult.Failed(e.hint) else PersonaResult.Abandoned
        } catch (e: Exception) {
            return if (isAlive(own)) {
                PersonaResult.Failed("生成失败：${e.message ?: "未知错误"}")
            } else {
                PersonaResult.Abandoned
            }
        }

        // 结果回来之后再对一次令牌：对不上说明用户已经划走了，直接丢掉。
        if (!isAlive(own)) return PersonaResult.Abandoned

        // 内容为空 = 这次调用是失败的，不属于「JSON 坏了」那一类，不兜底
        if (raw.isBlank()) return PersonaResult.Failed("模型没有返回内容，稍后再试")

        return PersonaResult.Done(parse(raw, trimmed, source))
    }

    private fun isAlive(own: String): Boolean = token.get() == own

    /**
     * 宽容解析。
     *
     * **指望模型吐一个干净 JSON 会在真机上翻车** —— 围栏、前后寒暄、字段缺失都见过。
     * 所以三步走：剥 ```` ```json ```` 围栏 → 取第一个 `{` 到最后一个 `}` → 缺字段补默认。
     * 全都没拿到才走静默兜底（人格内核取用户原话、名字取前四字）。
     */
    private fun parse(raw: String, description: String, source: PersonaSource): Persona {
        val obj = extractJsonObject(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }

        val name = obj?.str("name")?.take(PERSONA_NAME_MAX) ?: fallbackName(description)
        val tagline = obj?.str("tagline")?.take(PERSONA_TAGLINE_MAX).orEmpty()
        val greeting = obj?.str("greeting") ?: DEFAULT_GREETING

        // 每一块都单独兜底，不是「整个 JSON 坏了才兜」：
        // 模型漏写一块是常事（尤其是 rules 和 scenario），而**人格内核漏了最致命**——
        // 它是唯一一块能独立成 prompt 的内容。所以它缺了就退回用户原话，
        // 至少保证「有人」而不是「有个名字的空壳」。
        val soul = obj?.str("soul") ?: description

        // 模型要的 avatarSeed 目前**不落库**：Persona 里没有这个字段。
        // 让它吐出来是为了给「角色长什么样」一个锚点，将来真做头像时直接用。
        val now = System.currentTimeMillis()
        return Persona(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { fallbackName(description) },
            tagline = tagline,
            soul = soul,
            style = obj?.str("style").orEmpty(),
            rules = obj?.str("rules").orEmpty(),
            scenario = obj?.str("scenario").orEmpty(),
            examples = obj?.str("examples")?.takeIf { !it.isPlaceholder() }.orEmpty(),
            greeting = greeting,
            sourceDescription = description,
            source = source,
            createdAt = now,
            lastChatAt = 0L,
        )
    }

    /**
     * 这一块是不是「什么都没写」的占位答案。
     *
     * 提示词里明写了「必须产出示例对话，不许写「无」」，但模型偶尔还是回一个「无」
     * 或者「（无）」。那不是示例，是一条噪声 —— 塞进 system prompt 只会让模型以为
     * 「这里本来就该是空的」。所以在这一层把它当空处理，宁可少一块也不要一块假内容。
     */
    private fun String.isPlaceholder(): Boolean {
        val cleaned = trim().trim('（', '）', '(', ')', '【', '】', '「', '」', '：', ':', '-', '—', '。', '.', ' ')
        return cleaned.isEmpty() || cleaned.lowercase() in PLACEHOLDER_WORDS
    }

    /**
     * 取一个非空的字符串字段；缺失、显式 null、空串一律给 null。
     *
     * `isNull` 那一道必须走在前面：字段值是显式 JSON `null` 时，`optString` 会走
     * `String.valueOf(JSONObject.NULL)` 返回字面量 `"null"`，于是角色名会变成 "null"。
     * 同一个坑在 DeepSeekClient 的流式解析里也踩过。
     */
    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    /** 剥围栏 + 取最外层的一对花括号。拿不到就返回 null，交给上层兜底。 */
    private fun extractJsonObject(raw: String): String? {
        var text = raw.trim()

        if (text.startsWith("```")) {
            text = text.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```Json")
                .removePrefix("```")
            val close = text.lastIndexOf("```")
            if (close >= 0) text = text.substring(0, close)
            text = text.trim()
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /**
     * 名字兜底取用户原话的前四个字。
     *
     * 不用整句：手表列表的标题位摆不下，而且一句「我想要一个会陪我聊天的…」
     * 当名字比没有名字更滑稽。
     */
    private fun fallbackName(description: String): String {
        val cleaned = description.replace(Regex("\\s+"), "").trim()
        return cleaned.take(PERSONA_NAME_MAX).ifBlank { "新角色" }
    }

    /** 被当成「没写」的几种说法。只在小写比较前先把标点和空格削掉。 */
    private val PLACEHOLDER_WORDS = setOf(
        "无", "暂无", "没有", "略", "不适用", "不需要", "空", "省略",
        "none", "n/a", "na", "null", "nil", "empty",
    )

    /**
     * 捏人的 meta prompt。
     *
     * 三件事在这里定死：
     *
     *  1. **必须按 [Persona] 那六块分开产出，不许再压成一段。** 压成一段时模型自己
     *     分不清哪句是性格、哪句是语气、哪句是边界，扮演质量立刻掉一档；
     *     分开之后每块都有标签，运行时直接拼进 system prompt。
     *  2. **greeting 必须单独约束。** 模型默认会写「你好，我是周野，很高兴认识你」——
     *     第一句是这样的话，这个角色直接就废了。所以要的是「在那个场景里、此刻
     *     会对你说的第一句话，带情境，**不许自我介绍**」。
     *  3. **examples 必须产出。** 这是六块里性价比最高的一块：少样本示例比五百字
     *     性格描述更能把语气定住。模型有偷懒回「无」的倾向，所以要明确禁止。
     *
     * 提示词里必须出现「JSON」字样 —— 开了 `response_format: json_object` 之后，
     * 官方要求提示词里有这个字，否则直接 400。
     */
    private val META_PROMPT = """
        你是一个角色设计师。用户会用一句话描述他想要的角色，你要把它扩写成一个可以直接拿去对话的虚拟人。

        只输出一个 JSON 对象，不要输出任何别的文字、解释或 Markdown 代码块。字段如下：

        {
          "name": "给这个角色起的名字，不超过 6 个汉字",
          "tagline": "一句话说清 TA 是什么样的人，不超过 12 个字",
          "soul": "人格内核，要求见下",
          "style": "说话风格，要求见下",
          "rules": "行为规则，要求见下",
          "scenario": "场景设定，要求见下",
          "examples": "2 到 4 轮示例对话，要求见下",
          "greeting": "这个角色在当下的场景里，对用户说的第一句话"
        }

        soul（人格内核）的要求：
        - 写 TA 的性格、在意什么、怕什么、遇到事情会怎么反应。要具体到行为，不要空词。
        - 「善良温柔」这种不合格；「嘴上不饶人但真会替你着急，你摆烂时会直接数落你，但最后一定给你一个能做的小事」这种合格。
        - 三到五句。

        style（说话风格）的要求：
        - 写语气、用词习惯、口头禅、句子长短。这一块直接决定「听起来像不像 TA」，比性格描述更影响观感。
        - 必须写上「简短、像发消息、一次只说一到两句」。
        - 两到三句。

        rules（行为规则）的要求：
        - 写 TA 会做什么、不会做什么、边界在哪。不写这一块，聊久了人设会飘。
        - 例如：不主动打听对方的隐私；被问到自己的事就岔开；不说教、不总结、不列点。
        - 两到三句。

        scenario（场景设定）的要求：
        - 此刻在哪、你们是什么关系、为什么会聊起来。一到两句。

        examples（示例对话）的要求：
        - **必须产出，2 到 4 轮，绝对不许写「无」「省略」或者留空。**
        - 格式固定，一行一句，用户的句子用「你：」开头，角色的句子用 TA 的名字开头。
          例如：
          你：今天又考砸了。
          周野：几分？
          你：……61。
          周野：行，先把错题发我。
        - 对话要能看出这个人的语气和边界，长度跟真实聊天一样短。

        greeting（开场白）的要求：
        - 要带情境，是 TA 此刻会对用户说的第一句话，像发消息那样短，一到两句。
        - 绝对不要自我介绍。不要出现「你好，我是××」「很高兴认识你」这种句式。
        - 不要用「有什么可以帮你的吗」这类客服式开场，直接进入状态。

        所有字段都用第二人称或直接叙述写（不要写「TA 会……」，要写成「你会……」或者直接写出来），
        不要写输出格式、字数限制或任何技术性说明 —— 那部分由系统追加。
    """.trimIndent()
}
