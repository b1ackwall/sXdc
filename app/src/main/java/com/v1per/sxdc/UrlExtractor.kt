package com.v1per.sxdc

/**
 * Pulls the first http(s) link out of shared text.
 *
 * Shared text is rarely a bare URL -- "Check this out: https://youtu.be/x" is typical -- so the
 * first web link is taken from the payload rather than trusting the whole string.
 *
 * Plain Kotlin on purpose (no android imports), so it runs on the JVM in unit tests. It replaced
 * android.util.Patterns.WEB_URL, which is null on the plain JVM.
 */
internal object UrlExtractor {

    /** Intent.ACTION_SEND's value, as a literal so this file needs no android import. */
    private const val ACTION_SEND = "android.intent.action.SEND"

    private val LINK = Regex("(?i)https?://[^\\s<>\"]+")

    private const val TRAILING_PUNCTUATION = ".,;:!?)]}'"

    fun extract(action: String?, text: String?): String? {
        if (action != ACTION_SEND) return null
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val match = LINK.find(trimmed) ?: return null
        return match.value.trimEnd { it in TRAILING_PUNCTUATION }
    }
}
