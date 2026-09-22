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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same reasoning as app/build.gradle.kts: no dedicated keystore,
            // reuse the auto-generated debug signing config. What matters
            // for the Magisk-module trick is just that this stub and the
            // real release APK are signed with the same key *within a given
            // CI run* - see magisk-module/README.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
