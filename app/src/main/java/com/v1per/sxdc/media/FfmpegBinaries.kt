package com.v1per.sxdc.media

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Makes the bundled ffmpeg/ffprobe binaries runnable and discoverable by yt-dlp.
 *
 * Why this is not just "copy the asset and chmod +x":
 *
 * Android 10 (API 29) removed `execute` on `app_data_file` from the `untrusted_app` SELinux
 * domain. The domain is chosen from the app's *targetSdk* (36 here), so every file we write
 * under [Context.getFilesDir] is un-executable no matter what `setExecutable` reports — the
 * chmod succeeds and the later execve() fails with EACCES. This is Android's W^X enforcement.
 *
 * The binaries are therefore packaged as `lib*.so` under `jniLibs/arm64-v8a/`. The platform
 * installer extracts them into `applicationInfo.nativeLibraryDir`, which is read-only to us and
 * labelled `apk_data_file` — still executable. yt-dlp, however, resolves `ffmpeg_location` by
 * looking for files literally named `ffmpeg` and `ffprobe`, so we publish a small bin directory
 * of correctly-named **symlinks** pointing at the native libs. SELinux resolves the symlink and
 * checks the target's label, so exec is permitted while the real bytes stay outside app-writable
 * storage.
 *
 * Tiers, in order of preference:
 *  1. [Source.NATIVE_SYMLINK] — symlink filesDir/ffmpeg-bin/ffmpeg -> nativeLibraryDir/libffmpeg.so
 *  2. [Source.NATIVE_COPY]    — real copy of the native lib + chmod (works pre-API-29 only)
 *  3. [Source.ASSET_COPY]     — real copy from assets/ffmpeg/ + chmod (works pre-API-29 only)
 *
 * Tiers 2 and 3 exist so an API 28 device, or a build that ships the binaries as assets instead,
 * still comes up. On API 29+ they will install fine and fail at exec time; [Install.source] tells
 * you which tier you landed on.
 */
object FfmpegBinaries {

    private const val TAG = "FfmpegBinaries"

    /** Subdirectory of filesDir holding the yt-dlp-visible `ffmpeg` / `ffprobe` entries. */
    private const val BIN_DIR_NAME = "ffmpeg-bin"

    /** Directory inside assets/ used by the [Source.ASSET_COPY] fallback. */
    private const val ASSET_DIR = "ffmpeg"

    private const val STAMP_NAME = ".install-stamp"

    private val PROGRAMS = listOf("ffmpeg", "ffprobe")

    enum class Source { NATIVE_SYMLINK, NATIVE_COPY, ASSET_COPY }

    /**
     * @param binDir directory to hand to yt-dlp as `ffmpeg_location`
     * @param source which tier actually satisfied the install
     */
    data class Install(
        val binDir: File,
        val ffmpeg: File,
        val ffprobe: File,
        val source: Source,
    ) {
        val binDirPath: String get() = binDir.absolutePath
        val ffmpegPath: String get() = ffmpeg.absolutePath
        val ffprobePath: String get() = ffprobe.absolutePath
    }

    @Volatile
    private var cached: Install? = null

    /**
     * Idempotent. Safe (and intended) to call on every launch — the fast path is a stamp-file
     * read plus two `exists()` checks, so the cost of checking every time is a fraction of a
     * millisecond, while a strict once-ever install would silently keep stale binaries after an
     * app update. Re-materialises whenever the package's `lastUpdateTime` changes.
     *
     * @throws IOException if no tier could produce a usable pair of binaries.
     */
    @Synchronized
    fun install(context: Context): Install {
        cached?.let { return it }

        val app = context.applicationContext
        val binDir = File(app.filesDir, BIN_DIR_NAME)
        val stamp = File(binDir, STAMP_NAME)
        val expected = installStamp(app)

        if (binDir.isDirectory && stamp.isFile && stamp.readText() == expected) {
            val existing = PROGRAMS.map { File(binDir, it) }
            if (existing.all { it.exists() }) {
                // A symlink survives with the same stamp only if its target survived too.
                val source = if (existing.all { isSymlink(it) }) Source.NATIVE_SYMLINK else Source.NATIVE_COPY
                return Install(binDir, existing[0], existing[1], source).also {
                    cached = it
                    Log.i(TAG, "ffmpeg already installed (${it.source}) at ${it.binDirPath}")
                }
            }
        }

        // Rebuild from scratch so a half-finished previous attempt cannot leak through.
        binDir.deleteRecursively()
        if (!binDir.mkdirs() && !binDir.isDirectory) {
            throw IOException("Could not create ${binDir.absolutePath}")
        }

        val source = materialise(app, binDir)
        stamp.writeText(expected)

        val install = Install(binDir, File(binDir, "ffmpeg"), File(binDir, "ffprobe"), source)
        cached = install
        Log.i(TAG, "ffmpeg installed (${install.source}) at ${install.binDirPath}")
        return install
    }

    /** Last successful [install] result, or null if it has not run in this process yet. */
    fun installedOrNull(): Install? = cached

    private fun materialise(context: Context, binDir: File): Source {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeLibs = PROGRAMS.map { File(nativeDir, "lib$it.so") }
        val failures = mutableListOf<String>()

        if (nativeLibs.all { it.isFile }) {
            try {
                PROGRAMS.forEachIndexed { i, name ->
                    Os.symlink(nativeLibs[i].absolutePath, File(binDir, name).absolutePath)
                }
                return Source.NATIVE_SYMLINK
            } catch (e: Exception) {
                // ErrnoException, or SecurityException on unusual filesystems.
                failures += "symlink: ${e.message}"
                PROGRAMS.forEach { File(binDir, it).delete() }
            }

            try {
                PROGRAMS.forEachIndexed { i, name ->
                    nativeLibs[i].inputStream().use { src ->
                        File(binDir, name).outputStream().use { src.copyTo(it) }
                    }
                }
                markExecutable(binDir)
                return Source.NATIVE_COPY
            } catch (e: IOException) {
                failures += "native copy: ${e.message}"
                PROGRAMS.forEach { File(binDir, it).delete() }
            }
        } else {
            failures += "nativeLibraryDir: no lib{ffmpeg,ffprobe}.so in $nativeDir " +
                "(is android:extractNativeLibs / useLegacyPackaging still true?)"
        }

        try {
            PROGRAMS.forEach { name ->
                context.assets.open("$ASSET_DIR/$name").use { src ->
                    File(binDir, name).outputStream().use { src.copyTo(it) }
                }
            }
            markExecutable(binDir)
            return Source.ASSET_COPY
        } catch (e: IOException) {
            failures += "asset copy: ${e.message}"
            PROGRAMS.forEach { File(binDir, it).delete() }
        }

        throw IOException("No usable ffmpeg source. Tried -> ${failures.joinToString("; ")}")
    }

    private fun markExecutable(binDir: File) {
        PROGRAMS.forEach { name ->
            val f = File(binDir, name)
            // ownerOnly = false: the binary is still only reachable inside our sandbox.
            if (!f.setExecutable(true, false) && !f.canExecute()) {
                throw IOException("Could not mark ${f.absolutePath} executable")
            }
            f.setReadable(true, false)
        }
    }

    private fun isSymlink(file: File): Boolean = try {
        file.absolutePath != file.canonicalPath
    } catch (_: IOException) {
        false
    }

    /** Changes on every install/update, including debug reinstalls, so stale copies get rebuilt. */
    private fun installStamp(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.longVersionCode}@${info.lastUpdateTime}"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }
}
