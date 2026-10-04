package com.naoh.whalechat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import kotlin.math.roundToInt
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.LinearProgressIndicatorDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.ChatMessage
import com.naoh.whalechat.data.ChatRole
import com.naoh.whalechat.voice.VoicePhase
import com.naoh.whalechat.voice.rememberVoiceInput
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * 底部操作区的宽度。
 *
 * 这块屏 240dp 见方、圆半径 120dp，越靠下越窄：按钮行滚到底时大致落在
 * 圆心下方 55~95dp，该处可用半宽 = sqrt(120² - dy²)，dy=95 时只剩约 73dp。
 * 取 168dp（含底部 48dp 留白后实际落在 dy≈70~90）能完整落在圆内，
 * 再往后加宽就会被圆边切角。
 */
private val ACTION_ROW_WIDTH = 168.dp

/** 底部留白。它是列表的最后一个 item，滚到底时屏幕最下面那块空白。 */
private val BOTTOM_GAP = 48.dp

/**
 * 底部两颗按钮之间的间隙。
 *
 * 交给 ButtonGroup 的 `spacing`，不再自己写 `Arrangement.spacedBy` ——
 * 宽度分配是容器统一算的，间隙也得用同一个数，否则按下时变宽的那一边会把间隙算歪。
 */
private val ACTION_GAP = 4.dp

@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    /**
     * 打开文字输入页。
     *
     * [draft] 是带过去待改的初始文字（语音识别结果，无则空串）。
     * [rewriteIndex] 非 null 表示这次是「编辑重发」：改完发送时要作废第 index 条
     * 用户消息及其之后的全部内容（见 ChatEngine.rewrite）。注意只有**最新那一问**
     * 会被传进来 —— 更早的提问不给长按，判据在本文件下面那段注释里。
     */
    onOpenTextInput: (draft: String, rewriteIndex: Int?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()
    val streamingId by ChatEngine.streamingId.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val conversation = conversations.firstOrNull { it.id == conversationId }
    val streaming = streamingId == conversationId
    val messages = conversation?.messages.orEmpty()

    /**
     * 会话真的被删掉时，**自己退回上一页**，不停在这块空屏上。
     *
     * 以前这里显示「这条对话已经不在了」然后就没了下文 —— 那是个死页面：
     * 两个按钮都发不出去（没有会话可发），用户唯一能做的只有返回，可屏幕上
     * 什么都没提示他这一点，看起来就是「卡住了」。
     *
     * **判据是「被明确删过、且那时已经有过 DS 回复」，不是「列表里找不到」。**
     * 这一条是踩出来的：「找不到」至少有三条原因，只有一条该说话 ——
     *  1. 被删了 / 被「清空所有对话」清了，而且那时它已经是条真对话 → 该提示、该退回去；
     *  2. 刚点「＋」进来的空壳（DS 还没回过话）→ 用户进打字页一个字没打就退出，
     *     或从这儿绕去设置里清了个空，回到这条空白会话时它当然不在列表里 ——
     *     这时候弹一句「这个对话已经不在了」纯属胡说，因为**这个对话根本还没建立**。
     *     **有 DS 回复才算一条新对话**；
     *  3. **重建空窗期** —— 最阴的一条：Wear 的边缘滑动返回（SwipeDismissableNavHost）
     *     会让这一页短暂重建，重建那一瞬间 `conversations` 还没读回来、列表是空的，
     *     于是「找不到」成立，红卡片就从首页中间劈下来。用户只是正常返回了一次
     *     （用户实测的触发动作是「往右滑想返回上一页、又中途往左滑回打字页」），
     *     什么都没做错。
     *
     * 所以换成 `ChatEngine.isGone()`：**要正面证据（id 进过删除名单），不要缺席证据。**
     * 而删除名单本身也按 `hasReply` 闸过一道，第 2 类连进去的机会都没有。
     *
     * 代价说清楚：进程被回收、导航栈恢复出一个从没落过盘的假 id 时，这一页会停在
     * 空白状态而不是自动退回。这是可接受的 —— 那种情况下用户看到的就是一个空会话，
     * 和刚点＋进来长得一样，返回一下就走了；比每个正常返回的人都劈一张红卡片好得多。
     *
     * 留一句提示给首页：用户被弹回列表总得知道为什么，否则只会更困惑。
     *
     * key 用「该不该走」这个布尔而不是 conversation 本身：流式输出时
     * conversation 每个 token 都换一个新实例，拿它做 key 等于每一片都重建一次
     * 这个 effect。布尔只在真的该走那一次翻转。
     */
    val gone = ChatEngine.isGone(conversationId)
    LaunchedEffect(gone) {
        if (gone) {
            ChatEngine.postNotice("这个对话已经不在了")
            onBack()
        }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    /** 用户长按了最后一条提问，正在问他要不要改了重发。值是那条消息的下标。 */
    var pendingRewrite by remember { mutableStateOf<Int?>(null) }
    // 引擎侧的提示（「上一条还在回答，先等它说完」「请先填 API Key」）以前只在首页显示，
    // 可触发它们的动作恰恰都发生在聊天页（语音、文字输入页发出的消息），
    // 结果就是用户点了发送却看不到任何反馈 —— 这里也订阅一份。
    val engineNotice by ChatEngine.notice.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()

    /**
     * 是否让视图自动钉在最后一条。
     *
     * 只在「用户亲手滚动结束、且没停在底部」时置 false，滑回底部再恢复。
     * 这里必须丢掉 snapshotFlow 的首次发射：进页面时列表本来就是可滚的、
     * 又没在底部，直接把初始状态当作用户操作的话 follow 会立刻变 false，
     * 打开旧会话就再也不会定位到底部了。
     */
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }

    // 打开会话时的第一次定位：本 effect 在首个组合帧之后才启动，
    // 那时列表还没测量，totalItemsCount 是 0，直接读会落空。等它变正数再滚。
    //
    // **空会话不滚。** 空会话的列表只有三项：顶部留白、「按下面的按钮问点什么」、那排按钮
    // （还有可能一张「还没填 API Key」的卡片）。滚到底等于把只有一句话的空状态推到屏幕上方
    // ——实测它会被时间胶囊压掉一半（截图里「按下面的按钮问点什么」和 11:20 叠在一起），
    // 用户看到的是一个残废的提示。空会话本来就该老老实实停在顶部。
    //
    // 发第一条消息时不用担心：下面那个 follow effect 会接管（follow 初始为 true），
    // 它按「条数 + 最后一条长度」触发，一样会跟到底。
    LaunchedEffect(conversationId) {
        if (messages.isEmpty()) return@LaunchedEffect
        val count = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        listState.scrollToItem(count - 1)
    }

    // 流式追加时跟随：以「条数 + 最后一条长度」为触发点
    val lastLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastLength, streaming) {
        // **空会话一个都不跟。** 这条守卫是实测出来的，不是保险：
        // 这个 effect 的 key 在**首次组合**时就已经是稳定的（0/0/false），所以它一进来
        // 就会拿 `follow` 的初始值 true 跑一遍、直接滚到列表末尾 —— 列表末尾是底部留白，
        // 于是只有一句话的空状态被顶到屏幕最上面，正好压在时间胶囊底下（截图里
        // 「按下面的按钮问点什么」和 11:20 叠在一起，第一行根本读不出来）。
        // 空会话没有任何「最后一条」值得跟，直接返回。
        if (messages.isEmpty()) return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }

    // 提示出现时，把它滚进视野。**提示在列表末尾**，所以要滚到底。
    //
    // 提示项早先插在**列表最前面**（消息循环之前），于是每次语音识别失败
    // 都要把用户从底部**闪到顶端**去看那条红卡 —— 报错之后还得自己再滑回底部。
    // 现在提示项在消息循环**之后**，紧贴底部，报错不需要移动视线。
    //
    // 搬位置的同时，这里也必须跟着改：原来滚到索引 1，现在要滚到**最后一项**
    // （末尾是底部留白，见 `bottom_gap` —— 滚到留白处正好把提示那一项完整带进视野）。
    //
    // ## 为什么仍然值得主动滚一下
    //
    // 即使提示就在底部附近，LazyColumn 插入新项时以「首个可见项」为锚，列表已经滚在
    // 半途时新插进来的项可能刚好落在视口下方一点点。主动滚到底是最稳的读法，
    // 也顺带保证「提示一定被看见」这件事不依赖用户当前滚到哪。
    //
    // ## 代价（说清楚，别当成 bug）
    //
    // 这次程序化滚动会让上面那段 snapshotFlow 认为「用户滚了」，于是 follow 变成 false。
    // 现在滚的是**底部**（早先是顶部），所以 follow 很快会在下一次
    // 「用户滑回底部」时自己恢复；而且滚到底本来就更接近流式输出的落点。
    // 这是可接受的 —— 有提示的时候本来就更该先看提示。
    val noticeText = hint ?: engineNotice
    LaunchedEffect(noticeText) {
        val count = listState.layoutInfo.totalItemsCount
        if (noticeText != null && count > 0) {
            // 滚到**最后一项**。减 1 是索引补偿：totalItemsCount 是「数量」不是「最大下标」。
            listState.scrollToItem(count - 1)
        }
    }

    /**
     * 对话页的麦克风：**默认说完就发，不中转**；但在没发出去之前留一条退路。
     *
     * 这一档固定说完就发，不跟任何设置走。以前它开着「先编辑」时，识别结果会被直接
     * 带进文字输入页 —— 用户按的是「说话」，结果界面跳走、键盘还弹起来整屏盖住，
     * 动作和预期完全是两回事（真机反馈的原话是「割裂」）。
     *
     * 现在按**动作**分职责：
     *  * 按左边结束录制 → 识别完直接发送，一个字都不多等；
     *  * 录的时候按右边键盘 → 先把这一句正常录完（等最后一片结果，跟发送那次
     *    一样只等一次识别），再把完整文字带进编辑页让用户改。
     *
     * 这样「介绍 deepseek」被听成「deep thick」时，用户不用重说一遍，
     * 也不用等它发出去再想办法 —— 没按发送就还有救。
     */
    val voice = rememberVoiceInput(
        onResult = { spoken -> ChatEngine.send(conversationId, spoken) },
        onMessage = { message -> hint = message },
        onEditResult = { spoken -> onOpenTextInput(spoken, null) },
    )

    // 时间胶囊由外层 AppScaffold 统一提供，这里只用管滚动指示器
    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = { ScrollIndicator(state = listState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
        ) {
            item(key = "top_gap") { GapItem(20.dp) }

            if (conversation == null) {
                // 到这里都是「看不到内容」但**没被明确删掉**的情况 —— 主要是下面两种，
                // 都不该说话，只留一块留白让页面别跳：
                //  * 刚点＋进来、DS 还没回过话的空壳（`hasReply` 为 false）；
                //  * 这一页正在重建的那一瞬（conversations 还没读回来）。
                // 真被删了的话，上面那个 LaunchedEffect 已经在把这一页送走了。
                item(key = "missing") { GapItem(20.dp) }
                return@ScalingLazyColumn
            }

            // ⚠️ 提示项（`NoticeCard`）在**消息循环之后**，见下面
            // 「item(key = "notice")」那一处。别搬回这里 ——
            // 放在消息前会让每次语音识别失败都「闪到顶端」，用户还得滑回底部。

            if (messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有消息\n按下面的按钮问点什么",
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // **只有「最新那条提问」能长按改。**
            //
            // 判据是「它后面再没有别的用户消息」，而不是「它是列表的最后一项」：
            // 最新那一问后面通常还挂着一条回答 —— 那正是要重答的对象；出错时还可能
            // 挂着一张错误卡。这些都不影响「它是最新的一问」这个身份。
            //
            // 为什么不给更早的那些：改中间那句的后果是把它**后面已经发生的整段对话**
            // 一起作废。会话滚长之后回头改前面某句，代价是后面几轮问答全部消失 ——
            // 「不允许在已有多条对话时，去编辑以前的消息……
            // 不能出现为了编辑之前的消息然后舍弃下面更新的对话」。
            //
            // 两处仍然不给长按：
            //  * 助手消息 —— 改回答没有意义，回答是模型的产物，只能重问；
            //  * 正在生成时 —— 后面的回答还没定型，改了说不清要作废什么。
            val lastUserIndex = messages.indexOfLast { it.role == ChatRole.USER }
            messages.forEachIndexed { index, message ->
                item(key = "m$index-${message.timestamp}") {
                    MessageCard(
                        message = message,
                        onRetry = if (message.error) {
                            { ChatEngine.retry(conversationId) }
                        } else {
                            null
                        },
                        onLongClick = if (index == lastUserIndex && !streaming) {
                            { pendingRewrite = index }
                        } else {
                            null
                        },
                    )
                }
            }

            // 本地提示（语音识别失败）优先，其次是引擎提示。
            //
            // ⚠️ **位置在消息循环之后**。原来是列表第 1 项，后果是
            // 每次提示出现都要把用户从底部闪到顶端，然后他自己再滑回底部。
            // 搬到末尾 + 那个 `LaunchedEffect` 滚到底，提示出现在视线落点附近，不打断阅读。
            //
            // `hint`（语音/本地）和 `engineNotice`（缺 Key 等）**共用这一个 item**，
            // 必须一起搬 —— 只搬一半会让两处提示出现在不同位置。
            (hint ?: engineNotice)?.let { text ->
                item(key = "notice") {
                    NoticeCard(
                        text = text,
                        onDismiss = {
                            hint = null
                            ChatEngine.consumeNotice()
                        },
                    )
                }
            }

            // 只在第一条增量到达之前显示「正在思考…」。
            // 正文或思维链一开始进，消息卡片自己就是进度指示，再叠一张反而乱。
            val pending = streaming && messages.lastOrNull()
                ?.let { it.content.isEmpty() && it.reasoning.isEmpty() } == true
            if (pending) {
                item(key = "thinking") { ThinkingCard() }
            }

            // 录音 / 上传期间给一条明确反馈。手表上按钮就那么大，
            // 光靠图标换个形状说明不了「它到底在不在听」—— 音量条是最直接的证据。
            if (voice.phase != VoicePhase.IDLE) {
                item(key = "voice") {
                    VoiceStatusCard(
                        phase = voice.phase,
                        level = voice.level,
                        partial = voice.partial,
                    )
                }
            }

            item(key = "actions") {
                if (streaming) {
                    Button(
                        onClick = { ChatEngine.stop() },
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                    ) {
                        Icon(
                            imageVector = AppIcons.Stop,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp),
                        )
                        Text("停止生成", maxLines = 1)
                    }
                } else {
                    // 两个按钮放进 **ButtonGroup** —— Wear M3 里给「一排按钮」准备的组合容器。
                    //
                    // 换掉原来的 Row + weight 不是为了省几行代码，而是因为
                    // `Modifier.animateWidth()` **只在 ButtonGroup 里才有意义**：
                    // 按下时这颗按钮按 ExpansionWidth 变宽、旁边那颗同步变窄，**总宽一点不变**。
                    // 宽度缩到接近高度时，胶囊形状自然就变成一个圆 —— 参考 app 里
                    // 那两个胶囊按下后的形变就是这个，不用自己画动画（是官方容器自带的反馈）。
                    ButtonGroup(
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                        spacing = ACTION_GAP,
                        // 默认的 fullWidthPaddings() 是给「占满全屏宽」的用法算的
                        // （左右各留屏高的 5.2%）。这里总宽已经按圆屏几何钉死在 168dp 了，
                        // 再吃一遍内缩两颗都会变窄、还往圆屏边沿挤。
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        // 每颗按钮一个独立的 interactionSource：animateWidth 靠它听按压状态。
                        // 共用一颗的话，按左边会把右边也一起撑开。
                        val voicePress = remember { MutableInteractionSource() }
                        val textPress = remember { MutableInteractionSource() }

                        FilledTonalButton(
                            // 一下开始、一下结束。手表上「按住说话」很难按住，
                            // 而且系统手势会跟长按抢事件，点按切换才稳。
                            onClick = {
                                if (voice.phase == VoicePhase.RECORDING) {
                                    voice.stop()
                                } else {
                                    voice.start()
                                }
                            },
                            // 识别中再点没有意义（请求已经发出去了，取消不了它），
                            // 置灰比「能点但没反应」诚实
                            enabled = voice.phase != VoicePhase.TRANSCRIBING,
                            modifier = Modifier.animateWidth(voicePress),
                            interactionSource = voicePress,
                            // 宽度由 ButtonGroup 定，内边距清零，居中交给里面那个 fillMaxWidth。
                            //
                            // 以前是靠一组**对称 padding**（水平 29dp）凑居中的，那个数只在
                            // 「宽度恒为 82dp」时成立 —— 现在按下会变宽/变窄，硬编码的 padding
                            // 立刻就歪了。ButtonGroup 会给每颗按钮一个固定宽度约束，
                            // fillMaxWidth 这才撑得开（Button 内部是 intrinsicSize 宽度的 Row，
                            // 约束不定宽时它只按内容量，那才是以前 fillMaxWidth 失效的原因）。
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (voice.phase == VoicePhase.RECORDING) {
                                        AppIcons.Stop
                                    } else {
                                        AppIcons.Mic
                                    },
                                    contentDescription = when (voice.phase) {
                                        VoicePhase.RECORDING -> "结束录音并识别"
                                        VoicePhase.TRANSCRIBING -> "正在识别"
                                        VoicePhase.IDLE -> "语音输入"
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Button(
                            onClick = {
                                when (voice.phase) {
                                    // 正录着：先停录、等这一趟识别完，再带着完整文字进编辑页。
                                    //
                                    // 这里必须"先收尾再跳"，不能直接带屏幕上那半句 partial 跳走：
                                    // 跳页会销毁这个页面，DisposableEffect 里那两下（掐录音、
                                    // 取消协程）跟着执行，服务端随后补发的最后一片就永远收不到，
                                    // 用户带过去的是半句话。多等的这一次识别，按左边「结束录制」
                                    // 发送时本来也要等，所以不多花时间。
                                    VoicePhase.RECORDING -> voice.stopToEdit()
                                    // 已经在等结果了：马上就带着字跳过去，再点没有去处
                                    VoicePhase.TRANSCRIBING -> Unit
                                    // 没在录：空框进编辑页，跟以前一样
                                    VoicePhase.IDLE -> onOpenTextInput("", null)
                                }
                            },
                            enabled = voice.phase != VoicePhase.TRANSCRIBING,
                            modifier = Modifier.animateWidth(textPress),
                            interactionSource = textPress,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.Keyboard,
                                    contentDescription = if (voice.phase == VoicePhase.RECORDING) {
                                        "结束录音，带文字去修改"
                                    } else {
                                        "文字输入"
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (settings.apiKey.isBlank()) {
                item(key = "no_key") {
                    ActionCard(
                        title = "还没填 API Key",
                        subtitle = "点这里去设置",
                        icon = AppIcons.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }

            item(key = "bottom_gap") { GapItem(BOTTOM_GAP) }
        }
    }

    // 长按最新那条提问 → 全屏确认要不要改。原文一并摆出来：用户得先看清自己要改的是哪句
    // （弹窗是全屏的，背后那条气泡会被盖住）。
    //
    // 能长按到的只有「最新一问」，所以后果只剩两种：它已经是最后一条（下面什么都没有），
    // 或者它下面挂着一条回答（少数情况下是一张失败卡）。不再有「下面还有 N 轮追问会一起
    // 删掉」那种量级 —— 那种编辑已经被上面的判据挡在门外了。
    pendingRewrite?.let { index ->
        val original = messages.getOrNull(index)?.content.orEmpty()
        val dropped = messages.size - index - 1
        WhaleChatDialog(
            title = "改一下这句？",
            body = if (dropped == 0) {
                "改完发送，就用新的问法重问一遍。"
            } else {
                "改完发送，原来那条回复会被删掉再重新答一遍。"
            },
            confirmLabel = "编辑",
            quote = original,
            onConfirm = {
                pendingRewrite = null
                onOpenTextInput(original, index)
            },
            onDismiss = { pendingRewrite = null },
        )
    }
}

/**
 * 一条消息。
 *
 * 第一行是名字：用户消息写「你」，助手消息写「DeepSeek」。
 */
@Composable
private fun MessageCard(
    message: ChatMessage,
    onRetry: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val isUser = message.role == ChatRole.USER

    val container = when {
        message.error -> scheme.errorContainer
        isUser -> scheme.primaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        message.error -> scheme.onErrorContainer
        isUser -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }

    /** 名字的前景色。 */
    val labelColor = when {
        message.error -> scheme.onErrorContainer
        isUser -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }

    // 有思维链就展开/收起，顺手给整张卡片的点击一个去处
    var reasoningOpen by remember { mutableStateOf(false) }
    val hasReasoning = message.reasoning.isNotBlank()

    Card(
        onClick = { if (hasReasoning) reasoningOpen = !reasoningOpen },
        modifier = Modifier.fillMaxWidth(),
        // 长按只挂在用户消息上（见上面的循环）。传 null 时卡片行为和以前完全一样，
        // 不会多出一个点不出东西的长按手势。
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) "编辑重发" else null,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isUser) "你" else "DeepSeek",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = labelColor,
            )

            if (hasReasoning) {
                Text(
                    text = if (reasoningOpen) message.reasoning else "思考过程 · 点击展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isUser -> scheme.onPrimaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = if (reasoningOpen) Int.MAX_VALUE else 1,
                )
            }

            val body = message.content.ifBlank { if (message.error) "出错了" else "…" }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun ThinkingCard() {
    // Wear M3 的 Card 只有「可点击」一种重载，没有静态版本 ——
    // 静态卡片的写法是 onClick = {} + enabled = false（官方 KDoc：not enabled 就是不可点）。
    // 光写 onClick = {} 会给出水波纹，等于骗用户去点一张什么都没发生的卡。
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = "正在思考…",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 录音 / 识别状态。
 *
 * 做成静态卡片（`enabled = false`）是刻意的：它只报状态、没有任何点击行为，
 * 而 Wear M3 的 Card 只有「可点击」一种重载，不加 enabled = false 就会带水波纹，
 * 看起来像能点。理由和 ThinkingCard 完全一样。
 *
 * [partial] 是流式识别的中间结果（讯飞那条才有）。把它显示出来是有意为之：
 * 「边说边出字」如果只在识别完才给结果，用户没法判断机器到底听对没有，
 * 而这个页面又没有输入框可以回看 —— 中间结果就是唯一的证据。
 */
@Composable
private fun VoiceStatusCard(phase: VoicePhase, level: Float, partial: String) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.width(ACTION_ROW_WIDTH),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (phase == VoicePhase.RECORDING) "正在聆听…" else "正在识别…",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (phase == VoicePhase.RECORDING) {
                Spacer(Modifier.height(6.dp))
                LevelMeter(level)
            }
            if (partial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = partial,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 录音电平条。
 *
 * 用 Wear M3 的 `LinearProgressIndicator`，不再手绘两个叠起来的 Box：
 *  * 它本来就是「一条轨道 + 一段进度」的语义，电平条要做的事一模一样；
 *  * 圆头、端点那颗小圆点、主色 / 容器色、粗细 token 都是 M3 定好的 ——
 *    手绘那版这些数全是我自己凑的，跟主题色板对不上，换主题也不会跟着走；
 *  * `strokeWidth` 只能给到 [LinearProgressIndicatorDefaults.StrokeWidthSmall]（8dp），
 *    那是它保证端点圆点还看得见的底线，硬压更细会画出畸形。
 *
 * 值必须 coerceIn —— 浮点误差会让它偶尔略微越过 1。
 */
@Composable
private fun LevelMeter(level: Float) {
    LinearProgressIndicator(
        progress = { level.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        strokeWidth = LinearProgressIndicatorDefaults.StrokeWidthSmall,
    )
}
