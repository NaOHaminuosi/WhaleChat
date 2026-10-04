# OkHttp：保留 SSE 流式读取所需的类
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 反射构造的枚举/数据类（JSON 手工解析，无需 keep，仅保底）
-keepclassmembers class com.naoh.whalechat.data.** { *; }

# ZXing core：QRCodeWriter / BitMatrix 直接调用，但保底全保留，避免 R8 误删分支
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
