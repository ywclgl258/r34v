# R34V development rules

- 本项目为 Kotlin 原生 Android 工程，保持 `com.r34v.r34v` 正式包名。
- 每次正式更新递增 `release.properties` 中的 versionCode。主题保留黑底；用户已授权在本轮 UI/UX 重构中调整强调色、主按钮颜色与控件样式，并要求更多参考 Netflix App。
- 旧源码仅作仓库外本地归档，不纳入 Git。未经用户指示不推送或发布。
- 常规验收使用标准设备尺寸和默认系统字体，不再执行 320dp 窄屏或 200% 字体专项；仅在用户明确要求时追加这些检查。既有测试记录保留为历史证据。

## 统一打包与签名（所有 Agent、IDE、命令行及 CI 遵守）

- 所有 APK 通过当前仓库的 Gradle Wrapper 构建，统一使用现有签名配置；不得让工具自行生成发布密钥、另建打包工程或在构建后自行重新签名。
- 发布密钥统一从 `R34V_SIGNING_HOME/signing.properties` 读取；未设置时使用运行 Gradle 的用户主目录下 `.r34v/signing/signing.properties`。本机固定目录为 `C:\Users\ywclg\.r34v\signing`。不同系统用户、容器或电脑须显式配置同一份安全保管的签名材料，不能各自产生新密钥。
- 禁止在仓库、聊天、日志或命令参数中保存/输出私钥和密码。CI 可以从 Secrets 注入同一套固定密钥，但必须通过相同的指纹检查。不得用临时环境变量或 IDE 选项改用另一套签名。
- 正式 APK 的唯一构建入口是下列命令，在仓库根目录执行；其他系统使用等价的 `./gradlew`：

  ```powershell
  $env:R34V_SIGNING_HOME = 'C:\Users\ywclg\.r34v\signing'
  .\gradlew.bat :app:validateReleaseSigning :app:assembleRelease
  ```

- 缺少密钥、密码或别名，或证书与 `release.properties` 的 `releaseCertificate` / `historicalCertificate` 不匹配时，必须停止正式构建。禁止回退 debug 签名，禁止为通过校验而改写预期指纹。
- 必须由 `assembleRelease` 执行现有 `signUpgradeRelease`，保留 `app/signing/upgrade.lineage`；不能只运行 `packageRelease`、只做 IDE 签名或从中间产物交付正式 APK。
- 正式交付须同时保留标准 APK，以及 Android 7/8 上已装 2.0.0 用户专用的 `*-compat-2.0.0.apk`。标准包在 API 24–27 使用历史证书、API 28+ 使用当前证书；兼容包在 API 24–27 使用当前证书。这是既有升级设计，不得为了让各平台显示同一指纹而删除继承链或重签名。
- 开发包使用 `assembleDebug`，包名为 `com.r34v.r34v.debug`、名称为“R34V 开发版”，交付时必须标明开发包，不能冒充正式更新。当前开发签名仍来自运行用户的 `.android/debug.keystore`，尚未跨电脑锁定；需要跨工具/电脑覆盖开发包时，应复用同一份现有开发密钥并显式绑定 Gradle，禁止擅自删除、重建密钥或声称默认开发签名已统一。
- 交付前用 SDK 的 `apksigner verify --print-certs` 核验最终 APK，正式标准包须分别验证 API 24–27 和 API 28+，兼容包须验证 API 24–27；同时核对包名和版本。交付说明记录包类型、版本、证书 SHA-256 和校验结果。APK 文件 SHA-256 仅用于文件完整性，不能当作证书指纹判断签名是否一致。
- 详细背景见 [共享签名与覆盖升级](docs/signing.md)。修改本规范不代表已获得推送、发布或更换密钥的授权。
