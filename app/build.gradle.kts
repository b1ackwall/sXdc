import java.io.File
import java.util.Properties
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Python 3.12 interpreter used by Chaquopy at build time. Resolution order:
// 1) local.properties key sxdc.buildPython (per machine, gitignored)
// 2) env var SXDC_BUILD_PYTHON
// 3) python3.12 on PATH
val sxdcBuildPython: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    val value = props.getProperty("sxdc.buildPython")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("SXDC_BUILD_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "python3.12"
    if (value.contains('/') && !File(value).exists()) {
        throw GradleException("sxdc.buildPython points to a missing file: $value (see docs/linux-setup.md)")
    }
    value
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
        versionCode = 4
        versionName = "1.2.0"

        // Swaps in TestSxdcApp (androidTest): own database, no startup recovery, no permission prompt.
        testInstrumentationRunner = "com.v1per.sxdc.SxdcTestRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "sXdc Dev")
            // AGP built-in JaCoCo coverage (no extra plugin). Makes the debug APK an
            // instrumented build; reports via create{DebugUnitTest,DebugAndroidTest}CoverageReport.
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
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
        buildConfig = true
        resValues = true
    }

    testOptions {
        unitTests {
            // Plain-JVM unit tests run against android.jar stubs. Without this, any incidental
            // framework call such as android.util.Log throws "Stub!" instead of returning a default.
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Existing findings live here; regenerate with ./gradlew updateLintBaselineDebug.
        baseline = file("lint-baseline.xml")
        // Version-freshness checks depend on the network and the date. Keep them visible in the
        // report but never let them fail a build.
        informational += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
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

// Report-only for now: findings never fail the build. See docs/code-quality.md.
ktlint {
    version.set(libs.versions.ktlint.get())
    ignoreFailures.set(true)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
    filter {
        // Never lint generated code (KSP/Room output, BuildConfig, R) under build/.
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

// Report-only for now (alpha). Run the plain `detekt` task: hand-written main, test and
// androidTest sources only, never generated code. See docs/code-quality.md.
detekt {
    toolVersion = libs.versions.detekt.get()
    source.setFrom("src/main/java", "src/test/java", "src/androidTest/java")
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    ignoreFailures = true
    // Relative paths in reports and the baseline, so they are the same on every machine.
    basePath.set(rootDir)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(sxdcBuildPython)
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
    // collectAsStateWithLifecycle for the home screen's live status/history feeds.
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    // Foundation for the queue-engine tests (runTest, TestDispatcher).
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}