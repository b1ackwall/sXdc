# sXdc — Project Rules

sXdc (synthX Downloader Companion). Package `com.v1per.sxdc`. Fresh Android project, separate app from synthX — no shared code, dependencies, or design tokens.

## Testing / running the app

- Claude Code may run, install and test the app on a device or emulator by default when the task requires it. No per-prompt permission is needed.
- The default confirmation step for implementation tasks is still a build check: `./gradlew assembleDebug` (or equivalent build/lint/compile task). Device or emulator use is for when the task actually needs it.

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

### Dev environments

**XPS laptop (current)**
- OS: CachyOS (Arch-based), x86_64
- Shell: fish. Terminal: kitty.
- Repo path: /home/v1per/Projects/sXdc-Project/sXdc
- JDK: 17 (jdk17-openjdk, system default). JAVA_HOME is not set and not needed.
- Android SDK: $ANDROID_HOME = /home/v1per/Android/Sdk (platform 36, build-tools 36.0.0). adb is on PATH.
- Python 3.12 for Chaquopy comes from uv. The interpreter path is set per machine in local.properties (gitignored) with the key sxdc.buildPython.
- Android Studio is installed.

**DarkHorse desktop**
- TODO: fill in when set up. (CachyOS. Hardware and tool paths to be added.)

## Architecture

Lightweight — no strict MVVM ceremony required. Just clean, functional Kotlin + Jetpack Compose.

## Design

Dark plum theme with a cool accent (palette in `docs/code-quality.md`, "Color palette"), sharing an aesthetic direction with the synthX sister project. sXdc does not share code, dependencies, or design tokens with synthX — treat it as a fully separate app that happens to share an aesthetic direction.

## Git

- Claude Code may use read-only git commands only (status, log, diff, show, branch listing, tag listing).
- Claude Code must never run git write commands (add, commit, tag, branch create/delete, checkout/switch, merge, rebase, reset, stash, push, pull, etc.) unless the owner explicitly gives permission for that specific action in the prompt.

## Code quality

- `./scripts/check.sh` runs all checks (lint, tests, file size, ruff, ktlint, detekt); see docs/code-quality.md.
- Claude Code may run it after code changes.
- It must not run format or auto-fix tasks (ktlintFormat, lintFix, detekt --auto-correct, ruff --fix, ruff format) unless the prompt says so.

## File size

- No file may exceed 500 lines. Whenever Claude Code creates, edits or comes across a file longer than 500 lines, it must list the path and its line count in its final report. It reports only. It does not split or refactor the file unless the prompt says to.
