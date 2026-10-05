package com.naoh.whalechat.net

import com.naoh.whalechat.data.ChatMessage
import com.naoh.whalechat.data.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** DeepSeek 官方 API 地址（OpenAI 兼容）。 */
const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"

/**
 * 当前通用模型：DeepSeek-V4.1-Flash。
 *
 * 旧的 `deepseek-chat` / `deepseek-reasoner` 别名已于 2026-07-24 停服。
 * 现在「快答 / 深度思考」是同一个模型上的思考开关，不再是两个模型名。
 */
const val MODEL_FLASH = "deepseek-flash"

/** 思考强度。官方默认就是 high，手表上没必要再往上调（max 明显更慢）。 */
const val REASONING_EFFORT = "high"

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** 面向用户的错误：hint 是可以直接显示在手表上的中文提示。 */
class DeepSeekException(val hint: String, val detail: String? = null) :
    Exception(if (detail.isNullOrBlank()) hint else "$hint：$detail")

/** 流式增量：正文与思维链分开，只有开着思考模式才会有后者。 */
sealed interface StreamEvent {
    val text: String

    data class Content(override val text: String) : StreamEvent
    data class Reasoning(override val text: String) : StreamEvent

    /**
     * 最后一个 chunk 里的 token 用量（`usage`）。
     *
     * 文本恒为空串 —— 它不是正文，只是这条流收尾时的计费数据。
     * [promptTokens] 是「有效输入 token」，[completionTokens] 是「生成 token」。
     *
     * [cacheHitTokens] / [cacheMissTokens] 是**输入侧**的两个子集：
     * `prompt_tokens == hit + miss`（前提是服务端把缓存部分也算进 prompt ——
     * 所以判命中率时**分母用 hit + miss 而不是 promptTokens**，见 [Conversation.cacheHitRate]）。
     * 服务端没给、或中间 chunk 那个全零占位 ⇒ 都是 0。
     */
    data class Usage(
        override val text: String = "",
        val promptTokens: Long,
        val completionTokens: Long,
        /** 缓存命中的输入 token。服务端没给 / 中间占位 chunk ⇒ 0。 */
        val cacheHitTokens: Long = 0L,
        /** 缓存未命中的输入 token。同上。 */
        val cacheMissTokens: Long = 0L,
    ) : StreamEvent
}

class DeepSeekClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 思考模式的首包可能很慢，读超时给足；流式下每个 token 都会刷新这个计时
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 同一时刻只允许一条流，stop() 需要能立刻掐断它。 */
    private val activeCall = AtomicReference<okhttp3.Call?>(null)

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    /**
     * 流式对话。整个 flow 体跑在 IO 线程；取消 collect 会关闭连接。
     * emit 的是增量片段，调用方自己累加。
     *
     * @param thinking 打开后模型先输出思维链再给答案；关闭则直接作答（更快更省）。
     */
    fun stream(
        apiKey: String,
        history: List<ChatMessage>,
        systemPrompt: String = "",
        thinking: Boolean = false,
    ): Flow<StreamEvent> = flow {
        val payload = buildBody(
            history = history,
            systemPrompt = systemPrompt,
            stream = true,
            thinking = thinking,
        )
        val request = baseRequest(apiKey)
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
                val source = response.body?.source() ?: throw DeepSeekException("服务端返回了空响应")

                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    // SSE：空行是分隔符，"data:" 才是载荷，": " 开头是心跳注释
                    if (line.isEmpty() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break

                    val delta = parseDelta(data) ?: continue
                    // Usage 必须**单独放行**：它的 [StreamEvent.text] 恒为空串（它不是正文，
                    // 只是这条流收尾时的计费数据）。沿用一个「有文字才 emit」的通用判据，
                    // 会把 usage 整条丢掉 —— 现象就是上层的 `is StreamEvent.Usage ->` 分支
                    // 一次都走不到，所有会话的用量恒为 0、界面上什么都不显示。
                    if (delta is StreamEvent.Usage || delta.text.isNotEmpty()) emit(delta)
                }
            }
        } catch (e: IOException) {
            // cancel() 会以 IOException 的形式抛出，别把它显示成网络错误
            if (call.isCanceled()) return@flow
            throw DeepSeekException(networkHint(e), e.message)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 一次性补全：用来生成会话标题、以及捏一个角色的人设。
     * 失败就交给调用方处理，不要打断聊天。
     *
     * @param jsonObject 要求服务端以 JSON 对象作答（`response_format`）。提示词里必须
     *   出现「JSON」字样，否则官方会直接报错 —— 调用方负责写上。
     * @param readTimeoutSeconds 本次调用单独的读超时。null 表示沿用客户端默认的 180 秒
     *   （那是给思考模式首包留的）。**捏人必须单独设短**：手表上盯着转圈超过 30 秒，
     *   用户就认为它死了 —— 那个数写在 `PersonaFactory.PERSONA_READ_TIMEOUT_SECONDS`。
     */
    suspend fun complete(
        apiKey: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int = 40,
        temperature: Double = 0.2,
        jsonObject: Boolean = false,
        readTimeoutSeconds: Long? = null,
    ): String = withContext(Dispatchers.IO) {
        // 起标题这种小事不开思考：又快又便宜，也不需要推理
        val payload = buildBody(
            history = listOf(ChatMessage(ChatRole.USER, userContent)),
            systemPrompt = systemPrompt,
            stream = false,
            thinking = false,
            temperature = temperature,
            maxTokens = maxTokens,
            jsonObject = jsonObject,
        )
        val request = baseRequest(apiKey)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            clientFor(readTimeoutSeconds).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
                val raw = response.body?.string().orEmpty()
                JSONObject(raw)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .trim()
            }
        } catch (e: IOException) {
            throw DeepSeekException(networkHint(e), e.message)
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * 按需派生一个读超时更短的客户端。
     *
     * `newBuilder().build()` 出来的实例**共用原来的连接池与调度器**，所以这不是
     * 「为了一次调用新建一套 HTTP 栈」，只是换了一个超时数字。捏人一次只会走一次，
     * 更没有池化它的必要。
     */
    private fun clientFor(readTimeoutSeconds: Long?): OkHttpClient =
        if (readTimeoutSeconds == null) {
            http
        } else {
            http.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        }

    private fun baseRequest(apiKey: String) = Request.Builder()
        .url("$DEEPSEEK_BASE_URL/chat/completions")
        .addHeader("Authorization", "Bearer ${apiKey.trim()}")
        .addHeader("Content-Type", "application/json")

    private fun buildBody(
        history: List<ChatMessage>,
        systemPrompt: String,
        stream: Boolean,
        thinking: Boolean,
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonObject: Boolean = false,
    ): String {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt.trim()))
        }
        history.forEach { message ->
            // 出错的本地提示、以及还没说完的空消息，都不能回传给模型
            if (message.error) return@forEach
            if (message.content.isBlank()) return@forEach
            messages.put(
                JSONObject()
                    .put("role", if (message.role == ChatRole.USER) "user" else "assistant")
                    .put("content", message.content),
            )
        }
        return JSONObject()
            .put("model", MODEL_FLASH)
            .put("messages", messages)
            .put("stream", stream)
            .put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            .apply {
                // 流式时要求服务端带上 usage。其实不开这个，最后一个 chunk 也照样带完整
                // usage（它是 DeepSeek 的固定行为）；开它只为对齐 OpenAI 兼容实现的行为 ——
                // 中间 chunk 也会带一个 usage 字段（值为 null），解析逻辑统一按
                // 「有非零 usage 才收」处理，不会把占位当数据。
                if (stream) {
                    put("stream_options", JSONObject().put("include_usage", true))
                }
                // 官方说明：思考模式下 temperature 不生效（发了也不报错，只是被忽略）。
                // 既然没作用就别发，免得看起来像在调参。
                if (thinking) {
                    put("reasoning_effort", REASONING_EFFORT)
                } else if (temperature != null) {
                    put("temperature", temperature)
                }
                if (maxTokens != null) put("max_tokens", maxTokens)
                // 官方约束：开了 json_object 就必须在提示词里出现「JSON」字样，
                // 否则直接 400。调用方的 system prompt 里都写了这个字。
                //
                // 即便服务端答应给 JSON，也不要指望它就一定吐一个干净的 JSON 对象 ——
                // 围栏、前后废话、字段缺失都出现过。调用方必须自带宽容解析。
                if (jsonObject) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }
            .toString()
    }

    private fun parseDelta(data: String): StreamEvent? = runCatching {
        val json = JSONObject(data)

        // usage 必须放在 choices 检查**之前**：最后一个 chunk 的 `choices` 是空数组
        // （`optJSONArray("choices")?.optJSONObject(0)` 拿到 null），但那个 chunk 恰恰
        // 带着完整的 usage。放在后面的话会被 `?: return null` 拦掉，usage 永远收不到。
        val usage = json.optJSONObject("usage")
        if (usage != null) {
            val prompt = usage.optLong("prompt_tokens", 0L)
            val completion = usage.optLong("completion_tokens", 0L)
            // 全零的 usage（中间 chunk 因为开了 include_usage 而带的 null/空 usage）
            // 不产生事件 —— 那只是占位，不是真数据。
            //
            // ⚠️ 发射判据**故意只看 prompt / completion**，不许破。
            // cache 两个字段是**搭车**的：把它们加进这个判据，会让「只有 cache 数据、
            // 没有 prompt」的 chunk 也发射一条 usage —— 那是一个本来不存在的计费事件。
            if (prompt > 0L || completion > 0L) {
                return StreamEvent.Usage(
                    promptTokens = prompt,
                    completionTokens = completion,
                    cacheHitTokens = usage.optLong("prompt_cache_hit_tokens", 0L),
                    cacheMissTokens = usage.optLong("prompt_cache_miss_tokens", 0L),
                )
            }
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: return null

        // 注意必须先用 isNull 挡一下：字段值是显式 JSON `null` 时，
        // optString 会走 String.valueOf(JSONObject.NULL) 返回字面量 "null"，
        // 于是回答里会凭空多出一段 "null" 文字。
        if (!delta.isNull("reasoning_content")) {
            val reasoning = delta.optString("reasoning_content")
            if (reasoning.isNotEmpty()) return StreamEvent.Reasoning(reasoning)
        }
        if (!delta.isNull("content")) {
            val content = delta.optString("content")
            if (content.isNotEmpty()) return StreamEvent.Content(content)
        }
        null
    }.getOrNull()

    private fun httpError(response: Response): DeepSeekException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()

        val hint = when (response.code) {
            400 -> "请求格式有误"
            401 -> "API Key 无效，请到设置里重新填写"
            402 -> "账户余额不足"
            422 -> "请求参数不合法"
            429 -> "请求过于频繁，请稍后再试"
            500, 502, 503, 504 -> "DeepSeek 服务端暂时不可用"
            else -> "请求失败（HTTP ${response.code}）"
        }
        return DeepSeekException(hint, detail)
    }

    private fun networkHint(e: IOException): String = when {
        e is java.net.UnknownHostException -> "网络不可用，检查手表连接"
        e is java.net.SocketTimeoutException -> "连接超时，请重试"
        else -> "网络错误"
    }
}
