# R34V development rules

- 本项目为 Kotlin 原生 Android 工程，保持 `com.r34v.r34v` 正式包名。
- 所有工具通过本仓库 Gradle 构建。发布密钥统一从 `R34V_SIGNING_HOME/signing.properties` 读取；未设置时使用用户目录 `.r34v/signing/signing.properties`。
- 禁止生成替代发布密钥、回退 debug 签名或在仓库内保存私钥/密码。指纹必须匹配 `release.properties`，否则停止发布构建。
- `assembleRelease` 必须保留签名继承链；交付标准 APK，另有 Android 7/8 上已装 2.0.0 用户专用的兼容 APK。开发包名称为“R34V 开发版”，不能冒充正式更新。
- 每次正式更新递增 `release.properties` 中的 versionCode。主题保留黑底；用户已授权在本轮 UI/UX 重构中调整强调色、主按钮颜色与控件样式，并要求更多参考 Netflix App。
- 旧源码仅作仓库外本地归档，不纳入 Git。未经用户指示不推送或发布。
