# sXdc — Project Rules

sXdc (synthX Downloader Companion). Package `com.v1per.sxdc`. Fresh Android project, separate app from synthX — no shared code, dependencies, or design tokens.

## Testing / running the app

- Claude Code never runs, installs, or tests the app on any device or emulator. All on-device testing is done manually by , who reports results back.
- The confirmation step for implementation tasks is a build check only: `./gradlew assembleDebug` (or equivalent build/lint/compile task). Do not attempt to launch, install, or run the app, and do not start emulators.

## Build environment

- `AGP` 9.0.1
- `Kotlin` 2.0.21
- `Gradle` 9.1.0
- `Chaquopy` 17.0.0, restricted to `arm64-v8a` only
- `minSdk` 28, `compileSdk`/`targetSdk` 36
- Version catalog (`gradle/libs.versions.toml`) + `plugins{}` block style throughout — no legacy `buildscript{}`

Developed across two machines:
- XPS laptop — hostname `-XPS-15`, path `C:\XPS\v1per\sXdc`
- Desktop — hostname `V1PER` (personally referred to as "DarkHorse" — same machine, both names may appear)

## Architecture

Lightweight — no strict MVVM ceremony required. Just clean, functional Kotlin + Jetpack Compose.

## Design

Cyberpunk theme, carried over conceptually from the synthX sister project. sXdc does not share code, dependencies, or design tokens with synthX — treat it as a fully separate app that happens to share an aesthetic direction.

## Git workflow

Commit messages are single-line only — no multi-line commit message blocks.
