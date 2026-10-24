import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// CycleGuide's prompt pack: the app's prompt files and its built-in channels. Plain JVM so the
// channel management system renders exactly the prompts the app sends (platform
// ADR-ECOSYSTEM-PLATFORM E-18, E-26: the representative app for `cycle`).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.duwenji.yorisoi.cycleguide"
version = providers.gradleProperty("cycleguidePromptsVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.yorisoi.core.format)
    api(libs.yorisoi.core.prompt)
    api(libs.yorisoi.kit.channel)

    testImplementation(libs.junit)
    // core-format reads JSON with org.json, which it leaves to its users.
    testImplementation(libs.json)
}

// A jar (or the APK) can't list a resource folder, so the built-in channels come with a list of
// their files (kit-channel's ResourceChannels.index).
val channelsDir = layout.projectDirectory.dir("src/main/resources/cycleguide/channels")
val channelIndexDir = layout.buildDirectory.dir("generated/channel-index")
val channelIndex by tasks.registering {
    inputs.dir(channelsDir)
    outputs.dir(channelIndexDir)
    doLast {
        val root = channelsDir.asFile
        val paths = root.walkTopDown().filter { it.isFile && it.name != "index.txt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        val out = channelIndexDir.get().file("cycleguide/channels/index.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(paths.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main {
    resources.srcDir(channelIndex)
}
