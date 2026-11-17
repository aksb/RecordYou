pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 传统 Xposed API（仅编译期使用，运行时由 LSPosed 提供）
        maven {
            url = uri("https://api.xposed.info/")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Record You"
include(":app")
include(":magisk-placeholder")
