import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.voicepersona.llm"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16"
                arguments += "-DBUILD_SHARED_LIBS=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                // Release posture: no debug info in the shipped objects. The
                // default externalNativeBuild type is Debug, which carried -g
                // and left ~93 MB of DWARF in the libraries.
                cppFlags += listOf("-O2", "-fno-unwind-tables", "-fno-asynchronous-unwind-tables")
                cFlags += listOf("-O2")
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

/**
 * Strips the built .so files and stages them where AGP merges jniLibs.
 *
 * AGP's own stripReleaseDebugSymbols task declined to touch these libraries
 * ("Unable to strip the following libraries, packaging them as they are"),
 * which shipped tens of MB of DWARF in the APK. The work is delegated to
 * strip_native_libs.sh, which is testable on its own and fails loudly when a
 * strip does nothing.
 */
val stripNativeLibs by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine(
        "bash",
        "strip_native_libs.sh",
        layout.buildDirectory.dir("intermediates/cxx/Release").get().asFile.absolutePath,
        layout.buildDirectory
            .dir("intermediates/stripped_native_libs/release/out/lib/arm64-v8a")
            .get().asFile.absolutePath
    )
    isIgnoreExitValue = false
}

tasks.matching { it.name == "mergeReleaseNativeLibs" }.configureEach {
    dependsOn(stripNativeLibs)
}
