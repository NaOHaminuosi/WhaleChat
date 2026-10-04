package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextToggleButtonDefaults
import androidx.wear.compose.material3.TextToggleButtonShapes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 列表里的纯留白项。
 *
 * 圆屏上想让内容避开时间胶囊，只能靠真实 item 撑开：
 * `ScalingLazyColumn` 关掉 autoCentering 之后 `contentPadding.top` 推不动第一项，
 * 只有插空白 item 才有确定的像素位移。
 */
@Composable
fun GapItem(height: Dp) {
    Box(Modifier.size(1.dp, height))
}

/**
 * 一次性提示条：缺 Key、请求被拒之类的本地消息。
 *
 * ⚠️ **这个卡片在对话页里必须排在消息列表末尾**。
 * 早先它在列表第 1 项，后果是每次提示出现都要把用户从底部闪到顶端、
 * 再自己滑回来。位置归调用点管（`ChatScreen`），
 * 这里只管长什么样 —— 但换位置的时候别把它又摆回列表开头。
 */
@Composable
fun NoticeCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onDismiss,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("知道了")
            }
        }
    }
}

/** 带头行图标的小卡片，首页和设置页共用。 */
@Composable
fun ActionCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 6.dp),
                )
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 同一天内的钟点。**应用里只此一份 24 小时制定义** —— chat 卡片的时间也复用它，
 * 不另起 `SimpleDateFormat`（`SimpleDateFormat` 不是线程安全的，两份还会改一处漏一处）。
 *
 * 已知取舍：写死 `HH:mm`，**不跟随系统的 12/24 小时制**。所以系统设成 12 小时制时，
 * 顶部时间胶囊（跟系统）显示 `7:32`、卡片显示 `19:32`，两处不一致。
 * 全项目一个格式比「跟系统」更重要，且 12 小时制要靠 `Context` 拿
 * （`DateFormat.getTimeFormat`），会把这里的函数签名拖上 `Context` —— 不值。
 */
private val sameDay = SimpleDateFormat("HH:mm", Locale.CHINA)
private val thisYear = SimpleDateFormat("M月d日", Locale.CHINA)
private val fullDate = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

/** 列表里的时间：越近越具体，越远越粗略。 */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - millis
    if (diff < 0) return sameDay.format(Date(millis))
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        diff < 31_536_000_000L -> thisYear.format(Date(millis))
        else -> fullDate.format(Date(millis))
    }
}


/**
 * 应用里所有「几个选项里选一个」的开关组共用这一套形状。
 *
 * 官方默认的 `TextToggleButtonDefaults.shapes()` 是**所有状态共用一个形状**（整圆胶囊），
 * 于是选中与否只靠颜色区分。而 Wear M3 专门为「选一个」这种开关备了
 * `variantAnimatedShapes()`：未选中是整圆胶囊、**选中拉成 18dp 圆角矩形**，
 * 再叠一层按下时圆角收紧到 66% 的形变。
 *
 * 这就是 M3 Expressive 里「形状本身也在表达状态」那一条。一排在圆屏上只有两三个字的
 * 按钮，光靠色块看不太出哪个是选中的，形状一变就一眼分明 —— 这是设置页最常被读的一处控件。
 */
@Composable
fun selectionToggleShapes(): TextToggleButtonShapes =
    TextToggleButtonDefaults.variantAnimatedShapes()

/** 一行说明文字。设置页各子页共用，故意做成不适合点击的样子。 */
@Composable
fun HintText(text: String) {
    // 静态卡片：Wear M3 的 Card 没有非点击重载，静态版本 = onClick = {} + enabled = false，
    // 只写 onClick = {} 会带上水波纹，看着像能点。
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/** Key 在界面上永远只露头尾，手表上被人瞟一眼也拿不到完整串。 */
fun maskKey(key: String): String =
    if (key.length <= 8) "已保存" else "${key.take(5)}····${key.takeLast(4)}"

/**
 * 应用里所有「要不要」的确认框都走这一个。
 *
 * **为什么不用 `ConfirmationDialog`**：那个控件是给「操作成功了 / 失败了」这类一次性
 * 反馈设计的 —— 它把确认动作渲染成一个旋转 45° 的**圆形图标按钮**，再压在一块圆角
 * 方形容器上。在圆屏上就是一个红圆叠一个方片，跟应用里其余卡片的形状语言完全脱节
 * （用户的评价是「好丑」），而且它只有图标、没有文字余地，「删除」这种要说清楚后果的
 * 场景根本放不下。
 *
 * `AlertDialog` 才是对的控件：它是 ScreenScaffold + 全屏可滚动的 ScalingLazyColumn，
 * 标题、正文、两个胶囊按钮的形状与配色都跟应用其它页面同源，也顺带解决了圆屏
 * 顶部时间胶囊的避让。默认色板跟着莫奈取色走，所以它就是 Material You。
 *
 * @param quote 需要给用户看原文的场合（比如「你要改的是这一句」），作为强调行排在
 *   解释文字上方。传 null 就没有这一行。
 * @param destructive 危险动作（删除之类）用 error 色，跟普通确认拉开距离。
 */
@Composable
fun WhaleChatDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    quote: String? = null,
) {
    val quoted = quote
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        // 原文和解释挤在同一个 text 槽里，而不是给原文单独套一张卡片。
        //
        // 这是量出来的：这块屏只有 226dp 见方，卡片自带的内容内边距（上下各 16dp）
        // 光为了一行字就要吃掉 90px，整段内容因此溢出到圆屏下沿，确认按钮被圆边切掉。
        // 合成一段之后，原文靠字号和颜色跟解释拉开层次，省下的全是按钮的空间。
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (quoted != null) {
                    Text(
                        text = quoted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                // 形状必须显式钉成胶囊。
                //
                // Wear M3 的 ButtonDefaults.shape 是 RoundedCornerShape(26.dp) —— 一个
                // **绝对**半径。这些按钮只有 48dp 高，26dp 的圆角已经超过半高，Compose
                // 不会把半径夹到一半，而是照画四段圆弧，结果四角互相盖住、渲染成一个
                // 歪鸭蛋（实测截图确认）。percent = 50 是相对半径，永远等于半高，
                // 于是无论按钮多大都是一个规规矩矩的胶囊 —— 和首页、输入页那几个按钮同款。
                //
                // 光有形状还不够：48dp 高的小按钮如果宽度也只有 56dp，胶囊本身就是一个
                // 圆。「编辑」两个字撑不出宽度（Wear 的 Button 左右内边距只有 14dp），
                // 所以再给一个最小宽度把它拉成横着的胶囊 —— 输入页那个「发送」就是
                // 这个比例。widthIn 而不是 width：设置页那个「全部清空」四个字更长，
                // 钉死宽度会把它挤没。
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier.widthIn(min = 76.dp),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                // 这一行必须显式让文字自己居中，不能指望 Button 帮我们居中。
                //
                // Wear M3 的 `Button`（内部 `ButtonImpl`）是一个
                // `Row(modifier, verticalAlignment = CenterVertically)` ——
                // **没有 horizontalArrangement**，也就是默认的 `Arrangement.Start`。
                // 手机版 Material 3 在同一位置写的是 `Arrangement.Center`，Wear 版没有；
                // 因为官方假定按钮宽度总是"抱住内容"
                // （`Modifier.width(intrinsicSize = IntrinsicSize.Max)`），
                // 宽度 == 内容宽度时 Start 和 Center 看不出区别 —— 这个假定一破就露馅。
                //
                // 文字虽然带着 `TextAlign.Center`，但它在 Row 里是 wrap 宽度，
                // 那个 center 没有可居中的余量，等于没生效。
                //
                // 平时按钮宽度就是"抱住文字"，看不出问题；一旦上面那个
                // `widthIn(min = 76.dp)` 把按钮从「圆」拉成「胶囊」，
                // 多出来的宽度就**全部堆在右边**。实测「编辑」两字：
                // 左边距 16.5dp、右边距 35.0dp，肉眼就是"字没居中"。
                //
                // 让 Text 自己占满整行，`TextAlign.Center` 才有余量可用。
                Text(
                    text = confirmLabel,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", maxLines = 1)
            }
        },
    )
}
