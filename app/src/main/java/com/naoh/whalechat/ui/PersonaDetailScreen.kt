package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.DEFAULT_GREETING
import com.naoh.whalechat.data.Persona
import com.naoh.whalechat.data.PersonaField

/**
 * 角色详情页：**把模型真正拿到的那份人设逐块摊开**。
 *
 * ```
 * 人设详情
 * ── 手表上能改 ──
 * 名字      › 周野
 * 一句简介  › 嘴硬的剑修理匠
 * 开场白    › 又来了？把剑放下…
 * ── 人设正文 ──
 * 人格内核 / 说话风格 / 行为规则 / 场景 / 示例对话   （各一块，只读）
 * ── 用手机改 ──
 * [用手机改这五块]
 * ```
 *
 * ## 为什么要专门做这一页
 *
 * 在这之前，用户能看到的人设只有卡片上那一句 12 字的简介。他没法知道模型到底读了什么，
 * 也就没法判断「这个角色为什么老是跑调」—— 人设写了什么，直接决定 TA 怎么说话。
 * 六块逐块摆出来是这个 App 唯一一处「所见即所发」的地方。
 *
 * ## 为什么只读的五块不放在手表上改
 *
 * 六块里这五块每一块都是三到五句甚至好几轮对话。240dp 的表盘上敲这种长度的文字，
 * 体验上等于不可用 —— 与其做一个「能改但没法用」的入口，不如老实告诉用户
 * 「这几块去手机上改」，并且**把入口就摆在同一组里**（[onOpenPhonePage]）。
 * 短字段（名字 / 简介 / 开场白）反过来：手表上改最快，就在这一页直接点。
 *
 * 两边不是两套数据：详情页读的、手机改的、发消息时拼进 prompt 的，都是同一个
 * [Persona] 上的那几个字段。**没有任何一份「拼好的正文」被缓存或落库。**
 *
 * @param onEditField 点某个短字段 → 去文字输入页改（传的是**字段的 key**，见 [PersonaField]）
 * @param onOpenPhonePage 去「手机扫码」那一页
 */
@Composable
fun PersonaDetailScreen(
    personaId: String,
    onEditField: (PersonaField) -> Unit,
    onOpenPhonePage: () -> Unit,
) {
    val personas by ChatEngine.personas.collectAsStateWithLifecycle()
    val persona = personas.firstOrNull { it.id == personaId }

    // 角色已经不在了（多半是在手机那一页或别处删过）。给一句人话 + 回得去的路，
    // 别留一块纯空白 —— 那看起来就是卡死了。
    if (persona == null) {
        ScreenScaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HintText("这个角色已经不在了。返回上一页看看列表吧。")
            }
        }
        return
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
        ) {
            // 顶上垫一行：圆屏在这个高度只剩约 198dp 的弦宽，整屏宽的内容（约 216dp）
            // 会四个角被圆边切掉。留白之后第一行才完整落进圆里（和预设详情页同一个数）。
            item(key = "top_gap") { GapItem(28.dp) }

            item(key = "header") { ListHeader { Text("人设详情") } }

            item(key = "editable_header") { ListSubHeader { Text("手表上能改") } }

            item(key = "row_name") {
                DetailRow(
                    label = "名字",
                    value = persona.name,
                    onClick = { onEditField(PersonaField.NAME) },
                )
            }

            item(key = "row_tagline") {
                DetailRow(
                    label = "一句简介",
                    value = persona.tagline,
                    placeholder = "（还没有）",
                    onClick = { onEditField(PersonaField.TAGLINE) },
                )
            }

            item(key = "row_greeting") {
                DetailRow(
                    label = "开场白",
                    value = persona.greeting,
                    // 留空时实际用的是这句兜底（见 Persona.openingLine），
                    // 灰字摆出来，用户才知道「空着」到底会发生什么
                    placeholder = DEFAULT_GREETING,
                    onClick = { onEditField(PersonaField.GREETING) },
                )
            }

            item(key = "core_header") { ListSubHeader { Text("人设正文") } }

            // 这五块的标签和 [Persona.buildCorePrompt] 里那一套逐字对应 ——
            // 用户在这儿看到的字，就是他发消息时模型看到的那几行。
            CORE_BLOCKS.forEach { (label, body) ->
                item(key = "block_$label") { PromptBlock(label = label, body = body(persona)) }
            }

            item(key = "phone_header") { ListSubHeader { Text("用手机改") } }

            item(key = "phone") {
                ActionCard(
                    title = "用手机改这五块",
                    subtitle = "扫二维码，全都能改",
                    icon = AppIcons.Phone,
                    onClick = onOpenPhonePage,
                )
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }
}

/**
 * 人设五块的标签 + 取值方式。
 *
 * 顺序和 [Persona.buildCorePrompt] 一致，标签也逐字一样 —— 详情页是给用户看的
 * 「模型到底拿到了什么」，两边对不上的话这一页就失去意义了。
 */
private val CORE_BLOCKS: List<Pair<String, (Persona) -> String>> = listOf(
    "人格内核" to { p: Persona -> p.soul },
    "说话风格" to { p: Persona -> p.style },
    "行为规则" to { p: Persona -> p.rules },
    "场景" to { p: Persona -> p.scenario },
    "示例对话" to { p: Persona -> p.examples },
)

/**
 * 一块只读的人设正文：小块标题 + 正文。
 *
 * 静态卡片走 `onClick = {} + enabled = false`（和 [HintText] 同一套写法）：
 * 只写 `onClick = {}` 会带上水波纹，看着像能点，而这一页上「能点」是有意义的
 * —— 上面那三行才是能改的。
 *
 * 正文**不截断**：这一页存在的理由就是让人读全。长文由 ScalingLazyColumn 滚，
 * 不是靠 `maxLines` 砍。
 */
@Composable
private fun PromptBlock(label: String, body: String) {
    val blank = body.isBlank()
    Card(
        onClick = {},
        enabled = false,
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
                // 空块也要说出来，不能留白：留白分不清「这一块没写」和「这一块不显示」，
                // 而「有没有写」正是用户来这一页要确认的事。
                text = if (blank) "（这一块还空着）" else body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (blank) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * 一行「字段名 + 值」并可以点进去改。
 *
 * 空值显示成灰掉的占位提示，而不是留白 —— 留白分不清是「还没填」还是「不需要填」。
 * （和预设详情页那一行同一个做法，只是这里底色用高一级的 `surfaceContainerHigh`，
 * 因为这一页上还有一批**不能点**的人设正文卡片，两种卡片要一眼分得开。）
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    placeholder: String = "（还没有）",
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                // 开场白可能好几句，两行够看出是哪一句了；再长也不影响识别
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
