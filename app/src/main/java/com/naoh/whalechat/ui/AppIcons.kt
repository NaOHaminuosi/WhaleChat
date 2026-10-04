package com.naoh.whalechat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自绘的 Material Symbols 图标（标准 24×24 pathData）。
 *
 * 全应用只用到这几个图标，为它们引入 `material-icons-extended` 会白白撑大安装包，
 * 所以按需把 pathData 抄进来。`Icon` 会用 tint 覆盖这里的填充色，写黑色只是占位。
 */
object AppIcons {

    val Mic: ImageVector by lazy { vector("Mic", MIC) }
    val Keyboard: ImageVector by lazy { vector("Keyboard", KEYBOARD) }
    val Send: ImageVector by lazy { vector("Send", SEND) }
    val Stop: ImageVector by lazy { vector("Stop", STOP) }
    val Delete: ImageVector by lazy { vector("Delete", DELETE) }
    val Settings: ImageVector by lazy { vector("Settings", GEAR) }
    val Add: ImageVector by lazy { vector("Add", ADD) }
    val Check: ImageVector by lazy { vector("Check", CHECK) }
    val Back: ImageVector by lazy { vector("Back", BACK) }
    val Sparkle: ImageVector by lazy { vector("Sparkle", SPARKLE) }
    val Phone: ImageVector by lazy { vector("Phone", PHONE) }

    private const val MIC =
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 " +
            "1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 " +
            "6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"

    private const val KEYBOARD =
        "M20,5L4,5c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 " +
            "2,-2L22,7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2L11,8zM11,11h2v2h-2v-2zM8,8h2v2L8,10L8,8zM8,11h2v2L8,13v-2zM7,13L5,13v-2h2v2zM7,10L5,10L5,8h2v2zM16,17L8,17v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2L14,8h2v2zM19,13h-2v-2h2v2zM19,10h-2L17,8h2v2z"

    private const val SEND = "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"

    private const val STOP = "M7,7h10c1.1,0 2,0.9 2,2v6c0,1.1 -0.9,2 -2,2L7,17c-1.1,0 -2,-0.9 -2,-2L5,9c0,-1.1 0.9,-2 2,-2z"

    private const val DELETE =
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12zM19,4h-3.5l-1,-1h-5l-1,1L5,4v2h14L19,4z"

    /** Material Symbols「settings」：经典齿轮，带中心圆孔 */
    private const val GEAR =
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 " +
            "0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81" +
            "c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29" +
            "L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 " +
            "4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22" +
            "l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 " +
            "0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
            "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 " +
            "3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"

    private const val ADD = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"

    private const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

    private const val BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

    private const val SPARKLE =
        "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z"

    /** Material Symbols「smartphone」：手机轮廓，用于「手机扫码导入」 */
    private const val PHONE =
        "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z"

    private fun vector(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
