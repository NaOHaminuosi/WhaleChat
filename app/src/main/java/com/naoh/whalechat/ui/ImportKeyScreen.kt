package com.naoh.whalechat.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.ImportStatus
import com.naoh.whalechat.data.ImportedCredentials
import com.naoh.whalechat.data.KeyImportServer
import com.naoh.whalechat.data.Persona
import com.naoh.whalechat.data.PersonaBridge
import com.naoh.whalechat.data.PersonaFactory
import com.naoh.whalechat.data.PersonaResult
import com.naoh.whalechat.data.PersonaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 二维码卡片的边长（含白边）。 */
private val QR_CARD_SIZE = 136.dp

/** 二维码位图边长（像素）。取 2 的整数倍，缩放到 136dp 时每个模块都是整像素。 */
private const val QR_BITMAP_PX = 272

/**
 * 手机页上要**落在哪一页**。取值是**服务端路由的后缀**，直接拼在 `server.url` 后面
 * （`url` 本身已经带了 `/$token`，见 [KeyImportServer.start]）。
 *
 * 传后缀而不是传一个「模式」枚举，是有意的：服务端本来就是一个 token下面挂两条路由
 * （`/$token` 和 `/$token/p…`），手表这边再包一层枚举的话，
 * 就等于把同一件事写两遍 —— 哪天路由改了，枚举改了，拼出来的 URL 没改，
 * 出问题的表现是「二维码扫开是 404」，而且只在真机上才看得见。
 */
object PhoneLanding {

    /** 凭证页 `/$token`：DeepSeek Key + 讯飞三串，改完提交即关停。 */
    const val CREDENTIALS = ""

    /** 人设列表 `/$token/p`：新增角色、改六块人设、按描述生成一份。 */
    const val PERSONAS = "/p"

    /** 某个角色的人设表单 `/$token/p/{id}`：从人设详情页点进来时直达 TA 那一张。 */
    fun persona(personaId: String) = "$PERSONAS/$personaId"
}

/**
 * 这次扫码落在哪一页，决定标题和两段说明怎么写。
 *
 * 从 [landing] 这个后缀**反推**，不额外接一个参数：多一个参数就多一个和 landing
 * 对不上的机会，而这里只需要看路径有几段 —— `/p` 两段、`/p/{uuid}` 三段。
 * 想加落地页就在这里加一个分支，别把判断散到三个用到它的地方去。
 */
private enum class PhoneTarget { CREDENTIALS, PERSONA_LIST, ONE_PERSONA }

/** 由路由后缀判落点。空串 = 凭证页（见 [PhoneLanding.CREDENTIALS]）。 */
private fun phoneTargetOf(landing: String): PhoneTarget = when {
    landing.isEmpty() -> PhoneTarget.CREDENTIALS
    // `/p/{id}`：斜杠有两个（开头那个 + 中间那个）。id 是 UUID，不含斜杠。
    landing.count { it == '/' } >= 2 -> PhoneTarget.ONE_PERSONA
    else -> PhoneTarget.PERSONA_LIST
}

/**
 * 用手机管理手表里的东西：手表起一个临时本地 HTTP 服务并把它编成二维码，
 * 手机相机扫一下，网页里就能看到并修改。整套走 Wear M3 原生组件 + 莫奈取色。
 *
 * **几个入口共用同一次扫码**（同一份 token、同一个服务），区别只在二维码里的
 * 落地路径 —— 见 [PhoneLanding]：
 *  * `/$token` —— 全部凭证。收的不只是 DeepSeek 的 Key，讯飞那三串
 *    （AppID / APIKey / APISecret）也在这张表里。理由很简单：手表上打这几串十六进制
 *    是这块屏最难受的操作之一，而它们本来就要跟 Key 一起从手机/电脑的控制台里复制过来。
 *  * `/$token/p` —— 角色。新增、改六块人设，还能让模型按一句描述生成一份再逐块改。
 *    手表上没有能舒服地敲大段文字的地方，而人设六块**恰恰是越详细越好**，
 *    所以这一页不是「简化的替代品」，是唯一能把人设写好的地方。
 *
 * **为什么不给角色单独起一个服务**：服务本身管的是「把这块表临时摊给同一 Wi-Fi 下的
 * 一台手机看」这件事，和摊的是凭证还是人设无关；两套服务意味着两个端口、两份 token、
 * 两份超时逻辑，而暴露窗口一秒都不会变短。所以区分的是**落地页**，不是服务。
 *
 * 页面是可读可写的：GET 出去的表单拿 `snapshot()` 里手表当前的值预填，
 * 所以它同时也是「我到底存进去没有」的核对入口（详见 KeyImportServer 的类注释）。
 */
@Composable
fun ImportKeyScreen(landing: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val target = phoneTargetOf(landing)
    /** 这一趟是来看人设的，不是来交凭证的。标题、说明、底部状态都跟着它走。 */
    val personaPage = target != PhoneTarget.CREDENTIALS
    val server = remember {
        KeyImportServer(
            // 每次 GET 现取：用户可能在别的页面刚改过设置，预填的必须是当下的真值
            snapshot = {
                val s = ChatEngine.settings.value
                ImportedCredentials(
                    apiKey = s.apiKey,
                    asrEngine = s.asrEngine.key,
                    xfyAppId = s.xfyAppId,
                    xfyApiKey = s.xfyApiKey,
                    xfyApiSecret = s.xfyApiSecret,
                )
            },
            onSubmit = { ChatEngine.settings.importFromPhone(it) },
            // 角色那一侧的桥。放在这里而不是让服务直接抓 ChatEngine：
            // 「网页 + HTTP」那一层不该知道角色存在哪、生成怎么发。
            personas = object : PersonaBridge {
                override fun list(): List<Persona> = ChatEngine.personas.value

                override fun find(id: String): Persona? = ChatEngine.findPersona(id)

                override suspend fun save(persona: Persona): String? =
                    ChatEngine.savePersonaFromPhone(persona)

                override suspend fun generate(description: String): PersonaResult {
                    val apiKey = ChatEngine.settings.value.apiKey
                    if (apiKey.isBlank()) {
                        return PersonaResult.Failed(
                            "手表上还没填 DeepSeek API Key。先回「改凭证」那一页填上，再回来生成。",
                        )
                    }
                    // **复用 PersonaFactory，不另写一套生成** —— 手表造的人和手机造的人
                    // 必须是同一条管道、同一套字段，否则会出现两种质量。
                    //
                    // 这里开完令牌立刻作废，不留一个「活着」的令牌在全局飘着：
                    // 手机端这一次生成不需要「离开 = 取消」那套语义（HTTP 响应就是这个
                    // 事务的终点，用户关掉网页也退不了钱），而那个令牌是手表等待页的东西。
                    val own = PersonaFactory.begin()
                    return try {
                        PersonaFactory.generate(apiKey, own, description, PersonaSource.CUSTOM)
                    } finally {
                        PersonaFactory.abandon(own)
                    }
                }
            },
        )
    }
    val status by server.status.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf<String?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    /** 二维码位图；生成完成前为 null，先占一块白底避免卡片跳变。 */
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        // 枚举网卡、绑端口都是阻塞调用，别放在主线程上（低端表上会卡出一帧长停顿）
        startFailed = !withContext(Dispatchers.IO) { server.start() }
        url = server.url
    }

    // 二维码和下面那行字都要**带上落地路径**：扫开就直接落在该落的页上，
    // 手打（扫不动时的退路）也不用再自己接一段。`landing` 现在是这个 Composable
    // 的入参、不会再变，所以这个值是稳定的，不需要 remember。
    val scanUrl = url?.plus(landing)

    LaunchedEffect(scanUrl) {
        val target = scanUrl ?: return@LaunchedEffect
        // 272×272 要逐像素写 7.4 万次，同样不能占着主线程画
        qr = withContext(Dispatchers.Default) { buildQrBitmap(target, QR_BITMAP_PX) }
    }

    // 扫码期间屏幕必须常亮：Wear 默认几十秒就熄屏/进环境模式，二维码一暗就没法扫了。
    // 离开页面时清掉常亮标记，并关停临时服务（取消 / 完成 / 超时都走这里）。
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            server.stop()
        }
    }

    // 保存成功后稍停一下再返回，让用户看到「已保存到手表」
    LaunchedEffect(status) {
        if (status == ImportStatus.DONE) {
            delay(1500)
            onDone()
        }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = { ScrollIndicator(state = listState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                ListHeader {
                    Text(if (personaPage) "用手机管理人设" else "手机扫码改凭证")
                }
            }

            item(key = "hint") {
                // 文案刻意压到一行半：圆屏上这段一长，下面的二维码就被挤到屏幕外，
                // 用户得先滚一下才能扫 —— 而「拿出手机扫」是他点进来唯一要做的事。
                // 「网页里能看到并改全部凭证」那句放到二维码下面去说，不跟它抢位置。
                Text(
                    "手表和手机连同一个 Wi-Fi，扫下面的二维码。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (startFailed) {
                item(key = "fail") {
                    Text(
                        "无法获取网络地址。请确认手表已连上 Wi-Fi，再重试。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            scanUrl?.let { u ->
                item(key = "qr") {
                    // 二维码必须高对比：白底黑模块（Google 钱包/迁移助手同款画法），
                    // 颜色刻意不走主题，保证任何色板下都扫得动；卡片本身仍用主题圆角形状。
                    Card(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(QR_CARD_SIZE),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        val bitmap = qr
                        if (bitmap == null) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "导入二维码，用手机相机扫码",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                item(key = "url") {
                    Text(
                        u,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "what") {
                    Text(
                        // 说的是**这一趟能做的事**，不是把服务端全部能力抄一遍：
                        // 从 chat 进来的用户眼下要改的是人设，跟他讲「也能改凭证」
                        // 只会让他多读一行无关的字。落在某一个人身上时又不一样 ——
                        // 那一页上没有「新增角色」，说它有等于让人去找一个不存在的按钮。
                        when (target) {
                            PhoneTarget.CREDENTIALS ->
                                "打开后能看到这块表上的全部凭证（对话 Key 和语音识别凭证），" +
                                    "直接在上面改或删；也能新增角色、改角色的人设。"

                            PhoneTarget.PERSONA_LIST ->
                                "打开后能新增角色、改六块人设（人格内核 / 风格 / 规矩 / 场景 / " +
                                    "示例对话 / 开场白），也能让模型按一句描述先生成一份再逐块改。"

                            PhoneTarget.ONE_PERSONA ->
                                "打开后是这个角色的六块人设（人格内核 / 风格 / 规矩 / 场景 / " +
                                    "示例对话 / 开场白），改完直接返回。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (!startFailed) {
                item(key = "status") {
                    when (status) {
                        // 两页在底下等的东西根本不是同一件事：
                        // 凭证页等的是「手机上按保存」这一个动作（按完这里会自己关）；
                        // 人设页没有「提交」这个终点 —— 改一块存一块，服务一直活着，
                        // 所以说「等待手机提交」是错的，会让人以为改完还要回去按什么。
                        ImportStatus.WAITING -> Text(
                            if (personaPage) "在手机上改就行，改完直接返回" else "等待手机提交…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        ImportStatus.DONE -> Card(
                            onClick = {},
                            enabled = false,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    "已保存到手表",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }

                        ImportStatus.FAILED -> Card(
                            onClick = {},
                            enabled = false,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text(
                                if (personaPage) {
                                    // 人设这一路本来就没有「提交」，所以不该说
                                    // 「超时未收到」——那是凭证页才有的失败。
                                    "这一页已经关了，还要改就重开一次"
                                } else {
                                    "超时未收到，请取消后重试"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item(key = "cancel") {
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("取消")
                }
            }
        }
    }
}

/** 把字符串编成二维码 Bitmap（ZXing core，手绘每个模块，保证黑白高对比）。 */
private fun buildQrBitmap(content: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bmp.setPixel(
                x,
                y,
                if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE,
            )
        }
    }
    return bmp.asImageBitmap()
}
