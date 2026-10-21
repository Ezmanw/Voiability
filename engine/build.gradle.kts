plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voiability.engine"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_static",
                )
                cppFlags += listOf("-O3")
                cFlags += listOf("-O3")
            }
        }
    }

    buildTypes {
        // Native code is always built optimized; a debug ggml is unusably slow.
        debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
