package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import com.naoh.whalechat.data.AsrEngine
import com.naoh.whalechat.data.ChatEngine

/**
 * 语音识别的引擎选择。
 *
 * 为什么要有这一页：安卓那套语音 API 只负责把音频交给设备上装的
 * `RecognitionService`，**跑在本地还是联网由厂商决定，App 没有任何开关能指定**。
 * 于是设备上装的是谁，效果就是谁 —— 装的是 Google 的那套，在国内网络下
 * 就会表现为「经常报错」「提示没有可用的识别」「识别不准」。
 *
 * 想真正拿到稳定的联网识别，只能自己录、自己传。讯飞那一档就是那条路。
 */
@Composable
fun SpeechSettingsScreen(
    onEditXfyAppId: () -> Unit,
    onEditXfyApiKey: () -> Unit,
    onEditXfyApiSecret: () -> Unit,
) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
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
        ) {
            item(key = "header") { ListHeader { Text("语音识别") } }

            item(key = "engine") {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    AsrEngine.entries.forEach { engine ->
                        TextToggleButton(
                            checked = settings.asrEngine == engine,
                            onCheckedChange = { checked ->
                                if (checked) ChatEngine.settings.setAsrEngine(engine)
                            },
                            modifier = Modifier.weight(1f),
                            // 同设置页：选中的一档变圆角矩形，用形状说明「当前是哪个」。
                            shapes = selectionToggleShapes(),
                        ) {
                            Text(
                                text = engine.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            item(key = "engine_hint") {
                HintText(
                    when (settings.asrEngine) {
                        AsrEngine.SYSTEM ->
                            "不用配置，直接用设备自带的识别服务。它准不准取决于设备上装的是谁，App 换不了也调不了。"

                        AsrEngine.XFYUN ->
                            "边说边出字，不用等录完，等待感最小。下面那三串凭证缺一样都调不通。"
                    },
                )
            }

            // ------------------------------------------------------------ 讯飞
            if (settings.asrEngine == AsrEngine.XFYUN) {
                item(key = "xfy_cred_header") { ListSubHeader { Text("讯飞听写") } }

                // 三串都是十六进制长串，在这块圆屏上逐字敲基本没法完成。
                // 手机扫码导入那张表里也有这三格，而且是从控制台复制过来的同一份东西，
                // 顺手提示一句，省得用户在手表上跟自己较劲。
                item(key = "xfy_import_hint") {
                    HintText("这三串在手表上敲很费劲。回设置页用「手机扫码改凭证」，在手机上填一次更快。")
                }

                item(key = "xfy_appid") {
                    ActionCard(
                        title = "AppID",
                        subtitle = settings.xfyAppId.ifBlank { "未填写" },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyAppId,
                    )
                }

                item(key = "xfy_apikey") {
                    ActionCard(
                        title = "APIKey",
                        subtitle = if (settings.xfyApiKey.isBlank()) {
                            "未填写"
                        } else {
                            maskKey(settings.xfyApiKey)
                        },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyApiKey,
                    )
                }

                item(key = "xfy_apisecret") {
                    ActionCard(
                        title = "APISecret",
                        subtitle = if (settings.xfyApiSecret.isBlank()) {
                            "未填写"
                        } else {
                            maskKey(settings.xfyApiSecret)
                        },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyApiSecret,
                    )
                }

                item(key = "xfy_note") {
                    HintText(
                        "三串都在讯飞控制台「我的应用」里，缺一样都调不通。说完立刻有结果，短句效果最好。",
                    )
                }
            }

            item(key = "limit_note") {
                HintText("单次录音最长 60 秒，说短句效果最好。")
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }
}
