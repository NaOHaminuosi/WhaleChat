package com.naoh.whalechat.net

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞语音听写（流式版）。
 *
 * ## 这里为什么只剩一个产品
 *
 * 讯飞控制台里看着有好几档能选，实际能用的只有这一档：
 *
 * - **语音听写（流式版）**，端点 `iat-api.xfyun.cn/v2/iat`。控制台建完应用默认
 *   每日 500 次免费，个人用足够。**本项目唯一跑通过的一档。**
 * - 语音听写大模型（中英）V1.0，端点 `iat.xf-yun.com/v1`：官方只对**历史已购用户**
 *   开放新开通入口。拿真凭证实测握手：服务端回 `10404 no category route found`
 *   然后主动断连 —— 新账号连门都进不去，代码留着也永远验证不了，已删除。
 * - 曾经按文档猜过一版「大模型 V2.0」：连端点主机名都不存在（DNS 解析直接失败），
 *   等于在设置里摆一个必然失败的选项，已删除。
 *
 * 这三者**鉴权算法完全相同**（HMAC-SHA256 签 `host + date + request-line`），
 * 差别只在端点和报文信封。所以将来真要接别的讯飞产品，改 [XfyunAuth] 里的
 * host/path 和 [frameMessage] 的信封即可，不用动签名。
 *
 * @see <a href="https://www.xfyun.cn/doc/asr/voicedictation/API.html">语音听写(流式版)WebAPI 文档</a>
 */
data class XfyunConfig(
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
    /** `zh_cn` 或 `en_us` */
    val language: String,
    /** 断句静音时长，毫秒。手表上说短句，不用等太久 */
    val eosMs: Int = 3000,
)

/**
 * 讯飞语音听写（流式）。
 *
 * **边录边发、边发边出字**：音频按 1280 字节（40ms）切片推上去，
 * 服务端把识别结果一片一片推回来。所以这里接收的是一个帧通道，
 * 而不是一个完整的字节数组。
 *
 * 用户能直接感受到的好处：说完话立刻就有字，不用再等一轮上传。
 */
class XfyunIat {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // WebSocket 升级之后由 pingInterval 负责保活，读超时必须关掉，
        // 否则会话中间一段静音就会被本地超时掐断
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * 边收边发。帧通道被关闭 = 录音结束，会自动补一帧 status=2。
     *
     * @param onPartial 中间结果，用来做「边说边出字」。回调保证在调用方的
     *   调度器上执行（界面那边是主线程），不用自己切线程。
     * @return 最终识别文本
     */
    suspend fun transcribe(
        config: XfyunConfig,
        frames: ReceiveChannel<ByteArray>,
        onPartial: (String) -> Unit = {},
    ): String = coroutineScope {
        val partials = Channel<String>(Channel.CONFLATED)
        // 结果回调来自 OkHttp 的线程，而 onPartial 会去改 Compose 状态，
        // 所以统一经这个通道汇到调用方的调度器上再往外抛。
        val pump = launch { for (text in partials) onPartial(text) }

        val finalText = try {
            withContext(Dispatchers.IO) { runSession(config, frames, partials) }
        } finally {
            partials.close()
        }

        pump.join()
        finalText
    }

    // ---------------------------------------------------------------- internals

    private suspend fun runSession(
        config: XfyunConfig,
        frames: ReceiveChannel<ByteArray>,
        partials: Channel<String>,
    ): String {
        val opened = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()
        val accumulator = IatAccumulator()

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            /**
             * **任何异常都不许从这里漏出去。**
             *
             * OkHttp 的读线程对监听器里的异常没有任何兜底：漏出去就是未捕获异常，
             * 直接杀掉整个进程 —— 用户看到的是「闪退」，而不是一句识别失败提示。
             * 所以先整个包进 runCatching，兜不住了才转成业务错误。
             */
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text, accumulator, partials, finished) }
                    .onFailure { cause ->
                        finished.completeExceptionally(
                            SpeechException("讯飞返回的结果解析失败", cause.message),
                        )
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = SpeechException(connectHint(response?.code, t), t.message)
                // 握手都没成功就失败了（域名解析不了 / 连不上 / 被拒），
                // **立刻**把 opened 也点爆：否则调用方还要傻等 OPEN_TIMEOUT_MS 那 15 秒，
                // 而这 15 秒里用户看到的是一个一直「正在聆听」、什么都没发生的界面。
                opened.completeExceptionally(error)
                finished.completeExceptionally(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // 正常收尾时 finished 已经完成，这里只是兜底（complete* 对已完成的
                // deferred 是空操作，不会抛）
                val error = SpeechException("讯飞连接提前关闭（$code）")
                opened.completeExceptionally(error)
                finished.completeExceptionally(error)
            }
        }

        // 建 socket 也要在兜底范围内：URL 不合法时 Request.Builder().url() 会直接抛，
        // 这是同步异常，不包住就会以非 SpeechException 的样子漏给上层
        val socket = runCatching {
            http.newWebSocket(
                Request.Builder().url(XfyunAuth.buildUrl(config)).build(),
                listener,
            )
        }.getOrElse { throw SpeechException("讯飞接口地址不合法", it.message) }

        try {
            if (withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } == null) {
                throw SpeechException("讯飞握手超时，检查网络")
            }

            var seq = 1
            var sentAny = false
            for (frame in frames) {
                val status = if (sentAny) 1 else 0
                if (!socket.send(frameMessage(config, seq, status, frame))) {
                    throw SpeechException("讯飞连接已断开")
                }
                seq++
                sentAny = true
            }

            // 一帧都没发过就结束（录音太短）：也得先补首帧，否则服务端说帧序非法
            if (!sentAny) {
                socket.send(frameMessage(config, seq, 0, ByteArray(0)))
                seq++
            }
            socket.send(lastMessage())

            return withTimeoutOrNull(RESULT_TIMEOUT_MS) { finished.await() }
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    // 超时了也把已经收到的字交出去，总比让用户白说一遍强
                    val sofar = accumulator.text()
                    if (sofar.isNotBlank()) sofar else throw SpeechException("没听清，再说一次")
                }
        } catch (e: SpeechException) {
            throw e
        } catch (e: Exception) {
            throw SpeechException("识别失败：${e.message ?: "未知错误"}")
        } finally {
            runCatching { socket.close(1000, null) }
        }
    }

    /** 一帧结果。整个函数处在 [WebSocketListener.onMessage] 的 runCatching 之下。 */
    private fun handleMessage(
        text: String,
        accumulator: IatAccumulator,
        partials: Channel<String>,
        finished: CompletableDeferred<String>,
    ) {
        val body = JSONObject(text)
        val data = body.optJSONObject("data")

        val code = body.optInt("code", 0)
        if (code != 0) {
            finished.completeExceptionally(
                SpeechException(mapCode(code, body.stringOrBlank("message"))),
            )
            return
        }

        data?.optJSONObject("result")?.let { result ->
            partials.trySend(accumulator.accept(result))
        }

        if (data?.optInt("status", -1) == 2) {
            finished.complete(accumulator.text())
        }
    }

    /**
     * 首帧带 `common` / `business`，中间帧只发 `data` —— 多带一次
     * 服务端会认为参数重复。
     */
    private fun frameMessage(
        config: XfyunConfig,
        seq: Int,
        status: Int,
        audio: ByteArray,
    ): String = JSONObject()
        .apply {
            if (status == 0) {
                put("common", JSONObject().put("app_id", config.appId))
                put(
                    "business",
                    JSONObject()
                        .put("language", config.language)
                        .put("domain", "iat")
                        .put("accent", "mandarin")
                        // wpgs：让服务端把「修正」也推回来，边说话边出字才准
                        .put("dwa", "wpgs")
                        .put("eos", config.eosMs),
                )
            }
            put(
                "data",
                JSONObject()
                    .put("status", status)
                    .put("format", FORMAT_16K)
                    .put("encoding", "raw")
                    .put("audio", base64(audio)),
            )
        }
        .toString()

    /** 结束帧就是一句 status=2，不带音频 */
    private fun lastMessage(): String = JSONObject()
        .put("data", JSONObject().put("status", 2))
        .toString()

    private fun mapCode(code: Int, message: String): String = when (code) {
        10005 -> "讯飞 AppID 没开通这个服务（去控制台给应用加上语音听写）"
        10043 -> "讯飞解码音频失败"
        10114, 10014, 10019, 10200 -> "讯飞会话超时，请重试"
        10160, 10161 -> "发给讯飞的报文不合法"
        10163 -> "讯飞说缺少必传参数"
        10165 -> "讯飞说音频帧顺序不对"
        // 拿真凭证实测大模型档时服务端回的就是这个：应用没有该服务的路由
        10404 -> "讯飞说这个应用没开通该服务（去控制台确认已添加对应能力）"
        11200 -> "讯飞这个能力的额度用完或未授权"
        11201 -> "讯飞今日调用次数已用完"
        11202 -> "讯飞调用太频繁，稍后再试"
        11203 -> "讯飞授权已过期"
        else -> "讯飞识别失败（$code${if (message.isBlank()) "" else "：$message"}）"
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FORMAT_16K = "audio/L16;rate=16000"
        const val OPEN_TIMEOUT_MS = 15_000L

        /** 等服务端把最后一片结果吐完。正常几百毫秒，给足余量 */
        const val RESULT_TIMEOUT_MS = 20_000L

        fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        /**
         * 连不上时给一句人话。
         *
         * 手表上最常见的其实是**域名解析失败**（出门没网 / 只在蓝牙范围里），
         * 这个和「服务端拒绝我」完全是两回事，提示要分开，否则用户会去翻凭证。
         */
        fun connectHint(code: Int?, cause: Throwable): String = when {
            code == 401 || code == 403 -> "讯飞鉴权失败，检查 APIKey 和 APISecret"
            code == 404 -> "讯飞接口地址不对（404）"
            cause is UnknownHostException -> "连不上讯飞（域名解析失败），检查手表网络"
            cause is SocketTimeoutException || cause is ConnectException ->
                "连不上讯飞（网络超时），检查手表网络"

            else -> "连不上讯飞识别服务"
        }
    }
}

/**
 * 把一片片结果拼成整句。
 *
 * 开了 `dwa=wpgs` 之后服务端会推两种片：
 *  - `apd`（追加）：接在已有结果后面；
 *  - `rpl`（修正）：**替换**前面第 rg[0]..rg[1] 片，也就是它把之前识别错的部分改掉。
 *    边说话边出字时这个必须处理，不然错字会一直留在屏幕上下不去。
 *
 * `sn` 是服务端给的序号，用它当键，天然去重也天然有序。
 *
 * **必须加锁**：`accept` 在 OkHttp 的读线程上跑，`text()` 会在识别超时那条路上
 * 被主线程读一次，而 `TreeMap` 不是线程安全的 —— 并发读写会抛
 * `ConcurrentModificationException`，砸在 OkHttp 线程上就是整个进程挂掉。
 */
private class IatAccumulator {

    private val lock = Any()
    private val segments = TreeMap<Int, String>()

    fun accept(result: JSONObject): String = synchronized(lock) {
        val text = words(result)
        val sn = result.optInt("sn", segments.size)

        if (result.stringOrBlank("pgs") == "rpl") {
            val range = result.optJSONArray("rg")
            val from = range?.optInt(0, sn) ?: sn
            val to = range?.optInt(1, sn) ?: sn
            // 先清掉被替换的那几片，再把新的填进自己的位置
            for (i in from..to) segments.remove(i)
        }

        if (text.isNotEmpty()) segments[sn] = text
        text()
    }

    fun text(): String = synchronized(lock) { segments.values.joinToString("") }

    /** `ws[].cw[0].w` 连起来就是这一片的文字 */
    private fun words(result: JSONObject): String {
        val ws = result.optJSONArray("ws") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until ws.length()) {
            val candidates = ws.optJSONObject(i)?.optJSONArray("cw") ?: continue
            builder.append(candidates.optJSONObject(0)?.stringOrBlank("w").orEmpty())
        }
        return builder.toString()
    }
}

/**
 * 讯飞那套签名。所有产品的算法都一样，所以单独抽出来。
 *
 * 参与签名的是三行文本：`host: ...` / `date: ...` / `GET <path> HTTP/1.1`，
 * 用 APISecret 做 HMAC-SHA256，再 base64 两次（一次给 signature，一次给整个 authorization）。
 * 服务端允许 300 秒时钟偏差。
 */
private object XfyunAuth {

    /** 语音听写（流式版）中英文推荐节点。小语种是 iat-niche-api.xfyun.cn。 */
    private const val HOST = "iat-api.xfyun.cn"
    private const val PATH = "/v2/iat"

    fun buildUrl(config: XfyunConfig): String {
        val date = rfc1123()
        val origin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val signature = base64(hmacSha256(origin, config.apiSecret))

        val authorizationOrigin =
            "api_key=\"${config.apiKey}\", algorithm=\"hmac-sha256\", " +
                "headers=\"host date request-line\", signature=\"$signature\""

        val query = listOf(
            "authorization" to base64(authorizationOrigin.toByteArray(Charsets.UTF_8)),
            "date" to date,
            "host" to HOST,
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }

        return "wss://$HOST$PATH?$query"
    }

    /**
     * RFC1123、GMT 时区，形如 `Tue, 14 May 2024 08:46:48 GMT`。
     *
     * 必须锁 Locale：默认区域是中文时会格式化成「周二」，签名立刻对不上，
     * 而且报错只说 "HMAC signature does not match"，很难查。
     */
    fun rfc1123(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())

    private fun hmacSha256(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /**
     * URLEncoder 把空格编成 `+`，但 query 里必须是 `%20`（date 里全是空格），
     * 不换掉的话签名照样对不上。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** 和 DeepSeekClient 同一个坑：显式 JSON null 时 optString 会返回字面量 "null" */
private fun JSONObject.stringOrBlank(key: String): String =
    if (isNull(key)) "" else optString(key).orEmpty()
