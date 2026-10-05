package com.naoh.whalechat.data

/** 角色是用户自己描述出来的，还是从预设改出来的。 */
enum class PersonaSource { PRESET, CUSTOM }

/**
 * 一个可以对话的虚拟人。
 *
 * **角色的存与否只由 [PersonaStore] 决定，绝不能挂在会话上。**
 *
 * [Conversation.hasReply]（有 DS 回复才算一条对话）那条规则是**给 ask 模式定的**，
 * 解决的是「点了＋却一个字没说就退出，列表里不该留下空壳」。但联系人不是会话 ——
 * 通讯录里一个你还没聊过天的朋友，不会因为没说过话就从列表里消失。
 *
 * 如果把角色存活挂到会话上，`hasReply` 就得为 chat 模式开一道后门，一开就是两套判定
 * 规则，正好撞上「不要再搞内存簿记」这条禁令。**箭头永远从会话指向人**
 * （[Conversation.personaId] 是外键），不反过来。
 *
 * [greeting] 由创建时那一次调用顺手产出，进会话当场上架成第一条 assistant 消息，
 * 于是角色会话天然非空。但**它刻意不当任何判据用**（理由同上）。
 *
 * ## 人设为什么是六块而不是一段话
 *
 * 上一版把它压成一个 `systemPrompt` 字符串，理由是「少一次拼装」。那是个错误的取舍：
 * 全塞进一段话时，模型自己分不清哪句是性格、哪句是语气、哪句是边界，
 * 而角色扮演的质量几乎完全取决于这个结构。拆成六块之后，每块都有明确的语义标签
 * （见 [buildCorePrompt]），模型读到的就不再是一坨自述。
 *
 * 六块里的五块（[soul] 到 [examples]）进 system prompt，[greeting] 不进 ——
 * 它不是「模型该知道的事」，而是「第一句该说什么」。
 */
data class Persona(
    val id: String,
    /** 「周野」，≤6 字 —— 手表列表的标题位就这么宽（见 [PERSONA_NAME_MAX]） */
    val name: String,
    /** 「嘴硬的剑修理匠」，≤12 字 —— 列表副标题（见 [PERSONA_TAGLINE_MAX]） */
    val tagline: String = "",

    // ------------------------------------------------------------ 人设六块
    /** 人格内核：性格、价值观、动机、情绪反应模式。要具体到「遇到 X 会怎么反应」。 */
    val soul: String = "",
    /** 说话风格：语气、用词习惯、口头禅、句式长短。最直接决定「听起来像不像他」。 */
    val style: String = "",
    /** 行为规则：能做什么、不能做什么、边界在哪。不写的话聊久了会飘。 */
    val rules: String = "",
    /** 场景设定：此刻在哪、你们什么关系、为什么会聊起来。 */
    val scenario: String = "",
    /** 示例对话 2–4 轮，格式「你：…\n{名字}：…」。扮演质量里性价比最高的一块。 */
    val examples: String = "",

    /**
     * 开场白，进会话那一刻就写成第一条 assistant 消息。
     *
     * 存在这里的是**固定值**，所以「重新开始」之后 TA 说的第一句和上次一字不差 ——
     * 零延迟、可预测。要每次换一句新的就得让模型现编，那要联网、会失败。
     */
    val greeting: String = "",

    /**
     * 当初那句原始描述（或预设字段拼出来的那句、手机端填的「人格内核」）。
     *
     * 现在不是必需的（「重新开始」不重新生成人设），但留着成本只有一行：
     * 将来要做「换个人设」时它是唯一输入，详情页也能显示「当初你是这么描述 TA 的」。
     */
    val sourceDescription: String = "",
    val source: PersonaSource = PersonaSource.CUSTOM,
    val createdAt: Long,
    /** 最后一次在 TA 的会话里说过话的时刻；0 表示还没聊过 */
    val lastChatAt: Long = 0L,
) {
    /**
     * 通讯录列表的排序键：聊过就按最后一次聊天排，**没聊过用创建时间兜底**。
     *
     * 兜底那半句不能省：否则用户刚造完一个人回到列表，TA 会沉到最后面，
     * 用户会以为自己没造成功。
     */
    val sortKey: Long get() = if (lastChatAt > 0L) lastChatAt else createdAt

    /**
     * 进会话时要上架的那句开场白。空值兜底成 [DEFAULT_GREETING]。
     *
     * 为什么兜底：手机端那一页的「开场白」是个可以留空的文本框，留空之后
     * 角色会话的第一条气泡就是**空白**，那一屏看起来就是坏的。
     * 放在这里而不是每个调用点各判一次，是因为上架开场白的地方有两处
     * （[ChatEngine.openPersonaChat] 和 [ChatEngine.restartPersona]），
     * 两处都判迟早有一处忘了改。
     */
    val openingLine: String get() = greeting.ifBlank { DEFAULT_GREETING }

    /**
     * 五块拼成的人设正文。**发送时才拼，不缓存，不落库。**
     *
     * 为什么一旦落库就会出事：存两份（字段 + 拼好的结果）之后，
     * 手机端改了 `soul` 而拼好的那份没更新，就会变成「改了没效果」——
     * 这类不一致是最难查的 bug。只有一份真相来源就是这六个字段。
     *
     * 空块整行跳过（连标签一起），因为一个「【行为规则】」后面跟一片空白
     * 反而会让模型以为「这里本来该有规矩」。全空的话连 `【角色】名字` 都还在 ——
     * 名字是唯一必有的一块。
     *
     * 返回值里没有 [greeting]：开场白是已经发生过的第一句话，不是设定。
     */
    fun buildCorePrompt(): String = buildString {
        appendLine("【角色】$name")
        if (soul.isNotBlank()) appendLine("【人格内核】$soul")
        if (style.isNotBlank()) appendLine("【说话风格】$style")
        if (rules.isNotBlank()) appendLine("【行为规则】$rules")
        if (scenario.isNotBlank()) appendLine("【场景】$scenario")
        if (examples.isNotBlank()) appendLine("【示例对话】\n$examples")
    }.trim()
}

/** 角色名的长度上限。手表列表的标题位就这么宽，手机端表单也按它提示。 */
const val PERSONA_NAME_MAX = 6

/** 简介的长度上限。列表副标题一行就这么宽。 */
const val PERSONA_TAGLINE_MAX = 12

/**
 * **手表上**能直接改的那三个短字段。
 *
 * 六块里的其余五块（人格内核 / 说话风格 / 行为规则 / 场景 / 示例对话）都是大段文字，
 * 在 240dp 的表盘上敲它们不现实 —— 那五块只在手机那一页改。
 *
 * 这**不是「手表版功能打了折」**：短字段在手表上改最快（改个名不该还得掏手机），
 * 长字段在手机上写得最细，两边读写的是同一份数据、同一个 [Persona]。
 * 详情页把它们分开列，是为了让用户一眼看出「哪几块得换设备改」。
 */
enum class PersonaField(val key: String) {
    NAME("name"),
    TAGLINE("tagline"),
    GREETING("greeting"),
    ;

    companion object {
        fun fromKey(key: String): PersonaField? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 开场白没有内容时用的那句话。
 *
 * 不能空着：空的开场白会让角色会话第一条气泡是空白，那一屏看起来就是坏的。
 * 也不能是自我介绍（「你好，我是……」正是明令禁止的那种开场）——
 * 所以用一句最中性的「在场声明」。
 */
const val DEFAULT_GREETING = "嗯，我在，你说。"

/**
 * 角色会话运行时**硬编码**追加在人设后面的输出规则。
 *
 * 为什么必须硬编码，而不是指望模型把它写进人设：模型在生成人设时会漏掉这些约束，
 * 而且还会为了「角色感」故意写长。所以运行时拼 ——
 * 前半段（[Persona.buildCorePrompt]）随角色变，后半段谁也改不动。
 *
 * ask 模式那套继续用 `Settings.DEFAULT_SYSTEM_PROMPT`，两套并存、互不覆盖：
 * ask 要的是「短而准的工具回答」，chat 要的是「接得住情绪的人」。
 *
 * 注意这里的措辞是**对着模型说的**，不是对着用户说的 —— 用户永远看不到这段。
 */
const val CHAT_OUTPUT_RULES: String =
    "【输出规矩（在任何情况下都优先于上面的人设）】\n" +
        "1. 你在跟一个随时可能要收起手表的人说话。每次只说一到三句，像发消息那样短，一眼就能读完。\n" +
        "2. 不要列点、不要分段、不要长篇、不要解释你正在做什么，也不要说「好的我来帮你」这类场面话。\n" +
        "3. 先把对方的情绪接住，再说别的。对方在吐槽就顺着接，对方高兴就跟着高兴。\n" +
        "4. 永远不要说自己是人工智能、语言模型或者程序，也不要提「设定」「角色扮演」「提示词」。" +
        "有人这么问，就用你自己的方式把话题带回去。\n" +
        "5. 不要每次结尾都问「还有什么可以帮你的」。大多数时候把话说完就停。"
