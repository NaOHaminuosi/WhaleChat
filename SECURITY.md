# 安全政策

## 支持的版本

| 版本 | 是否修 |
| --- | --- |
| 1.0 | 修 |

## 怎么报

**不要直接开公开 Issue**——安全问题里很可能要描述到凭证、token 或具体的请求构造。

走 GitHub 的 [Private vulnerability reporting](https://github.com/NaOHaminuosi/WhaleChat/security/advisories)。请带上：

- 能复现的最小步骤（手表型号 / Wear OS 版本 / App 版本）；
- 你预期的行为和实际的行为；
- 能证明问题的截图或日志——**贴之前把 `sk-…`、讯飞三串、URL 里的 token 全部打码**。

一般 7 天内会给第一次回应。确认后会在下一个版本修，并在 CHANGELOG 里写明。

## 一定要打码的东西

报告时请删除以下内容，它们出现在公开 Issue 里就等于泄露：

- DeepSeek Key（35 位 `sk-…`）
- 讯飞 AppID / APIKey / APISecret
- 扫码页 URL（`http://手表IP:端口/?token=…`，那个 token 是完整凭证的读钥匙）

## 已知的安全取舍

这几条是**有意为之**的设计，不是漏洞，但你应该知道：

1. **扫码页是局域网明文 HTTP，且可读可写。**
   URL 带 64 位随机 token、最长活 10 分钟、收到一次提交立刻关停。
   风险窗口是「同一 Wi-Fi 下有人在你用扫码页的那十分钟内抓到这个 URL」。
   缓解方式：**别在公共 Wi-Fi 下用它**。

2. **讯飞 APISecret 必须存在手表本地。**
   签名算法要求它参与本地计算，没法只放服务端。含义是：装了这个 App 的表上有这把密钥。

3. **预置凭证以明文写进 APK。**
   `keystore.properties` / `-PpresetXfyApiSecret=…` 出包时，凭证是明文的，
   谁拿到包谁就能读出来。带凭证的包**不要外发**，出外发包用命令行把这几项显式清空：

   ```bash
   ./gradlew assembleRelease \
     -PpresetApiKey= -PpresetXfyAppId= -PpresetXfyApiKey= -PpresetXfyApiSecret= -PpresetAsrEngine=
   ```

4. **不做证书锁定。**
   没有统计、没有遥测、没有账号，App 里值得中间人盯的东西就是那几把 Key，
   而它们本来就要发给 DeepSeek / 讯飞——锁了也只是把信任从系统 CA 挪到硬编码的指纹上，
   收益不抵维护成本。

## 不在范围内

- 你自己泄露的 Key：请直接去对应控制台吊销，本项目无法代劳。
- DeepSeek / 讯飞服务端的安全问题：找它们官方。
- 「手表丢了」：App 数据在系统沙箱内，且 `allowBackup=false`；
  但沙箱挡不住已 root 的设备。真丢了就去吊销 Key。
