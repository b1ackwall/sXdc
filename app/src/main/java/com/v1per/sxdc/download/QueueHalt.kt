package com.v1per.sxdc.download

/**
 * Why a fatal stop halted the queue. [id] is the value persisted by [QueueHaltStore]; never
 * rename one, or a halt written by an older build reads back as no halt at all.
 */
enum class HaltKind(val id: String) {
    /** The staging volume or the destination ran out of room. */
    STORAGE("storage"),

    /** The site answered "too many requests". */
    RATE_LIMITED("rate_limited"),

    /** A precondition failed: no download folder, lost folder access, or no working ffmpeg. */
    SETUP("setup"),
    ;

    companion object {
        fun fromId(id: String?): HaltKind? = entries.firstOrNull { it.id == id }
    }
}

/** A persisted fatal stop: what stopped the queue, and when (wall-clock millis). */
data class QueueHalt(val kind: HaltKind, val haltedAt: Long)

/** Who asked the queue to start. */
enum class StartReason {
    /** MainActivity.onResume picking the queue back up; not a user decision. */
    RESUME_SCREEN,

    /** A new link was shared. The user's signal that they believe the cause is fixed. */
    NEW_SHARE,

    /** The Resume button on the main screen. */
    USER_RESUME,
}

/** What the caller should do with the halt and with the service. */
enum class StartDecision(val clearHalt: Boolean, val start: Boolean) {
    START(clearHalt = false, start = true),
    DO_NOT_START(clearHalt = false, start = false),
    CLEAR_AND_START(clearHalt = true, start = true),

    /** A halt with nothing left waiting means nothing; drop it and start nothing. */
    CLEAR_ONLY(clearHalt = true, start = false),
}

/**
 * The single rule for when a halted queue may run again. Pure: no Android, no clock of its own.
 *
 * There is deliberately no background timer: a dataSync service cannot be started from the
 * background, so an expired rate-limit halt is only picked up by the next visible onResume.
 */
object QueueHaltPolicy {

    /** How long a rate-limit halt holds before an onResume may try again. */
    const val RATE_LIMIT_HOLD_MS = 30L * 60 * 1000

    /**
     * @param pendingCount QUEUED plus DOWNLOADING rows, as the DAO's countPending returns
     * @param preconditionsOk evaluated only for a [HaltKind.SETUP] halt on [StartReason.RESUME_SCREEN]
     */
    fun decide(
        halt: QueueHalt?,
        now: Long,
        pendingCount: Int,
        reason: StartReason,
        preconditionsOk: () -> Boolean,
    ): StartDecision = when {
        // A share or a Resume tap is an explicit "go": it always clears, and always starts (a
        // share has just added a row; a Resume with nothing left simply stops again).
        reason != StartReason.RESUME_SCREEN ->
            if (halt != null) StartDecision.CLEAR_AND_START else StartDecision.START
        pendingCount <= 0 -> if (halt != null) StartDecision.CLEAR_ONLY else StartDecision.DO_NOT_START
        halt == null -> StartDecision.START
        autoClears(halt, now, preconditionsOk) -> StartDecision.CLEAR_AND_START
        else -> StartDecision.DO_NOT_START
    }

    private fun autoClears(halt: QueueHalt, now: Long, preconditionsOk: () -> Boolean): Boolean =
        when (halt.kind) {
            // Free space cannot be detected reliably (the needed size is unknown, and SAF free
            // space is optional per provider), so only the user can say it is fixed.
            HaltKind.STORAGE -> false
            HaltKind.SETUP -> preconditionsOk()
            HaltKind.RATE_LIMITED -> rateLimitExpired(halt.haltedAt, now)
        }

    /**
     * Expired at exactly [RATE_LIMIT_HOLD_MS]. A clock that moved back before [haltedAt] cannot
     * be trusted to ever catch up, so that counts as expired too rather than holding indefinitely.
     */
    fun rateLimitExpired(haltedAt: Long, now: Long): Boolean =
        now < haltedAt || now - haltedAt >= RATE_LIMIT_HOLD_MS
}
