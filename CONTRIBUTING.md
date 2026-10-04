# 参与开发

先说清楚几件事，省得改了半天被退回。

## 开工前

```bash
git clone https://github.com/NaOHaminuosi/WhaleChat.git
cd WhaleChat
./gradlew assembleDebug          # 不需要签名配置，debug 包直接能出
```

`assembleRelease` 才需要 `keystore.properties`。没有这个文件也能编 debug，不影响开发。

装到手表：

```bash
adb -s <手表序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

**debug 包在手表上会明显卡**——`debuggable=true` 时 ART 关掉了大部分编译优化。
判断性能问题请务必装 release 包，别拿 debug 的帧率当结论。

## 三条硬规矩

1. **任何凭证都不进版本库。**
   `keystore.properties`、`.jks`、真实的 `sk-…` / 讯飞三串，一律不提交。
   PR 里出现这些会被直接要求重做，并且**已经泄露的 Key 请自己去控制台吊销**。
   `.gitignore` 已经挡住大部分，但截图、日志粘贴、测试 fixture 是最常见的漏点——贴之前看一眼。
   出包后跑一遍 `python tools/audit_apk.py <apk>`，它直接扫 dex 里的字符串，
   确认凭证到底有没有被烤进去。

2. **改行为前先看代码注释里有没有「为什么」。**
   这个仓库里有些写法看着别扭，是被具体 bug 逼出来的：`SettingsStore.write()` 的
   `sync` 参数（异步写在被 force-stop 时会丢）、预置凭证的**指纹判断**（布尔标记会导致
   换凭证静默失效）、扫码保存的**整屏覆盖**语义（旧语义下凭证删不掉）。
   改之前读注释，改之后更新注释。

3. **UI 文案要经得起逐字读。**
   手表屏幕小，弹窗里多一个字都可能换行。改文案请在 480×480 的圆形模拟器上看过再提。

## 代码风格

- Kotlin，Compose，4 空格缩进，单行不超过 120 字符。
- 注释写**原因**，不写**做了什么**。代码本身已经说明做了什么。
  反例：`// 设置 API Key`。正例：`// 留空表示不预置：老用户升级后不该被塞一个空串进去`。
- 新增设置项要同时改三处：`Settings` 数据类、`SettingsStore.write()` 的落盘、
  手机扫码页的表单（`KeyImportServer`）。漏一处就会出现「页面上改了但没存」或
  「存了但扫码页读不出来」。
- 一排按钮的按下变形用官方 `ButtonGroup` + `Modifier.animateWidth`，不要退回
  `Row` + `weight`。选一档的开关统一走 `selectionToggleShapes()`。

## 提交信息

一行说清改了什么，正文说为什么。中文即可，不用英文。

```
扫码保存改同步落盘

页面上已经写了「已保存到手表」，apply() 异步写在进程被杀时会丢，
实测过：提交成功页刚回来 force-stop，重开读到的还是旧值。
```

## 签名配置

`keystore.properties` 里 `keyAlias` 和 keystore 文件名**故意不一样**，别去「顺手修正」：

- 文件名只是外包装，随便改，零风险；
- **别名是密钥实体的名字**，改它要跑 `keytool -changealias`，口令一旦对不上就把签名密钥弄丢，
  而签名密钥丢了就没法给已发布的包做覆盖升级。

## 提 PR

1. 从 `main` 拉分支，命名 `fix/xxx` 或 `feat/xxx`。
2. 确认 `./gradlew assembleDebug` 能过。
3. 描述里写清**改之前是什么表现、改之后是什么表现**，最好带截图（480×480）。
4. 涉及凭证、网络、存储改动的，说明你在真机或模拟器上怎么验证的。

## 不打算接受的方向

- 接入 DeepSeek 之外的第三方兼容端点（保持单一后端，代码才留得住）。
- 加统计 / 遥测 / 崩溃上报。这个项目的立场是「数据不出手表」，加了就是自己打自己脸。
- 把凭证搬到云端或加账号体系。
