plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voiability"
    compileSdk = 37
    // Same NDK as :engine so packaging can strip native debug symbols.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.voiability"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // CI release key, supplied through environment variables (see .github/workflows/release.yml).
        System.getenv("VOI_KEYSTORE")?.takeIf { file(it).exists() }?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("VOI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOI_KEY_ALIAS")
                keyPassword = System.getenv("VOI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload-friendly: signed with the debug key unless a release key is configured.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}


dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.shapes)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Host-built native engine for HostPipelineTest (see that file).
    System.getenv("VOI_HOST_LIB")?.let { systemProperty("java.library.path", it) }
    listOf("VOI_MODELS", "VOI_WAV").forEach { key -> System.getenv(key)?.let { environment(key, it) } }
    testLogging { showStandardStreams = true }
}
