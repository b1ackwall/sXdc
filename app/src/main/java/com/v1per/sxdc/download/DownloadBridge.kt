package com.v1per.sxdc.download

import android.util.Log
import com.chaquo.python.Python
import org.json.JSONObject

internal data class Track(val url: String, val title: String?)

internal data class JobResult(
    val ok: Boolean,
    val path: String?,
    val title: String?,
    /** Bytes yt-dlp saw on disk. The move verifies its copy against this before deleting. */
    val size: Long?,
    /** One of sxdc_download.py's KIND_* ids. Chooses the message; [error] is log-only. */
    val kind: String?,
    val error: String?,
    val trace: String?,
)

/** The Python side of a download. Both calls are blocking. */
internal interface DownloadBridge {
    fun expandUrls(url: String): List<Track>

    /**
     * One duplicate-detection key per URL, same length and order: equal keys mean the same video.
     * Never throws; on any failure the URLs themselves come back, which is exact-string matching.
     */
    fun dedupKeys(urls: List<String>): List<String>
    fun download(url: String, outputDir: String, ffmpegBinDir: String): JobResult
}

internal class PythonDownloadBridge : DownloadBridge {

    /**
     * Blocking, same reasoning as [download] -- Chaquopy's callAttr has no interruption point.
     * Falls back to treating [url] as a single untitled track on any bridge failure, which is
     * exactly today's single-track behaviour and never worse than not expanding at all.
     */
    override fun expandUrls(url: String): List<Track> = try {
        val module = Python.getInstance().getModule("sxdc_download")
        val raw = module.callAttr("expand_urls", url).toString()
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            Track(url = entry.getString("url"), title = entry.optStringOrNull("title"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "expand_urls bridge call failed for $url", e)
        listOf(Track(url = url, title = null))
    }

    /**
     * Same JSON-string crossing as [expandUrls]. A pure string computation in sxdc_dedup.py (no
     * network, no yt_dlp), so it is cheap even though the call is blocking.
     */
    override fun dedupKeys(urls: List<String>): List<String> {
        if (urls.isEmpty()) return urls
        return try {
            val module = Python.getInstance().getModule("sxdc_dedup")
            val raw = module.callAttr("dedup_keys_json", org.json.JSONArray(urls).toString()).toString()
            val array = org.json.JSONArray(raw)
            check(array.length() == urls.size) { "got ${array.length()} keys for ${urls.size} urls" }
            (0 until array.length()).map { i -> array.getString(i) }
        } catch (e: Exception) {
            Log.w(TAG, "dedup_keys bridge call failed (${e.javaClass.simpleName}); comparing exact URLs")
            urls
        }
    }

    /**
     * Blocking, and deliberately left that way. Chaquopy's callAttr is a JNI call with no
     * interruption point, so wrapping it in anything cancellable would only give the illusion of
     * one -- cancelling the scope cannot stop yt-dlp mid-download. A job in flight runs to
     * completion; a job whose process is killed leaves its row DOWNLOADING for recovery.
     *
     * @param outputDir the staging directory's absolute path
     */
    override fun download(url: String, outputDir: String, ffmpegBinDir: String): JobResult = try {
        val module = Python.getInstance().getModule("sxdc_download")
        val raw = module.callAttr(
            "download_audio",
            url,
            outputDir,
            ffmpegBinDir,
        ).toString()
        val json = JSONObject(raw)
        JobResult(
            ok = json.optBoolean("ok", false),
            path = json.optStringOrNull("path"),
            title = json.optStringOrNull("title"),
            size = if (json.isNull("size")) null else json.optLong("size").takeIf { it > 0 },
            kind = json.optStringOrNull("kind"),
            error = json.optStringOrNull("error"),
            trace = json.optStringOrNull("trace"),
        )
    } catch (e: Exception) {
        // The bridge itself failed -- Python never ran, or returned something unparseable.
        Log.e(TAG, "yt-dlp bridge call failed", e)
        JobResult(
            ok = false,
            path = null,
            title = null,
            size = null,
            kind = ErrorKinds.ENGINE,
            error = "${e.javaClass.simpleName}: ${e.message}",
            trace = null,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadService"
    }
}
