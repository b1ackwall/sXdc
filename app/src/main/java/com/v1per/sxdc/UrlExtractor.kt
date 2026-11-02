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

        // A bare scheme ("https://" followed only by punctuation) is skipped for the next link.
        return LINK.findAll(trimmed)
            .map { trimTrailing(it.value) }
            .firstOrNull { link -> link.substringAfter("://").any { it.isLetterOrDigit() } }
    }

    /**
     * Drops trailing punctuation, except a ")" that closes a "(" inside the link, so a
     * Wikipedia-style ".../Foo_(band)" keeps its bracket while "(https://x/y)" loses it.
     */
    private fun trimTrailing(link: String): String {
        var end = link.length
        while (end > 0 && link[end - 1] in TRAILING_PUNCTUATION) {
            if (link[end - 1] == ')' && isBalanced(link, end)) break
            end--
        }
        return link.substring(0, end)
    }

    /** True while the first [end] characters hold at least as many "(" as ")". */
    private fun isBalanced(link: String, end: Int): Boolean {
        val head = link.subSequence(0, end)
        return head.count { it == '(' } >= head.count { it == ')' }
    }
}
