# sXdc test-suite discovery

- **Date:** 2026-10-31
- **Branch:** onepointthree
- **Note:** This is a read-only audit snapshot; line numbers may drift as code changes.

## Audit notes

No repo file was created, edited or deleted during the audit, and no state-changing git command ran. Three things happened outside that:

- **`gradlew` was invoked as `bash ./gradlew`.** It is not executable: mode `100644` in git (`git ls-files -s gradlew`), `-rw-r--r--` on disk. It was not chmod'ed, because that would edit the repo.
- **Gradle installed an SDK package outside the repo.** The first run auto-installed "Android SDK Platform 36 (rev 2)" into `/home/v1per/Android/Sdk/platforms/android-36`.
- **Gradle downloaded its own distribution.** It fetched `gradle-9.7.0-bin.zip`.

---

## Summary

### Overall coverage estimate by layer

| Layer | Coverage |
|---|---|
| Pure Kotlin logic | none |
| Room | none |
| Service / lifecycle | none |
| Python | none |
| UI | none |
| Notifications | none |

The only two tests are unmodified templates, and the instrumented one is probably wrong for the debug build.

### Top 10 untested behaviors by risk (likelihood of regression × cost during daily use)

1. Dedup and enqueue under rapid shares (`countPendingForUrl` plus the Mutex). Duplicate downloads or lost links.
2. Drain loop STOP/CONTINUE rules (`runJob`, `Outcome`). A wrong rule either silently drops the queue or burns it on one bad URL.
3. SAF copy, size verification and staging cleanup (`moveToDestination`). Data-loss risk, since a staged file is deleted after the copy.
4. Crash-recovery SQL ordering and the 3-attempt cap (`recoverOrphans`). Off-by-one or ordering bugs would loop or lose jobs.
5. `claimNext` / status transitions, including `cancelAllPending` (QUEUED only) and `clearHistory`.
6. Python `classify()` marker ordering. `ffmpeg` / `connection` / `ssl` substring matching can misclassify, and the user then sees the wrong message.
7. URL extraction from shared text. A regression here means shares silently do nothing or open the fix-it screen.
8. Preconditions and fix-it trigger (folder grant, ffmpeg usability). The share flow either blocks or fails late.
9. FGS start-from-resume and MainActivity resume restart. This breaks on Android 12+ with an exception, and it is invisible until a device test.
10. `expand_urls` playlist handling and the preview-versus-real-options drift (`YtDlpCommandPreview` vs `download_audio`).

### Flaky or low-value existing tests

`ExampleUnitTest.addition_isCorrect` (can't fail) and `ExampleInstrumentedTest.useAppContext` (stale package name, would fail on the debug build). Neither is flaky, since neither does anything non-deterministic.

### Behaviors blocked from JVM testing, and the missing seam

| Seam | Blocked behaviors |
|---|---|
| Python bridge interface (direct `Python.getInstance()`) | #4 Kotlin side, #7, #14, #15, #17 |
| `getString` / Context injection (string provider) | #14, #17 (Kotlin mapping), #18 |
| Dispatcher / scope injection (hard-coded `Dispatchers.IO`, `appScope`) | #7, #8, #16, #21 |
| DAO / database provider (static singleton, fixed name, abstract class) | #5-8 on the JVM, #20 |
| Extractable queue/drain component (logic lives in `Service`) | #5, #7, #14, #15 |
| SAF copy abstraction (`DocumentFile` / `ContentResolver`) | #13 |
| Pure URL parser (embedded in the Activity, uses `Patterns`) | #1 |
| Settings preview constant shared with Python options | #10 |
| Build seam: a Python 3.12 `buildPython` available on Linux | **Everything**, including `testDebugUnitTest` |

### Realistic test type per layer

- **JVM-only:** a URL parser once extracted, `terminalId`, `StagingFiles`, the preview string, and DAO/drain logic once behind interfaces. `Patterns.WEB_URL` would need Robolectric or `returnDefaultValues`.
- **Instrumented (on-device, run manually per CLAUDE.md):** Room DAO against a real SQLite, SAF copy, FfmpegBinaries symlinks, notification channels and ids, FGS-from-resume, and Activity flows (fix-it, Settings, MainActivity panels).
- **Python tests (pytest, host-side):** `classify`, `build_ydl_opts`, and `expand_urls` / `download_audio` with yt-dlp stubbed. Live-URL behavior stays manual.

---

## PART 1: Test infrastructure inventory

### 1.1 Test source sets

| Path | Lines | Test methods | Framework |
|---|---|---|---|
| `app/src/test/java/com/v1per/sxdc/ExampleUnitTest.kt` | 16 | 1 (`addition_isCorrect`) | JUnit 4 |
| `app/src/androidTest/java/com/v1per/sxdc/ExampleInstrumentedTest.kt` | 23 | 1 (`useAppContext`) | JUnit 4 + AndroidJUnit4 runner |

- There are no other source sets and no Python test directories.
- Both files are untouched Android Studio templates.
- Searched for `*test*.py`, `test_*.py`, `pytest.ini`, `tox.ini` and `requirements*.txt`: none found.

### 1.2 Test dependencies (`app/build.gradle.kts`, `gradle/libs.versions.toml`)

| Configuration | Artifact | Version |
|---|---|---|
| `testImplementation` | `junit:junit` | 4.13.2 |
| `androidTestImplementation` | `androidx.test.ext:junit` | 1.3.0 |
| `androidTestImplementation` | `espresso-core` | 3.7.0 |
| `androidTestImplementation` | `compose-bom` (platform) and `ui-test-junit4` | BOM 2024.09.00 |
| `debugImplementation` | `compose-ui-tooling` and `ui-test-manifest` | BOM 2024.09.00 |

- Runner: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` (`build.gradle.kts:18`).
- There is **no** `testOptions {}`, `unitTests {}`, `includeAndroidResources`, `returnDefaultValues` or custom `sourceSets` block.
- Not present in the catalog: coroutines-test, MockK, Mockito, Robolectric, Room testing, Turbine, Truth.
- The main source also has **no** explicit `kotlinx-coroutines` dependency. It comes in transitively, not declared (`UNKNOWN` which artifact supplies it; `dependencies` was not run).

### 1.3 Coverage tooling

None. The plugins, `jacoco` and `kover` were grepped in the gradle files and nothing was found. There is no coverage task to report.

### 1.4 CI

None. There is no `.github/`, and no other `*.yml` within two directory levels.

### 1.5 Python-side tests

No. There is no pytest, unittest, tox or requirements-dev file anywhere. `sxdc_verify.py` is a diagnostics module, not a test suite.

---

## PART 2: Baseline run

`bash ./gradlew testDebugUnitTest` **failed before any test ran** (build time 1m 2s, exit 1):

```
> Task :app:installDebugPythonRequirements FAILED
Execution failed for task ':app:installDebugPythonRequirements' (registered by plugin 'com.chaquo.python').
> [C:\Users\\AppData\Local\Python\pythoncore-3.12-64\python.exe] is not a valid Python 3.12 command:
  Couldn't find 'C:\...\python.exe' on the PATH or in the project directory.
```

- Pass, fail and skipped counts: **none (0 tests executed)**. `app/build/test-results/` was not produced.
- `@Ignore` / disabled tests: none in the source.
- The "no tests found" warning never fired because the build died first.
- The first-run SDK message, `This version only understands SDK XML versions up to 3 but ... version 4`, is a cmdline-tools/Studio version-mismatch warning.
- Even `testDebugUnitTest` needs the Python build step. JVM tests are therefore blocked by `buildPython` right now.
- No coverage task exists, so there was nothing to report or skip.

---

## PART 3: Behavior inventory vs. existing tests

Existing tests that touch production code: **none**. Both example tests only assert `2+2==4` and the package name. Every verdict below is therefore NONE for coverage. Nature and location are listed for each behavior.

| # | Behavior | Production location | Nature | Verdict |
|---|---|---|---|---|
| 1 | URL extraction | `ShareReceiverActivity.extractUrl` `:163-176` | Mixed. The logic is pure, but it uses `Patterns.WEB_URL` (a framework class) and `Intent` | NONE |
| 2 | Precondition checks and fix-it trigger | `DownloadPreconditions.check` `:100-141`; fix-it state set in `ShareReceiverActivity.handle` `:148-150`, and the FIX_IT action `:130-134` | Android-dependent: `Context`, SharedPreferences, `ContentResolver`, `FfmpegBinaries` | NONE |
| 3 | `DownloadService.start` with `EXTRA_URL`; FGS from onResume | `DownloadService.start` `:521-524`; call at `ShareReceiverActivity:153`; `onResume` `:116-121`; `startForegroundNow` first thing in `onStartCommand` `:94` | Android lifecycle | NONE |
| 4 | `expand_urls()` | `sxdc_download.py:16-60`; Kotlin side `DownloadService.expandUrls` `:136-148` | Python, yt-dlp dependent (the Kotlin fallback is a mixed bridge) | NONE |
| 5 | Dedup (QUEUED/DOWNLOADING only) | `DownloadJobDao.countPendingForUrl` `:284-288`; use at `DownloadService:113` | Room SQL | NONE |
| 6 | Room schema, DAO, status transitions | `DownloadJob.kt:446-460`; `DownloadStatus` `:416`; `DownloadJobDao`; `SxdcDatabase:481` (version 1, `exportSchema=false`) | Room | NONE |
| 7 | `claimNext()` and Mutex drain loop | `DownloadJobDao.claimNext` `:307-312`; `processMutex` `:60`, `drain()` `:152-176`, `withLock` at `:99` | Room, plus coroutines inside a Service | NONE |
| 8 | Crash recovery, 3 attempts | `SxdcApp.onCreate:229`; `DownloadQueue.recoverOrphans` `:36-50`; DAO `recoverOrphans` `:341-349`, `requeueOrphans` `:318-323`, `failExhaustedOrphans` `:325-331`, `MAX_ATTEMPTS=3` `:398` | Room, plus Context and the filesystem | NONE |
| 9 | Service restart from MainActivity.onResume | `MainActivity.onResume` `:203-221` | Android lifecycle | NONE |
| 10 | yt-dlp invocation, options, Settings preview string | `sxdc_download.py:63-155`, opts built by `sxdc_verify.build_ydl_opts` `:31-52`; preview is `YtDlpCommandPreview.render` `SettingsActivity:342-350` | Python, yt-dlp dependent. The preview is pure Kotlin and `private` | NONE. No test links the preview to the Python options; the KDoc at `:336-340` says it is "updated to match" by hand |
| 11 | FLAC, thumbnail, metadata | postprocessor list `sxdc_download.py:~82-105` (`FFmpegExtractAudio`, `FFmpegMetadata`, `EmbedThumbnail`) | Python plus the ffmpeg binary | NONE |
| 12 | ffmpeg/ffprobe symlinks | `FfmpegBinaries.install` `:79-113`, `materialise` `:118-166` | Android: `Os.symlink`, `nativeLibraryDir`, PackageManager | NONE |
| 13 | SAF copy, size verify, staging cleanup | `DownloadService.moveToDestination` `:~313-366`, `StagingFiles.clear` `StagingFiles.kt:181-193` | Android SAF (`DocumentFile`, `ContentResolver`) plus the filesystem. `StagingFiles` alone is pure `java.io` | NONE |
| 14 | Queue-wide STOP conditions | Preconditions fail → `Outcome.STOP` (`runJob`); storage kind → STOP; `MoveResult.Failed(queueWide=true)` for folder lost, ENOSPC and short copy; timeout in `onTimeout` | Mixed | NONE |
| 15 | Per-URL failure only fails that job | `runJob`: `failJob` then `Outcome.CONTINUE` for non-storage kinds; catch-all in `drain` | Mixed | NONE |
| 16 | dataSync timeout handling | `DownloadService.onTimeout` `:~455-480`, `@RequiresApi(35)`; manifest `foregroundServiceType="dataSync"` `AndroidManifest.xml:67`. The 6h/24h cap is OS-side and the code has no counter | Android lifecycle | NONE |
| 17 | Error classification and mapping | Python `classify` / `_MARKERS` `sxdc_download.py:~160-200`; Kotlin `messageFor` `DownloadService:~290-305` | The Python `classify()` is pure (see Part 5). `messageFor` needs `getString` | NONE |
| 18 | Notifications | `Notifications.kt`: `ID_PROGRESS=1001` `:262`, `ID_TERMINAL_SUMMARY=1002`, `TERMINAL_ID_BASE=2000` `:271`, `terminalId` `:275`, channels `:278-290`, group `GROUP_TERMINAL` | Android | NONE |
| 19 | MainActivity system-check rows, Live Status, History (50) | `MainActivity` `:73-96` (diagnostics), `LiveStatusPanel` `:299`, `HistoryPanel` `:368-390`. The 50 limit is applied twice: SQL `LIMIT 50` (DAO `:375`) and `.take(50)` | Compose plus Room Flow plus Python | NONE |
| 20 | SettingsActivity | `folderLabel` `:316-334`, `ClearHistorySection` `:217-258`, `EmergencyStopSection` `:262-300`, `SxdcSettings.saveExtraArgs` `:82`, `saveTreeUri` `:247` | Compose, SharedPreferences, SAF, Room | NONE |
| 21 | Cancellation and scope cleanup | `DownloadService.onDestroy` (`serviceJob.cancel()`), `drain` `CancellationException` rethrow, `appScope` `AppScope.kt:19`. Emergency stop is `cancelAllPending` `:390-394` | Coroutines plus Android | NONE |

Two behaviors have notable gaps:

- **#20, extra-params persistence.** The saved value is **never read by anything outside Settings**. A grep for `extraArgs`/`ytdlp_extra_args` finds only `SxdcSettings` and `SettingsActivity`. It is stored but not wired into yt-dlp, which the preview KDoc also states at `SettingsActivity:~175`.
- **#16, the 6h/24h cap.** There is no code for the cap itself, only the timeout callback. `onTimeout` marks the in-flight job FAILED and stops the service.

---

## PART 4: Test quality and flakiness

| File | Findings | Verdict |
|---|---|---|
| `ExampleUnitTest.kt` | `assertEquals(4, 2+2)` at `:13-15`. The test touches no production code, so it can never fail. No sleeps, network, Room or `@Ignore` | **DELETE** (template, zero signal) |
| `ExampleInstrumentedTest.kt` | The assertion at `:19-21` is `assertEquals("com.v1per.sxdc", appContext.packageName)`. This will be **wrong** for the debug build (applicationId `com.v1per.sxdc.debug`, `build.gradle.kts:~32`). It has not been run since the 1.2.0 suffix change | **DELETE** (stale and failing by construction) |

Neither file has the problems listed in the audit prompt: no sleeps, runBlocking, network, `Dispatchers.Main`, shared state, real Room or filesystem, `@Ignore`, or commented-out tests. Their names match what they assert.

---

## PART 5: Testability seams

### Constructors and signatures

- `class DownloadService : Service()` (no-arg; instantiated by the OS)
- `object DownloadQueue { fun dao(context: Context): DownloadJobDao; suspend fun recoverOrphans(context: Context) }`
- `abstract class SxdcDatabase : RoomDatabase { companion { fun get(context: Context): SxdcDatabase } }`
- `abstract class DownloadJobDao`: abstract class, not an interface. `claimNext()` and `recoverOrphans()` are `open suspend` `@Transaction` members.
- `object DownloadPreconditions { fun check(context: Context): Result; fun outputDir(context: Context): File }`
- `object FfmpegBinaries { fun install(context: Context): Install }`
- `object SxdcSettings`: every function takes `Context`.
- `object Notifications { fun progress(Context, String, Int, Int); fun success(Context, String, String); fun failure(Context, Long, String); fun postSummary(Context); fun post(Context, Int, Notification) }`
- `internal object StagingFiles { fun clear(file: File) }`
- `internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`

### What blocks plain JVM testing

| Item | Blockers |
|---|---|
| **DownloadService** | It is a monolith. It extends `Service`. The scope is hard-wired to `Dispatchers.IO` (`:44`). It uses `Handler(Looper.getMainLooper())`, `Toasts`, `getString` and `startForeground`. Python is reached via `Python.getInstance()` directly (`:141`, `:~265`). The DAO comes from the static `SxdcDatabase.get` through a `lazy`. SAF copy, notifications and the timeout path sit in the same class. No extracted collaborators exist. |
| **Queue/drain and dedup** | Not separable. `expandAndEnqueue`, `drain`, `runJob`, `failJob` and `stopWhenIdle` are all private members of the Service and depend on `dao`, `getString` and `Notifications`. Dedup logic is split across the Service loop (`:113`) and one DAO SQL query. |
| **Room provider and DAO** | The DAO is JVM-unfriendly only because it needs the Android SQLite framework. The provider is a static `instance` singleton with the DB name hard-coded to `"sxdc.db"`. There is no in-memory builder hook, so a test must construct `Room.inMemoryDatabaseBuilder` itself. The DAO is an abstract class, so it can't be faked as an interface without subclassing. |
| **Error-to-string mapper** | The Kotlin side is the private `messageFor` in the Service, which needs `getString(R.string...)`. The Python `classify()` is plain and pure. |
| **URL extraction** | Private method on the Activity. It uses `android.util.Patterns` (a stub on the JVM unless `returnDefaultValues` or Robolectric is used) and `Intent`. The core logic is a few lines of pure Kotlin that could be isolated. |
| **SAF copy/verify** | Private `moveToDestination` in the Service uses `DocumentFile`, `contentResolver.openOutputStream` and `Uri`. Needs an instrumented test or a faked URI provider. |
| **Notification builder** | `Notifications` is an `object` taking `Context`, so it needs a Context, `NotificationCompat` and `PendingIntent`. `terminalId()` is the only pure part. |
| **Python bridge** | Direct `Python.getInstance().getModule("sxdc_download").callAttr(...)` in two places with no interface. It cannot be faked from the JVM today. The return value is a JSON string, which is easy to parse and fake once an interface exists. |

### Public functions in `sxdc_download.py`

| Signature | Pure? |
|---|---|
| `expand_urls(url)` | **yt-dlp / network dependent** (`extract_info`). It swallows exceptions and falls back to a single track |
| `download_audio(url, out_dir, ffmpeg_dir, codec="flac")` | **yt-dlp dependent** (network, ffmpeg, filesystem) |
| `classify(exc)` | **Pure** (string match over `_MARKERS`) |
| `_fail(kind, message, trace=None)` | Pure (private) |
| `KIND_*` constants, `_MARKERS` | Pure data |

### Functions in `sxdc_verify.py`

| Signature | Pure? |
|---|---|
| `build_ydl_opts(bin_dir, outtmpl=None, extra=None, noplaylist=True)` | **Pure** (dict builder) |
| `hello()`, `add(a, b)` | Pure |
| `yt_dlp_version()` | Imports yt-dlp (no network) |
| `ffmpeg_check(bin_dir)`, `_run_version(path)` | Subprocess / filesystem. Not network |
| `ytdlp_ffmpeg_check(bin_dir)`, `ffmpeg_report(bin_dir)` | Imports yt-dlp and probes ffmpeg. No network |

Note that `sxdc_download.py` calls `classify` at import time and `build_ydl_opts` through `from sxdc_verify import ...`, so the pure parts can be tested by plain `pytest` without yt-dlp.

---

## PART 6: Linux build environment facts

### 6.1 `./gradlew assembleDebug`

**Fails**, with the same error as Part 2.

```
> Task :app:installDebugPythonRequirements FAILED
[C:\Users\\AppData\Local\Python\pythoncore-3.12-64\python.exe] is not a valid Python 3.12 command
```

### 6.2 Python and JDK lines

- `app/build.gradle.kts:~63-76` (`chaquopy {}` block):
  ```
  buildPython("C:\\Users\\\\AppData\\Local\\Python\\pythoncore-3.12-64\\python.exe")
  ```
  Yes, a Windows path is hard-coded. The comment above it says it is specific to V1PER.
- `gradle.properties` has `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8` and no `JAVA_HOME` or python key.
- `local.properties` **does not exist** (gitignored). The SDK came from `ANDROID_HOME=/home/v1per/Android/Sdk`, so there are no key names to report.

### 6.3 Python and JDK on this machine

| Check | Result |
|---|---|
| `which python3.12` | not found |
| `python3 --version` | **Python 3.14.8** (`/usr/bin/python3`) |
| pyenv / uv | not installed |
| Gradle launcher JVM | OpenJDK **17.0.20.1** (Arch), `/usr/lib/jvm/java-17-openjdk`; no daemon JVM pinned |
| Other JDKs installed | `java-17-openjdk`, `java-27-openjdk` |
| Gradle | 9.7.0 |

Chaquopy 17 needs a Python 3.12 build interpreter, and this machine has none.

### 6.4 Stale content in `DARKHORSE_SETUP.md`

- Header line 4: Windows paths (`C:\XPS\v1per\sXdc`).
- Table row line 20: "Python 3.12 on PATH as `py -3.12`".
- Section 2 (line 33) title and body: "required, and must be `py -3.12`".
- Line 42: the hard-coded Windows `buildPython` path.
- Lines 69-96: the `py` launcher / WindowsApps alias explanation and `py install 3.12`.
- Lines 208-228, 256-262: PowerShell user-PATH commands.

Only a grep for Windows markers was done, not a line-by-line audit, so this list may miss items.

### 6.5 Other Windows assumptions in the repo

- `gradlew.bat` is the standard wrapper script (harmless).
- `CLAUDE.md:23` mentions `C:\XPS\v1per\sXdc` (documentation only).
- `settings.gradle.kts:5-6` backslashes are regex escapes, not paths.
- No `.ps1` files.
- `gradlew` lost its executable bit (see Audit notes).

---

## PART 7: Git loose ends

1. **Suffix and label change in v1.2.0?** **Yes.**
   - Tag `v1.2.0` is an annotated tag pointing at `5e52934baaa87355291fd64d00889a9058f1dfb2` ("Bump versionCode/versionName to 1.2.0").
   - `git log -S'applicationIdSuffix'` finds the introducing commit `0be6839` ("Separate debug/release package and app name (sXdc Dev vs sXdc)"), dated 2026-08-22.
   - `git merge-base --is-ancestor 0be6839 v1.2.0` is true, and `git tag --contains 0be6839` lists `v1.2.0`.
2. **`version-1.0` branch:** it does **not** exist locally or on origin. Branches: `main`, `* onepointthree`, `remotes/origin/HEAD -> origin/main`, `remotes/origin/main`.
3. **Tags:** `v1.0.0`, `v1.1.0`, `v1.1.1`, `v1.2.0`. Current branch: `onepointthree`. `git status` is **clean** (no untracked or modified files, and `local.properties` still absent).
