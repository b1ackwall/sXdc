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
}

internal object ErrorMessageMapper {

    /**
     * Turn a classifier id into something worth putting on a lock screen.
     *
     * The classifier's own "unknown" maps to the generic line explicitly. Unexpected or absent ids
     * fall through to the same line rather than to raw text: a message the user cannot act on is
     * worse than one that simply admits the failure.
     */
    fun messageFor(kind: String?, strings: StringProvider): String = strings.get(
        when (kind) {
            ErrorKinds.NETWORK -> R.string.error_network
            ErrorKinds.UNAVAILABLE -> R.string.error_unavailable
            ErrorKinds.UNSUPPORTED -> R.string.error_unsupported
            ErrorKinds.RESTRICTED -> R.string.error_restricted
            ErrorKinds.AUTH -> R.string.error_auth
            ErrorKinds.STORAGE -> R.string.error_storage
            ErrorKinds.MEDIA_TOOLS -> R.string.error_media_tools
            ErrorKinds.NO_OUTPUT -> R.string.error_no_output
            ErrorKinds.ENGINE -> R.string.error_engine
            ErrorKinds.EXTRACTOR -> R.string.error_extractor
            ErrorKinds.RATE_LIMITED -> R.string.error_rate_limited
            ErrorKinds.UNKNOWN -> R.string.error_unknown
            else -> R.string.error_unknown
        },
    )
}
