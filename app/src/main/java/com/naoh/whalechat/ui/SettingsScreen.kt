package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import com.naoh.whalechat.data.AsrEngine
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.ChatMode

/**
 * 设置页。
 *
 * 排序沿用一条原则：**先给「一次配完所有东西」的入口，再按「这块设置属于谁」分组。**
 *
 * 第 1 项是「手机扫码改凭证」，故意提出来放在最前面、不属于任何分组。
 * 理由是这个应用要用户配的是**两套完全不同的凭证** ——
 *  1. 跟模型对话用的 DeepSeek API Key；
 *  2. 把话转成字的语音识别凭证（讯飞听写的 AppID/APIKey/APISecret）。
 * 而扫码那一页**同时管这两套**。它原先挂在「语音识别」组里，看起来只管说话那一路，
 * 用户想改对话的 Key 时根本不会想到去点它；挪到最前面之后，它的定位是「凭证总入口」，
 * 和下面两个分组并列，谁都不隶属。
 *
 * 下面各组各自把同类设置放在一起：**模型**（对话用）、**语音识别**（说话用）、
 * **数据**（清对话）、**关于**。
 */
@Composable
fun SettingsScreen(
    onEditApiKey: () -> Unit,
    onEditPrompt: () -> Unit,
    onImportApiKey: () -> Unit,
    onOpenSpeech: () -> Unit,
) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    // 订阅会话列表。以前这里直接读 ChatEngine.conversations.value —— 那不会触发重组，
    // 于是「清空所有对话」之后卡片上的「共 N 条」会一直停在旧数字上。
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

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
            item(key = "header") { ListHeader { Text("设置") } }

            // 第 1 项：**凭证总入口**。它同时管「模型」和「语音识别」两套凭证，
            // 所以不隶属下面任何一组，摆在全页最前面。
            item(key = "import_key") {
                ActionCard(
                    // 文案跟着职责走：这一页会把表上已有的值摊出来，所以它是
                    // 「看 + 改」而不是单向「导入」—— 不说清的话用户想不到它能用来核对。
                    // 副标题点明**它管的是全部凭证**，包括下面「模型」组里的 Key。
                    title = "手机扫码改凭证",
                    subtitle = "同 Wi-Fi 扫码 · 两套凭证一起改",
                    icon = AppIcons.Phone,
                    onClick = onImportApiKey,
                )
            }

            // -------------------------------------------------------------- 模型
            item(key = "model_header") { ListSubHeader { Text("模型") } }

            item(key = "key") {
                ActionCard(
                    // 「DeepSeek」必须写进标题：下面语音那一组也有 Key，
                    // 只说「API Key」两张卡就读不出区别
                    title = "DeepSeek API Key",
                    subtitle = if (settings.apiKey.isBlank()) {
                        "未填写 · 点这里粘贴 sk-…"
                    } else {
                        "已配置 ${maskKey(settings.apiKey)}"
                    },
                    icon = AppIcons.Sparkle,
                    onClick = onEditApiKey,
                )
            }

            item(key = "mode_header") { ListSubHeader { Text("回答模式") } }

            item(key = "mode") {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    ChatMode.entries.forEach { mode ->
                        TextToggleButton(
                            checked = settings.mode == mode,
                            onCheckedChange = { checked ->
                                if (checked) ChatEngine.settings.setMode(mode)
                            },
                            modifier = Modifier.weight(1f),
                            // 选中的那一档拉成圆角矩形，未选中保持整圆胶囊：
                            // 形状本身在说明「哪个是当前这一档」，不用只靠颜色。
                            shapes = selectionToggleShapes(),
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            item(key = "mode_hint") {
                HintText(
                    if (settings.mode == ChatMode.THINKING) {
                        "先推理再作答：答案更周全，但首字要等。回答里可以展开思考过程。"
                    } else {
                        "直接作答，最快的一档。日常问答够用，适合在手表上快点看完。"
                    },
                )
            }

            item(key = "prompt_header") { ListSubHeader { Text("系统提示词") } }

            item(key = "prompt") {
                ActionCard(
                    title = "系统提示词",
                    subtitle = if (settings.systemPrompt.isBlank()) {
                        "未设置 · 点这里给模型加提示"
                    } else {
                        settings.systemPrompt.take(24) +
                            if (settings.systemPrompt.length > 24) "…" else ""
                    },
                    icon = AppIcons.Keyboard,
                    onClick = onEditPrompt,
                )
            }

            // ---------------------------------------------------------- 语音识别
            item(key = "speech_header") { ListSubHeader { Text("语音识别") } }

            item(key = "speech") {
                ActionCard(
                    title = "识别引擎",
                    subtitle = when (settings.asrEngine) {
                        AsrEngine.SYSTEM -> "系统识别 · 不用配置"
                        AsrEngine.XFYUN -> "讯飞听写 · 边说边出字"
                    },
                    icon = AppIcons.Mic,
                    onClick = onOpenSpeech,
                )
            }

            // -------------------------------------------------------------- 数据
            item(key = "data_header") { ListSubHeader { Text("数据") } }

            item(key = "clear") {
                Card(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        // 空白的新对话不算记录，条数里也不该有它们
                        text = "清空所有对话 · 共 ${conversations.count { it.messages.isNotEmpty() }} 条",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item(key = "about_header") { ListSubHeader { Text("关于") } }

            item(key = "about") {
                HintText(
                    "非官方客户端，与 DeepSeek 官方无关。\n" +
                        "API Key、识别凭证与聊天记录只存手表本机。\n" +
                        "by NaOH_aminuosi",
                )
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    if (confirmClear) {
        WhaleChatDialog(
            title = "清空所有对话？",
            body = "记录会从手表上删掉，无法恢复。",
            confirmLabel = "全部清空",
            destructive = true,
            onConfirm = {
                ChatEngine.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}
