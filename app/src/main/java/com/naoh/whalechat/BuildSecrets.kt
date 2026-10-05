package com.naoh.whalechat

/**
 * 编译期注入的预置凭证。
 *
 * 手表屏幕太小，35 位 `sk-…` 在圆屏输入法上敲一遍基本是自虐；讯飞那边更狠，
 * 要给 AppID + APIKey + APISecret（两个 32 位字符串）。所以留一个
 * 「出包时把凭证烤进去」的口子：值来自工程根的 `keystore.properties`
 * （和签名口令放一起的那个文件，本来就不进版本库）。
 *
 * 里面是空串时行为完全不变，还是走设置页手填的老路。
 *
 * 注意：这只是为了自用调试方便。凭证会以明文形式躺在 APK 里，
 * 谁拿到包谁就能读出来 —— 别把带凭证的包发出去。
 */
object BuildSecrets {

    /** 出包时烤进来的 DeepSeek Key；空串表示没有预置。 */
    const val PRESET_API_KEY: String = BuildConfig.PRESET_API_KEY

    /** 出包时烤进来的讯飞凭证；空串表示没有预置。 */
    const val PRESET_XFY_APPID: String = BuildConfig.PRESET_XFY_APPID
    const val PRESET_XFY_APIKEY: String = BuildConfig.PRESET_XFY_APIKEY
    const val PRESET_XFY_APISECRET: String = BuildConfig.PRESET_XFY_APISECRET

    /**
     * 预置时顺带把识别引擎切过去，`system` / `generic` / `xfyun`。
     *
     * 留空时：[hasPresetXfyun] 为真就切 `xfyun`，否则保持 App 自己的默认值。
     */
    const val PRESET_ASR_ENGINE: String = BuildConfig.PRESET_ASR_ENGINE

    val hasPresetApiKey: Boolean get() = PRESET_API_KEY.isNotBlank()

    /** 三样齐全才算预置了讯飞凭证，缺一样都不生效 */
    val hasPresetXfyun: Boolean
        get() = PRESET_XFY_APPID.isNotBlank() &&
            PRESET_XFY_APIKEY.isNotBlank() &&
            PRESET_XFY_APISECRET.isNotBlank()
}
