import java.util.Properties

plugins {
    id("com.android.application")
}

// This module produces a tiny, code-free "stub" APK for the RecordYou
// keep-alive Magisk module (see /magisk-module). It carries only the real
// app's package name (applicationId) - no activities, no services, no
// resources beyond the manifest.
//
// The Magisk module installs this stub into /system/priv-app, which is what
// lets Android grant RecordYou the privileged permission it needs
// (CAPTURE_AUDIO_OUTPUT, for recording internal audio) - see
// /magisk-module/system/etc/permissions/privapp-permissions-recordyou.xml.
// Once the module is flashed, the *real* RecordYou APK is installed/updated
// over this stub the normal way (sideloaded, or via the Release APK) and
// keeps its privileged status as long as the package name matches and this
// stub's versionCode never exceeds the real app's - so this module should
// essentially never need to change once it works, regardless of how many
// times RecordYou itself gets released.
// 与 app 模块共用同一把正式签名：占位包要装进 /system/priv-app，
// 真包覆盖安装时签名必须完全一致，否则特权链断裂、正式版装不上。
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

val releaseKeystoreFile = rootProject.file("RecordYou.jks")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val canSignRelease = releaseKeystoreFile.exists() &&
    releaseStorePassword != null && releaseKeyPassword != null && releaseKeyAlias != null

android {
    namespace = "com.bnyro.recorder.placeholder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bnyro.recorder"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
            isMinifyEnabled = false
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
