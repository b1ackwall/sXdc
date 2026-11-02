package com.v1per.sxdc.ui

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.v1per.sxdc.R
import com.v1per.sxdc.appScope
import com.v1per.sxdc.media.FfmpegBinaries
import com.v1per.sxdc.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** One subsystem's outcome: which label to show, what to show, and whether it succeeded. */
internal data class DiagField(@StringRes val label: Int, val value: String, val ok: Boolean)

/** Result of standing up the bundled ffmpeg/ffprobe binaries and probing them. */
internal sealed class NativeDiagnostics {
    data class Ok(
        val source: String,
        val binDirPath: String,
        val ffmpeg: DiagField,
        val ffprobe: DiagField,
        val integration: DiagField,
    ) : NativeDiagnostics()

    data class Failed(val message: String) : NativeDiagnostics()
}

/** Everything the System check panel shows, gathered once by [loadDiagnostics]. */
internal data class Diagnostics(
    val greeting: String,
    val sum: Int,
    val ytDlpVersion: String,
    val native: NativeDiagnostics,
)

/**
 * Runs a loader at most once per process and hands every later caller the same result (#21).
 *
 * The load runs on [scope], not on the caller's: an activity recreated mid-load (rotation) cancels
 * only its wait, and the next activity picks up the same in-flight load instead of starting
 * another. A failed load is not kept, so the next caller tries again.
 */
internal class OnceCache<T : Any>(private val scope: CoroutineScope) {
    private val lock = Any()
    private var pending: Deferred<T>? = null

    @Volatile
    private var value: T? = null

    /** The finished result, or null while nothing has loaded yet. */
    fun peek(): T? = value

    suspend fun get(loader: suspend () -> T): T {
        value?.let { return it }
        val load = synchronized(lock) {
            pending ?: scope.async { loader().also { value = it } }.also { pending = it }
        }
        try {
            return load.await()
        } finally {
            // A finished load is dropped: a success already sits in [value], and a failure must
            // not stick. Still active means only this caller was cancelled: keep it for the next.
            if (!load.isActive) synchronized(lock) { if (pending === load) pending = null }
        }
    }
}

/** The process's one System check result, shared by every MainActivity instance. */
internal val systemCheckCache = OnceCache<Diagnostics>(appScope)

/**
 * Runs every startup check on an IO thread: the Chaquopy bridge, yt-dlp's version, the ffmpeg
 * install and the three ffmpeg probes. These used to run on the main thread before the first frame.
 */
internal suspend fun loadDiagnostics(context: Context): Diagnostics = withContext(Dispatchers.IO) {
    // Python is started in SxdcApp; the share flow can reach the interpreter without MainActivity.
    val verifyModule = Python.getInstance().getModule("sxdc_verify")
    val greeting = verifyModule.callAttr("hello").toString()
    val sum = verifyModule.callAttr("add", 2, 2).toInt()
    val ytDlpVersion = verifyModule.callAttr("yt_dlp_version").toString()

    val native = try {
        val install = FfmpegBinaries.install(context)
        // One ffmpeg_check call probes both programs; read each entry from the same result.
        val check = verifyModule.callAttr("ffmpeg_check", install.binDirPath)
        NativeDiagnostics.Ok(
            source = install.source.name,
            binDirPath = install.binDirPath,
            ffmpeg = ffmpegField(check, "ffmpeg", R.string.check_ffmpeg),
            ffprobe = ffmpegField(check, "ffprobe", R.string.check_ffprobe),
            integration = integrationField(verifyModule, install.binDirPath),
        )
    } catch (e: Exception) {
        Log.e(TAG, "ffmpeg install failed", e)
        NativeDiagnostics.Failed("ffmpeg install FAILED: ${e.message}")
    }
    Diagnostics(greeting, sum, ytDlpVersion, native)
}

private const val TAG = "SystemCheck"

/** `None` round-trips through [PyObject.toString] as the literal text "None". */
private fun PyObject?.orNull(): String? = this?.toString()?.takeUnless { it == "None" }

/** Reads one program's entry out of a [sxdc_verify.ffmpeg_check] result dict. */
private fun ffmpegField(check: PyObject, name: String, @StringRes label: Int): DiagField {
    val entry = check.callAttr("get", name)
    val version = entry.callAttr("get", "version").orNull()
    val error = entry.callAttr("get", "error").orNull()
    return if (version != null) {
        DiagField(label, version, ok = true)
    } else {
        DiagField(label, error ?: "unknown error", ok = false)
    }
}

/** Reads [sxdc_verify.ytdlp_ffmpeg_check]'s result: whether yt-dlp's own discovery finds ffmpeg. */
private fun integrationField(verifyModule: PyObject, binDirPath: String): DiagField {
    val result = verifyModule.callAttr("ytdlp_ffmpeg_check", binDirPath)
    val error = result.callAttr("get", "error").orNull()
    if (error != null) {
        return DiagField(R.string.check_integration, "ERROR: $error", ok = false)
    }
    val available = result.callAttr("get", "available").toBoolean()
    val versions = result.callAttr("get", "versions").orNull()
    val value = if (available) "yes${versions?.let { " $it" } ?: ""}" else "NO"
    return DiagField(R.string.check_integration, value, ok = available)
}

/** The rows a successful check shows, in order; drawn as "checking…" while the load runs. */
private val CHECK_ROWS = listOf(
    R.string.check_bridge,
    R.string.check_ytdlp,
    R.string.check_ffmpeg,
    R.string.check_ffprobe,
    R.string.check_integration,
    R.string.check_native_tier,
)

/** The System check panel, drawn from [state] (see [loadSystemCheck]). */
@Composable
internal fun SystemCheckPanel(state: SystemCheckState) {
    Panel {
        SectionLabel(stringResource(R.string.main_diagnostics))

        when (state) {
            SystemCheckState.Pending -> {
                val pending = stringResource(R.string.check_pending)
                CHECK_ROWS.forEach { StatusRow(stringResource(it), pending, ok = null) }
            }
            // The load threw before any row had a result: the bridge row carries the error, and the
            // rest were never reached.
            is SystemCheckState.Failed -> {
                StatusRow(
                    stringResource(CHECK_ROWS.first()),
                    stringResource(R.string.check_failed, state.message),
                    ok = false,
                )
                val notChecked = stringResource(R.string.check_not_checked)
                CHECK_ROWS.drop(1).forEach { StatusRow(stringResource(it), notChecked, ok = null) }
            }
            is SystemCheckState.Loaded -> LoadedRows(state.diagnostics)
        }
    }
}

@Composable
private fun LoadedRows(state: Diagnostics) {
    StatusRow(stringResource(R.string.check_bridge), "${state.greeting} (2+2=${state.sum})", ok = true)
    StatusRow(stringResource(R.string.check_ytdlp), state.ytDlpVersion, ok = true)
    when (val native = state.native) {
        is NativeDiagnostics.Ok -> {
            listOf(native.ffmpeg, native.ffprobe, native.integration).forEach {
                StatusRow(stringResource(it.label), it.value, it.ok)
            }
            StatusRow(
                label = stringResource(R.string.check_native_tier),
                value = "${native.source} @ ${native.binDirPath}",
                ok = true,
            )
        }
        is NativeDiagnostics.Failed ->
            StatusRow(stringResource(R.string.check_native_install), native.message, ok = false)
    }
}

/** One diagnostics line: a check/cross indicator (neutral while pending), a label, and its value. */
@Composable
private fun StatusRow(label: String, value: String, ok: Boolean?) {
    val (mark, indicatorColor) = when (ok) {
        true -> "✓" to MaterialTheme.colorScheme.primary
        false -> "✗" to MaterialTheme.colorScheme.error
        null -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = mark,
            color = indicatorColor,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = Spacing.Tight),
        )
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
