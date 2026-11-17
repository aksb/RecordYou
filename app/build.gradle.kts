import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 本地构建标识：配置阶段取一次时间，versionName 与产物文件名共用同一个值。
// CI 构建由工作流用 sed 写入版本号，环境变量 VERSION_NAME 存在即视为 CI。
val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
val isCi = System.getenv("VERSION_NAME") != null

// 正式签名：CI 读环境变量（GitHub Secrets），本地回退读 keystore.properties（已 gitignore）。
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName) ?: keystoreProps.getProperty(propName)

val releaseKeystoreFile = rootProject.file("RecordYou.jks")
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val canSignRelease = releaseKeystoreFile.exists() &&
    releaseStorePassword != null && releaseKeyPassword != null && releaseKeyAlias != null

android {
    namespace = "com.bnyro.recorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bnyro.recorder"
        minSdk = 21
        targetSdk = 34
        // CI 用 grep 抓这一行作基准（见 .github/workflows/ci.yml），必须保持字面量写法
        versionCode = 1
        versionName = "8.23.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
                keyAlias = releaseKeyAlias
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式包必须用项目自己的固定钥匙签名，用户才能覆盖升级。
            // 本地缺 RecordYou.jks 或 keystore.properties 时不签名，产物装不上属预期。
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            // 本地每次编译都带一个时间戳，方便在手机应用信息里认出装的是哪一次编的
            if (!isCi) {
                versionNameSuffix = ".dev$stamp"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.9"
    }
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 产物文件名带同一个时间戳（只影响命令行编译，不影响 Android Studio 的 Run 按钮）。
// AGP 8 的新 API 没有 outputFileName，只能继续用旧 API。
android.applicationVariants.all {
    val namePart = if (buildType.name == "debug") "debug" else "release-local"
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "app-$namePart-$stamp.apk"
    }
}

dependencies {
    // Core & runtime
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val media3Version = "1.2.1"

    // For media playback using ExoPlayer
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation-graphics")

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material:material-icons-extended")

    //Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Xposed 模块入口：仅编译期依赖，运行时由 LSPosed 注入，不能打进 APK
    compileOnly("de.robv.android.xposed:api:82")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
