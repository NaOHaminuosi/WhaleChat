import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 本地私密配置都放工程根的 keystore.properties：签名口令 + 可选的预置 API Key。
 * 这个文件不进版本库。读不到就跳过签名配置，只影响 assembleRelease，不影响 assembleDebug。
 *
 * 之所以借这个文件装 API Key：手表圆屏上敲 35 位 sk- 太痛苦，
 * 出个自带 Key 的包能省掉这件事。文件不存在或没写这一项时，App 行为完全不变。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 命令行走 -PpresetApiKey=… 可以临时覆盖，优先级高于 properties 文件。 */
val presetApiKey: String =
    (project.findProperty("presetApiKey") as String?)
        ?: keystoreProps.getProperty("presetApiKey")
        ?: ""

/**
 * 讯飞语音听写的凭证，同样是「出包时烤进去」。
 *
 * 讯飞要给三样东西（AppID / APIKey / APISecret），两个 32 位字符串加一个数字，
 * 在手表圆屏输入法上敲一遍比 DeepSeek 那个 sk- 还难受，所以一视同仁地预置。
 *
 * 讯飞那边固定走「语音听写（流式版）」（iat-api.xfyun.cn/v2/iat）：控制台里另外
 * 几档大模型要么只对历史已购用户开放，要么连端点都不可用，所以不留档位开关。
 */
fun prop(name: String): String =
    (project.findProperty(name) as String?) ?: keystoreProps.getProperty(name) ?: ""

val presetXfyAppId: String = prop("presetXfyAppId")
val presetXfyApiKey: String = prop("presetXfyApiKey")
val presetXfyApiSecret: String = prop("presetXfyApiSecret")

/**
 * 预置凭证时要不要顺手把识别引擎也切过去。
 *
 * 只预置凭证、不给引擎，用户装完还得自己去设置里把「识别引擎」从「系统」改成
 * 「讯飞听写」—— 那预置这件事就白做了。留空时：给了讯飞凭证就切讯飞，
 * 没给就保持不动。取值 system / xfyun。
 */
val presetAsrEngine: String = prop("presetAsrEngine")

/** 生成的 Java 字符串字面量。凭证里万一有引号/反斜杠，不转义会直接编不过。 */
fun String.asLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 版本号。
 *
 * **`versionName` 每次改动 +0.001**：`1.976` → `1.977` → … → `2.0`。
 * 大版本（动第一位，例如 `2.0`）留给人看得出来是阶段变化的时刻。
 *
 * ⚠️ **次版本最多 3 位。** versionCode 是 `主×1000 + 次` 算出来的，
 *    写成 4 位（`1.9178`）会算出 10178，反而大过 `2.0` 的 2000 ——
 *    于是正式版装不上（`INSTALL_FAILED_VERSION_DOWNGRADE`）。
 *
 * **`versionCode` 不手写，由 `versionName × 1000` 算出来。** 两个数分开手写迟早会
 * 忘掉其中一个，而 `versionCode` 忘了递增的后果是实打实的：装覆盖包会失败
 * （`INSTALL_FAILED_VERSION_DOWNGRADE`），而且是那种「明明改了代码，装机后画面还是旧的」
 * 的隐蔽症状。算出来的就永远是单调递增的。
 *
 * （别用 `java.math.BigDecimal`：Kotlin DSL 里 `java` 这个名字被 JavaPluginExtension
 * 占了，`java.math` 会报 `Unresolved reference: math`。）
 */
val appVersionName = "1.976"
val appVersionCode = appVersionName.split(".").let { parts ->
    require(parts.size == 2 && parts[0].toIntOrNull() != null) {
        "versionName「$appVersionName」不是「主.次」的写法（约定是 1.976 / 1.977 / 2.0 …）"
    }
    parts[0].toInt() * 1000 + parts[1].padEnd(3, '0').toInt()
}

android {
    namespace = "com.naoh.whalechat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naoh.whalechat"
        // Wear OS 3.0 起可用；莫奈取色在 API 31+ 才拿得到系统色板，低版本自动回退基线主题
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 预置 Key 走 BuildConfig 而不是 resources：resources 里的值更容易被 aapt 之外
        // 的工具 dump 出来，放 BuildConfig 是常规做法，也方便 R8 处理。
        // 默认空串 = 不预置，App 行为与「设置页手填」完全一致。
        buildConfigField("String", "PRESET_API_KEY", "\"$presetApiKey\"")

        // 讯飞凭证，同样默认空串 = 不预置，App 行为与「设置页手填」完全一致
        buildConfigField("String", "PRESET_XFY_APPID", presetXfyAppId.asLiteral())
        buildConfigField("String", "PRESET_XFY_APIKEY", presetXfyApiKey.asLiteral())
        buildConfigField("String", "PRESET_XFY_APISECRET", presetXfyApiSecret.asLiteral())
        buildConfigField("String", "PRESET_ASR_ENGINE", presetAsrEngine.asLiteral())
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // debug 包在手表上会明显卡：debuggable=true 时 ART 关掉了大部分编译优化。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
