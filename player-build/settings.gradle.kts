pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "musicassistantplugin-player"
include(":bundle", ":player", ":shared", ":sdk")
project(":player").projectDir = file("../third_party/sendspindroid/android/player")
project(":shared").projectDir = file("../third_party/sendspindroid/android/shared")
