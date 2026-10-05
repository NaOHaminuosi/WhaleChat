package com.naoh.whalechat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.Conversation
import com.naoh.whalechat.data.Persona
import kotlinx.coroutines.delay

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
 *
 * **两页共用这四个常量**：第二页（通讯录）的按钮行和第一页几何完全相同，
 * 差一个字都会让两页在左右滑动时跳一下。
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
 * 首页的两页。`ordinal` 直接当页码用，加一页只要在这里加一项
 * （页数读 `entries.size`，不会再出现「枚举加了、页数忘了改」）。
 *
 *  * [ASK] —— 最近对话。问一句就走，不挂人设。
 *  * [CHAT] —— 通讯录。跟角色聊，人设是主角。
 *
 * 这两个名字**同时也是给用户看的**：滑过去时顶部会闪一行 `Ask` / `Chat`
 * （见 [displayName] 与 [ModeFlashLabel]）。**显示写法从枚举名推出来，不另抄一份** ——
 * 所以它们不只是内部代号，改的时候要当成 UI 文案改。
 *
 * 调用方也靠它区分来源：设置页要知道自己是从哪一页打开的
 * （从 chat 打开会多一项「用手机管理人设」）。
 */
enum class HomePage {
    ASK,
    CHAT,
    ;

    /**
     * 给用户看的写法：首字母大写（`Ask` / `Chat`）。
     *
     * 由枚举名推出来而不是再写一遍字面量 —— 「文案和枚举名各写一份」正是
     * 改了一处忘另一处的来源。要换显示写法就改这条推导规则。
     */
    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercaseChar() }
}

/**
 * 顶部模式提示的停留时长。
 *
 * 「出现」跟着滑动动作走，「消失」不跟：手指一停就消失的话，
 * 想确认「我现在在哪一页」根本来不及看。所以手势结束后再多留 2 秒。
 */
private const val MODE_FLASH_HOLD_MS = 2_000L

/**
 * 模式提示文字的竖直落点。
 *
 * 实测（在 480×480 @ density 320 上抓图逐行数亮像素得到的）：
 *  * 时间胶囊占 y≈12–34px；
 *  * 顶部两颗胶囊占 y≈118–214px。
 * 中间 34–118px 整条是空的。文字用 labelLarge 放在 28dp 处，
 * 墨迹正好落在 y≈64–94px 一带 —— 也就是这条空档的**正中**。
 *
 * **它是覆盖层，不进列表布局。** 进了的话每次滑动都会把整页内容顶下去一截，
 * 而第二页那点竖直空间是算到像素的（见 [ContactsPage] 里那段几何推导，
 * 角色卡片底角本来就已经贴着圆边相切）—— 再顶一下就会被圆边切掉。
 */
private val MODE_FLASH_TOP = 28.dp

/**
 * 角色卡片右列（右上角日期 / 右下角钟点）与左侧内容（名字 / 用量）之间的**间隙**。
 *
 * ## 右列怎么对齐（**居右**，别改成居左）
 *
 * 这两项分处两个槽（日期在 `title`、钟点在 `subtitle`），前面各有一个 `weight(1f)`
 * （名字 / 用量）。`weight(1f)` 吃满剩余空间、把后面的 `Text` 顶到 Row 末端，而
 * `Text` 在「被 Row 末端顶住 + 自己的盒子只有文字宽」时的**默认行为就是右对齐**
 * —— 于是上下两行的**右边界自动成一条竖线**，这正是要的效果。
 *
 * ⇒ **不要**给这两个 `Text` 加 `Modifier.width(...)`：一加就把盒子固定成左对齐，
 * 右边界反而参差（`昨天` 比 `23:06` 少 2 个字符 ⇒ 右边会往里缩一截）。
 * 规则是：「右边显示文字和左边是 x 轴上对齐，然后就是
 * **各自居左或居右显示**」—— 左列（名字 / 用量）居左、**右列（日期 / 钟点）居右**，
 * 两列各自成列，中间留一道固定间隙。
 *
 * ## 取值
 *
 * 取 8dp：够把名字和日期分开，又不至于让右列整体左移太多。
 * ⚠️ 两处的 `Spacer` **必须同宽** —— 它参与右列的定位，一处 8dp、一处 4dp 的话
 * 上下两行的右边界就会差 4dp，对齐立刻失效。
 */
private val TimeColumnGap = 8.dp

/**
 * 首页 = **横向分页的两页**。
 *
 *  * 第一页 ask：顶部两颗胶囊（＋ 新建对话 / 齿轮 设置）+ 最近对话列表；
 *  * 第二页 chat：结构照搬第一页，顶部同样是两颗胶囊（左 ＋ = 新建角色、右 齿轮 = 设置），
 *    下面排已创建的角色。
 *
 * 两页各自的 ButtonGroup 几何完全相同（[TOP_ACTION_WIDTH] / [TOP_ACTION_GAP]），
 * 所以左右滑动时那一排按钮不会跳一下。
 *
 * 手势分工交给官方的 `HorizontalPager` + `PagerScaffoldDefaults.gestureInclusion`：
 * 它在**第一页**的左边缘（屏宽 15% 以内）主动放行手势，于是那一块仍然归
 * `SwipeDismissableNavHost` 管 —— 从首页左边缘右滑照常退出应用；
 * 从第二页任意位置往右滑则回到第一页。这套判断是库内建的，不要自己写手势层去抢。
 *
 * 顶部还有一行**模式提示**（`Ask` / `Chat`，见 [ModeFlashLabel]）：
 * 每次滑动都出现，停手 2 秒后淡出。它挂在页面**里面**，
 * 所以是跟着页面一起移动的，不会被 AnimatedPage 那套过渡落在后面。
 *
 * 滚动指示器只留一套：竖的那条**关掉**（`scrollIndicator = {}`），
 * 只留底部的页面圆点。两套同时出现时一个在右侧、一个在底部，看着像两套互相矛盾的
 * 位置提示。
 */
@Composable
fun HomeScreen(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    /** 参数是**从哪一页点进设置的** —— 设置页拿它决定要不要多给一个「管理人设」入口。 */
    onOpenSettings: (HomePage) -> Unit,
    onOpenPersona: (String) -> Unit,
    onNewPersona: () -> Unit,
    onOpenPersonaDetail: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { HomePage.entries.size })

    // 顺手把内存里那些空壳收走：否则点了十次＋再取消，内存里就躺十条占位，
    // 虽然不显示也不落盘，但没有任何理由留着。放在每次进首页时做一次最省事 ——
    // 从对话页返回时首页会重新进入组合，这一下正好把刚放弃的那条清掉。
    //
    // 放在分页外壳这一层而不是某一页里面：两页共用一个首页生命周期，
    // 挂到某一页上会让「翻到第二页」也触发一次（虽然无害，但没必要）。
    LaunchedEffect(Unit) { ChatEngine.discardEmpty() }

    // 模式提示的显隐。
    //
    // 触发条件用 `PagerState.isScrollInProgress`：它只在**真的在滑动**时为 true，
    // 「滑了但没切页」（拖过一点又弹回去）同样算数 —— 用户要的就是这个，
    // 只要手指横向划过就给他一个「你在哪一页」的反馈。
    //
    // 用官方这个信号，别自己套 `pointerInput` 去数手势：第一页左边缘那块手势
    // 是主动放行给外层导航的（见上面 doc），自己加一层会和
    // `PagerScaffoldDefaults.gestureInclusion` 的判断打架。
    var modeFlash by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            modeFlash = true
        } else if (modeFlash) {
            // 手指抬起（含回落动画结束）之后再留 2 秒。中途又滑一下的话，
            // key 变化会让这个协程重启，计时自然重来。
            delay(MODE_FLASH_HOLD_MS)
            modeFlash = false
        }
    }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 官方给 Wear M3 调过的吸附手感（阈值 0.35，最多翻一页）。
            // 用默认的 PagerDefaults.snapFlingBehavior 也能跑，但阈值是 0.5，
            // 在圆屏上「滑了一半又弹回去」的挫败感明显更强。
            flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
            // 表冠不参与翻页：首页两页各自是可竖滚的列表，表冠留给列表。
            rotaryScrollableBehavior = null,
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (page) {
                        0 -> AskPage(
                            onOpenConversation = onOpenConversation,
                            onNewConversation = onNewConversation,
                            // 来源在这里定死，两页自己不用知道自己是第几页
                            onOpenSettings = { onOpenSettings(HomePage.ASK) },
                        )

                        else -> ContactsPage(
                            onOpenPersona = onOpenPersona,
                            onNewPersona = onNewPersona,
                            onOpenSettings = { onOpenSettings(HomePage.CHAT) },
                            onOpenPersonaDetail = onOpenPersonaDetail,
                        )
                    }

                    ModeFlashLabel(
                        text = HomePage.entries[page].displayName,
                        visible = modeFlash,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = MODE_FLASH_TOP),
                    )
                }
            }
        }
    }
}

/**
 * 顶部那行模式提示：滑到哪一页就闪哪一页的名字（`Ask` / `Chat`），停手 2 秒后淡出。
 *
 * 淡出没有随手写时长，用的是 Wear M3 自己发布的动效规范：
 * `MaterialTheme.motionScheme.defaultEffectsSpec()` —— 它专管**透明度、颜色**
 * 这类不涉及位移的变化，正是淡入淡出该用的那一条。
 * 换成自己填的 `tween(200)` 也能动，但那等于把 M3 在低功耗设备上对
 * 时长与缓动的取舍丢掉，也失去了以后跟着主题一起变的能力。
 *
 * 排版是**最朴素的那一版**：`labelLarge` + `onSurfaceVariant`，不加字距、
 * 不做模糊、不拉伸字形。中间试过「小 + 疏 + 虚化」和「大 + 扁 + 压灰」两版，
 * 都被否掉了 —— **别再加回来的东西**：
 *  * `Modifier.blur` —— 这么小的字上只是把笔画糊在一起，而且 `RenderEffect`
 *    要 API 31+（minSdk 30 上是空操作）；
 *  * `letterSpacing` / `graphicsLayer { scaleX }` —— 用户要的不是这两种「宽」；
 *  * 颜色乘 alpha 压暗 —— 不在这一版里。
 */
@Composable
private fun ModeFlashLabel(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = spec),
        exit = fadeOut(animationSpec = spec),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 第一页：ask。
 *
 * **除了外面套了一层分页外壳，这一页的内容与 1.0 逐字一致** ——
 * 几何常量、留白数值、空态文案、长按删除的确认框，一个都没动。
 * 唯一的差别是滚动指示器：竖的那条让位给底部的页面圆点，见 [HomeScreen] 的注释。
 */
@Composable
private fun AskPage(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    // 历史列表只认「说过话」的会话。首页那个＋只是拿一个 id 就跳进对话页，
    // 用户在空白页什么都没问就返回时，这条记录不该出现在这里。
    //
    // 另外**必须排掉角色会话**：它们挂在第二页的通讯录里，掉进 ask 的「最近对话」
    // 就等于同一条聊天在两个地方各出现一次，而其中一个地方还删不掉它。
    val history = conversations.filter { it.messages.isNotEmpty() && it.personaId == null }

    ScreenScaffold(
        scrollState = listState,
        // 竖滚动指示器关掉，只留底部的页面圆点（见 [HomeScreen] 的注释）
        scrollIndicator = {},
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
 * 第二页：chat 模式的通讯录（管的是「人」）。
 *
 * 长按一个角色出三件事：**人设详情 / 重新开始 / 删角色**。
 * 「话」的操作（编辑重发、停止生成、说话 / 打字）全在对话页，那边完全复用 ask 那套，
 * 一个字都没为新模式改。两套操作互不越界 —— 通讯录里不提供任何针对单条消息的东西。
 *
 * 「改名」和「人设详情」里那一行名字是同一个入口、同一条路由（改短字段），
 * 不是两套代码；既然详情页里点得到，菜单里就不重复放一份。
 */
@Composable
private fun ContactsPage(
    onOpenPersona: (String) -> Unit,
    onNewPersona: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonaDetail: (String) -> Unit,
) {
    val personas by ChatEngine.personas.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    // 只为把「角色列表显示用量」这个开关传进 PersonaRow。**在这里订阅一次**，
    // 不在列表项内部订阅 —— 每个 item 各订阅一次状态，滚动时会掉帧。
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()

    /** 长按某一行之后停在哪个角色上（菜单 / 二次确认都从这里派生）。 */
    var menuFor by remember { mutableStateOf<Persona?>(null) }
    /** 正在确认「重新开始」。 */
    var restartTarget by remember { mutableStateOf<Persona?>(null) }
    /** 正在确认「删角色」。 */
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }

    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = {},
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "top_gap") { GapItem(32.dp) }

            item(key = "actions") {
                TopActionButtons(
                    onAdd = onNewPersona,
                    onOpenSettings = onOpenSettings,
                    addDescription = "新建角色",
                )
            }

            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            // 第二页这个间隙给 0dp（第一页是 12dp）。理由见下面那段长注释：这里省下的
            // 每一 px 都是首屏那张角色卡片不被圆边吃掉底角所必需的。
            item(key = "actions_gap") { GapItem(0.dp) }

            // ── 第二页为什么比第一页「挤」────────────────────────────────────
            // 两页的结构本来是一模一样的，但第二页必须再抠出两处空间，否则**首屏那张
            // 角色卡片会被圆边切开**。不是审美问题，是几何问题。数字都是 480x480
            // 圆屏（半径 240px）上实测的：
            //
            //   卡片：428px 宽 × 145px 高（居中、未缩放时）。
            //   圆屏在 y 处的弦宽 = 2*sqrt(240^2 - (y-240)^2)。
            //   y=417 时弦宽 324px，y=344 时 432px —— 卡片要整张落在圆里，它的
            //   **最宽那条中线**（卡片顶边往下 72.5px 处）必须位于 y≈344 以上。
            //
            // 按第一页的排法：ScreenScaffold 留白(82px) + top_gap(64px) + 两颗胶囊(102px)
            // + actions_gap(24px) + ListSubHeader「角色」(111px) = 卡片顶边在 383px，
            // 卡片中线落到 455px，那里弦宽只剩 385px < 428px → 底角被切、第二行字
            // （相对时间/「还没聊过」）整个看不见。实测截图确认。
            //
            // 两处调整：
            //   1. 去掉 ListSubHeader。一个纯标题实测占 111px —— 比两颗胶囊还高。
            //      去掉后卡片顶边 272px，但底角仍被切 9~17px（截图确认，下缘两侧变斜口）。
            //   2. actions_gap 12dp → 0dp，再还 24px。卡片顶边 248px，中线 320px，
            //      该处弦宽 452px > 428px，底角只剩约 3px 的相切，肉眼看不出。
            //      胶囊与卡片之间仍有约 21dp 空隙，不会显得粘在一起。
            //
            // top_gap 的 32dp 不能动：第一页那段注释说过它是量出来的，再往上两颗胶囊
            // 自己的上角会顶到圆屏上沿。
            //
            // 顺带，去掉标题也更贴规格：顶部两颗胶囊 + 下面按顺序排列角色
            // 卡片就够了，并没有要求加标题。（第一页的「最近对话」属于「一个字都不要动」。）
            if (personas.isEmpty()) {
                item(key = "empty") {
                    Card(
                        onClick = onNewPersona,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "还没有角色\n点 ＋ 造一个",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                // 直接排卡片，不加 ListSubHeader —— 原因见上面那段几何推导。
                personas.forEach { persona ->
                    item(key = persona.id) {
                        PersonaRow(
                            persona = persona,
                            showUsageOnPersona = settings.showUsageOnPersona,
                            onClick = { onOpenPersona(persona.id) },
                            onLongClick = { menuFor = persona },
                        )
                    }
                }
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    // 长按 → 三选一。就这三件事，不设下拉菜单、不加二级页面。
    menuFor?.let { persona ->
        WhaleChatMenuDialog(
            title = persona.name,
            onDismiss = { menuFor = null },
            actions = listOf(
                DialogAction("人设详情") {
                    menuFor = null
                    onOpenPersonaDetail(persona.id)
                },
                DialogAction("重新开始") {
                    menuFor = null
                    restartTarget = persona
                },
                DialogAction("删角色", destructive = true) {
                    menuFor = null
                    deleteTarget = persona
                },
            ),
        )
    }

    // 「重新开始」= 人设不动、清空消息、把存的 greeting 再上架一次。不联网、瞬间完成，
    // 所以文案要写明它做什么，不要写成「重置」这种含糊的词。
    restartTarget?.let { persona ->
        WhaleChatDialog(
            title = "跟 ${persona.name} 重新开始？",
            body = "和 TA 的聊天记录会一起清空，无法恢复。TA 会说回那句老开场白。",
            confirmLabel = "重新开始",
            onConfirm = {
                ChatEngine.restartPersona(persona.id)
                restartTarget = null
            },
            onDismiss = { restartTarget = null },
        )
    }

    deleteTarget?.let { persona ->
        WhaleChatDialog(
            title = "删掉 ${persona.name}？",
            body = "和 TA 的聊天记录会一起删掉，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                ChatEngine.deletePersona(persona.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * 顶部那两颗胶囊。**两页共用同一份实现**，为的是几何完全一致。
 *
 * 用 **ButtonGroup** 而不是 Row + weight。Wear M3 里这一排按钮的正确容器就是
 * ButtonGroup，它比 Row 多出来的一件事正好是我们要的：`Modifier.animateWidth()`。
 * 那颗 Modifier 只在 ButtonGroup 里有意义 —— 按下时**这颗按钮按
 * ButtonGroupDefaults.ExpansionWidth（24dp）涨出去、旁边那颗同步缩回来，
 * 总宽一动不动**。变窄的那颗宽度缩到接近高度时，胶囊形状自然就变成一个圆。
 * 参考 app（微思应用商店）那两个胶囊按下后的形变就是这个 ——
 * 不是自己画的动画，是官方容器自带的按压反馈。
 *
 * [addDescription] 只用于无障碍描述，两页分别写「新建对话」「新建角色」。
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

/**
 * 通讯录里的一行。
 *
 * 版面是**两行、四个角各放一样东西**：
 * ```
 * 小夜                        昨天    ← 名字 titleMedium（左）；日期右对齐（右）
 * 19.2k tokens               19:32    ← 用量（左）；钟点右对齐（右）
 * ```
 *
 *  * 没聊过 → **两行都不出时间和用量**，副标题写「还没聊过」（那时能拿来算时间的
 *    只有创建时间，写出来是「刚刚」，等于说「你刚建了 TA」，不是说「你们聊过」）；
 *  * 聊过、拿到用量 → 左下角写 `19.2k tokens`（**带复数 s**）；
 *  * 聊过、没拿到用量 / 开关关掉 → 左下角**空着**（用 `Spacer(weight)` 顶开），
 *    右下角的钟点**必须还在**。
 *
 * ## 时间从右上角挪走，拆成右上日期 + 右下钟点
 *
 * 更早时右上角是 `lastChatTime()`（同一自然日给 `HH:mm`、跨日给 `N 天前`）。
 * 后来改成：**右上角给日期**（`今日`/`昨天`/`M月d日`），
 * **右下角给钟点**（`HH:mm`）。所以副标题槽从一个 Text 变成了一个 `Row`。
 *
 * ⚠️ 联动里最容易写错的一处：**用量开关关掉时，右下角那个钟点必须还在。**
 * 把两个信息拼成一个字符串再整体判 `null` 是最省事的写法，也是**假绿**的写法 ——
 * 「关掉用量，时间也没了」。要拆成 Row 里的两个子项分别判，左边没内容用
 * `Spacer(Modifier.weight(1f))` 顶开。
 *
 * ⚠️ **时间不用 `TitleCard` 的 `time` 槽**，两道理由：
 *  1. `time` 只在 `content != null` 时才贴到名字同一行右侧；而 `content != null`
 *     会把 subtitle 上方间距从 2dp 顶成 6dp，**卡片凭空高 4dp**（第二页几何是卡死的）；
 *  2. `time` 槽被 `timeWithTextStyle()` 强制套上 `TimeTypography`（bodyMedium，
 *     14sp/字重 450），比现在的 `labelSmall`（13sp/字重 500）更大更粗 ——
 *     用户明确说了「原先显示时间的字体大小效果就挺好」，**别再缩、也别换大**。
 *
 * 所以名字和时间都放进 `title` 槽（它的类型是 `RowScope.() -> Unit`，本来就在那层
 * `Row(Modifier.weight(1f))` 里，可以直接放多个子项）。副标题那个 Row 同理，
 * `subtitle` 的类型是 `ColumnScope`，里面当然可以再套一个 `Row`。
 *
 * [Persona.tagline]（那句简介）**不进这一行**：角色是用户自己造的，他不需要在列表上
 * 被反复提醒「TA 是什么样的人」，时间和用量才是他每次打开列表真正要看的。
 *
 * [showUsageOnPersona] 是设置里那个开关的值，由调用方订阅后**当参数传进来**，
 * 不在这里内部订阅 settings（列表项里重复订阅状态，滚动会掉帧）。
 * **开关只管左下角那个用量** —— 关掉时右上角的日期和右下角的钟点照常显示。
 */
@Composable
private fun PersonaRow(
    persona: Persona,
    showUsageOnPersona: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val conversation = persona.id.let { id ->
        ChatEngine.personaConversation(id)
    }
    val tokens = conversation?.totalTokens ?: 0L
    val tokenLine = if (showUsageOnPersona && tokens > 0L) {
        "${formatTokens(tokens)} tokens"
    } else {
        null
    }
    val chatted = persona.lastChatAt > 0L
    TitleCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        title = {
            Text(
                text = persona.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⭐ weight 给名字、不给 Spacer。Row 先量非 weight 的子项，
                // 名字会把想要的宽度全拿走、把时间挤成 0 宽（表现为「时间不见了」，
                // 而短名字时看起来完全正常）。weight 给名字 + Ellipsis，长名字截断、时间永远在。
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
            )
            if (chatted) {
                // ⚠️ 这个间隙必须和 subtitle 槽里那个**同宽**，否则上下两行的右列
                // 左右边界会各差一截（那样「对齐」就白做了）。见 [TimeColumnGap]。
                Spacer(modifier = Modifier.width(TimeColumnGap))
                Text(
                    // 右上角 = 日期。更早那版这里是 `lastChatTime()` 的
                    // 「HH:mm / N 天前」，规则已作废 —— 别改回去。
                    text = lastChatDate(persona.lastChatAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // ⭐ 库里那两层 Row 的 verticalAlignment 都写死 Top，槽里改不了 Row 的对齐，
                    // 只能给子项自己 align。不写这行的话 16sp 名字和 13sp 日期都贴顶，行首差一截。
                    //
                    // ⭐⭐ 右列**居右**（别加 `width()`）：名字的 `weight(1f)` 吃满剩余空间，
                    // 把这个 `Text` 顶到 Row/卡片右端；盒子只有文字宽、又被顶住 ⇒ 默认就是右对齐，
                    // 上下两行的右边界自动成一条竖线。
                    //
                    // ⚠️ **别给这个 Text 加 `Modifier.width(...)`**：一加盒子就固定成左对齐，
                    // 右边界反而参差（`昨天` 比 `23:06` 少 2 个字符 ⇒ 右边会往里缩一截）。
                    // 规则：「各自居左或居右显示」= 左列居左、右列居右。
                    //
                    // ⚠️ 前面那个 `Spacer(TimeColumnGap)` 是**两处都要有**的：名字（或用量）
                    // 的 `weight(1f)` 会把右列顶走，没有这个间隙的话名字和日期会贴着写。
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        },
        // 副标题是一个 Row：**左用量、右钟点**，两个信息各判各的。
        //
        // 三种情况分开处理（这是本版最容易假绿的一处，见上面 KDoc）：
        //  1. 没聊过 → 整槽给「还没聊过」，**不出时间也不出用量**；
        //  2. 聊过 → 出 Row：左边用量（没有就用 Spacer 顶开），右边钟点恒在；
        //  3. 没内容且没聊过 —— 已在 1 里覆盖，这里不必再写 `null` 分支。
        //
        // 注：`subtitle` 的类型是可空的 `@Composable (ColumnScope.() -> Unit)?`，
        // 传 `null` 就是「不渲染这一槽」；**别传 `Text("")`** —— 空 Text 仍占一整行
        // 16sp 行高，卡片会凭空长高，而界面上完全看不出来。
        subtitle = if (!chatted) {
            {
                Text(
                    text = "还没聊过",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tokenLine != null) {
                        Text(
                            text = tokenLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 和名字同一个道理：用量先量、钟点会被挤掉。weight 给左边那个。
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // ⚠️ 用量关掉时**必须留一个占位**把钟点顶到右端。直接不渲染左边，
                        // 右侧那个 Text 会贴到左边缘 —— 时间和名字一样长时会看不出来，
                        // 短名字 + 短时间时一眼就歪。用 Spacer 承担 weight，不要用
                        // `Arrangement.SpaceBetween`：那会让「有关键内容时」的间距也跟着变。
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // ⚠️ 与 title 槽里那个间隙**同宽**（见 [TimeColumnGap]），
                    // 否则上下两行的右列会各差一截。
                    Spacer(modifier = Modifier.width(TimeColumnGap))
                    Text(
                        // 右下角 = 钟点，恒为 HH:mm，跟右上角的日期是一对。
                        //
                        // ⭐⭐ 右列**居右**（别加 `width()`）：左边用量的 `weight(1f)` 把这里
                        // 顶到 Row 末端 ⇒ 默认右对齐，和右上角的日期右边界落在同一条竖线。
                        // 别加 `Modifier.width(...)`、也别改成 `IntrinsicSize.Max` —— 会把
                        // 右列的右边界打散（`昨天` 和 `23:06` 会各缩各的）。
                        text = lastChatClock(persona.lastChatAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        onLongClick = onLongClick,
        onLongClickLabel = "更多操作",
    )
}
