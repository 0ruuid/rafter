rootProject.name = "Rafter"

include("plugin")

include("project:api", "project:core", "project:pack", "project:authoring", "project:bukkit")

pluginManagement {
    repositories {
        maven("https://repo.hiusers.com/releases")
        maven("https://repo.tabooproject.org/repository/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

include("tools:authoring-cli")
