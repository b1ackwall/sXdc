plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.v1per.sxdc"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.v1per.sxdc"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // ffmpeg/ffprobe are shipped as lib*.so so the platform installer places them
            // in nativeLibraryDir, the only app-readable location Android still allows
            // execve() from (W^X, see FfmpegBinaries). They must be extracted to real
            // files on disk rather than mapped out of the APK.
            // MUST stay explicit. AGP 9 defaults this to false for *both* debug and release,
            // which leaves the binaries compressed inside the APK with no on-disk path in
            // nativeLibraryDir — FfmpegBinaries then falls through to a filesDir copy that
            // Android refuses to exec (W^X). Verified by removal: default is false.
            useLegacyPackaging = true

            // These are complete executables, not libraries. Never let the NDK strip tool
            // rewrite the pre-verified FFmpeg 8.1.1 bytes.
            keepDebugSymbols += listOf("**/libffmpeg.so", "**/libffprobe.so")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // On V1PER, "py" resolves to the Windows app-execution-alias
        // (...\WindowsApps\py.exe), which is unreliable when spawned from Gradle's
        // non-interactive daemon even though it works fine in an interactive shell.
        // Pointing straight at the real interpreter sidesteps the alias entirely.
        // Path is specific to V1PER (via `py install 3.12`) -- update if built elsewhere.
        buildPython("C:\\Users\\\\AppData\\Local\\Python\\pythoncore-3.12-64\\python.exe")
        pip {
            install("yt-dlp")
            // Not optional for us: yt-dlp's EmbedThumbnail postprocessor has no ffmpeg or
            // AtomicParsley fallback for flac/ogg/opus -- it raises EmbedThumbnailPPError
            // outright when mutagen is missing. Pure-Python wheel, so no ABI concerns.
            install("mutagen")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // SAF destination folder: DocumentFile wraps the DocumentsContract tree/child plumbing.
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Icons.Filled.Settings for the MainActivity top-bar entry point.
    implementation(libs.androidx.compose.material.icons.core)
    // Persistent download queue. room-ktx is not optional here: Room's generated code for a
    // suspend @Transaction function calls androidx.room.withTransaction, which ships in it.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}