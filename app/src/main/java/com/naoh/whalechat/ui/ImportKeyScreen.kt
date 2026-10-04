package com.naoh.whalechat.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 二维码卡片的边长（含白边）。 */
private val QR_CARD_SIZE = 136.dp

/** 二维码位图边长（像素）。取 2 的整数倍，缩放到 136dp 时每个模块都是整像素。 */
private const val QR_BITMAP_PX = 272

/**
 * 用手机管理手表里的凭证：手表起一个临时本地 HTTP 服务并把它编成二维码，
 * 手机相机扫一下，网页里就能看到并修改。整套走 Wear M3 原生组件 + 莫奈取色。
 *
 * 收的不只是 DeepSeek 的 Key，讯飞那三串（AppID / APIKey / APISecret）也在这张表里。
 * 理由很简单：手表上打这几串十六进制是这块屏最难受的操作之一，而它们本来就要
 * 跟 Key 一起从手机/电脑的控制台里复制过来。
 *
 * 页面是可读可写的：GET 出去的表单拿 `snapshot()` 里手表当前的值预填，
 * 所以它同时也是「我到底存进去没有」的核对入口（详见 KeyImportServer 的类注释）。
 */
@Composable
fun ImportKeyScreen(onDone: () -> Unit) {
    val context = LocalContext.current
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

    LaunchedEffect(url) {
        val target = url ?: return@LaunchedEffect
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
                ListHeader { Text("手机扫码改凭证") }
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

            url?.let { u ->
                item(key = "qr") {
                    // 二维码必须高对比：白底黑模块，颜色刻意不走主题，
                    // 保证任何色板下都扫得动；卡片本身仍用主题圆角形状。
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
                        "打开后能看到这块表上的全部凭证（对话 Key 和语音识别凭证），" +
                            "直接在上面改或删。",
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
                        ImportStatus.WAITING -> Text(
                            "等待手机提交…",
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
                                "超时未收到，请取消后重试",
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
