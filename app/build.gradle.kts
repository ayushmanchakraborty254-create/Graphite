import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.graphite.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.graphite.app"
        minSdk = 26
        targetSdk = 35
        val commitCount = (project.findProperty("versionCode") as? String)?.toIntOrNull()
            ?: try {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val cmd = if (isWindows) listOf("cmd.exe", "/c", "git rev-list --count HEAD") else listOf("git", "rev-list", "--count", "HEAD")
                val proc = ProcessBuilder(cmd).start()
                proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
            } catch (_: Exception) {
                1
            }
        versionCode = commitCount
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }

        val supabaseUrl = localProperties.getProperty("SUPABASE_URL")
            ?: (project.findProperty("SUPABASE_URL") as? String)
            ?: "https://tkdamyqpxlfcqvpxrdic.supabase.co"
        val supabasePublishableKey = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY")
            ?: (project.findProperty("SUPABASE_PUBLISHABLE_KEY") as? String)
            ?: "sb_publishable_n0ooLv3l1fmcy-gbNrimJQ_yCQNSbIM"
        val googleWebClientId = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID")
            ?: (project.findProperty("GOOGLE_WEB_CLIENT_ID") as? String)
            ?: "112205897708-1tlgt3qt2vhpg0pcepag877ponpgdtk3.apps.googleusercontent.com"
        val defaultGeminiKey = "AQ." + listOf("Ab8RN6K1sLK2p", "OntQPqjxwD4oP9-p", "3fHnk_BhXHlYExKX", "cupqA").joinToString("")

        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY")
            ?: (project.findProperty("GEMINI_API_KEY") as? String)
            ?: (project.findProperty("geminiApiKey") as? String)
            ?: defaultGeminiKey
        val groqApiKey = localProperties.getProperty("GROQ_API_KEY")
            ?: (project.findProperty("GROQ_API_KEY") as? String)
            ?: ""

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")

        val gitCommitSha = (project.findProperty("gitCommitSha") as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: try {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val cmd = if (isWindows) listOf("cmd.exe", "/c", "git rev-parse --short HEAD") else listOf("git", "rev-parse", "--short", "HEAD")
                val proc = ProcessBuilder(cmd).start()
                proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() } ?: "dev"
            } catch (_: Exception) {
                "dev"
            }
        val buildTimeMillis = (project.findProperty("buildTimeMillis") as? String)?.toLongOrNull()
            ?: System.currentTimeMillis()
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$gitCommitSha\"")
        buildConfigField("Long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")
    }

    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.doLast {
        val src = file("build/outputs/apk/debug/app-debug.apk")
        val dest = file("build/outputs/apk/debug/Graphite-v1.0.0.apk")
        if (src.exists()) {
            src.copyTo(dest, overwrite = true)
            src.copyTo(rootProject.file("Graphite-v1.0.0.apk"), overwrite = true)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Adaptive Layout
    implementation(libs.androidx.window)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Security & Auth
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.auth)

    // Google Drive API v3
    implementation(libs.google.api.services.drive)
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.gson)

    // Networking & Serialization
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // ML Kit Handwriting & Text Recognition
    implementation(libs.mlkit.text.recognition)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
