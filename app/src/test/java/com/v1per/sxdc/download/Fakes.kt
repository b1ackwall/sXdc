package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import java.io.File

/** One ordered log every fake appends to, so tests can assert call order across collaborators. */
class EventLog {
    private val events = mutableListOf<String>()

    @Synchronized
    fun add(event: String) {
        events += event
    }

    @Synchronized
    fun all(): List<String> = events.toList()

    /** Events whose name (text before the first ':') is in [names], in order. */
    fun only(vararg names: String): List<String> = all().filter { it.substringBefore(':') in names }

    /** Just the names of [only]. */
    fun names(vararg names: String): List<String> = only(*names).map { it.substringBefore(':') }
}

/** Returns "str:<id>" and, with args, "str:<id>[a,b]". */
class FakeStringProvider : StringProvider {
    override fun get(id: Int, vararg args: Any): String = render(id, *args)

    companion object {
        fun render(id: Int, vararg args: Any): String =
            if (args.isEmpty()) "str:$id" else "str:$id[${args.joinToString(",")}]"
    }
}

const val FIXED_NOW = 1_700_000_000_000L
val fixedClock: () -> Long = { FIXED_NOW }

/** In-memory queue with the DAO's semantics for the calls the engine makes. Thread-safe. */
internal class FakeJobStore(private val log: EventLog) : JobStore {
    private val rows = mutableListOf<DownloadJob>()
    private var nextId = 1L
    val recoverCalls = mutableListOf<Pair<String, Long>>()

    /** Test setup, not logged. */
    @Synchronized
    fun seed(url: String, status: DownloadStatus = DownloadStatus.QUEUED, title: String? = null,
             stagedFilePath: String? = null): Long {
        val id = nextId++
        rows += DownloadJob(id = id, url = url, status = status, title = title, stagedFilePath = stagedFilePath)
        return id
    }

    @Synchronized
    fun job(id: Long): DownloadJob = rows.single { it.id == id }

    @Synchronized
    fun jobs(): List<DownloadJob> = rows.toList()

    private fun pending(job: DownloadJob) =
        job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.DOWNLOADING

    private fun replace(id: Long, f: (DownloadJob) -> DownloadJob) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i])
    }

    override suspend fun insert(job: DownloadJob): Long {
        synchronized(this) {
            val id = nextId++
            rows += job.copy(id = id)
            log.add("insert:${job.url}")
            return id
        }
    }

    override suspend fun pendingUrls(): List<String> {
        synchronized(this) {
            log.add("pendingUrls")
            return rows.filter { pending(it) }.sortedBy { it.id }.map { it.url }
        }
    }

    override suspend fun countPending(): Int {
        synchronized(this) {
            log.add("countPending")
            return rows.count { pending(it) }
        }
    }

    override suspend fun claimNext(): DownloadJob? {
        synchronized(this) {
            val job = rows.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.id }
            log.add("claimNext:${job?.id}")
            job ?: return null
            replace(job.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
            return job.copy(status = DownloadStatus.DOWNLOADING)
        }
    }

    /** Same guard as the DAO: only a DOWNLOADING row is written. Returns rows affected. */
    private fun guarded(id: Long, f: (DownloadJob) -> DownloadJob): Int {
        if (rows.none { it.id == id && it.status == DownloadStatus.DOWNLOADING }) return 0
        replace(id, f)
        return 1
    }

    override suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?): Int {
        synchronized(this) {
            log.add("markStaged:$id")
            return guarded(id) { it.copy(title = title, stagedFilePath = stagedFilePath) }
        }
    }

    override suspend fun markCompleted(id: Long, title: String?, completedAt: Long): Int {
        synchronized(this) {
            log.add("markCompleted:$id")
            return guarded(id) {
                it.copy(status = DownloadStatus.COMPLETED, title = title, stagedFilePath = null,
                    failureReason = null, completedAt = completedAt)
            }
        }
    }

    override suspend fun markFailed(id: Long, reason: String, completedAt: Long): Int {
        synchronized(this) {
            log.add("markFailed:$id")
            return guarded(id) {
                it.copy(status = DownloadStatus.FAILED, failureReason = reason, completedAt = completedAt)
            }
        }
    }

    /** Test setup, not logged: force a status, as another actor (onTimeout, a user) would. */
    @Synchronized
    fun setStatus(id: Long, status: DownloadStatus) = replace(id) { it.copy(status = status) }

    override suspend fun orphanedJobs(): List<DownloadJob> {
        synchronized(this) {
            log.add("orphanedJobs")
            return rows.filter { it.status == DownloadStatus.DOWNLOADING }
        }
    }

    override suspend fun recoverOrphans(reason: String, now: Long) {
        synchronized(this) {
            log.add("recoverOrphans")
            recoverCalls += reason to now
        }
    }
}

internal class FakeDownloadBridge(private val log: EventLog) : DownloadBridge {
    /** Per-URL expansion; a URL not in the map expands to itself, untitled. */
    val expansions = mutableMapOf<String, List<Track>>()
    val expandThrows = mutableMapOf<String, Throwable>()

    /** Per-URL result; a URL not in the map succeeds with a staged path. */
    val results = mutableMapOf<String, JobResult>()
    val downloadThrows = mutableMapOf<String, Throwable>()

    /** Runs inside download() before it returns; lets a test observe or block mid-job. */
    var onDownload: (url: String) -> Unit = {}

    val downloadArgs = mutableListOf<Triple<String, String, String>>()

    /** The key of one URL. The default is the URL itself, i.e. the old exact-string matching. */
    var keyOf: (String) -> String = { it }

    /** Thrown from dedupKeys when set, to prove the share still enqueues. */
    var dedupThrows: Throwable? = null

    /** Every batch dedupKeys was asked about, in call order. */
    val dedupCalls = mutableListOf<List<String>>()

    override fun expandUrls(url: String): List<Track> {
        log.add("expand:$url")
        expandThrows[url]?.let { throw it }
        return expansions[url] ?: listOf(Track(url, null))
    }

    override fun dedupKeys(urls: List<String>): List<String> {
        dedupCalls += urls
        dedupThrows?.let { throw it }
        return urls.map(keyOf)
    }

    override fun download(url: String, outputDir: String, ffmpegBinDir: String): JobResult {
        log.add("download:$url")
        synchronized(downloadArgs) { downloadArgs += Triple(url, outputDir, ffmpegBinDir) }
        onDownload(url)
        downloadThrows[url]?.let { throw it }
        log.add("downloadDone:$url")
        return results[url] ?: ok(path = "/staging/${url.substringAfterLast('/')}.flac")
    }

    companion object {
        fun ok(path: String = "/staging/x.flac", title: String? = null) =
            JobResult(ok = true, path = path, title = title, size = 10, kind = null, error = null, trace = null)

        fun failed(kind: String?, error: String = "boom") =
            JobResult(ok = false, path = null, title = null, size = null, kind = kind, error = error, trace = null)
    }
}

internal class FakeFileMover(private val log: EventLog) : FileMover {
    /** Per staged path; a path not in the map moves successfully as "<file name>". */
    val results = mutableMapOf<String?, MoveResult>()
    val moves = mutableListOf<Pair<JobResult, String>>()

    /** Runs after the copy, before move() returns; lets a test change the world mid-move. */
    var afterMove: () -> Unit = {}

    override fun move(result: JobResult, destination: String): MoveResult {
        log.add("move:${result.path}")
        moves += result to destination
        afterMove()
        return results[result.path] ?: MoveResult.Moved(File(result.path ?: "unnamed").name)
    }

    var sweepPartialsCalls = 0
        private set

    /** Thrown from sweepPartials, despite its never-throws contract, to prove the run survives it. */
    var sweepThrows: Throwable? = null

    /** Runs inside sweepPartials before it returns; lets a test observe or block mid-sweep. */
    var onSweep: () -> Unit = {}

    override fun sweepPartials(): Int {
        log.add("sweepPartials")
        sweepPartialsCalls++
        onSweep()
        sweepThrows?.let { throw it }
        return 0
    }
}

internal class FakePreconditionChecker(private val log: EventLog) : PreconditionChecker {
    var result: PreconditionResult = PreconditionResult.Ok(OUT_DIR, BIN_DIR, DESTINATION)

    override fun check(): PreconditionResult {
        log.add("preconditions")
        return result
    }

    companion object {
        const val OUT_DIR = "/staging"
        const val BIN_DIR = "/ffmpeg-bin"
        const val DESTINATION = "content://tree/primary%3AMusic"
    }
}

internal class RecordingQueueNotifier(private val log: EventLog) : QueueNotifier {
    data class Progress(val label: String, val position: Int, val total: Int)
    data class Terminal(val jobId: Long, val title: String?, val text: String)

    val progress = mutableListOf<Progress>()
    val successes = mutableListOf<Terminal>()
    val failures = mutableListOf<Terminal>()
    val toasts = mutableListOf<String>()

    @Synchronized
    override fun progress(label: String, position: Int, total: Int) {
        log.add("progress:$label")
        progress += Progress(label, position, total)
    }

    @Synchronized
    override fun success(jobId: Long, title: String, savedTo: String) {
        log.add("success:$jobId")
        successes += Terminal(jobId, title, savedTo)
    }

    @Synchronized
    override fun failure(jobId: Long, message: String) {
        log.add("failure:$jobId")
        failures += Terminal(jobId, null, message)
    }

    @Synchronized
    override fun toast(message: String) {
        log.add("toast:$message")
        toasts += message
    }
}

internal class FakeStagingCleaner(private val log: EventLog) : StagingCleaner {
    var sweeps = 0
        private set

    @Synchronized
    override fun sweep() {
        log.add("sweep")
        sweeps++
    }
}

/** Everything a QueueProcessor test needs, wired to one event log. */
/** @param awaitRecovery the startup-recovery gate; open by default, so it never delays a test. */
internal class Harness(awaitRecovery: suspend () -> Unit = {}) {
    val log = EventLog()
    val store = FakeJobStore(log)
    val bridge = FakeDownloadBridge(log)
    val mover = FakeFileMover(log)
    val preconditions = FakePreconditionChecker(log)
    val notifier = RecordingQueueNotifier(log)
    val strings = FakeStringProvider()
    val cleaner = FakeStagingCleaner(log)
    val clearedStaged = java.util.Collections.synchronizedList(mutableListOf<File>())
    val processor = QueueProcessor(
        store, bridge, mover, preconditions, notifier, strings, fixedClock, cleaner,
        awaitRecovery = awaitRecovery,
        clearStaged = { clearedStaged += it },
    )
}
