import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    // Applied here only to lint the root build.gradle.kts and settings.gradle.kts.
    alias(libs.plugins.ktlint)
}

// Report-only, same settings as the app module. See docs/code-quality.md.
ktlint {
    version.set(libs.versions.ktlint.get())
    ignoreFailures.set(true)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}
