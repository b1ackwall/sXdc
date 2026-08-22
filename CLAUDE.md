# sXdc — Project Rules

sXdc (synthX Downloader Companion). Package `com.v1per.sxdc`. Fresh Android project, separate app from synthX — no shared code, dependencies, or design tokens.

## Testing / running the app

- Claude Code never runs, installs, or tests the app on any device or emulator. All on-device testing is done manually by , who reports results back.
- The confirmation step for implementation tasks is a build check only: `./gradlew assembleDebug` (or equivalent build/lint/compile task). Do not attempt to launch, install, or run the app, and do not start emulators.

## Build environment

- `AGP` 9.0.1
- `Kotlin` 2.2.10 — supplied by AGP 9's **built-in Kotlin** support, which is on by default and is why no `org.jetbrains.kotlin.android` plugin is applied anywhere. The `kotlin = "2.0.21"` entry in the version catalog is only the alias for the Compose plugin, and AGP forces even that up to 2.2.10; it is not the toolchain version.
- `Gradle` 9.7.0 (per `gradle/wrapper/gradle-wrapper.properties`)
- `KSP` 2.3.11 — needed for Room's annotation processor. Versions decoupled from the Kotlin compiler at KSP 2.3.0; AGP 9 + built-in Kotlin support landed in 2.3.1, and Google's migration guidance asks for ≥ 2.3.6. `kapt` is incompatible with built-in Kotlin — never add it.
- `Room` 2.8.4. Do not put `@Parcelize` on a Room `@Entity`: that exact combination fails KSP type resolution under AGP 9 built-in Kotlin (google/ksp#3053).
- `Chaquopy` 17.0.0, restricted to `arm64-v8a` only
- `minSdk` 28, `compileSdk`/`targetSdk` 36
- Version catalog (`gradle/libs.versions.toml`) + `plugins{}` block style throughout — no legacy `buildscript{}`
- Debug builds use applicationId `com.v1per.sxdc.debug` (via `applicationIdSuffix = ".debug"` on the `debug` build type) and launcher label "sXdc Dev" (via a `resValue("string", "app_name", "sXdc Dev")` override on that same build type); release uses `com.v1per.sxdc` and label "sXdc", consistent with synthX's own debug-naming convention. This lets a debug and a release install coexist on the same device, visually distinguishable in the app drawer. Any pre-existing debug install from before this change used the release applicationId and needs a clean reinstall — the old and new debug packages are different apps to Android, so data/permissions don't carry over automatically.

Developed across two machines:
- XPS laptop — hostname `-XPS-15`, path `C:\XPS\v1per\sXdc`
- Desktop — hostname `V1PER` (personally referred to as "DarkHorse" — same machine, both names may appear)

## Architecture

Lightweight — no strict MVVM ceremony required. Just clean, functional Kotlin + Jetpack Compose.

## Design

Cyberpunk theme, carried over conceptually from the synthX sister project. sXdc does not share code, dependencies, or design tokens with synthX — treat it as a fully separate app that happens to share an aesthetic direction.

## Git workflow

Commit messages are single-line only — no multi-line commit message blocks.
