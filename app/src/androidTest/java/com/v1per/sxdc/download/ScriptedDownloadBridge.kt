package com.v1per.sxdc.download

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A DownloadBridge for service tests that never runs Python (#61). Handed to the service through
 * TestSxdcApp.bridgeOverride.
 *
 * Each download stages "<id>.part" the way yt-dlp does, then renames it to the finished name,
 * "<id> [<id>].flac", where <id> is the URL's last path segment. The download of [blockUrl]
 * blocks between the two steps until [release] opens, like a yt-dlp call that cannot be
 * interrupted. A .part file that is gone by then (swept by another run) fails the download.
 */
internal class ScriptedDownloadBridge(
    private val blockUrl: String,
    /** A URL whose expansion blocks until [expandRelease] opens, then returns [expansions] for it (#75). */
    private val expandBlockUrl: String? = null,
    private val expansions: Map<String, List<Track>> = emptyMap(),
) : DownloadBridge {
    /** "start:<url>" and "return:<url>", in the order they happened. */
    val events = CopyOnWriteArrayList<String>()

    val release = CountDownLatch(1)

    val expandRelease = CountDownLatch(1)

    private val running = AtomicInteger()

    /** The most downloads that were ever running at once. */
    @Volatile
    var maxConcurrent = 0
        private set

    fun started(url: String) = "start:$url" in events

    fun returned(url: String) = "return:$url" in events

    override fun expandUrls(url: String): List<Track> {
        events += "expand:$url"
        if (url == expandBlockUrl) expandRelease.await(BLOCK_LIMIT_S, TimeUnit.SECONDS)
        return expansions[url] ?: listOf(Track(url, null))
    }

    override fun dedupKeys(urls: List<String>): List<String> = urls

    /** Ignores [abort] on purpose: the blocked call stands for one that cannot notice it yet. */
    override fun download(url: String, outputDir: String, ffmpegBinDir: String, abort: AbortSignal): JobResult {
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, running.incrementAndGet()) }
        events += "start:$url"
        try {
            val id = idOf(url)
            val part = File(outputDir, partName(id))
            part.writeBytes(CONTENT)
            if (url == blockUrl) release.await(BLOCK_LIMIT_S, TimeUnit.SECONDS)
            val finished = File(outputDir, finishedName(id))
            if (!part.renameTo(finished)) {
                return JobResult(false, null, null, null, ErrorKinds.UNKNOWN, "${part.name} is gone", null)
            }
            return JobResult(true, finished.path, id, finished.length(), null, null, null)
        } finally {
            running.decrementAndGet()
            events += "return:$url"
        }
    }

    companion object {
        /** Long enough for any test, short enough that a forgotten latch cannot hang the run. */
        private const val BLOCK_LIMIT_S = 120L

        val CONTENT = "fLaC scripted audio".toByteArray()

        fun idOf(url: String) = url.substringAfterLast('/')

        fun partName(id: String) = "${finishedName(id)}.part"

        fun finishedName(id: String) = "$id [$id].flac"
    }
}
