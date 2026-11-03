# 共享签名与覆盖升级

唯一活动签名目录由 `R34V_SIGNING_HOME` 指定，默认是用户目录下 `.r34v/signing`。本机为 `C:\Users\ywclg\.r34v\signing`。其中包含 `release.jks`、`historical.jks` 和 `signing.properties`；密码只在此本地配置或 CI Secrets 中读取，不写入项目、日志、命令参数或 Git。

不同 IDE、开发工具和工作副本一律调用项目 Gradle，读取相同的签名目录。不要为新工具生成新密钥；需要迁移电脑时，安全复制整份共享签名目录。旧项目内的活动配置已移入该目录的 `retired-project-config` 存档，Gradle 不再读取它。

## 固定身份

正式包名始终为 `com.r34v.r34v`。公开身份和递增版本号维护在根目录 `release.properties`：

| 来源 | SHA-256 指纹前缀 |
| --- | --- |
| 1.18.0 发布签名 | `73543870ea2d…` |
| 2.0.0 发布签名 | `75f3270c0914…` |

`validateReleaseSigning` 校验两份证书的完整指纹。缺配置、别名不匹配或密钥变更都会阻止发布构建。开发版具有独立包名和清楚的应用名称，不作为正式升级包。

## 升级策略

`assembleRelease` 会自动执行 `signUpgradeRelease`，用已验证的旧、新密钥和公开的 `app/signing/upgrade.lineage` 生成签名继承包：Android 7/8 使用原 1.18.0 签名，Android 9 及以上使用 2.0.0 签名并附带从原签名继承的证明。这个行为使用 [Android 官方 apksigner 的密钥轮换机制](https://developer.android.com/tools/apksigner)。

Android 9 及以上两种已发布签名都能直接升级标准包；Android 7/8 无法使用同一继承机制，已装 2.0.0 的设备使用同时生成的 `*-compat-2.0.0.apk`。应用内更新会根据系统版本和现有签名选择，并在调用安装器前核验更新包。未来发版应同时保留这两个产物，而不是换密钥或改包名。

## CI

现有 `KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` 对应固定的 `75f327…` 密钥；`LEGACY_KEYSTORE_BASE64 / LEGACY_KEYSTORE_PASSWORD / LEGACY_KEY_ALIAS / LEGACY_KEY_PASSWORD` 对应 `735438…` 密钥。工作流解码到 runner 的同一共享目录，仍执行相同的指纹校验和签名继承步骤。仓库中只包含工作流和公开证明，不包含任何私钥或密码。

本轮没有修改远端 Secrets 或发布文件；远端配置仍需按此补齐。不要直接沿用可能指向旧证书的 Secret 名称而跳过指纹检查。

发布含签名修复的版本时先上传标准 APK，再上传补充兼容包；旧 1.18.0 的更新器会选择 Release 中第一个 APK。新更新器已按平台和现有签名选包。

## 已执行验证

- 隔离 Android 16 模拟器：安装原 1.18.0，写入合成收藏和观看记录，再用 `adb install -r` 升级 2.0.1；安装成功，迁移后两类数据完整保留。
- 现有 Android 16 模拟器：2.0.0 直接覆盖安装 2.0.1 成功。
- `apksigner verify` 分别以 API 24–27 和 API 28 及以上验证标准 APK，证书分别与上述旧、新身份相符。
- Android 7/8 的运行时未在本轮启动实机或对应系统镜像验证；兼容包已做签名静态校验。
- 将签名目录指向空目录，验证发布构建失败；把当前密钥错误指向旧密钥且提供其正确密码，验证指纹检查仍拒绝构建。不会生成另一份正式签名。
