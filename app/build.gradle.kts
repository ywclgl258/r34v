import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

plugins { id("com.android.application") }

val signingHome = System.getenv("R34V_SIGNING_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
    ?: File(System.getProperty("user.home"), ".r34v/signing")
val signing = Properties().apply { File(signingHome, "signing.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
val identity = Properties().apply { rootProject.file("release.properties").inputStream().use { load(it) } }
fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: signing.getProperty(name)
fun keyFile(name: String): File? = signingValue(name)?.let { raw -> File(raw).let { if (it.isAbsolute) it else File(signingHome, raw) } }?.takeIf { it.isFile }
val releaseKey = keyFile("KEYSTORE_PATH")

android {
    namespace = "com.r34v.r34v"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = identity.getProperty("applicationId")
        minSdk = 24
        targetSdk = 36
        versionCode = identity.getProperty("versionCode").toInt()
        versionName = identity.getProperty("versionName")
        buildConfigField("String", "RELEASE_CERTIFICATE", "\"${identity.getProperty("releaseCertificate")}\"")
        buildConfigField("String", "HISTORICAL_CERTIFICATE", "\"${identity.getProperty("historicalCertificate")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; resValues = true }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = signingValue("KEYSTORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; resValue("string", "app_name", "R34V 开发版") }
        release {
            resValue("string", "app_name", "R34V")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { abortOnError = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(identity.getProperty("applicationId") == "com.r34v.r34v") { "正式包名不可变更" }
        for ((prefix, expected) in listOf("" to "releaseCertificate", "LEGACY_" to "historicalCertificate")) {
            val file = keyFile("${prefix}KEYSTORE_PATH") ?: error("缺少固定发布密钥，请配置 $signingHome/signing.properties；禁止临时生成密钥")
            val password = signingValue("${prefix}KEYSTORE_PASSWORD") ?: error("签名配置缺少密码")
            val alias = signingValue("${prefix}KEY_ALIAS") ?: error("签名配置缺少别名")
            val keyStore = KeyStore.getInstance(file, password.toCharArray())
            val certificate = keyStore.getCertificate(alias) ?: error("发布密钥中不存在指定别名")
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
            check(digest == identity.getProperty(expected)) { "发布证书指纹不匹配，已阻止生成不可覆盖安装的包" }
        }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(validateReleaseSigning) }

val signUpgradeRelease = tasks.register("signUpgradeRelease") {
    dependsOn("packageRelease", validateReleaseSigning)
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val compatibilityApk = File(apk.parentFile, "R34V-v${identity.getProperty("versionName")}-compat-2.0.0.apk")
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val signer = File(sdk, "build-tools/${android.buildToolsVersion}/lib/apksigner.jar")
        val java = File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}")
        val environment = mapOf(
            "R34V_CURRENT_STORE" to signingValue("KEYSTORE_PASSWORD")!!,
            "R34V_CURRENT_KEY" to signingValue("KEY_PASSWORD")!!,
            "R34V_HISTORICAL_STORE" to signingValue("LEGACY_KEYSTORE_PASSWORD")!!,
            "R34V_HISTORICAL_KEY" to signingValue("LEGACY_KEY_PASSWORD")!!)
        fun runSigner(args: List<String>) {
            val process = ProcessBuilder(listOf(java.absolutePath, "-jar", signer.absolutePath) + args).redirectErrorStream(true)
            process.environment().putAll(environment)
            val running = process.start(); val output = running.inputStream.bufferedReader().readText()
            check(running.waitFor() == 0) { "签名校验失败：$output" }
        }
        fun signerArgs(legacy: Boolean): List<String> {
            val prefix = if (legacy) "LEGACY_" else ""
            val env = if (legacy) "HISTORICAL" else "CURRENT"
            return listOf("--ks", keyFile("${prefix}KEYSTORE_PATH")!!.absolutePath, "--ks-key-alias", signingValue("${prefix}KEY_ALIAS")!!,
                "--ks-pass", "env:R34V_${env}_STORE", "--key-pass", "env:R34V_${env}_KEY")
        }
        runSigner(listOf("sign", "--v4-signing-enabled", "false", "--out", compatibilityApk.absolutePath) + signerArgs(false) + apk.absolutePath)
        runSigner(listOf("sign", "--v4-signing-enabled", "false", "--rotation-min-sdk-version", "28", "--lineage", rootProject.file("app/signing/upgrade.lineage").absolutePath) +
            signerArgs(true) + "--next-signer" + signerArgs(false) + apk.absolutePath)
        runSigner(listOf("verify", apk.absolutePath)); runSigner(listOf("verify", compatibilityApk.absolutePath))
        logger.lifecycle("发布签名继承校验通过；正式 APK 和旧 2.0.0 兼容 APK 已生成")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(signUpgradeRelease) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-database:1.9.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
