package com.naoh.whalechat.data

/**
 * 预设详情页里的一行。
 *
 * [label] 是给用户看的字段名，同时也是拼进生成描述时的键；[placeholder] 只在空值时显示；
 * [default] 是进页面时的预填值 —— 预填出来的那几行就是「就它了」的依据，
 * 用户只在不对的地方动一下手。
 *
 * [placeholder] 必须是**一句话的具体举例**，不能是「TA 是什么样的」这种复述标签的话：
 * 用户看到「例：耐心，从不直接给答案，会先说『你先讲讲你卡在哪』」才知道这一格该写什么，
 * 看到「TA 是什么样的」还是不知道。
 */
data class PresetField(
    val key: String,
    val label: String,
    val placeholder: String,
    val default: String = "",
)

/**
 * 一个预设。
 *
 * **[title] / [subtitle] 和 [description] 是两套文本，不要用同一句**：
 *  * [title] + [subtitle] 是给用户看的：一个短名字加一句人选理由，扫一眼就知道是什么；
 *  * [description] 是给模型看的生成输入，具体、把定位说清楚
 *    （比如「不是管家、不是服务者」这种话只有模型需要读，摆到界面上只会让人困惑）。
 *
 * 用的同一句就会出现「用户看不懂 / 模型看不懂」必居其一。
 *
 * **打磨人设质量时改的就是 [description] 的文案本身，架构不用动** ——
 * 这个数据结构保持不变就是给这件事留的位置。
 */
data class PersonaPreset(
    val title: String,
    /** 列表卡片上的那一行「一句话描述」，只给用户看 */
    val subtitle: String,
    val description: String,
    val fields: List<PresetField>,
)

/**
 * 预设详情页正在编辑的那几个字段（外加预设自带的那个可选字段）。
 *
 * 是**页面级草稿**：进预设详情页时铺一份，改完拿去生成，生成完清掉。
 * 不落盘、不进历史，跟 [Persona.sourceDescription] 不是一回事（那个是已经造出来的角色
 * 留存的原始描述，是要写进 personas.json 的）。
 */
data class PresetDraft(
    val presetIndex: Int,
    val values: Map<String, String>,
) {
    fun valueOf(key: String): String = values[key].orEmpty()

    fun with(key: String, value: String): PresetDraft = copy(values = values + (key to value))

    /**
     * 把这一屏字段拼成一句自然语言描述，然后交给**和「自己说一句」完全相同**的生成管道。
     *
     * 预设**绝不能自成一套生成逻辑** —— 兜底、超时、取消的规则一旦写两份，
     * 迟早有一份忘了改。所以这里只负责把字段铺平，剩下的全走同一个入口。
     *
     * 空字段直接略过：用户把一个默认值清空，意思就是「这条不要」，不是「这条留空」。
     */
    fun toDescription(preset: PersonaPreset): String {
        val lines = preset.fields.mapNotNull { field ->
            valueOf(field.key).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "${field.label}：$it" }
        }
        return (listOf(preset.description.trim()) + lines)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    companion object {
        fun from(preset: PersonaPreset, index: Int): PresetDraft = PresetDraft(
            presetIndex = index,
            values = preset.fields.associate { it.key to it.default },
        )
    }
}

/**
 * 三个预设。
 *
 * **预设里不许出现任何第三方作品的虚构角色名。** 用户自己在描述框里写「我要跟某人聊天」，
 * 那是用户输入；我们把它做成**预置选项**，等于在产品里内置第三方 IP 的入口，
 * 而这个 App 是要公开发布的。外加模型未必捏得准具体角色，捏出四不像体验更差。
 *
 * 历史人物那一档因此做成**通用模板 + 一个「人物」字段**（默认秦始皇），而不是写死一个：
 * 一格字段就覆盖了李白、孔子、武则天、苏轼……写死一个反而压低了天花板。
 * 顺带，真实历史人物没有 IP 归属，扮演正当。
 *
 * 措辞一律中性（「说话温柔、很会照顾对方情绪的人」这种），避开容易让模型往暧昧或
 * 性化方向发挥的词。**我们不做内容护栏** —— 生成内容走 DeepSeek API，由用户负责；
 * 但预设文案是我们自己写的，分寸由我们把握。
 */
private val PRESETS: List<PersonaPreset> = listOf(
    PersonaPreset(
        title = "辅助学习的老师",
        subtitle = "讲得清、会追问，陪你把题弄懂",
        description = "一个陪着用户学习的老师：能把难点拆开讲清楚，会追问用户到底卡在哪一步，" +
            "不讲空话，也不替用户做题。",
        fields = listOf(
            PresetField(key = "name", label = "称呼", placeholder = "老师", default = "老师"),
            PresetField(key = "gender", label = "性别", placeholder = "男 / 女（可留空）"),
            PresetField(
                key = "background",
                label = "背景",
                placeholder = "例：我在准备考试，函数那块一直听不懂（可留空）",
                default = "我在准备考试，有不懂的想问你",
            ),
            PresetField(
                key = "personality",
                label = "性格",
                placeholder = "例：耐心，不直接甩答案，会先问「你卡在哪一步」",
                default = "耐心，会追问",
            ),
            PresetField(
                key = "style",
                label = "说话",
                placeholder = "例：短句，一次只讲一个点，讲完让我复述一遍",
                default = "简短，一次只讲一个点",
            ),
        ),
    ),
    PersonaPreset(
        title = "贴心主动的伴侣",
        subtitle = "不等你说，TA 先问；先接住情绪，不急着讲道理",
        description = "一个主动、贴心的伴侣关系中的人。会主动问今天怎么样、吃了什么、累不累；" +
            "对方不说时也会找话题，不会冷场。对方心情不好时先接住情绪，" +
            "不急着讲道理或给建议。说话自然，不过分甜腻，也不敷衍。",
        fields = listOf(
            PresetField(key = "name", label = "称呼", placeholder = "例：直接叫名字，或小名（可留空）"),
            PresetField(
                key = "together",
                label = "在一起多久",
                placeholder = "例：在一起两年多了",
                default = "我们在一起两年多了",
            ),
            PresetField(
                key = "personality",
                label = "TA 的性格",
                placeholder = "例：温柔，但你一天没消息 TA 会先问",
                default = "温柔，会主动找话说",
            ),
            PresetField(
                key = "style",
                label = "说话方式",
                placeholder = "例：短句，像发消息，不爱用感叹号",
                default = "简短，像发消息",
            ),
        ),
    ),
    PersonaPreset(
        title = "历史人物",
        subtitle = "秦始皇、李白、武则天……填个人名试试",
        description = "一个真实存在的历史人物，请根据用户填写的人物名还原 TA 的口吻与见识。" +
            "说话要像那个时代的人：用他自己的经验和眼界去理解眼前的事，" +
            "遇到现代事物会好奇、会用自己的方式类比。" +
            "说到的史实要有依据；记不清或不确定的地方，直说「这个我记不清了」，绝不要编造。",
        fields = listOf(
            PresetField(
                key = "person",
                label = "人物",
                placeholder = "秦始皇",
                default = "秦始皇",
            ),
            PresetField(
                key = "topic",
                label = "想聊的话题",
                placeholder = "例：统一六国最难的是哪一步（可留空）",
            ),
            PresetField(
                key = "address",
                label = "TA 称你什么",
                placeholder = "例：让 TA 叫你「卿」（可留空）",
            ),
            PresetField(
                key = "scene",
                label = "场景",
                placeholder = "例：我在两千多年后，隔着时间跟你说话（可留空）",
            ),
        ),
    ),
)

/** 新建页和预设详情页都读这一份，别各自写一份（写两份迟早对不上）。 */
fun personaPresets(): List<PersonaPreset> = PRESETS
