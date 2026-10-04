package com.naoh.whalechat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * 取系统动态色板（Material You 的莫奈取色）。
 *
 * Wear M3 的 `dynamicColorScheme(context)` 自带系统判断，拿不到时返回 null，
 * 所以调用方只要兜底就行，不需要写 SDK_INT 守卫。
 */
@Composable
fun rememberDynamicColorScheme(enabled: Boolean): ColorScheme? {
    val context = LocalContext.current
    return remember(enabled, context) {
        if (enabled) dynamicColorScheme(context) else null
    }
}

/**
 * 主题外壳：固定启用莫奈取色（Material You 动态配色）。
 *
 * 外层先建一次默认 MaterialTheme —— 那是 Wear M3 的基线深色色板，用作兜底；
 * 内层换成系统动态色板。设备给不出动态色板（比如某些表盘/旧系统）时退回基线。
 *
 * 之所以这么绕：Wear M3 没有手机端那套 `darkColorScheme()/lightColorScheme()` 构造器，
 * 自己 new 一个 ColorScheme 得手写 29 个颜色值，用基线做兜底既准确又不啰嗦。
 */
@Composable
fun WhaleChatTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        val baseline = MaterialTheme.colorScheme
        val dynamic = rememberDynamicColorScheme(true)
        MaterialTheme(colorScheme = dynamic ?: baseline, content = content)
    }
}
