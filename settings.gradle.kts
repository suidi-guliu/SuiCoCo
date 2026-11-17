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
        google()
        mavenCentral()
    }
}

rootProject.name = "Suicoco"

include(":app")

// :core:* — 纯逻辑层，永不依赖 :feature:*
include(":core:design")
include(":core:resource")
include(":core:fs")
include(":core:config")
include(":core:buffer")
include(":core:editor")
include(":core:term")
include(":core:distro")
include(":core:syntax")
include(":core:extapi")
include(":core:extengine")

// :feature:* — 功能 UI 层，只依赖 :core:*
include(":feature:explorer")
include(":feature:editorpane")
include(":feature:terminalpane")
include(":feature:marketplace")
include(":feature:onboarding")
include(":feature:settings")

// :native:* — 叶子模块，产出 .so（无其它模块依赖时保持叶子）
include(":native:proot")
include(":native:pty")
