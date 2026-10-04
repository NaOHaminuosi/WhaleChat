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
                    if (delta.text.isNotEmpty()) emit(delta)
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
     * 一次性补全：用来生成会话标题。
     * 失败就交给调用方处理，不要打断聊天。
     */
    suspend fun complete(
        apiKey: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int = 40,
        temperature: Double = 0.2,
    ): String = withContext(Dispatchers.IO) {
        // 起标题这种小事不开思考：又快又便宜，也不需要推理
        val payload = buildBody(
            history = listOf(ChatMessage(ChatRole.USER, userContent)),
            systemPrompt = systemPrompt,
            stream = false,
            thinking = false,
            temperature = temperature,
            maxTokens = maxTokens,
        )
        val request = baseRequest(apiKey)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { response ->
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
                // 官方说明：思考模式下 temperature 不生效（发了也不报错，只是被忽略）。
                // 既然没作用就别发，免得看起来像在调参。
                if (thinking) {
                    put("reasoning_effort", REASONING_EFFORT)
                } else if (temperature != null) {
                    put("temperature", temperature)
                }
                if (maxTokens != null) put("max_tokens", maxTokens)
            }
            .toString()
    }

    private fun parseDelta(data: String): StreamEvent? = runCatching {
        val json = JSONObject(data)

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
