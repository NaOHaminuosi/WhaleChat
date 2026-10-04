package com.naoh.whalechat.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.Conversation

/**
 * 顶部两个主操作按钮的尺寸。
 *
 * 这块屏是 480px @ density 320，也就是 **240dp** 见方 —— 圆屏半径只有 120dp。
 * 两个胶囊并排后总宽必须完整落在圆内：设按钮行中心距圆心 dy，则该高度处的
 * 可用半宽 = sqrt(120² - dy²)。
 *
 * 现在把按钮行抬到圆心上方约 56dp 处（top_gap=32 + 按钮半高 24 → 中心 dy≈56），
 * 该处可用半宽 ≈ sqrt(120² − 56²) ≈ 106dp，故安全总宽取 184dp：
 * 两栏各 90dp + 中间 4dp。高度 48dp，比初版（56dp）略小，更贴近参考 app 的轻盈感。
 *
 * 注意 [TOP_ACTION_WIDTH] 是**静止**宽度。按下时 ButtonGroup 会让被按的那颗
 * 按 ExpansionWidth（默认 24dp）涨出去、旁边那颗同步缩回来，总宽始终 184dp ——
 * 所以圆屏几何这条约束不会被按坏。
 */
private val TOP_ACTION_WIDTH = 90.dp
private val TOP_ACTION_HEIGHT = 48.dp

/**
 * 两颗胶囊的间隙。
 *
 * 交给 ButtonGroup 的 `spacing`，不再自己写 `Arrangement.spacedBy` ——
 * 宽度分配是容器统一算的，间隙也必须用同一个数，否则按下时变宽的那一边会把间隙算歪。
 */
private val TOP_ACTION_GAP = 4.dp

/** 两个胶囊并排后的总宽。写成显式常量，免得 `a * 2 + b` 这种式子读起来含糊。 */
private val TOP_ACTION_ROW_WIDTH = TOP_ACTION_WIDTH * 2 + TOP_ACTION_GAP

/** 顶部图标尺寸（比初版的 26dp 略小，配合更小的胶囊）。 */
private val TOP_ACTION_ICON_SIZE = 22.dp

/**
 * 首页：顶部两颗胶囊（＋ 新建对话 / 齿轮 设置）+ 最近对话列表。
 */
@Composable
fun HomeScreen(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    // 顺手把内存里那些空壳收走：否则点了十次＋再取消，内存里就躺十条占位，
    // 虽然不显示也不落盘，但没有任何理由留着。放在每次进首页时做一次最省事 ——
    // 从对话页返回时首页会重新进入组合，这一下正好把刚放弃的那条清掉。
    LaunchedEffect(Unit) { ChatEngine.discardEmpty() }

    // 历史列表只认「说过话」的会话。首页那个＋只是拿一个 id 就跳进对话页，
    // 用户在空白页什么都没问就返回时，这条记录不该出现在这里。
    val history = conversations.filter { it.messages.isNotEmpty() }

    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = { ScrollIndicator(state = listState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            // 列表非空时必须关掉自动居中：否则第 2 项会被强行滚到屏幕正中，
            // 上面那排按钮会被顶出圆屏上沿。
            autoCentering = null,
            // 关掉自动居中之后，item 默认按屏宽左对齐（圆屏上圆内左边距看着很歪），
            // 显式居中，两个胶囊才会稳稳落在圆心里。
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 原来这里有个应用名标题，现在去掉了：
            // 圆屏最顶那一行本来就窄，留给两个主操作更值。
            //
            // 这段留白是量出来的：时间胶囊底边在 y≈18dp，两个胶囊起点压到
            // 约 32dp 处 —— 比初版的 42dp 再上移一圈，更接近参考 app「贴着
            // 顶边、轻巧两个小按钮」的观感，又不至于和时间胶囊打架。
            item(key = "top_gap") { GapItem(32.dp) }

            item(key = "actions") {
                TopActionButtons(
                    onAdd = onNewConversation,
                    onOpenSettings = onOpenSettings,
                    addDescription = "新建对话",
                )
            }

            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            // 按钮行和下面的卡片之间补一点空隙。
            // 之前两者紧挨着（只有 4.5dp），视觉上像被粘在一起。
            item(key = "actions_gap") { GapItem(12.dp) }

            if (settings.apiKey.isBlank()) {
                item(key = "need_key") {
                    ActionCard(
                        title = "还没填 API Key",
                        subtitle = "点上面的齿轮去设置",
                        icon = AppIcons.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }

            if (history.isEmpty()) {
                item(key = "empty") {
                    Card(
                        onClick = onNewConversation,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "还没有对话\n点 ＋ 开始",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                item(key = "recent_header") {
                    ListSubHeader { Text("最近对话") }
                }
                history.forEach { conversation ->
                    item(key = conversation.id) {
                        ConversationRow(
                            conversation = conversation,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongClick = { pendingDelete = conversation },
                        )
                    }
                }
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    // 圆屏上没地方放长按提示，所以长按只是「进入待删除」，真正删除要在这再确认一次
    pendingDelete?.let { target ->
        WhaleChatDialog(
            title = "删除这个对话？",
            body = "「${target.displayTitle}」和它的全部消息都会一起删掉，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                ChatEngine.deleteConversation(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 顶部那两颗胶囊。
 *
 * 用 **ButtonGroup** 而不是 Row + weight。Wear M3 里这一排按钮的正确容器就是
 * ButtonGroup，它比 Row 多出来的一件事正好是我们要的：`Modifier.animateWidth()`。
 * 那颗 Modifier 只在 ButtonGroup 里有意义 —— 按下时**这颗按钮按
 * ButtonGroupDefaults.ExpansionWidth（24dp）涨出去、旁边那颗同步缩回来，
 * 总宽一动不动**。变窄的那颗宽度缩到接近高度时，胶囊形状自然就变成一个圆。
 * 参考 app 那两个胶囊按下后的形变就是这个 ——
 * 不是自己画的动画，是官方容器自带的按压反馈。
 *
 * [addDescription] 只用于无障碍描述。
 */
@Composable
private fun TopActionButtons(
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    addDescription: String,
) {
    ButtonGroup(
        modifier = Modifier.width(TOP_ACTION_ROW_WIDTH),
        spacing = TOP_ACTION_GAP,
        // 默认的 fullWidthPaddings() 是给「占满全屏宽」的用法算的
        // （左右各留屏高的 5.2%）。这里总宽已经被圆屏几何钉死在 184dp 了，
        // 再吃一遍内缩两颗都会变窄、还会被顶出圆屏上沿。
        contentPadding = PaddingValues(0.dp),
    ) {
        // 每颗胶囊一个独立的 interactionSource：animateWidth 全靠它听按压状态。
        // 共用一颗的话，按左边会把右边也一起撑开。
        val addPress = remember { MutableInteractionSource() }
        val settingsPress = remember { MutableInteractionSource() }

        Button(
            onClick = onAdd,
            modifier = Modifier
                .height(TOP_ACTION_HEIGHT)
                .animateWidth(addPress),
            interactionSource = addPress,
            // 宽度由 ButtonGroup 定，内边距清零，居中交给里面那个 fillMaxWidth。
            //
            // 以前是靠一组**对称 padding**（水平 34dp）把图标顶到正中的，
            // 那个数只在「宽度恒为 90dp」时成立 —— 现在按下会变宽/变窄，
            // 硬编码的 padding 立刻歪掉。
            //
            // 顺带纠正一条以前的误解：Button 内部是 `width(IntrinsicSize.Max)`
            // 的 Row，约束**不定宽**时它只按内容量，那才是 fillMaxWidth 撑不开的
            // 真实原因；ButtonGroup 会给每颗按钮一个定宽约束，这里就撑得开了。
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = addDescription,
                    modifier = Modifier.size(TOP_ACTION_ICON_SIZE),
                )
            }
        }

        FilledTonalButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .height(TOP_ACTION_HEIGHT)
                .animateWidth(settingsPress),
            interactionSource = settingsPress,
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(TOP_ACTION_ICON_SIZE),
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    TitleCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        title = {
            Text(
                text = conversation.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = {
            Text(
                text = relativeTime(conversation.sortKey),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onLongClick = onLongClick,
        onLongClickLabel = "删除对话",
    )
}
