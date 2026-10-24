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
        // The yorisoi platform. Reading GitHub Packages needs a token with
        // read:packages: $GITHUB_TOKEN in CI, or gpr.user / gpr.key in ~/.gradle/gradle.properties.
        // Without one, only versions published locally (`./gradlew publishToMavenLocal` in
        // yorisoi-platform) are used.
        val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
        val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        if (gprUser != null && gprKey != null) {
            maven {
                name = "yorisoiPlatform"
                url = uri("https://maven.pkg.github.com/duwenji/yorisoi-platform")
                credentials {
                    username = gprUser
                    password = gprKey
                }
                content { includeGroup("io.github.duwenji.yorisoi") }
            }
        }
        mavenLocal {
            content { includeGroup("io.github.duwenji.yorisoi") }
        }
    }
}
rootProject.name = "CycleGuide"
// CycleGuide and its prompt pack. The platform comes from GitHub Packages (gradle/libs.versions.toml).
include(":app")
include(":prompts")

// To change the platform and the app together, build against yorisoi-platform's sources instead of
// the published version: -Pyorisoi.platformDir=../yorisoi-platform, or set it in
// ~/.gradle/gradle.properties. Never set in CI.
providers.gradleProperty("yorisoi.platformDir").orNull?.let { includeBuild(it) }
