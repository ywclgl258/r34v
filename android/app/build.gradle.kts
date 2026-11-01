import java.util.Properties
import java.io.File
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 签名配置从环境变量读（CI 用 Secrets 注入）。
// 本地没有这些变量时自动退回 debug 签名，不影响 `flutter run`。
val keystoreProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun envOrProp(key: String, fallback: String?): String? =
    System.getenv(key) ?: keystoreProps.getProperty(key) ?: fallback

/// 把路径归一化到 android/ 根下。
///
/// CI 传的是相对路径（`app/upload-keystore.jks`），而本文件跑在 app 模块里，
/// 直接 file(...) 会解析成 android/app/app/...。统一按 rootProject 解析。
///
/// 注意：这里必须 `import java.io.File` 后用短名。
/// 写全限定名 `java.io.File(...)` 在 Kotlin DSL 里会报
/// `Unresolved reference 'io'`——脚本编译期不认这种调用形式。
fun resolveKeystore(raw: String?): File? {
    if (raw.isNullOrBlank()) return null
    val f = File(raw)
    val resolved = if (f.isAbsolute) f else rootProject.file(raw)
    return if (resolved.exists()) resolved else null
}

val keystoreFile: File? = resolveKeystore(envOrProp("KEYSTORE_PATH", null))
val hasKeystore = keystoreFile != null

android {
    namespace = "com.r34v.r34v"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.r34v.r34v"
        // 24 = Android 7.0。覆盖面和参考项目一致；media_kit 的最低要求在这一档内。
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // 只打 arm64（现代手机全是这个架构）。
        // 注意：abiFilters 只对 AAR 里的 jni 生效，media_kit 是通过
        // jar 依赖塞 so 的，不吃这一套——真正滤掉它要靠下面的 packaging.excludes。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // media_kit 的 jar 把三个架构的 libmpv（各 11-15MB）全塞进来，
            // abiFilters 管不到 jar 来源，这里按路径排除。
            excludes += "/lib/armeabi-v7a/**"
            excludes += "/lib/x86_64/**"
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = envOrProp("KEYSTORE_PASSWORD", null)
                keyAlias = envOrProp("KEY_ALIAS", null)
                keyPassword = envOrProp("KEY_PASSWORD", null)
            }
        }
    }

    buildTypes {
        release {
            // 有签名密钥就用它（可覆盖安装），没有就退回 debug 签名（仅自用）
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
