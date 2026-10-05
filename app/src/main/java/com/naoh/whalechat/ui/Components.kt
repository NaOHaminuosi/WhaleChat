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
 * 更早它在列表第 1 项，后果是每次提示出现都要把用户从底部闪到顶端、
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
 * ⚠️ 这里原来有一个 `lastChatTime()`：同一自然日给钟点、跨日给 `N 天前`（一直数天数）。
 * **已删除**，因为改了 chat 卡片的时间形态 ——
 * 现在是右上角 [lastChatDate]（日期）+ 右下角 [lastChatClock]（钟点）。
 *
 * 删除而不是留着：留着会有人以为「跨日给几天前」那条规则还在，改回旧形态。
 *
 * ⚠️ [relativeTime]（ask 第一页）**不受这次改动影响**，两页时间档位故意不一样这条仍然成立。
 */

/**
 * 消息气泡右上角那个时间。
 *
 * ⚠️ **只用在用户自己的气泡上**：助手回答不挂时间。
 * 判据在调用处（`ChatScreen.MessageCard` 的 `isUser && showBubbleTime`），
 * 这个函数本身不知道是谁的气泡。
 *
 * 档位是**两段拼的**：日期部分越近越粗略、钟点只在当天/昨天给。
 *
 *  * 今天 → `HH:mm`
 *  * 昨天 → `昨天 HH:mm`
 *  * 更早、同年 → `M月d日 HH:mm`
 *  * 跨年 → `yyyy年M月d日`（**不带钟点**）
 *
 * ## 为什么复用它而不是另写一套
 *
 * 判据是**自然日**（[dayDiff]），跟 [lastChatDate] 同一套。`yesterday` 那一档
 * 不能写成 `now - millis < 172_800_000L`（48 小时窗口）—— 那是 [relativeTime] 的算法，
 * 会把「前天 23:00 说、今天 01:00 看」（26 小时前）判成「昨天」。**两套算法别混。**
 *
 * ## 为什么跨年那档砍掉钟点
 *
 * `yyyy年M月d日 HH:mm` 在这个宽度上是 17 个字符，放不进气泡名字那行的右端
 * （名字本身还要占地方）。而且跨年的消息本来就少、也不差那点精度 —— 年份比钟点重要。
 * **别为此把字号缩到 11sp 以下**：气泡里最小可读的是当前这个 `labelSmall`(13sp)。
 *
 * @param now 注入「现在」是为了能在脚本里断言边界，照 [relativeTime] 的写法。
 */
fun bubbleTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val days = dayDiff(millis, now)
    val clock = sameDay.format(Date(millis))
    return when {
        // 未来时间（手表时钟被改过）当作「今天」，给钟点，别显示成负数的「前天」。
        days <= 0L -> clock
        days == 1L -> "昨天 $clock"
        startOfDay(millis) >= startOfDay(now) - 365L * 86_400_000L -> "${thisYear.format(Date(millis))} $clock"
        else -> fullDate.format(Date(millis))
    }
}

/**
 * chat 第二页角色卡片**右上角**那个日期。
 *
 * `今日` / `昨天` / `M月d日` / 跨年 `yyyy年M月d日`。
 *
 * ## 这一版推翻了什么
 *
 * 更早的 chat 卡片右上角是那个已删除的 `lastChatTime()`（同一自然日给 `HH:mm`、
 * 跨日给 `N 天前`，一直数到 91 天）。后来改了需求：
 * **右上角给日期、右下角给钟点**，两个信息各占一角。
 *
 * 判据仍是**自然日**（[dayDiff]），不是满 24 小时 —— 这条老规矩没变。
 *
 * @param now 注入「现在」是为了能断言边界（1 号 23:59 → 2 号 00:00 必须是「昨天」）。
 */
fun lastChatDate(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    return when (dayDiff(millis, now)) {
        in Long.MIN_VALUE..0L -> "今日"
        1L -> "昨天"
        else -> if (startOfDay(millis) >= startOfDay(now) - 365L * 86_400_000L) {
            thisYear.format(Date(millis))
        } else {
            fullDate.format(Date(millis))
        }
    }
}

/**
 * chat 第二页角色卡片**右下角**那个钟点。恒为 `HH:mm`。
 *
 * 和 [lastChatDate] 是一对，同一条 `persona.lastChatAt` 派生出来。
 * 复用同一个 [sameDay] —— 全应用只此一份 24 小时制定义。
 *
 * 和右上角的日期不同，这里**不做「几天前」的换算**：那件事现在由日期那一角承担
 * （「昨天」/「3月5日」），同一张卡上再写一遍天数没有信息量。
 */
fun lastChatClock(millis: Long): String =
    if (millis <= 0L) "" else sameDay.format(Date(millis))

/**
 * 两个时间点之间**隔了几个自然日**：各自先截断到当天 0 点，再相减整除。
 *
 * 不能直接 `(now - millis) / 86_400_000L` —— 那算的是「满了几个 24 小时」，
 * 会在「1 号 23:59 → 2 号 00:00」这种只差 1 分钟的情况上返回 0（显示成钟点）。
 *
 * 已知取舍：截断后整除在**有夏令时的地区**理论上会差 1 天（那天只有 23 或 25 小时）。
 * 本应用主场景在中国（无夏令时），按这个写；要严格正确得上
 * `java.time.LocalDate` + `ChronoUnit.DAYS`。
 */
private fun dayDiff(millis: Long, now: Long): Long =
    (startOfDay(now) - startOfDay(millis)) / 86_400_000L

/** 把某一刻截断到当天 0 点（本地时区）。 */
private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

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

/**
 * 菜单弹窗里的一项。
 *
 * [destructive] 用 error 色，跟普通项拉开距离 —— 「删角色」和上面那几项并排放在一起，
 * 光靠文字分不出轻重，颜色是最省事也最有效的那层区分。
 */
data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 一个标题 + 若干项动作的菜单弹窗。
 *
 * 用 `AlertDialog` 的 **`content: ScalingLazyListScope` 重载**（而不是
 * confirmButton/dismissButton 那套两按钮版本）：长按一个角色要选的是三件事，
 * 塞进「确认 / 取消」两个槽里就得自己写一堆 Column，而 content 重载本来
 * 就是为「对话框里放一列东西」准备的。
 *
 * ## 为什么这里跟 [WhaleChatDialog] 长得不一样（按钮不撑满、三项挤在同一个 item 里）
 *
 * 这块屏只有 240dp 见方（半径 240px），而 Wear M3 的 `Button` 布局高度**恒为 48dp**
 * —— 想压矮没用，`minimumInteractiveComponentSize` 会把布局重新撑回 48dp
 * （`ButtonDefaults.CompactButtonHeight` 也是 48dp，那 32dp 只是可见高度）。
 * 所以「三行按钮 + 一行标题」总共要 ~430px，而 480px 的屏幕里真正能放东西的
 * 只有圆心附近那一块：一个宽 w 的元素，整条都落在圆内的纵向区间是
 * `y ∈ [240 ± sqrt(240² - (w/2)²)]`，越宽越窄。
 *
 * 两个后果，都是量出来的：
 *
 * 1. **所有项塞进同一个 `item {}` 里，自己用 Column 排行距。** 每一项单独做 item
 *    时，对话框内部的 ScalingLazyColumn 会按自己的档位加行距（实测每项占 120px，
 *    比 48dp 的按钮高出 24px）；合成一项之后行距由下面这个 4dp 说了算，
 *    省下 ~48px。这是让「删角色」不被切掉的关键那一下。
 * 2. **按钮宽度收在 118dp（不是 `fillMaxWidth`）。** 撑满 428px 时安全纵向区间
 *    只剩 [84,396]，第三行「删角色」必然掉出去、胶囊底被圆边切平（截图确认过）；
 *    收到 236px 后区间扩到 [31,449]，三行就都完整了。118dp 也是标签量出来的：
 *    「重新开始」四个字配 Wear 的按钮内边距大约 92dp，比它宽一点，三个按钮
 *    宽度自然统一。
 *
 * 3. **名字不写进 `title` 槽，而是当内容区第一行的小字。** `title` 槽是必填的
 *    （Wear 四个重载都要它），但传空 lambda 高度就是 0（`Title()` 只负责套一层
 *    横向 padding，还会额外插一个 `AlertContentTopSpacing`）。实测把名字写在
 *    title 槽里那一行占 72px，挪进内容区当 labelMedium 只要 ~35px，省下的 40px
 *    正好是「删角色」能不能整条落进圆内所需要的那一段。
 *
 * ## 为什么不放「取消」
 *
 * 不是漏了。加回来后就是**四项**（三件事 + 取消）。四项放不下 —— 「取消」整个落在
 * 屏幕外，长按之后根本看不见 —— 那才是真的把人堵住。去掉它剩三项，三项都能完整看见。
 *
 * 退出走两条 Wear 惯用路径，都通：
 * 1. 点对话框外面（`AlertDialog` 的 scrim 本来就接 `onDismissRequest`）；
 * 2. 按手表返回键（`AlertDialog` 内部把 Back 也接到 `onDismissRequest`）。
 *
 * 所以「取消」在这里是纯冗余，占的却是最稀缺的那一行的位置。
 */
@Composable
fun WhaleChatMenuDialog(
    title: String,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        // 名字放回 title 槽，但字号压到 labelMedium。
        //
        // 试过把它挪进内容区当第一行小字，结果反而更差（首个按钮从 128px 掉到
        // 142px）：`alertDialogCommonContent` 在标题和内容之间**永远**会插一个
        // `AlertContentTopSpacing`，所以「标题槽里的一行字」比「内容区里的一行字 +
        // 那个固定间隔」更省。别绕这一圈。
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        // 上下留白钉死。默认那套是「整屏组件」的量法：上 10% 屏高（24dp）、
        // 下 **36.46%**（87dp）—— 那个 36% 是留给「底部还有个 EdgeButton」的形态的，
        // 我们这种「一列按钮、没有确认/取消」用不着。内容在这个对话框里是**顶对齐**
        // 的（实测：把下留白从 87dp 改成 0 只挪动了 2px），所以真正有用的是上留白，
        // 它必须 ≥24dp 才能避开屏幕顶端的系统时间胶囊 —— 保持默认值不动。
        contentPadding = PaddingValues(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 0.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                // **4dp。** 每个 Wear 按钮钉了 `.height(48.dp)` 之后，布局高**实测 96px**
                // （density 2.0 ⇒ 48dp；不钉 height 的默认是 111px，见下面那段）。
                // 三颗 = 288px，加两段 4dp（8px）行距 = 304px。对话框内容顶对齐、
                // 上留白 24dp（48px），三行落在 y≈124–428px，整段都在圆内
                // （宽 236px 时安全纵向区间 [31,449]）⇒ 「删角色」上下圆角都完整、底边不被切平
                // （截图确认）。4dp 比原来的 0dp 肉眼能分出间隙，又不会把第三行顶出圆外。
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions.forEach { action ->
                    Button(
                        onClick = action.onClick,
                        // Wear M3 的 `ButtonDefaults.shape` 是绝对半径 26dp，48dp 高的
                        // 小按钮会被画成歪鸭蛋（和 [WhaleChatDialog] 里那段同一个坑）。
                        shape = RoundedCornerShape(percent = 50),
                        // **显式钉 48dp。** 不写这一行的话，按钮默认占 111px（55.5dp），
                        // 三颗就多出 45px —— 而圆屏只肯给我们 414px 的纵向额度。
                        // 顶部那排胶囊（[TopActionButtons]）也是这么钉的，同一套做法。
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 118.dp, max = 128.dp),
                        colors = if (action.destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(
                            text = action.label,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Key 在界面上永远只露头尾，手表上被人瞟一眼也拿不到完整串。 */
fun maskKey(key: String): String =
    if (key.length <= 8) "已保存" else "${key.take(5)}····${key.takeLast(4)}"

/**
 * token 数格式化：`1.2k` / `12.4k` / `1.2M`。
 *
 * 手表副标题就那么宽，`12345678` 这种裸数字会把卡片撑坏。规则：
 *  * 不到 1000 直接给整数（`864`）；
 *  * 1000 到 100 万给 `x.xk`（保留一位小数，去掉多余的 0）；
 *  * 百万以上给 `x.xM`。
 *
 * 「小于 1」这件事这里**不做兜底**：`tokens = 0` 会原样返回 `"0"`，不做语义判断。
 * 是否该显示由调用方决定 —— 两个调用方（对话页那行、角色卡片副标题）都先判了 `> 0`，
 * 没拿到用量时整块不出现，而不是显示一个「0」（见 PersonaRow）。
 */
fun formatTokens(tokens: Long): String {
    if (tokens < 1_000L) return tokens.toString()
    if (tokens < 1_000_000L) return trimZero(tokens / 1_000.0) + "k"
    return trimZero(tokens / 1_000_000.0) + "M"
}

/** 把 `1.0` 这种小数末位的 0 去掉，只保留一位有效小数。 */
private fun trimZero(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

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
