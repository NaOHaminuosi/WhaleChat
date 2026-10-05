package com.naoh.whalechat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.PersonaFactory
import com.naoh.whalechat.data.PersonaResult
import com.naoh.whalechat.data.PersonaSource

/**
 * 造人的等待页：转圈 + 把用户那句描述摆出来，让他知道自己在等什么。
 *
 * **这一页和隔壁的 `InputScreen` 规矩正好相反，实现时千万别顺手照抄那一套。**
 *
 * ## 三条硬规矩
 *
 * **1. 离开 = 取消，而且回来的结果必须作废。**
 *
 * `InputScreen` 的规矩是**离开 = 提交**（`BackHandler` + `onDispose` 两道兜底，
 * 专门为了「敲过的字一个都不丢」）。等待页的规矩**正好相反**：划走就是想算了。
 * 两页紧挨着（点「自己说一句」就是从这里过去的），实现时若顺手照抄输入页那套，
 * 用户划走等待页会被 `onDispose` 又写一次库，**凭空多出一个他以为已经取消了的角色**。
 *
 * 所以这一页的 `BackHandler` 做的是**作废**，不是提交；`onDispose` 只当兜底。
 * 两道都要有 —— 只留 `onDispose` 会漏（NavHost 把销毁推迟到退场动画之后，
 * 结果可能先回来），实测复现过，细节写在下面 `BackHandler` 那段注释里。
 *
 * **2. 不掐 HTTP，只作废结果。**
 *
 * `complete()` 是非流式 suspend，不走 `activeCall` 那条取消通道；为捏人这一个调用
 * 引入第二套取消机制不值。做法是带一个令牌，结果回来时对不上就丢掉 ——
 * 浪费一次调用（钱已经花了），但行为正确。
 *
 * 顺带一层：这一页被销毁时 `LaunchedEffect` 的协程会被取消，那次调用自然也白做。
 * 两道保险不冲突，令牌是权威的那一道。
 *
 * **3. 超时单独设短（30 秒）。**
 *
 * `complete()` 默认吃的是 OkHttp 的 180 秒读超时（给思考模式首包留的）。
 * 拿这个数捏人荒唐：手表上盯着转圈超过 30 秒用户就认为死了。那个数写在
 * `PersonaFactory.PERSONA_READ_TIMEOUT_SECONDS`。
 *
 * ## 成功之后
 *
 * **直接进聊天页，不要回第二页。** 用户刚说完想要什么样的人，最想看到的是 TA 开口
 * （greeting 已经在建会话时上架了）。回列表等于打断。
 */
@Composable
fun PersonaWaitScreen(
    description: String,
    source: PersonaSource,
    onReady: (conversationId: String) -> Unit,
    onBack: () -> Unit,
) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    // 本次捏人的令牌。`remember` 而不是 `rememberSaveable`：这一页被重建（进程恢复）
    // 时上一次调用早就没了，重开一个令牌才是对的。
    val token = remember { PersonaFactory.begin() }

    // 规矩 1：离开 = 取消。这里**只作废、不提交、不导航**。
    //
    // ## 为什么非要有个 BackHandler（这是实测逼出来的，不是照抄输入页）
    //
    // 光靠下面的 `onDispose` 作废**会漏**。NavHost 把离场那一页的销毁推迟到
    // 退场动画结束之后，于是「结果回来了」完全可能早于「onDispose 被调用」。
    // 假服务故意拖 8 秒、在第 5.5 秒按返回，实测日志顺序是这样的
    // （这几行是当时临时插的 Log.w 打出来的，验完已经删掉，要复现得再插回去）：
    //
    //     begin token=9021e2bd
    //     back own=9021e2bd alive=true rawLen=242   ← 结果回来时令牌**还有效**，当成成功
    //     wait.dispose token=9021e2bd               ← 这时候才轮到销毁
    //     abandon own=9021e2bd cas=true now=null    ← 作废来晚了，角色已经建出来
    //
    // 结局就是用户划走了却又凭空多出一个角色 —— 正是要防的那件事。
    // 延迟 7.5 秒按返回同样复现。BackHandler 在按键那一刻就作废，跟网络快慢无关。
    //
    // 补上之后同一条复现变成：`backhandler token=…` → `abandon … cas=true`，
    // 而结果回来时 `alive=false`、直接被丢掉，personas.json 一个都没多。
    //
    // 注意它做的是**取消**，和输入页那个「离开 = 提交」的 BackHandler 正好相反：
    // 两个都叫 BackHandler，干的事却是反的，改的时候别把这一页也改成提交。
    BackHandler {
        PersonaFactory.abandon(token)
        onBack()
    }

    // 兜底：不走返回键的离开路径（外部导航、导航栈被整体清掉等）仍然靠 onDispose。
    DisposableEffect(token) {
        onDispose { PersonaFactory.abandon(token) }
    }

    LaunchedEffect(token, description) {
        // 没有 Key 就别转圈了，直接说清楚。放在这里而不是让 generate 去撞 401，
        // 是为了省掉一次注定失败的往返。
        if (settings.apiKey.isBlank()) {
            ChatEngine.postNotice("请先在设置里填写 DeepSeek API Key")
            onBack()
            return@LaunchedEffect
        }

        when (
            val result = PersonaFactory.generate(
                apiKey = settings.apiKey,
                own = token,
                description = description,
                source = source,
            )
        ) {
            is PersonaResult.Done -> {
                val conversationId = ChatEngine.createPersonaChat(result.persona)
                if (conversationId.isNotEmpty()) onReady(conversationId) else onBack()
            }

            // 用户已经划走了 —— 什么都不做，连提示都不要给
            PersonaResult.Abandoned -> Unit

            is PersonaResult.Failed -> {
                // 规矩：网络失败 / Key 无效 / 内容为空**不兜底**，退回并说明原因。
                // 提示走 ChatEngine.postNotice，因为这一页马上就要退场了，
                // 提示得留给上一页显示。
                ChatEngine.postNotice(result.hint)
                onBack()
            }
        }
    }

    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 用官方那个**不确定进度**的重载（不带 progress 参数）。
            // 不覆盖 strokeWidth：默认值是和「推荐 gap 尺寸」配套算出来的，
            // 手改粗细会让端点那个缺口比例失衡。
            CircularProgressIndicator(modifier = Modifier.size(36.dp))

            Text(
                text = "正在塑造 TA…",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            // 把用户那句描述摆出来，让他知道自己在等什么 —— 转圈本身不提供任何信息，
            // 而这次等待最长可能到 30 秒。
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
