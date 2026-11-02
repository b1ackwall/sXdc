package com.v1per.sxdc.download

import com.v1per.sxdc.R

/** Mirrors the WARN_* ids in sxdc_embed.py: what a delivered file lacks (#45). */
internal object WarningKinds {
    const val NO_COVER = "no_cover"
    const val NO_TAGS = "no_tags"
    const val SHORT_AUDIO = "short_audio"
}

internal object WarningMessageMapper {

    /**
     * The note for a delivered file with [warnings], or null when there is nothing to say.
     *
     * No tags and no cover together read as one line; the parts join with a space. Unknown ids
     * are dropped: a note is a courtesy, and a raw id is not something to show anyone.
     */
    fun noteFor(warnings: List<String>, strings: StringProvider): String? {
        val tags = WarningKinds.NO_TAGS in warnings
        val cover = WarningKinds.NO_COVER in warnings
        val parts = listOfNotNull(
            when {
                tags && cover -> R.string.warn_no_tags_or_cover
                tags -> R.string.warn_no_tags
                cover -> R.string.warn_no_cover
                else -> null
            },
            R.string.warn_short_audio.takeIf { WarningKinds.SHORT_AUDIO in warnings },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ") { strings.get(it) }
    }
}
