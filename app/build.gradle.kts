import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing keys live outside Git, in keystore.properties at the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without it the release APK is unsigned.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "io.github.duwenji.yorisoi.cycleguide"
    compileSdk = 36

    defaultConfig {
        // The Google Play ID: fixed once uploaded. The namespace and the Kotlin packages use the same name.
        applicationId = "io.github.duwenji.yorisoi.cycleguide"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"

        // The operator's AI (platform API-AI-RELAY): the development relay, as in SanpoGuide, until
        // production's is deployed. It takes this app once the console lists it (requirements X-5).
        buildConfigField("String", "AI_RELAY_URL", "\"https://teevxbmiaj.execute-api.ap-northeast-1.amazonaws.com/api/\"")
        // The Google Cloud project linked in Play Console for Play Integrity; 0 sends no token (the
        // development relay doesn't ask for one).
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "0L")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // The AI services' libraries (kit-llm) bring the same files more than once.
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Play services pull in a Fragment older than 1.3, too old for the ActivityResult API
    // (lint fails release builds with InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    // Only to name the app in the map's tile requests; the map itself is kit-geo's.
    implementation(libs.osmdroid.android)

    implementation(project(":prompts"))
    implementation(libs.yorisoi.kit.geo)
    implementation(libs.yorisoi.kit.companion)
    implementation(libs.yorisoi.kit.voice)
    implementation(libs.yorisoi.kit.llm)

    testImplementation(libs.junit)
    // The real org.json; Android's is a stub in local unit tests.
    testImplementation(libs.json)
}
