package com.naoh.whalechat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.PersonaField
import com.naoh.whalechat.data.PresetField
import com.naoh.whalechat.data.personaPresets
import com.naoh.whalechat.voice.VoicePhase
import com.naoh.whalechat.voice.rememberVoiceInput

/** 全屏文字输入页服务的各种目标。 */
sealed interface InputTarget {
    data object ApiKey : InputTarget
    data object SystemPrompt : InputTarget

    /** 讯飞的 AppID */
    data object XfyAppId : InputTarget

    /** 讯飞的 APIKey */
    data object XfyApiKey : InputTarget

    /** 讯飞的 APISecret */
    data object XfyApiSecret : InputTarget

    /**
     * 发消息。
     *
     * 从对话页那个键盘按钮进来，或者在设置里手打目标时用。
     * 这一页的麦克风是**另一条路**：它跟随设置里的「识别出字之后」，
     * 开着就先填字让你改，关掉就说完即发（见下方 rememberVoiceInput）。
     *
     * [draft] 是从对话页带过来的待改文字 —— 录音还没发出去时按了键盘按钮，
     * 那句识别结果就落在这儿，用户接上键盘接着改。空串表示没有草稿
     * （直接按键盘按钮进来），这一页从空框开始。
     *
     * [rewriteIndex] 非 null 表示这次是**编辑重发**：进来的是某条已经发过的提问
     * （[draft] 就是它的原文），发送时要把那条及其之后的全部内容作废重来，
     * 而不是当成一句新话追加进去。空串草稿在这儿不成立，所以两件事不会混。
     */
    data class Chat(
        val conversationId: String,
        val draft: String = "",
        val rewriteIndex: Int? = null,
    ) : InputTarget

    /**
     * 「自己说一句」：把想要的角色描述出来。
     *
     * 提交之后**不在这里导航**，只把文字交给 `ChatEngine.personaDraft` ——
     * 这一页离开时那个 `onDispose` 兜底只能写数据、不能导航（那里 navigate 是未定义行为）。
     * 往前去等待页的那一步由新建页观察到草稿之后发起，见 [ChatEngine.personaDraft]。
     */
    data object PersonaDescription : InputTarget

    /** 预设详情页里改某一格。[fieldKey] 对应 `PresetField.key`。 */
    data class PresetFieldEdit(val presetIndex: Int, val fieldKey: String) : InputTarget

    /**
     * 改已有角色的一个**短字段**（名字 / 简介 / 开场白）。
     *
     * 只有这三格走这里：六块里的其余五块是大段文字，在这块表上敲不现实，
     * 它们只在手机那一页改（见 `PersonaDetailScreen`）。
     *
     * 名字叫 `PersonaFieldEdit` 而不是 `PersonaField`，是为了不和
     * `data.PersonaField` 那个枚举撞名 —— 它就在上面几行，撞了以后
     * 这里每一处都得写全限定名。
     */
    data class PersonaFieldEdit(val personaId: String, val field: PersonaField) : InputTarget
}

/**
 * 手表上的文字输入。
 *
 * 实测（Wear OS 5 模拟器，Gboard Wear）：系统输入法是**整屏接管**的 ——
 * 键盘一升起来，这个页面基本全被盖住，只在顶部留一条输入预览。
 *
 * 键盘的提交在这个平台上是个坑（详见下方「提交兜底」一段）：
 * 回车键和发送箭头都未必把 ImeAction 递回来。所以这页的真实职责是：
 *  1. 进入就自动聚焦，把键盘叫起来，让用户直接打字；
 *  2. 无论用户以什么方式离开这页（返回、边缘滑动、发送按钮、语音识别完），
 *     只要框里有字，都保证把内容交出去 —— 敲过的字一个都不丢。
 */
@Composable
fun InputScreen(target: InputTarget, onDone: () -> Unit) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val isChat = target is InputTarget.Chat

    val title = when (target) {
        // 全名而不是「API Key」：语音那一侧也有 Key，进来只看这一行得能分清是哪一套。
        InputTarget.ApiKey -> "DeepSeek API Key"
        InputTarget.SystemPrompt -> "系统提示词"
        InputTarget.XfyAppId -> "讯飞 AppID"
        InputTarget.XfyApiKey -> "讯飞 APIKey"
        InputTarget.XfyApiSecret -> "讯飞 APISecret"
        // 「问点什么」是主动进来的（对话页那个键盘按钮），「改这句」是长按某条提问来的。
        // 两件事的动作不同，标题也该不同 —— 否则用户看不出这次点「发送」会不会覆盖旧内容。
        is InputTarget.Chat -> if (target.rewriteIndex != null) "改这句重发" else "问点什么"
        InputTarget.PersonaDescription -> "想要什么样的人"
        // 标题直接用这一行的字段名（「称呼」「背景」…），用户改的时候一眼知道在改哪一项
        is InputTarget.PresetFieldEdit -> target.field()?.label ?: "改一改"
        // 同样用字段名当标题：从详情页点进来时，用户刚看到的就是这几个字
        is InputTarget.PersonaFieldEdit -> when (target.field) {
            PersonaField.NAME -> "角色名"
            PersonaField.TAGLINE -> "一句简介"
            PersonaField.GREETING -> "开场白"
        }
    }
    val placeholder = when (target) {
        InputTarget.ApiKey -> "sk-…"
        InputTarget.SystemPrompt -> "留空表示不加设定"
        // 讯飞控制台里三样都是十六进制串，长度固定，写出来省得用户怀疑自己抄错了
        InputTarget.XfyAppId -> "8 位十六进制"
        InputTarget.XfyApiKey -> "32 位十六进制"
        InputTarget.XfyApiSecret -> "32 位十六进制"
        is InputTarget.Chat -> "打字或说句话"
        InputTarget.PersonaDescription -> "比如：一个嘴硬心软的剑修理匠"
        is InputTarget.PresetFieldEdit -> target.field()?.placeholder ?: "可以留空"
        // 长度上限写出来，用户才知道为什么会被截断（手表列表的标题位就这么宽）
        is InputTarget.PersonaFieldEdit -> when (target.field) {
            PersonaField.NAME -> "不超过 6 个字"
            PersonaField.TAGLINE -> "不超过 12 个字"
            PersonaField.GREETING -> "TA 进来说的第一句话"
        }
    }
    // 「生成」而不是「保存」：这一格按下去换来的是一次造人，不是一个存下来的设置项。
    val confirmLabel = when (target) {
        is InputTarget.Chat -> "发送"
        InputTarget.PersonaDescription -> "生成"
        else -> "保存"
    }

    /**
     * 输入框内容（连同光标位置）。
     *
     * 之所以拿 TextFieldValue 而不是裸 String：光标位置必须显式管。
     * `BasicTextField(value: String, …)` 这个重载内部会自己包成
     * `TextFieldValue(text)`，而它的默认 selection 是 0 —— **光标停在第一个字前面**。
     * 带草稿（那句识别结果）进来时这是致命的：用户想改句尾那个错字，得先在手表这块
     * 小屏上把光标从开头一路挪到结尾，基本没法操作。
     */
    var field by remember(target) {
        val initial = when (target) {
            InputTarget.ApiKey -> settings.apiKey
            InputTarget.SystemPrompt -> settings.systemPrompt
            InputTarget.XfyAppId -> settings.xfyAppId
            InputTarget.XfyApiKey -> settings.xfyApiKey
            InputTarget.XfyApiSecret -> settings.xfyApiSecret
            // 带草稿进来（录音还没发就按了键盘按钮）就先填上，用户接着改就是；
            // 没草稿则是空框，跟以前完全一样
            is InputTarget.Chat -> target.draft
            // 「自己说一句」永远是空框：这一句是从零开始想，没有可继承的旧值
            InputTarget.PersonaDescription -> ""
            // 预设那几格从页面级草稿里取，用户之前改过什么就接着改什么
            is InputTarget.PresetFieldEdit -> ChatEngine.presetDraft.value
                ?.takeIf { it.presetIndex == target.presetIndex }
                ?.valueOf(target.fieldKey)
                .orEmpty()

            is InputTarget.PersonaFieldEdit -> when (target.field) {
                PersonaField.NAME -> ChatEngine.findPersona(target.personaId)?.name
                PersonaField.TAGLINE -> ChatEngine.findPersona(target.personaId)?.tagline
                PersonaField.GREETING -> ChatEngine.findPersona(target.personaId)?.greeting
            }.orEmpty()
        }
        // 光标一律落在末尾：接上键盘就是接着敲，符合「改刚才那句」的直觉
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }

    /** 已经提交过一次就别再自动提交（成功路径会把这面旗立起来）。 */
    var submitted by remember { mutableStateOf(false) }

    /**
     * 本地提示（识别失败之类）。
     *
     * 这一页的提示很难显示 —— 键盘整屏接管之后基本什么都看不见 ——
     * 但识别失败时键盘通常已经收起，所以还是留一行，比什么都不说强。
     */
    var hint by remember { mutableStateOf<String?>(null) }

    /** 把内容写进对应的目标（发消息 / 存 Key / 存提示词 / 存识别配置），不碰导航。 */
    fun applyTarget(value: String) {
        when (target) {
            InputTarget.ApiKey -> ChatEngine.settings.setApiKey(value)
            InputTarget.SystemPrompt -> ChatEngine.settings.setSystemPrompt(value)
            InputTarget.XfyAppId -> ChatEngine.settings.setXfyAppId(value)
            InputTarget.XfyApiKey -> ChatEngine.settings.setXfyApiKey(value)
            InputTarget.XfyApiSecret -> ChatEngine.settings.setXfyApiSecret(value)
            // 编辑重发走的是另一条路：不是往下追加，而是把这条提问及其之后的内容
            // 一起作废再重来（ChatEngine.rewrite 里有理由）。
            is InputTarget.Chat -> {
                val index = target.rewriteIndex
                if (index != null) {
                    ChatEngine.rewrite(target.conversationId, index, value)
                } else {
                    ChatEngine.send(target.conversationId, value)
                }
            }

            // 只落到草稿里，导航由新建页接力 —— 理由见 InputTarget.PersonaDescription
            InputTarget.PersonaDescription -> ChatEngine.setPersonaDraft(value)

            is InputTarget.PresetFieldEdit ->
                ChatEngine.updatePresetDraft { it.with(target.fieldKey, value) }

            is InputTarget.PersonaFieldEdit ->
                ChatEngine.updatePersonaField(target.personaId, target.field, value)
        }
    }

    /**
     * 提交。raw 缺省取当前输入框内容；
     * 键盘回车兜底那条路会把带换行的原文直接递进来。
     */
    fun submit(raw: String = field.text) {
        val value = raw.trim()
        // 空值一律不提交 —— 只有一个例外：改预设的某一格时，**清空就是「这一项不要了」**。
        // 拼生成描述时空白字段本来就会被略过，所以空串在这一处是有意义的输入，
        // 不能被当成「什么都没输入」挡回去（否则预设自带的默认值永远删不掉）。
        //
        // 角色的短字段（名字 / 简介 / 开场白）**不吃这个例外**：这一页的规矩是
        // 「离开 = 提交」，而离开时框里是空的最常见的成因就是手滑 ——
        // 让那一下顺手把简介或开场白抹掉，代价比「手表上清不掉」大得多。
        // 真要清空，去手机那一页把那一格删掉（详情页上写着这句）。
        if (value.isEmpty() && target !is InputTarget.PresetFieldEdit) return
        submitted = true
        applyTarget(value)
        keyboard?.hide()
        onDone()
    }

    /**
     * 这一页的麦克风：**说完先出字、你改完再发**，和对话页那支刻意不一样。
     *
     * 差别来自语境：用户已经站在编辑页上、键盘也开着，「先填字再发」在这里不是中断
     * 而是顺路；对话页没有输入框，同样的行为就成了跳转。识别错字是常态
     * （「骁龙8gen3」听成「8阵3」），这一页本来就给了改字的机会，不如先用上。
     */
    val voice = rememberVoiceInput(
        onResult = { spoken ->
            field = TextFieldValue(spoken, TextRange(spoken.length))
        },
        onMessage = { message -> hint = message },
        // 边说边把识别到的字填进输入框：识别得对不对，当场就能看出来
        onPartial = { partial ->
            field = TextFieldValue(partial, TextRange(partial.length))
        },
    )

    // ---------------------------------------------------------------- 提交兜底
    //
    // Wear 版 Gboard（Wear OS 5 实测）的回车键和发送箭头都是坏的：
    //  * 回车既不走 ImeAction.Send，也不往输入框提交换行 —— onValueChange 什么都收不到；
    //  * 发送箭头点了没有任何反应；
    //  * 整屏键盘盖住了下面的「发送/保存」按钮，返回手势还会把整页 pop 掉、字全丢。
    // 结果就是：用户敲完字，没有任何办法交出去 —— 看起来「问答完全不可用」。
    //
    // 返回是唯一百分之百会递到应用手里的手势，所以把「离开输入页」定成提交动作：
    // 有内容就发送/保存，一个字都不白敲；想放弃就先把字删空。
    BackHandler(enabled = !submitted && field.text.isNotBlank()) { submit() }

    // 再兜一层：SwipeDismissableNavHost 的边缘滑动在部分系统上不走 BackHandler，
    // 页面被划走时（onDispose）把还没交出去的内容补交掉，别让用户白打一段话。
    // 注意这里只写数据、不做导航 —— onDispose 阶段再 popBackStack 是未定义行为。
    //
    // 带草稿进来时沿用同一条规矩：划走 = 认可框里这串字、把它发出去。
    // 不另立一套语义是有意的 —— 「不想发就先把字删空」这个约定在打字页早已存在，
    // 同一个页面里出现两种"离开"的含义只会让人更不敢动。
    DisposableEffect(target) {
        onDispose {
            // 这里读 field 而不是某个快照值：onDispose 只在离开这一页时跑一次，
            // 而 DisposableEffect 的 key 是 target、中途不会重建 —— 任何在组合期
            // 捕获下来的字符串都会是「刚进页面时」的旧值，用户后来敲的字会丢。
            if (!submitted && field.text.isNotBlank()) {
                submitted = true
                applyTarget(field.text.trim())
            }
        }
    }

    // 这一页没有可滚动列表，但照样走 ScreenScaffold：
    // 它会给出正确的 contentPadding，让内容避开圆屏顶部的时间胶囊和圆边。
    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Card(
                    onClick = { focusRequester.requestFocus() },
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BasicTextField(
                            value = field,
                            onValueChange = { value ->
                                // Wear 版 Gboard 有个要命的毛病（本地实测复现）：
                                // 回车键不走 ImeAction.Send，而是往输入框里塞一个换行。
                                // 用户在表盘上敲完字按回车，消息永远发不出去，
                                // 看起来就是「问答完全不可用」。
                                // 这里把换行截下来当回车用：直接触发提交，输入框也不留换行。
                                // 真键盘 / 手机输入法依旧走 keyboardActions 的 onSend/onDone。
                                if ('\n' in value.text) {
                                    submit(value.text)
                                } else {
                                    field = value
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            // 刻意**不设** singleLine：Wear 版 Gboard 的回车不走
                            // ImeAction，而是往输入框塞换行（本地实测）；singleLine
                            // 会把这个换行在回调之前就过滤掉，下面的拦截就永远不触发。
                            // 换行一进来就提交并吞掉，输入框实际上仍然保持单行。
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (isChat) ImeAction.Send else ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { submit() },
                                onDone = { submit() },
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (field.text.isEmpty()) {
                                        Text(
                                            text = placeholder,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        if (voice.phase == VoicePhase.RECORDING) voice.stop() else voice.start()
                    },
                    // 上传识别中再点没有意义（取消不了已经发出去的那次请求），
                    // 与其让它看起来能点、点了没反应，不如明确置灰
                    enabled = voice.phase != VoicePhase.TRANSCRIBING,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (voice.phase == VoicePhase.RECORDING) {
                            AppIcons.Stop
                        } else {
                            AppIcons.Mic
                        },
                        contentDescription = if (voice.phase == VoicePhase.RECORDING) {
                            "结束录音并识别"
                        } else {
                            "语音输入"
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            hint?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(
                    onClick = { submit() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 4.dp),
                    )
                    Text(
                        text = when {
                            voice.phase == VoicePhase.TRANSCRIBING -> "识别中"
                            voice.listening -> "聆听中"
                            else -> confirmLabel
                        },
                        maxLines = 1,
                    )
                }
                TextButton(
                    // 取消 = 明确放弃：先把旗立起来，跳过 BackHandler/onDispose 两道
                    // 提交兜底 —— 否则「取消」也会把框里的字偷偷存进去，取消就名存实亡
                    onClick = {
                        submitted = true
                        onDone()
                    },
                    modifier = Modifier.width(64.dp),
                ) {
                    Text("取消", maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }

    // 进页面就把键盘叫起来，少一次点击；万一系统没弹出来，点一下输入框也一样
    LaunchedEffect(target) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

/**
 * 找到这次要改的那一行预设字段的定义。
 *
 * 标题、占位文案都从这儿来，不在导航参数里传一份 —— 传一份就多一个会和
 * `PersonaPresets` 对不上的地方。拿不到就返回 null，调用方各自有兜底文案。
 */
private fun InputTarget.PresetFieldEdit.field(): PresetField? =
    personaPresets().getOrNull(presetIndex)?.fields?.firstOrNull { it.key == fieldKey }
