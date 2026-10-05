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
import com.naoh.whalechat.data.PersonaField
import com.naoh.whalechat.data.PersonaSource
import com.naoh.whalechat.ui.theme.WhaleChatTheme

private object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val SETTINGS_SPEECH = "settings/speech"

    /**
     * 显示设置二级页。三个显示开关都收在这一页。
     *
     * ⚠️ **主设置页那个入口隐藏了**（`SettingsScreen.SHOW_DISPLAY_SETTINGS_ENTRY`），
     * 但**这条路由必须保留** —— 要求「先隐藏、不删」，恢复入口时它得还在，
     * 否则点了入口进的是空白页。导航分支也一并留着。
     *
     * 和 [SETTINGS_SPEECH] 同级、同样**不带** `from` 参数：这一页的行为
     * 跟从哪一页进来的没有关系（理由见 `DisplaySettingsScreen` 的类注释）。
     */
    const val SETTINGS_DISPLAY = "settings/display"
    const val INPUT_API_KEY = "input/apikey"
    const val INPUT_PROMPT = "input/prompt"
    const val INPUT_XFY_APPID = "input/xfyappid"
    const val INPUT_XFY_APIKEY = "input/xfyapikey"
    const val INPUT_XFY_APISECRET = "input/xfyapisecret"
    const val INPUT_CHAT = "input/chat"
    const val IMPORT_PHONE = "import/phone"

    /**
     * 设置页。**同一个页面，只是带了「从哪一页进来的」** ——
     * 从 chat 进来时，「手机扫码改凭证」下面会多一项「用手机管理人设」
     * （判据在 [SettingsScreen] 的注释里）。
     *
     * 用查询参数而不是 `settings/ask`、`settings/chat` 这样的并列路径：
     * 隔壁就有一个 `settings/speech`，再放两个同级段的话，
     * `settings/{page}` 这个模板会和 `settings/speech` 撞上 ——
     * 撞的结果是 speech 那一页永远打不开，而且是运行时才发现。
     */
    fun settings(from: HomePage) = "$SETTINGS?from=${from.name}"

    /** 和 [settings] 拼出来的必须逐字对应。 */
    const val SETTINGS_PATTERN = "$SETTINGS?from={from}"

    /**
     * 手机扫码那一页。[landing] 是**手机上落在哪一页**，取值见 [PhoneLanding]。
     *
     * 必须 `Uri.encode`：取值里有斜杠（`/p`、`/p/{uuid}`），
     * 不编的话斜杠会被路由当成路径分隔符，把这一截吃掉。
     */
    fun importPhone(landing: String) = "$IMPORT_PHONE?land=${Uri.encode(landing)}"

    /** 和 [importPhone] 拼出来的必须逐字对应。 */
    const val IMPORT_PHONE_PATTERN = "$IMPORT_PHONE?land={land}"

    // ------------------------------------------------------------------ 角色
    const val PERSONA_NEW = "persona/new"
    const val PERSONA_PRESET = "persona/preset"
    const val PERSONA_WAIT = "persona/wait"
    const val PERSONA_DETAIL = "persona/detail"
    const val INPUT_PERSONA_DESC = "input/persona-desc"

    /**
     * 角色会话**复用 `chat/{id}`**，不另开一条路由。
     *
     * 会话本身就是同一种东西（聊天页只需要一个会话 id），另开一条只会让
     * 「编辑重发」「停止生成」「语音」这些全都要分叉。角色与普通对话的差别只体现在
     * 数据里（`Conversation.personaId`）和气泡上那一行身份标签。
     */
    fun chat(id: String) = "$CHAT/$id"

    /**
     * 预设详情页。用查询参数而不是路径段：Navigation 对空值宽容，参数缺失时走默认值，
     * 不会出现「少一截就匹配不上模板」的意外（和前一条 [inputChat] 同一个理由）。
     */
    fun personaPreset(index: Int) = "$PERSONA_PRESET?index=$index"

    /**
     * 等待页。描述必须 `Uri.encode` —— 里面是中文，还可能带空格、斜杠、问号，
     * 不编的话这些字符会被路由当成结构字符吃掉。
     */
    fun personaWait(description: String, preset: Boolean): String =
        "$PERSONA_WAIT?description=${Uri.encode(description)}&preset=$preset"

    /** 等待页与预设详情页的路由模板，和上面两个拼出来的必须逐字对应。 */
    const val PERSONA_PRESET_PATTERN = "$PERSONA_PRESET?index={index}"
    const val PERSONA_WAIT_PATTERN = "$PERSONA_WAIT?description={description}&preset={preset}"

    fun inputPersonaField(personaId: String, field: PersonaField) =
        "input/persona-field/$personaId/${field.key}"

    const val INPUT_PERSONA_FIELD_PATTERN = "input/persona-field/{personaId}/{field}"

    /**
     * 角色详情页（逐块看人设 + 改那三个短字段）。
     *
     * 用路径段而不是查询参数：`personaId` 是 UUID，不会带需要转义的字符，
     * 而这条路由不带任何可选参数 —— 没有「少一截就匹配不上」的那种风险。
     */
    fun personaDetail(personaId: String) = "$PERSONA_DETAIL/$personaId"

    const val PERSONA_DETAIL_PATTERN = "$PERSONA_DETAIL/{personaId}"

    fun inputPresetField(index: Int, fieldKey: String) = "input/preset-field/$index/$fieldKey"
    const val INPUT_PRESET_FIELD_PATTERN = "input/preset-field/{index}/{fieldKey}"

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
                        // 首页两页各自把「我是谁」传上去，设置页据此决定要不要多给一项
                        onOpenSettings = { navController.navigate(Routes.settings(it)) },
                        onOpenPersona = { personaId ->
                            // 已有会话就复用，没有就现建一条并把 greeting 上架。
                            // 返回 null 只有一种情况：这个角色已经不在了（被删之后
                            // 页面还没刷新到）。那就什么都不做 —— 列表很快会自己消失掉那一行，
                            // 硬导航到一个空会话只会多出一个空白页。
                            ChatEngine.openPersonaChat(personaId)?.let {
                                navController.navigate(Routes.chat(it))
                            }
                        },
                        onNewPersona = { navController.navigate(Routes.PERSONA_NEW) },
                        onOpenPersonaDetail = {
                            navController.navigate(Routes.personaDetail(it))
                        },
                    )
                }

                // ---------------------------------------------------------- 造人

                composable(Routes.PERSONA_NEW) {
                    PersonaNewScreen(
                        onDescribe = { navController.navigate(Routes.INPUT_PERSONA_DESC) },
                        onPickPreset = { navController.navigate(Routes.personaPreset(it)) },
                        // 与设置页那张「用手机管理人设」同一条路 —— 不另开路由、不另立说明。
                        onImportPersonas = {
                            navController.navigate(Routes.importPhone(PhoneLanding.PERSONAS))
                        },
                        // 「自己说一句」的接力棒：文字先落到 ChatEngine.personaDraft，
                        // 这一页重新回到前台才发起导航（见 ChatEngine.personaDraft 的注释）。
                        onDescriptionReady = { description ->
                            navController.navigate(Routes.personaWait(description, preset = false))
                        },
                    )
                }

                composable(
                    route = Routes.PERSONA_PRESET_PATTERN,
                    arguments = listOf(
                        navArgument("index") {
                            type = NavType.IntType
                            defaultValue = 0
                        },
                    ),
                ) { entry ->
                    PersonaPresetScreen(
                        presetIndex = entry.arguments?.getInt("index", 0) ?: 0,
                        onEditField = { fieldKey ->
                            val index = entry.arguments?.getInt("index", 0) ?: 0
                            navController.navigate(Routes.inputPresetField(index, fieldKey))
                        },
                        onGenerate = { description ->
                            navController.navigate(Routes.personaWait(description, preset = true))
                        },
                    )
                }

                composable(
                    route = Routes.PERSONA_WAIT_PATTERN,
                    arguments = listOf(
                        navArgument("description") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("preset") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    PersonaWaitScreen(
                        // Navigation 会把 query 里的 %XX 自动解回来，这里拿到的是原文
                        description = entry.arguments?.getString("description").orEmpty(),
                        source = if (entry.arguments?.getBoolean("preset", false) == true) {
                            PersonaSource.PRESET
                        } else {
                            PersonaSource.CUSTOM
                        },
                        onReady = { conversationId ->
                            // **直接进聊天页，不要回第二页**：用户刚说完想要什么样的人，
                            // 最想看到的是 TA 开口（greeting 已经在建会话时上架了）。
                            //
                            // popUpTo(HOME) 是必要的：不弹的话返回栈里会留着
                            // 新建页 / 预设页 / 等待页三张，从聊天页返回会一张张退回去，
                            // 而用户的直觉是「回到角色列表」。HOME 本身保留（inclusive=false），
                            // 所以返回正好落在通讯录那一页上。
                            navController.navigate(Routes.chat(conversationId)) {
                                popUpTo(Routes.HOME) { inclusive = false }
                            }
                        },
                        // 取消 = 作废这次捏人**并且**退回上一页。作废那道规矩写在
                        // PersonaWaitScreen 自己的 BackHandler 里（和输入页的「离开 = 提交」
                        // 正好相反），这里只负责导航。
                        //
                        // **落点是「上一页」而不是第二页，这是拍板定的。**
                        // 两条路各自回各自的上一步，都是用户刚设置它的地方：
                        //   * 自己说一句 → 回「造一个角色」页，可以立刻改描述或换个预设重来；
                        //   * 预设详情   → 回预设详情页，之前改过的那四格**还在**，按生成即可。
                        // 直接 popUpTo(HOME) 会把这两处中间状态一起丢掉，用户得从头再填一遍。
                        // 所以别把这里「修」成直回第二页。
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_PERSONA_DESC) {
                    InputScreen(
                        target = InputTarget.PersonaDescription,
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.INPUT_PERSONA_FIELD_PATTERN) { entry ->
                    val personaId = entry.arguments?.getString("personaId").orEmpty()
                    // 认不出来的字段名落回「名字」：这条路由理论上只有我们自己拼得出来，
                    // 但导航栈被恢复时参数是外部给的，兜一个默认值比崩掉好。
                    val field = PersonaField.fromKey(entry.arguments?.getString("field").orEmpty())
                        ?: PersonaField.NAME
                    InputScreen(
                        target = InputTarget.PersonaFieldEdit(personaId = personaId, field = field),
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(Routes.PERSONA_DETAIL_PATTERN) { entry ->
                    val personaId = entry.arguments?.getString("personaId").orEmpty()
                    PersonaDetailScreen(
                        personaId = personaId,
                        onEditField = { field ->
                            navController.navigate(Routes.inputPersonaField(personaId, field))
                        },
                        // 长字段只能在手机上改 —— 说「去手机上改」却不给路，等于没说。
                        // 入口在详情页「用手机改」那一组里。
                        //
                        // 落在**这个角色自己的人设表单**上，不是凭证页：
                        // 用户是从 TA 的详情页点过来的，扫完还要自己在手机上再从凭证页
                        // 找一遍「管理角色→这个角色」，那这一步就白点了。
                        onOpenPhonePage = {
                            navController.navigate(
                                Routes.importPhone(PhoneLanding.persona(personaId)),
                            )
                        },
                    )
                }

                composable(Routes.INPUT_PRESET_FIELD_PATTERN) { entry ->
                    InputScreen(
                        target = InputTarget.PresetFieldEdit(
                            presetIndex = entry.arguments?.getInt("index", 0) ?: 0,
                            fieldKey = entry.arguments?.getString("fieldKey").orEmpty(),
                        ),
                        onDone = { navController.popBackStack() },
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
                        // 对话页那个齿轮算「从哪一页进来的」，判据是**这条会话有没有角色**：
                        // 带人设的会话本来就是从 chat 那一页进来的，在它的设置里
                        // 给出「管理人设」入口才对；一次纯提问的会话给 ask 那一份。
                        // 用会话自己的 `personaId` 判，不靠调用方传 —— 少一个会传错的参数。
                        onOpenSettings = {
                            val from = if (ChatEngine.find(id)?.personaId != null) {
                                HomePage.CHAT
                            } else {
                                HomePage.ASK
                            }
                            navController.navigate(Routes.settings(from))
                        },
                    )
                }

                composable(
                    route = Routes.SETTINGS_PATTERN,
                    arguments = listOf(
                        navArgument("from") {
                            type = NavType.StringType
                            defaultValue = HomePage.ASK.name
                        },
                    ),
                ) { entry ->
                    // 认不出来就落回 ASK。ASK 是「少一项」的那一份，
                    // 兜底少给一个入口，比多给一个来源不明的入口安全。
                    val origin = HomePage.entries.firstOrNull {
                        it.name == entry.arguments?.getString("from")
                    } ?: HomePage.ASK
                    SettingsScreen(
                        origin = origin,
                        onEditApiKey = { navController.navigate(Routes.INPUT_API_KEY) },
                        onEditPrompt = { navController.navigate(Routes.INPUT_PROMPT) },
                        onImportApiKey = {
                            navController.navigate(Routes.importPhone(PhoneLanding.CREDENTIALS))
                        },
                        onImportPersonas = {
                            navController.navigate(Routes.importPhone(PhoneLanding.PERSONAS))
                        },
                        onOpenSpeech = { navController.navigate(Routes.SETTINGS_SPEECH) },
                        onOpenDisplay = { navController.navigate(Routes.SETTINGS_DISPLAY) },
                    )
                }

                composable(Routes.SETTINGS_SPEECH) {
                    SpeechSettingsScreen(
                        onEditXfyAppId = { navController.navigate(Routes.INPUT_XFY_APPID) },
                        onEditXfyApiKey = { navController.navigate(Routes.INPUT_XFY_APIKEY) },
                        onEditXfyApiSecret = { navController.navigate(Routes.INPUT_XFY_APISECRET) },
                    )
                }

                composable(Routes.SETTINGS_DISPLAY) {
                    DisplaySettingsScreen()
                }

                composable(
                    route = Routes.IMPORT_PHONE_PATTERN,
                    arguments = listOf(
                        navArgument("land") {
                            type = NavType.StringType
                            defaultValue = PhoneLanding.CREDENTIALS
                        },
                    ),
                ) { entry ->
                    ImportKeyScreen(
                        // Navigation 会把 query 里的 %XX 自动解回来，这里拿到的是原文
                        landing = entry.arguments?.getString("land").orEmpty(),
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
