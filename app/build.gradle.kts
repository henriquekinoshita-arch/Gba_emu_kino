import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ABIs we ship. Matches the set built for the NDS core below.
val targetAbis = listOf("arm64-v8a", "armeabi-v7a")

android {
    namespace = "com.kino.gbaemu"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.kino.gbaemu"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += targetAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}

// ---------------------------------------------------------------------------
// The NDS core (melondsds_libretro) cannot be built via CMake add_subdirectory
// from src/main/cpp/CMakeLists.txt: its own CMakeLists.txt hardcodes several
// include/config-file paths off CMAKE_SOURCE_DIR, which only resolves
// correctly when melonds-ds is configured as its own top-level CMake project.
// So we drive it here instead, once per target ABI, as a fully independent
// CMake configure+build using the same NDK the main native build uses, and
// drop the renamed output straight into src/main/jniLibs/<abi>/, which AGP
// packages automatically without any further wiring.
// ---------------------------------------------------------------------------
val melonDsSourceDir = file("${rootProject.projectDir}/external/melonds-ds")
val melonDsBuildRoot = File(layout.buildDirectory.get().asFile, "melonds-ds")
val jniLibsDir = file("src/main/jniLibs")

val buildMelonDsCores = tasks.register("buildMelonDsCores") {
    val producedSoFiles = targetAbis.map { abi -> File(jniLibsDir, "$abi/libkino_core_nds.so") }
    outputs.files(producedSoFiles)
    inputs.dir(melonDsSourceDir)

    doLast {
        val ndkDir = android.ndkDirectory
        val toolchainFile = File(ndkDir, "build/cmake/android.toolchain.cmake")
        check(toolchainFile.exists()) { "NDK toolchain file not found at $toolchainFile" }

        val cmakeExecutable = findCmakeExecutable()
        // Deliberately NOT sharing FETCHCONTENT_BASE_DIR across ABIs: each
        // dependency's FetchContent-managed "-build" directory (compiled
        // objects) lives under that same base dir alongside its source, and
        // reusing it across a different ANDROID_ABI/toolchain would hand
        // CMake a build tree configured for the wrong target - it either
        // errors out on the toolchain mismatch or, worse, doesn't. Every ABI
        // gets its own full FetchContent tree instead.

        targetAbis.forEach { abi ->
            val abiBuildDir = File(melonDsBuildRoot, abi)
            abiBuildDir.mkdirs()

            val configureArgs = listOf(
                cmakeExecutable,
                "-S", melonDsSourceDir.absolutePath,
                "-B", abiBuildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_AS_SHARED_LIBRARY=ON",
                "-DENABLE_JIT=OFF",
                "-DENABLE_OPENGL=OFF",
                "-DTRACY_ENABLE=OFF",
                "-DBUILD_TESTING=OFF"
            )
            runProcess(configureArgs, abiBuildDir)

            val buildArgs = listOf(
                cmakeExecutable,
                "--build", abiBuildDir.absolutePath,
                "--target", "melondsds_libretro",
                "--parallel"
            )
            runProcess(buildArgs, abiBuildDir)

            val builtSo = File(abiBuildDir, "src/libretro/melondsds_libretro_android.so")
            check(builtSo.exists()) { "Expected melonDS output not found at $builtSo" }

            val destDir = File(jniLibsDir, abi)
            destDir.mkdirs()
            builtSo.copyTo(File(destDir, "libkino_core_nds.so"), overwrite = true)
        }
    }
}

fun findCmakeExecutable(): String {
    val sdkCmakeDir = File(File(System.getenv("ANDROID_HOME") ?: ""), "cmake")
    val versionDirs = sdkCmakeDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    val binary = versionDirs.firstOrNull()?.let { File(it, "bin/cmake") }
    return if (binary != null && binary.exists()) binary.absolutePath else "cmake"
}

fun runProcess(args: List<String>, workingDir: File) {
    val process = ProcessBuilder(args)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().forEachLine { println(it) }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${args.joinToString(" ")}" }
}

tasks.named("preBuild") {
    dependsOn(buildMelonDsCores)
}
