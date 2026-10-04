package com.naoh.whalechat.data

import android.util.Log
import com.naoh.whalechat.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext

/** 导入流程的状态，给 UI 看。 */
enum class ImportStatus { WAITING, DONE, FAILED }

/**
 * 手机上提交上来的一屏设置。**字段就是表单里那几格，一个不多一个不少。**
 *
 * 提交的语义是**整屏覆盖**（用户看到的是一张真值表，清空某一格 = 删掉那一项），
 * 所以这里没有任何「空串表示没填」的妥协 —— 空串在这里是有含义的输入。
 */
data class ImportedCredentials(
    val apiKey: String = "",
    val asrEngine: String = AsrEngine.SYSTEM.key,
    val xfyAppId: String = "",
    val xfyApiKey: String = "",
    val xfyApiSecret: String = "",
) {
    /** 除识别引擎外，所有格都空着。引擎是个下拉框，永远有值，不参与「空不空」的判断。 */
    val allFieldsBlank: Boolean
        get() = listOf(apiKey, xfyAppId, xfyApiKey, xfyApiSecret).all { it.isBlank() }

    /** 讯飞三样是否齐全。缺一不可 —— 少一样讯飞那边一律鉴权失败。 */
    val xfyunComplete: Boolean
        get() = xfyAppId.isNotBlank() && xfyApiKey.isNotBlank() && xfyApiSecret.isNotBlank()
}

/**
 * 手表端临时 HTTP 服务：手机浏览器扫二维码进来，**看和改**这块表上的凭证。
 *
 * 设计取舍（贴合「简单 / 跨平台 / 不过度工程」）：
 *  - 不引第三方 server 库，直接用 `ServerSocket` 手搓一个单端点服务；
 *  - 手机端只是个普通浏览器网页（iOS/Android 都行），**不再养一个手机 App**；
 *  - 一次性 token 写在 URL 里，带超时强关；
 *  - 只在「手机扫码」那一页存活，凭证照旧只存这块手表。
 *
 * ## 安全边界（写清楚，免得后来人误判）
 *
 * 这是**局域网明文 HTTP**，而且这个 URL 等于**整份凭证的读句柄** —— 谁拿到它、
 * 在它还活着的那段时间里打开，就能看到全部 Key。靠「64 位随机 token + 只在一页存活 +
 * 提交即停」把暴露面压到极小。所以不要在公共 Wi-Fi 下用这个入口，也不要把服务做成常驻。
 */
class KeyImportServer(
    /** 取手表当前的值，用来预填表单。每次 GET 现取 —— 用户可能在别的页面刚改过。 */
    private val snapshot: () -> ImportedCredentials,
    private val onSubmit: (ImportedCredentials) -> Unit,
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    private val _status = MutableStateFlow(ImportStatus.WAITING)
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private val token = randomToken()
    private var stopped = false

    var url: String? = null
        private set

    /** 所有路由的前缀。 */
    private val base get() = "/$token"

    /** 起服务；拿不到网络地址、或端口绑不上，就返回 false。 */
    fun start(): Boolean {
        val ip = lanIp() ?: return false
        return try {
            // 必须显式绑 IPv4 通配：默认的 ServerSocket(0) 在部分内核上落到 ::（v6 栈），
            // 而 bindv6only 开着时 IPv4 连接（手机浏览器连手表 IP）会全被拒。
            val ss = ServerSocket(0, BACKLOG, InetAddress.getByName("0.0.0.0"))
            serverSocket = ss
            url = "http://$ip:${ss.localPort}$base"
            if (BuildConfig.DEBUG) Log.d("WhaleChatImport", "listening $url")
            launch { acceptLoop() }
            launch {
                delay(TIMEOUT_MS)
                if (!stopped) {
                    _status.value = ImportStatus.FAILED
                    stop()
                }
            }
            true
        } catch (e: Exception) {
            Log.w("WhaleChatImport", "start failed", e)
            false
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        try {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: continue
                // 每个连接单独一条协程：慢客户端（浏览器预连接、探测包）不会把
                // 后续正常的导入请求堵在 accept 循环里。
                launch { handle(socket) }
            }
        } catch (_: Exception) {
            // 关闭时 accept 会抛，忽略
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            // 兼容个别浏览器/代理附加的 query 与尾斜杠
            val route = parts[1].substringBefore("?").removeSuffix("/")

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.startsWith("Content-Length", ignoreCase = true)) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 不给 Content-Length 设上限的话，局域网里谁发一个 Content-Length: 2000000000
            // 就能让下面那个 CharArray 直接 OOM。
            if (contentLength > MAX_BODY_BYTES) {
                respond(sock, "请求体过大", 400)
                return
            }

            val body = if (contentLength > 0) {
                // read 可能一次读不满，必须循环读够 Content-Length
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            if (method == "GET") {
                if (route == base) respond(sock, formHtml(snapshot())) else respond(sock, "404 Not Found", 404)
                return
            }
            if (method == "POST" && route == base) {
                val form = parseForm(body, FORM_FIELDS)
                val submitted = ImportedCredentials(
                    apiKey = form[FIELD_API_KEY].orEmpty(),
                    asrEngine = form[FIELD_ASR_ENGINE].orEmpty(),
                    xfyAppId = form[FIELD_XFY_APP_ID].orEmpty(),
                    xfyApiKey = form[FIELD_XFY_API_KEY].orEmpty(),
                    xfyApiSecret = form[FIELD_XFY_API_SECRET].orEmpty(),
                )
                val before = snapshot()
                val effective = withAutoEngine(before, submitted)
                val engineSwitched = effective.asrEngine != submitted.asrEngine

                // 不能用的情况要在网页上指出**是哪一格**的问题，不然用户只能瞎猜。
                // 回显的是**用户刚提交的内容**（不是 snapshot）：改错一格不该让他把
                // 另外几格重新填一遍 —— 这一点在表单预填之后尤其要紧。
                val complaint = validate(effective)
                if (complaint != null) {
                    respond(sock, formHtml(effective, error = complaint))
                    return
                }
                // 先把成功页写回手机，再落盘/关停 —— 顺序反了浏览器会看到连接被掐断
                respond(sock, successHtml(effective, engineSwitched))
                runCatching { onSubmit(effective) }
                _status.value = ImportStatus.DONE
                stop()
                return
            }
            respond(sock, "404 Not Found", 404)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d("WhaleChatImport", "handle failed: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * 讯飞三项填齐、而用户**没有主动改过引擎**（提交上来仍是「系统」、之前也是「系统」）
     * 时，顺手把引擎切到讯飞听写。
     *
     * 为什么允许这个自动动作：只填凭证不切引擎，识别还是走系统那条路 —— 用户会觉得
     * 「我明明导进去了怎么没用」。所以表单里那句提示之外，再兜一次底。
     *
     * 为什么限定得这么死：用户自己选过别的档（之前不是「系统」）就绝不覆盖；
     * 而且这个动作会**明写在成功页上**（「另外把识别引擎切到了讯飞听写」），不是暗改。
     */
    private fun withAutoEngine(
        before: ImportedCredentials,
        submitted: ImportedCredentials,
    ): ImportedCredentials {
        if (submitted.asrEngine != AsrEngine.SYSTEM.key) return submitted
        if (before.asrEngine != AsrEngine.SYSTEM.key) return submitted
        return if (submitted.xfyunComplete) {
            submitted.copy(asrEngine = AsrEngine.XFYUN.key)
        } else {
            submitted
        }
    }

    /**
     * 逐项宽松校验，返回第一条人话错误（没问题就返回 null）。
     *
     * 刻意**不用正则卡格式**：讯飞那三样确实长度固定（8/32/32 位十六进制），
     * 但用户在手机上从控制台复制粘贴时经常带上前后的空格或换行（上面已经 trim），
     * 卡太死只会把能用的凭证拒之门外 —— 真正的判据是「像不像粘到一半的半截串」。
     * 存进去能不能用，用户点一次识别就知道了，代价远小于「明明粘贴对了却不让导入」。
     *
     * 反过来，「引擎和凭证对不上」这种**结构性**问题必须拦：选了讯飞听写却没填齐三样，
     * 页面上不报错的话，用户回到手表上只会看到语音一直失败，且完全不知道为什么。
     */
    private fun validate(c: ImportedCredentials): String? {
        val engine = AsrEngine.fromKey(c.asrEngine)
        return when {
            // 整页清空多半是手滑（这一下会把手表上的凭证全抹掉）。
            // 真要逐项删除是可以做到的：只清那一格、其余保持原样提交。
            c.allFieldsBlank ->
                "整页都是空的 —— 这一下会把手表上的凭证全清掉。只想删某一项的话，清那一格、其余保持原样再保存。"

            !looksLikeDeepSeekKey(c.apiKey) ->
                "DeepSeek API Key 看着不完整，应以 sk- 开头（不要这一项就清空它）。"
            !looksLikeToken(c.xfyAppId) -> "讯飞 AppID 看着不完整，请回去复制完整的一串。"
            !looksLikeToken(c.xfyApiKey) -> "讯飞 APIKey 看着不完整，请回去复制完整的一串。"
            !looksLikeToken(c.xfyApiSecret) -> "讯飞 APISecret 看着不完整，请回去复制完整的一串。"

            engine == AsrEngine.XFYUN && !c.xfyunComplete ->
                "选了「讯飞听写」，但三样凭证没填齐（缺 ${missingXfyun(c)}）。" +
                    "不用讯飞的话，把上面的「识别引擎」改成「系统」再保存。"

            else -> null
        }
    }

    /** DeepSeek 的 Key 形如 `sk-` + 32 位十六进制；放宽到「sk- 开头且够长」即可。空串跳过。 */
    private fun looksLikeDeepSeekKey(key: String): Boolean =
        key.isEmpty() || (key.startsWith("sk-") && key.length in 20..200 && key.none { it.isWhitespace() })

    /**
     * 讯飞的三串都是十六进制 token。同样只做「不像半截串」的判断：
     * 非空时要求长度在合理区间内、且不含空白（含空白说明复制时带上了别的东西）。
     */
    private fun looksLikeToken(token: String): Boolean =
        token.isEmpty() || (token.length in 6..128 && token.none { it.isWhitespace() })

    /** 拼出「缺 AppID、APISecret」这样的清单，用户不用自己一格一格对。 */
    private fun missingXfyun(c: ImportedCredentials): String = listOfNotNull(
        "AppID".takeIf { c.xfyAppId.isBlank() },
        "APIKey".takeIf { c.xfyApiKey.isBlank() },
        "APISecret".takeIf { c.xfyApiSecret.isBlank() },
    ).joinToString("、")

    // ---------------------------------------------------------------- 页面

    /**
     * 手机端页面。同样贯彻 Material You：M3 基线色板 + 明暗双主题 +
     * 系统字体 + 胶囊按钮，让网页和 Material 系的 app 摆在一起不违和。
     *
     * [error] 不为 null 时是**校验没过重新渲染**，[values] 这时是用户刚提交的内容，
     * 不能改回 snapshot：让他连同其它几格一起重填是纯粹的折磨。
     */
    private fun formHtml(values: ImportedCredentials, error: String? = null): String {
        val err = if (error == null) "" else """<p class="err">${escapeHtml(error)}</p>"""
        return """
            ${htmlHead("手表里的凭证")}
            <div class="card">
            <div class="badge">⌚ WhaleChat</div>
            <h1>手表里的凭证</h1>
            <p class="sub">下面是手表上<b>现在存着</b>的内容。直接在上面改，改完点最下面的按钮保存。
            <b>清空某一格再保存，就等于删掉手表上的那一项。</b></p>
            <form method="post" action="">
            <h2>模型 · 对话用</h2>
            ${field(FIELD_API_KEY, "DeepSeek API Key", values.apiKey, "sk-…", "对话用的 Key，不填就没法聊天。")}
            <h2>语音识别 · 说话用</h2>
            <label for="$FIELD_ASR_ENGINE">识别引擎</label>
            ${engineSelect(values.asrEngine)}
            <p class="hint">两档选一个。「系统」不用配置，直接用设备自带的识别服务；
            要用讯飞就选「讯飞听写」，下面三串填齐。三串一旦填齐、这一栏又还停在「系统」，
            保存时会自动切到「讯飞听写」（结果页会写出来）。</p>
            <h3>讯飞听写 <span class="opt">（选了它才需要填）</span></h3>
            <p class="hint">边说边出字，说完立刻有结果。三串都在讯飞控制台「我的应用」里。</p>
            ${field(FIELD_XFY_APP_ID, "AppID", values.xfyAppId, "8 位十六进制")}
            ${field(FIELD_XFY_API_KEY, "APIKey", values.xfyApiKey, "32 位十六进制")}
            ${field(FIELD_XFY_API_SECRET, "APISecret", values.xfyApiSecret, "32 位十六进制")}
            $err
            <button type="submit">保存到手表</button>
            </form>
            <p class="foot">此页面由手表直接提供 · 只有同一个 Wi-Fi 下打得开<br>
            页面会明文显示已保存的凭证，别在公共 Wi-Fi 下使用</p>
            </div></body></html>
        """.trimIndent()
    }

    /** 一行输入框。名字用常量，免得 HTML 和 [FORM_FIELDS] 两处改名改漏一处。 */
    private fun field(
        name: String,
        label: String,
        value: String,
        placeholder: String = "",
        hint: String? = null,
    ): String = buildString {
        // 这里刻意用普通字符串而不是三引号：属性值最后就是一个引号，
        // 三引号写作 `…"${v}""""` 会多出一个游离的引号，直接编不过。
        append("<label for=\"$name\">${escapeHtml(label)}</label>")
        append("<input id=\"$name\" name=\"$name\" type=\"text\" value=\"${escapeHtml(value)}\"")
        if (placeholder.isNotEmpty()) append(" placeholder=\"${escapeHtml(placeholder)}\"")
        append(" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
        if (hint != null) append("<p class=\"hint\">${escapeHtml(hint)}</p>")
    }

    /**
     * 引擎下拉框。取值就是 [AsrEngine.key]，和服务端 `AsrEngine.fromKey` 一一对应；
     * 认不出来的值会落回「系统」，所以这里不需要额外的白名单校验。
     */
    private fun engineSelect(current: String): String {
        val selected = AsrEngine.fromKey(current).key
        val options = AsrEngine.entries.joinToString("") { engine ->
            val mark = if (engine.key == selected) " selected" else ""
            """<option value="${engine.key}"$mark>${escapeHtml(engine.label)}</option>"""
        }
        return """<select id="$FIELD_ASR_ENGINE" name="$FIELD_ASR_ENGINE">$options</select>"""
    }

    /** 网页上的错误提示要回显，先挡掉拼 HTML 的可能（内容是本地常量，仍不省这一步）。 */
    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * 成功页把**保存后的完整状态**摊开：哪几项有值、哪几项是空的、引擎在哪一档。
     *
     * 这一页比「已导入」重要得多：整屏覆盖的语义下，用户的担心从「我是不是漏填了一格」
     * 变成了「我是不是不小心清掉了什么」—— 把结果摆出来他自己就能核对。
     */
    private fun successHtml(c: ImportedCredentials, engineSwitched: Boolean): String {
        val rows = listOf(
            "DeepSeek API Key" to c.apiKey.isNotBlank(),
            "识别引擎：${AsrEngine.fromKey(c.asrEngine).label}" to true,
            "讯飞 AppID" to c.xfyAppId.isNotBlank(),
            "讯飞 APIKey" to c.xfyApiKey.isNotBlank(),
            "讯飞 APISecret" to c.xfyApiSecret.isNotBlank(),
        )
        val list = rows.joinToString("") { (label, kept) ->
            if (kept) """<li>${escapeHtml(label)}</li>""" else """<li class="off">${escapeHtml(label)}</li>"""
        }
        val cleared = rows.any { !it.second }
        val notes = buildString {
            if (engineSwitched) {
                append(
                    """<p class="note">讯飞三项填齐了，识别引擎一并切到了「讯飞听写」。手表设置里可以改。</p>""",
                )
            }
            if (cleared) {
                append("""<p class="note">灰色那几项在手表上是空的。</p>""")
            }
        }
        return """
        ${htmlHead("已保存", bodyClass = "center")}
        <div class="card">
        <div class="ok">✓</div><h1>已保存到手表</h1><p>现在可以关闭这个页面了。</p>
        <ul>$list</ul>
        $notes
        </div></body></html>
    """.trimIndent()
    }

    /** 所有页面共用的头部：meta + 标题 + 共用样式表。`<body>` 也在这里开，各页只管里面的卡片。 */
    private fun htmlHead(title: String, bodyClass: String = ""): String {
        val bodyTag = if (bodyClass.isEmpty()) "<body>" else """<body class="$bodyClass">"""
        return """
        <!doctype html><html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <meta name="theme-color" content="#F7F2FA" media="(prefers-color-scheme:light)">
        <meta name="theme-color" content="#141218" media="(prefers-color-scheme:dark)">
        <title>${escapeHtml(title)}</title>
        $PAGE_STYLE
        </head>
        $bodyTag
        """.trimIndent()
    }

    private fun respond(sock: Socket, html: String, code: Int = 200) {
        runCatching {
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val text = when (code) {
                200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "OK"
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code $text\r\n")
            out.write("Content-Type: text/html; charset=UTF-8\r\n")
            out.write("Content-Length: ${bytes.size}\r\n")
            // 这页里带着一次性 token，而且现在连凭证本身都在页面上 ——
            // 不让浏览器/中间代理留下任何副本
            out.write("Cache-Control: no-store\r\n")
            out.write("Connection: close\r\n\r\n")
            out.write(html)
            out.flush()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { serverSocket?.close() }
        cancel() // 取消 SupervisorJob → acceptLoop / 超时协程一并结束
    }

    /**
     * 取局域网 IPv4。真机上可能有多个网卡（Wi-Fi / 蜂窝 / 蓝牙），
     * 优先叫 wlan/ap 的，避免把蜂窝内网地址编进二维码。
     */
    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") }
            ?.firstNotNullOfOrNull { ni ->
                ni.inetAddresses.toList().firstNotNullOfOrNull { addr ->
                    (addr as? Inet4Address)?.takeIf { !it.isLoopbackAddress }?.hostAddress
                }
            }
    }.getOrNull()

    private fun randomToken(): String = SecureRandom().let { r ->
        (1..16).joinToString("") { "0123456789abcdef"[r.nextInt(16)].toString() }
    }

    /**
     * 读取表单。只认白名单里的字段名，其余一律忽略 ——
     * 这个端点不该被塞进任何意料之外的东西。
     */
    private fun parseForm(body: String, allowed: Set<String>): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (pair in body.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] in allowed) {
                fields[kv[0]] = runCatching { URLDecoder.decode(kv[1], "UTF-8") }
                    .getOrDefault(kv[1])
                    .trim()
            }
        }
        return fields
    }

    private companion object {
        /** 表单字段名。手表端解析和手机端 HTML 两处必须一致，写成常量免得改一处漏一处。 */
        const val FIELD_API_KEY = "key"
        const val FIELD_ASR_ENGINE = "asr_engine"
        const val FIELD_XFY_APP_ID = "xfy_app_id"
        const val FIELD_XFY_API_KEY = "xfy_api_key"
        const val FIELD_XFY_API_SECRET = "xfy_api_secret"

        val FORM_FIELDS = setOf(
            FIELD_API_KEY,
            FIELD_ASR_ENGINE,
            FIELD_XFY_APP_ID,
            FIELD_XFY_API_KEY,
            FIELD_XFY_API_SECRET,
        )

        /**
         * 请求体上限。这一页就五格、每格最多几十个字符，8KB 绰绰有余；
         * 而下面那个 CharArray 是按 Content-Length 一次性分配的，不设上限的话
         * 局域网里谁发一个 Content-Length: 2000000000 就能把进程撑爆。这道闸必须留着。
         */
        const val MAX_BODY_BYTES = 8 * 1024

        /** 单连接读超时：挡住连上就不发数据的慢客户端 */
        const val SOCKET_TIMEOUT_MS = 10_000

        /** 页面停留多久后自动关服 */
        const val TIMEOUT_MS = 120_000L

        const val BACKLOG = 50
    }
}

/**
 * 所有手机页共用的样式表。
 *
 * M3 基线色板 + 明暗双主题 + 系统字体 + 胶囊按钮，让网页和 Material 系的 app
 * 摆在一起不违和。
 */
private val PAGE_STYLE = """
    <style>
    :root{color-scheme:light dark;
      --primary:#6750A4;--on-primary:#FFFFFF;--primary-ctn:#EADDFF;--on-primary-ctn:#21005D;
      --bg:#F7F2FA;--card:#FFFFFF;--on-bg:#1C1B1F;--on-bg-var:#49454F;
      --outline:#CAC4D0;--err:#B3261E;--surface:#F3EDF7}
    @media (prefers-color-scheme:dark){:root{
      --primary:#D0BCFF;--on-primary:#381E72;--primary-ctn:#4F378B;--on-primary-ctn:#EADDFF;
      --bg:#141218;--card:#1D1B20;--on-bg:#E6E0E9;--on-bg-var:#CAC4D0;
      --outline:#49454F;--err:#F2B8B5;--surface:#211F26}}
    *{box-sizing:border-box}
    body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
         background:var(--bg);color:var(--on-bg);display:flex;min-height:100vh;
         align-items:center;justify-content:center;padding:24px 20px;line-height:1.5}
    body.center{text-align:center;align-items:center}
    .card{background:var(--card);border-radius:28px;padding:28px 24px;width:100%;max-width:420px;
          box-shadow:0 2px 6px rgba(0,0,0,.08),0 12px 32px rgba(0,0,0,.12)}
    .badge{display:inline-block;font-size:12px;font-weight:600;letter-spacing:.4px;
           color:var(--on-primary-ctn);background:var(--primary-ctn);
           border-radius:999px;padding:6px 14px;margin:0 0 14px}
    h1{font-size:22px;font-weight:600;margin:0 0 6px}
    p.sub{color:var(--on-bg-var);font-size:14px;margin:0 0 20px}
    h2{font-size:12px;font-weight:600;letter-spacing:.6px;text-transform:none;
       color:var(--on-bg-var);margin:22px 0 10px;padding-top:18px;
       border-top:1px solid var(--outline)}
    h3{font-size:13px;font-weight:600;color:var(--on-bg);margin:20px 0 12px}
    h3 .opt{color:var(--on-bg-var);font-weight:400}
    label{display:block;font-size:12px;font-weight:600;letter-spacing:.6px;
          color:var(--on-bg-var);margin:0 0 8px}
    input,select{width:100%;padding:16px;font-size:16px;color:var(--on-bg);
          background:transparent;border:1px solid var(--outline);border-radius:16px;
          margin-bottom:16px;outline:none;transition:border-color .15s;
          font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}
    input:focus,select:focus{border-color:var(--primary);border-width:2px;padding:15px}
    .hint{color:var(--on-bg-var);font-size:12px;margin:-8px 0 16px;line-height:1.45;white-space:pre-line}
    button{width:100%;padding:16px;border:0;border-radius:999px;background:var(--primary);
           color:var(--on-primary);font-size:16px;font-weight:600;cursor:pointer;
           transition:filter .15s;margin-top:4px;font-family:inherit}
    button:active{filter:brightness(.92)}
    .err{color:var(--err);font-size:13px;margin:-4px 0 14px}
    .foot{color:var(--on-bg-var);font-size:12px;margin:18px 0 0;text-align:center;line-height:1.6}
    b{font-weight:600}
    .ok{width:96px;height:96px;border-radius:999px;background:var(--primary-ctn);
        color:var(--on-primary-ctn);display:flex;align-items:center;justify-content:center;
        font-size:48px;margin:0 auto 18px}
    ul{list-style:none;padding:0;margin:18px 0 0;text-align:left;display:inline-block;min-width:240px}
    li{font-size:14px;color:var(--on-bg);padding:5px 0;border-bottom:1px solid var(--outline)}
    li:before{content:"✓  ";color:var(--primary)}
    li.off{color:var(--on-bg-var)}
    li.off:before{content:"○  "}
    .note{color:var(--on-bg-var);font-size:13px;margin:16px 0 0;text-align:left}
    </style>
""".trimIndent()
