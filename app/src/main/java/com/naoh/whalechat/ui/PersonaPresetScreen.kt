package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.personaPresets

/**
 * 预设详情页：「基于预设创建，可让用户再做小修改」。
 *
 * ```
 * [ 生成 ]              ← 顶部大按钮，选完就按
 * ── 可以改 ──
 * 称呼    ›  老师
 * 背景    ›  我在准备考研
 * 性格    ›  耐心，会追问
 * 说话    ›  简短
 * ```
 *
 * **「生成」按钮置顶不置底。** 用户选完预设的第一反应是「就它了」，放底部要先把
 * 四行字段滚过去才够得着 —— 那是在替他制造犹豫。置顶之后「直接按」和「先改两行」
 * 是并列的两条路，谁都不挡谁。
 *
 * 点某一行 → 进 InputScreen 改 → 改完回到这页（字段值存在 ChatEngine.presetDraft 里，
 * 跨导航活着）→ 按生成。
 *
 * @param onEditField 去输入页改第 [onEditField] 个字段（参数是字段的 key）
 * @param onGenerate 拿着拼好的那句描述去等待页
 */
@Composable
fun PersonaPresetScreen(
    presetIndex: Int,
    onEditField: (fieldKey: String) -> Unit,
    onGenerate: (description: String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val presets = personaPresets()
    val preset = presets.getOrNull(presetIndex)
    // 索引越界（理论上到不了，只有手改过的导航参数才可能）也要给一句人话，
    // 别留一块纯空白 —— 那看起来就是卡死了。（同一条经验：目标已消失的页面要自己了断，
    // 不能留死屏。）
    if (preset == null) {
        ScreenScaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HintText("这个预设不在了，返回上一页重新挑一个吧。")
            }
        }
        return
    }

    val draft by ChatEngine.presetDraft.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()

    // 进页面铺一份草稿。**同一个预设不重铺** —— 去输入页改一行再回来时这一页会被
    // 销毁重建，无条件重铺会把用户改过的另外几行一起冲回默认值。
    LaunchedEffect(presetIndex) { ChatEngine.beginPresetDraft(presetIndex) }

    val current = draft?.takeIf { it.presetIndex == presetIndex }

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
            // 「生成」置顶是规格要求的（选完预设第一反应是「就它了」，放底部要滚才够得着），
            // 但它**不能顶到最上面**：圆屏在这个高度只剩约 198dp 的弦宽，而一级内容
            // 是整屏宽（约 216dp）—— 实测四个角会被圆边切掉，画出来是个梯形。
            // 垫这一行留白之后整颗按钮才完整落在圆内。
            item(key = "top_gap") { GapItem(28.dp) }

            item(key = "generate") {
                Button(
                    onClick = {
                        val text = current?.toDescription(preset).orEmpty()
                        // 生成完就把草稿清掉：下次从新建页重新进来，应该从头开始，
                        // 而不是接着上次改了一半的中间状态。
                        ChatEngine.clearPresetDraft()
                        onGenerate(text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 和 WhaleChatDialog 里那颗确认按钮同一个道理：Wear M3 的 Button
                    // 内部 Row 没有 horizontalArrangement，文字得自己占满整行，
                    // TextAlign.Center 才有可居中的余量。
                    Text(
                        text = "生成",
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 造人失败（Key 无效 / 断网 / 内容为空）会退回到**这一页**，原因得有地方显示。
            // 不订阅的话用户看到的就是「点了生成、闪了一下、什么都没发生」——
            // 这是实测踩到的：等待页确实退回来了，但提示只挂在首页，而用户根本回不到首页。
            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            item(key = "editable_header") {
                ListSubHeader { Text("可以改") }
            }

            preset.fields.forEach { field ->
                item(key = "field_${field.key}") {
                    FieldRow(
                        label = field.label,
                        value = current?.valueOf(field.key).orEmpty(),
                        placeholder = field.placeholder,
                        onClick = { onEditField(field.key) },
                    )
                }
            }

            item(key = "hint") {
                HintText("改完按上面的「生成」就行。名字是模型起的，不满意生成完还能改。")
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }
}

/**
 * 一行「字段名 › 值」。
 *
 * 空值显示成灰掉的占位提示（`onSurfaceVariant`）而不是留白 ——
 * 留白的话用户分不清是「这一项还没填」还是「这一项不需要填」。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value.ifBlank { placeholder },
                style = MaterialTheme.typography.titleMedium,
                color = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
