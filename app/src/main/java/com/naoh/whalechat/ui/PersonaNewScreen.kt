package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.personaPresets

/**
 * 新建角色页。**两条路，先给「自己说一句」，再给预设。**
 *
 * ```
 * [自己说一句]                    → 进输入页，空框
 * [用手机管理人设]                → 进扫码页，落手机端人设列表（和设置页那张同一条路）
 * ── 或者直接挑一个 ──
 * [预设 ×4]  预设名 + 一句话描述  → 进预设详情页
 * ```
 *
 * 顺序是有意的：自己说一句是最短的路，也是最能把用户想要的东西说准的那条；
 * 预设是给「一时想不出怎么说」的人兜底的。把预设摆前面会让人以为只能用预设。
 *
 * 「用手机管理人设」摆在「自己说一句」紧挨着的正下方 —— **这两张卡是同一类需求
 * （「想造一个全新角色」）的互补入口**：前者是手表上敲一句话让模型现编，
 * 后者是去手机上用网页表单填完整的开场白、长描述、头像等长字段。手表上没法改
 * 的那些字段，唯一的真路就是这个扫码入口。它和设置页那张「用手机管理人设」
 * **指向同一个扫码路由、同一份人设列表** —— 这里不需要另开一条。
 *
 * @param onDescribe 去输入页，空框
 * @param onPickPreset 去某个预设的详情页
 * @param onImportPersonas 进扫码页，落手机端人设列表（与设置页同路由）
 * @param onDescriptionReady 「自己说一句」那条路拿到最终描述之后的下一步（去等待页）。
 *   它**不是**在输入页提交时直接调用的，而是要等这一页重新回到前台 —— 理由见下面
 *   `LaunchedEffect` 的注释。
 */
@Composable
fun PersonaNewScreen(
    onDescribe: () -> Unit,
    onPickPreset: (Int) -> Unit,
    onImportPersonas: () -> Unit,
    onDescriptionReady: (String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val draft by ChatEngine.personaDraft.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    val presets = personaPresets()

    // 「自己说一句」那条路的接力棒。
    //
    // 用户在输入页按下提交（或者干脆划走），文字先落到 ChatEngine.personaDraft，
    // 页面 pop 回这一页之后才由这里发起向等待页的导航。为什么要多这一跳：
    // InputScreen 的通行规矩是「离开 = 提交」，而它离开时唯一能用的通道 onDispose
    // **只能写数据不能导航**（那里 navigate 是未定义行为）。见 ChatEngine.personaDraft。
    //
    // 顺序不能反：**先清草稿再导航**。反过来的话导航会销毁这一页、协程在这里被取消，
    // 草稿就留下了 —— 用户下次回到这一页会被**再送一次**等待页，凭空多造一个人。
    LaunchedEffect(draft) {
        val text = draft ?: return@LaunchedEffect
        ChatEngine.consumePersonaDraft()
        onDescriptionReady(text)
    }

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
            item(key = "header") { ListHeader { Text("造一个角色") } }

            // 造人失败（Key 无效 / 断网 / 内容为空）会退回这一页，原因得有地方显示 ——
            // 只挂在首页的话，用户在这一页上看到的是一次没有任何反馈的「闪了一下」。
            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            item(key = "describe") {
                ActionCard(
                    title = "自己说一句",
                    subtitle = "描述你想要什么样的人",
                    icon = AppIcons.Sparkle,
                    onClick = onDescribe,
                )
            }

            // 「用手机管理人设」：手表上每条预设/角色能改，但**完整人设（开场白、长
            // 描述、头像等长字段）只能在手机端网页上填**。这一页是给想造一个比预设
            // 更花心思的角色的人用的入口 —— 长字段拖不动、按不动的真实拦路虎。
            //
            // 摆在「自己说一句」**正下方**的理由：它俩是同一类需求（「想造一个全新
            // 角色」）的互补入口。把扫码入口塞到「或者直接挑一个」下面会和预设混
            // 成一类，反而暗示「这是给挑出来的人补字段的」，跟实际用途不符。
            //
            // 这一项和设置页那张**指向同一个扫码路由、同一份人设列表**
            // （`Routes.importPhone(PhoneLanding.PERSONAS)`），所以这里没有另开路由。
            // 文案/副标题/图标与设置页那张**逐字一致**：扫码页同一张脸、同一段描述，
            // 用户从两处进来看到的二维码和说明都是一样的，不另立一份。
            item(key = "import_persona") {
                ActionCard(
                    title = "用手机管理人设",
                    subtitle = "同 Wi-Fi 扫码改人设",
                    icon = AppIcons.Person,
                    onClick = onImportPersonas,
                )
            }

            item(key = "or") {
                ListSubHeader { Text("或者直接挑一个") }
            }

            presets.forEachIndexed { index, preset ->
                item(key = "preset_$index") {
                    ActionCard(
                        title = preset.title,
                        // 卡片上放的是**给用户看的那一句**，不是给模型看的描述 ——
                        // 后者里面写着「不是管家、不是服务者」，摆到界面上只会让人困惑。
                        subtitle = preset.subtitle,
                        onClick = { onPickPreset(index) },
                    )
                }
            }

            item(key = "hint") {
                HintText("模型现编名字、人设、开场白。生成完还能改名。")
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }
}
