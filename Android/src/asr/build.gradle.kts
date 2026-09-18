plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.voicepersona.asr"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16"
                arguments += "-DBUILD_SHARED_LIBS=OFF"
                arguments += "-DTRANSCRIBE_BUILD_TESTS=OFF"
                arguments += "-DTRANSCRIBE_BUILD_EXAMPLES=OFF"
                arguments += "-DTRANSCRIBE_BUILD_TOOLS=OFF"
                arguments += "-DTRANSCRIBE_USE_SYSTEM_BLAS=OFF"
                arguments += "-DTRANSCRIBE_USE_OPENMP=OFF"
                cppFlags += "-O3"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
