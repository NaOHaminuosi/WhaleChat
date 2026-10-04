package com.naoh.whalechat.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.ui.theme.WhaleChatTheme

private object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val SETTINGS_SPEECH = "settings/speech"
    const val INPUT_API_KEY = "input/apikey"
    const val INPUT_PROMPT = "input/prompt"
    const val INPUT_XFY_APPID = "input/xfyappid"
    const val INPUT_XFY_APIKEY = "input/xfyapikey"
    const val INPUT_XFY_APISECRET = "input/xfyapisecret"
    const val INPUT_CHAT = "input/chat"
    const val IMPORT_PHONE = "import/phone"

    fun chat(id: String) = "$CHAT/$id"

    /**
     * 文字输入页。对话页那个键盘按钮进这一页。
     *
     * [draft] 是带过去待改的文字（录音还没发出去时按键盘按钮，那句识别结果
     * 就跟着过来）。必须 `Uri.encode` —— 中文会被编成 %XX，也可能带空格和
     * 斜杠，不编的话这些字符会被路由模板当成路径分隔符吃掉。
     *
     * [rewriteIndex] 非 null 表示这次是「编辑重发」：进来的是某条发过的提问。
     *
     * 两个参数**永远都带上**（没有就写空串 / -1），这样拼出来的 URL 和
     * [INPUT_CHAT_PATTERN] 逐字对应，不会出现「少一截参数就匹配不上模板」的意外。
     * Navigation 对空值本身是宽容的：声明的查询参数缺失时会被跳过，取到空串走默认值。
     */
    fun inputChat(id: String, draft: String = "", rewriteIndex: Int? = null): String {
        val rewrite = rewriteIndex?.toString() ?: REWRITE_NONE
        return "$INPUT_CHAT/$id?draft=${Uri.encode(draft)}&rewrite=$rewrite"
    }

    /** 「不是编辑重发」。用 -1 占位而不是省掉这一截，理由见 [inputChat]。 */
    const val REWRITE_NONE = "-1"

    /** 文字输入页的路由模板，和 [inputChat] 拼出来的必须逐字对应。 */
    const val INPUT_CHAT_PATTERN = "$INPUT_CHAT/{id}?draft={draft}&rewrite={rewrite}"
}

@Composable
fun WhaleChatApp() {
    WhaleChatTheme {
        AppScaffold(timeText = { TimeText() }) {
            val navController = rememberSwipeDismissableNavController()

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = Routes.HOME,
            ) {

                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenConversation = { navController.navigate(Routes.chat(it)) },
                        onNewConversation = {
                            navController.navigate(Routes.chat(ChatEngine.createConversation()))
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable("${Routes.CHAT}/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    ChatScreen(
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                        onOpenTextInput = { draft, rewriteIndex ->
                            navController.navigate(Routes.inputChat(id, draft, rewriteIndex))
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onEditApiKey = { navController.navigate(Routes.INPUT_API_KEY) },
                        onEditPrompt = { navController.navigate(Routes.INPUT_PROMPT) },
                        onImportApiKey = { navController.navigate(Routes.IMPORT_PHONE) },
                        onOpenSpeech = { navController.navigate(Routes.SETTINGS_SPEECH) },
                    )
                }

                composable(Routes.SETTINGS_SPEECH) {
                    SpeechSettingsScreen(
                        onEditXfyAppId = { navController.navigate(Routes.INPUT_XFY_APPID) },
                        onEditXfyApiKey = { navController.navigate(Routes.INPUT_XFY_APIKEY) },
                        onEditXfyApiSecret = { navController.navigate(Routes.INPUT_XFY_APISECRET) },
                    )
                }

                composable(Routes.IMPORT_PHONE) {
                    ImportKeyScreen(
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_API_KEY) {
                    InputScreen(
                        target = InputTarget.ApiKey,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_PROMPT) {
                    InputScreen(
                        target = InputTarget.SystemPrompt,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_XFY_APPID) {
                    InputScreen(
                        target = InputTarget.XfyAppId,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_XFY_APIKEY) {
                    InputScreen(
                        target = InputTarget.XfyApiKey,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_XFY_APISECRET) {
                    InputScreen(
                        target = InputTarget.XfyApiSecret,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.INPUT_CHAT_PATTERN,
                    // draft / rewrite 必须在这里声明，否则它们会被当成路由的一部分而不是
                    // 查询参数：带中文的那句识别结果经 Uri.encode 后是一串 %XX，
                    // 不声明的话匹配不上模板，导航会直接抛 IllegalArgumentException。
                    // 给了默认值，「没带草稿」「不是编辑重发」两条路就都能落地。
                    arguments = listOf(
                        navArgument("draft") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("rewrite") {
                            type = NavType.IntType
                            defaultValue = -1
                        },
                    ),
                ) { entry ->
                    val rewrite = entry.arguments?.getInt("rewrite", -1) ?: -1
                    InputScreen(
                        target = InputTarget.Chat(
                            conversationId = entry.arguments?.getString("id").orEmpty(),
                            // Navigation 会把 query 里的 %XX 自动解回来，这里拿到的是原文
                            draft = entry.arguments?.getString("draft").orEmpty(),
                            rewriteIndex = rewrite.takeIf { it >= 0 },
                        ),
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
