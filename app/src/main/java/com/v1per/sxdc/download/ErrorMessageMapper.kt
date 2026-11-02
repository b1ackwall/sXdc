package com.v1per.sxdc.download

import com.v1per.sxdc.R

/** Mirrors the KIND_* ids in sxdc_classify.py (re-exported by sxdc_download.py). */
internal object ErrorKinds {
    const val NETWORK = "network"
    const val UNAVAILABLE = "unavailable"
    const val UNSUPPORTED = "unsupported"
    const val RESTRICTED = "restricted"
    const val AUTH = "auth"
    const val STORAGE = "storage"
    const val MEDIA_TOOLS = "media_tools"
    const val NO_OUTPUT = "no_output"
    const val ENGINE = "engine"
    const val EXTRACTOR = "extractor"
    const val RATE_LIMITED = "rate_limited"
    const val UNKNOWN = "unknown"

    /** The app asked the call to stop (#60): Emergency Stop, or a timeout. Never a halt. */
    const val ABORTED = "aborted"

    /** The finished file was definitely truncated, so it was not delivered (#46). Never a halt. */
    const val INCOMPLETE = "incomplete"
}

internal object ErrorMessageMapper {

    /**
     * Turn a classifier id into something worth putting on a lock screen.
     *
     * The classifier's own "unknown" maps to the generic line explicitly. Unexpected or absent ids
     * fall through to the same line rather than to raw text: a message the user cannot act on is
     * worse than one that simply admits the failure.
     */
    fun messageFor(kind: String?, strings: StringProvider): String =
        strings.get(MESSAGES[kind] ?: R.string.error_unknown)

    /** A table rather than a `when`: fourteen branches trip detekt's complexity limit. */
    private val MESSAGES: Map<String, Int> = mapOf(
        ErrorKinds.NETWORK to R.string.error_network,
        ErrorKinds.UNAVAILABLE to R.string.error_unavailable,
        ErrorKinds.UNSUPPORTED to R.string.error_unsupported,
        ErrorKinds.RESTRICTED to R.string.error_restricted,
        ErrorKinds.AUTH to R.string.error_auth,
        ErrorKinds.STORAGE to R.string.error_storage,
        ErrorKinds.MEDIA_TOOLS to R.string.error_media_tools,
        ErrorKinds.NO_OUTPUT to R.string.error_no_output,
        ErrorKinds.ENGINE to R.string.error_engine,
        ErrorKinds.EXTRACTOR to R.string.error_extractor,
        ErrorKinds.RATE_LIMITED to R.string.error_rate_limited,
        ErrorKinds.UNKNOWN to R.string.error_unknown,
        ErrorKinds.ABORTED to R.string.error_aborted,
        ErrorKinds.INCOMPLETE to R.string.error_incomplete,
    )
}
