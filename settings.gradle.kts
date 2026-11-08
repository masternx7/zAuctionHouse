rootProject.name = "zAuctionHouseV4"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        maven {
            name = "groupezReleases"
            url = uri("https://repo.groupez.dev/releases")
        }
        gradlePluginPortal()
    }
}


include("API")

// Hooks that require a gradle.properties flag to be enabled
val conditionalHooks = mapOf(
    "ZelAuction" to "hooks.zelauction",
    "DonutAuction" to "hooks.donutauction",
    "CrazyAuctions" to "hooks.crazyauctions"
)

file("Hooks").listFiles()?.forEach { file ->
    if (file.isDirectory and !file.name.equals("build")) {
        val propertyKey = conditionalHooks[file.name]
        if (propertyKey != null) {
            val enabled = settings.providers.gradleProperty(propertyKey).getOrElse("false").toBoolean()
            if (!enabled) {
                println("Skip Hooks:${file.name} ($propertyKey=false)")
                return@forEach
            }
        }
        println("Include Hooks:${file.name}")
        include(":Hooks:${file.name}")
    }
}
