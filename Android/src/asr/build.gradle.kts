import java.io.File

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
            abiFilters += listOf("arm64-v8a")
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
                # Release posture: no debug info in the shipped objects. The
                # default externalNativeBuild type is Debug, which carries
                # -g and left ~93 MB of DWARF in the .so files.
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
 * Strips the built .so and refreshes the JNI libs folder.
 *
 * AGP's own stripReleaseDebugSymbols task decides the NDK strip tool is too
 * old and packages the library untouched, which left tens of MB of DWARF in
 * the APK. Doing it here with --strip-unneeded keeps every dynamic symbol
 * (verified: JNI entry points still resolve through dlsym).
 */
val stripNativeLibs by tasks.registering {
    val ndkDir = android.ndkDirectory
    val stripTool = File(ndkDir, "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
    val builtLibs = layout.buildDirectory.dir("intermediates/cxx/Release")
    val jniOut = layout.buildDirectory.dir("intermediates/stripped_native_libs/release/out/lib/arm64-v8a")

    doLast {
        val sources = builtLibs.get().asFileTree.matching { it.include("**/$lib") }.files
        if (sources.isEmpty()) {
            throw GradleException("native library not found under ${builtLibs.get().asFile}")
        }
        if (!stripTool.exists()) {
            throw GradleException("llvm-strip not found at $stripTool")
        }
        val outDir = jniOut.get().asFile
        outDir.mkdirs()
        sources.forEach { src ->
            val dst = File(outDir, src.name)
            dst.writeBytes(src.readBytes())
            providers.exec {
                commandLine(stripTool.absolutePath, "--strip-unneeded", dst.absolutePath)
            }.result.get().assertNormalExitValue()
            logger.lifecycle("stripped ${dst.name}: ${src.length() / 1024} KiB -> ${dst.length() / 1024} KiB")
        }
    }
}

tasks.matching { it.name == "mergeReleaseNativeLibs" }.configureEach {
    dependsOn(stripNativeLibs)
}
