import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.io.File
import java.util.Properties

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
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

val sxdcBuildPython: String = run {
    val value = localProps.getProperty("sxdc.buildPython")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("SXDC_BUILD_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "python3.12"
    if (value.contains('/') && !File(value).exists()) {
        throw GradleException("sxdc.buildPython points to a missing file: $value (see docs/linux-setup.md)")
    }
    value
}

// Optional release signing (issue #73). Each item is read from local.properties first, then from
// the environment variable. All four set: the release build is signed. None set: it stays
// unsigned (app-release-unsigned.apk). Anything in between is a configuration error, reported
// by item name only, never by value, and raised only when a release task is in the task graph so
// debug builds are unaffected. Setup: docs/releasing.md.
class SigningItem(val label: String, val property: String, val env: String)

val signingItems = listOf(
    SigningItem("keystore path", "sxdc.signing.storeFile", "SXDC_KEYSTORE"),
    SigningItem("keystore password", "sxdc.signing.storePassword", "SXDC_KEYSTORE_PASSWORD"),
    SigningItem("key alias", "sxdc.signing.keyAlias", "SXDC_KEY_ALIAS"),
    SigningItem("key password", "sxdc.signing.keyPassword", "SXDC_KEY_PASSWORD"),
)
val signingValues: Map<String, String> = signingItems.mapNotNull { item ->
    val value = localProps.getProperty(item.property)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(item.env)?.trim()?.takeIf { it.isNotEmpty() }
    value?.let { item.property to it }
}.toMap()
val signingStoreFile: File? = signingValues["sxdc.signing.storeFile"]?.let {
    File(it).let { f -> if (f.isAbsolute) f else rootProject.file(it) }
}
val signingMissing: String = signingItems.filter { it.property !in signingValues }
    .joinToString { "${it.label} (${it.property} or ${it.env})" }
val signingProblem: String? = if (signingValues.isEmpty()) {
    null
} else if (signingValues.size < signingItems.size) {
    "Incomplete release signing configuration, missing: $signingMissing. " +
        "Set all four items or none; see docs/releasing.md."
} else if (signingStoreFile?.isFile != true) {
    "Release signing: the keystore file named by sxdc.signing.storeFile / SXDC_KEYSTORE " +
        "does not exist. Fix the path or unset all four signing items; see docs/releasing.md."
} else {
    null
}
val signingEnabled = signingValues.size == signingItems.size && signingProblem == null

gradle.taskGraph.whenReady {
    if (signingProblem != null && allTasks.any { it.project == project && it.name.contains("Release") }) {
        throw GradleException(signingProblem)
    }
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
        versionCode = 5
        versionName = "1.3"

        // Swaps in TestSxdcApp (androidTest): own database, no startup recovery, no permission prompt.
        testInstrumentationRunner = "com.v1per.sxdc.SxdcTestRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingEnabled) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingValues.getValue("sxdc.signing.storePassword")
                keyAlias = signingValues.getValue("sxdc.signing.keyAlias")
                keyPassword = signingValues.getValue("sxdc.signing.keyPassword")
            }
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
            if (signingEnabled) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        // ChromeOsAbiSupport: the app ships arm64-v8a only on purpose (Chaquopy restricted to arm64,
        // see abiFilters above), so the ChromeOS x86 advice does not apply.
        // OldTargetApi: targetSdk is bumped deliberately, with a behaviour-change review, not when
        // lint notices a newer platform.
        disable += listOf("ChromeOsAbiSupport", "OldTargetApi")
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

// ignoreFailures keeps the Gradle task from aborting so check.sh can count the findings and
// fail on any. See docs/code-quality.md.
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

// Findings fail scripts/check.sh, not the Gradle task (ignoreFailures). detekt is an alpha.
// Run the plain `detekt` task: hand-written main, test and
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
            // Exact versions and wheel hashes live in requirements-android.txt (yt-dlp, plus
            // mutagen: yt-dlp's EmbedThumbnail postprocessor has no ffmpeg or AtomicParsley
            // fallback for flac/ogg/opus and raises EmbedThumbnailPPError without it).
            // --require-hashes fails the build on any unhashed or mismatching package,
            // including a new transitive dependency. Update steps: docs/dependencies.md.
            options("--require-hashes")
            install("-r", "requirements-android.txt")
        }
    }
}

/**
 * Copies the repo's LICENSE and licenses/ (texts plus components.tsv) into a generated asset
 * directory, so the in-app Licenses screen reads the same files as the repository. Output lands
 * under assets/licenses/ in every variant's APK.
 */
abstract class CopyLicenseAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val licenseFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("licenses")
        target.deleteRecursively()
        target.mkdirs()
        val names = mutableSetOf<String>()
        licenseFiles.files.filter { it.isFile }.forEach { file ->
            check(names.add(file.name)) { "Duplicate license asset name: ${file.name}" }
            file.copyTo(target.resolve(file.name))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val copyTask = tasks.register<CopyLicenseAssets>(
            "copy${variant.name.replaceFirstChar { it.uppercase() }}LicenseAssets",
        ) {
            licenseFiles.from(
                rootProject.layout.projectDirectory.file("LICENSE"),
                rootProject.layout.projectDirectory.dir("licenses").asFileTree,
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopyLicenseAssets::outputDir)
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
