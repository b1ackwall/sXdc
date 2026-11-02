package com.v1per.sxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {

    private val send = "android.intent.action.SEND"

    private fun extract(text: String?, action: String? = send) = UrlExtractor.extract(action, text)

    @Test
    fun plainLink() {
        assertEquals("https://youtu.be/abc", extract("https://youtu.be/abc"))
    }

    @Test
    fun plainLink_withSurroundingWhitespace() {
        assertEquals("https://youtu.be/abc", extract("   https://youtu.be/abc \n"))
    }

    @Test
    fun linkInsideText() {
        assertEquals(
            "https://youtu.be/abc",
            extract("Check out this song by Artist https://youtu.be/abc it is great"),
        )
    }

    @Test
    fun twoLinks_firstWins() {
        assertEquals("https://a.example/1", extract("https://a.example/1 and https://b.example/2"))
    }

    @Test
    fun musicYoutubePlaylistLink_keepsQuery() {
        val url = "https://music.youtube.com/watch?v=abc123&list=OLAK5uy_xyz"
        assertEquals(url, extract("Song - Artist $url"))
    }

    @Test
    fun youtuBeLink_keepsSiParameter() {
        val url = "https://youtu.be/dQw4w9WgXcQ?si=AbC-123_x"
        assertEquals(url, extract(url))
    }

    @Test
    fun plainHttp() {
        assertEquals("http://example.com/x", extract("see http://example.com/x"))
    }

    @Test
    fun upperCaseScheme_isAccepted_andReturnedAsWritten() {
        assertEquals("HTTPS://EXAMPLE.COM/X", extract("HTTPS://EXAMPLE.COM/X"))
    }

    @Test
    fun trailingPeriod_stripped() {
        assertEquals("https://youtu.be/abc", extract("Listen: https://youtu.be/abc."))
    }

    @Test
    fun trailingComma_stripped() {
        assertEquals("https://youtu.be/abc", extract("https://youtu.be/abc, then more"))
    }

    @Test
    fun trailingParenthesis_stripped() {
        assertEquals("https://youtu.be/abc", extract("(https://youtu.be/abc)"))
    }

    @Test
    fun repeatedTrailingPunctuation_allStripped() {
        assertEquals("https://youtu.be/abc", extract("[see https://youtu.be/abc)].!?'"))
    }

    @Test
    fun queryAndFragment_keptIntact() {
        val url = "https://example.com/path/a_b-c?x=1&y=two#frag"
        assertEquals(url, extract("go to $url now"))
    }

    @Test
    fun textWithoutLink_isNull() {
        assertNull(extract("just some words, no link here"))
    }

    @Test
    fun emptyBlankAndNullText_areNull() {
        assertNull(extract(""))
        assertNull(extract("   \n\t "))
        assertNull(extract(null))
    }

    @Test
    fun wrongAction_isNull() {
        assertNull(extract("https://youtu.be/abc", action = "android.intent.action.VIEW"))
    }

    @Test
    fun nullAction_isNull() {
        assertNull(extract("https://youtu.be/abc", action = null))
    }

    @Test
    fun ftpScheme_isNull() {
        assertNull(extract("ftp://x/y"))
    }

    @Test
    fun linkWithoutScheme_isNull() {
        assertNull(extract("www.youtube.com/x"))
    }

    @Test
    fun linkBetweenLineBreaks() {
        assertEquals(
            "https://music.youtube.com/watch?v=abc",
            extract("Song title\nhttps://music.youtube.com/watch?v=abc\nShared from the app"),
        )
    }

    @Test
    fun angleBracketsAndQuotes_endTheLink() {
        assertEquals("https://example.com/a", extract("<https://example.com/a>"))
        assertEquals("https://example.com/b", extract("\"https://example.com/b\""))
    }

    @Test
    fun wikipediaStyleLink_keepsBalancedParenthesis() {
        val url = "https://en.wikipedia.org/wiki/Foo_(band)"
        assertEquals(url, extract(url))
    }

    @Test
    fun wikipediaStyleLink_midSentence_keepsBalancedParenthesis() {
        val url = "https://en.wikipedia.org/wiki/Foo_(band)"
        assertEquals(url, extract("see $url now"))
    }

    @Test
    fun balancedParenthesisInQuery_kept() {
        val url = "https://example.com/a?q=(x)"
        assertEquals(url, extract(url))
    }

    @Test
    fun wikipediaStyleLinkInsideBrackets_dropsOnlyTheOuterParenthesis() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(band)",
            extract("(see https://en.wikipedia.org/wiki/Foo_(band))"),
        )
    }

    @Test
    fun schemeFollowedOnlyByPunctuation_isNull() {
        assertNull(extract("https://."))
    }

    @Test
    fun bareScheme_isSkippedForTheNextLink() {
        assertEquals("https://youtu.be/x", extract("https://, then https://youtu.be/x"))
    }

    /** Pins current behaviour, which matches the old Patterns matcher: no word-boundary rule. */
    @Test
    fun schemeGluedToText_stillExtractsTheLink() {
        assertEquals("https://youtu.be/abc", extract("xhttps://youtu.be/abc"))
    }

    @Test
    fun localhost_isAccepted() {
        assertEquals("http://localhost:8080/x", extract("http://localhost:8080/x"))
    }

    @Test
    fun youtubeShare_withSi_extractsExactly() {
        val url = "https://youtu.be/dQw4w9WgXcQ?si=Xy-9_aBcDeFgHiJk"
        assertEquals(url, extract("Watch this! $url"))
    }

    @Test
    fun youtubeMusicShare_withListAndSi_extractsExactly() {
        val url = "https://music.youtube.com/watch?v=abc-_123XYZ&list=OLAK5uy_kAbC-123&si=Qw_E-rTy"
        assertEquals(url, extract("Song - Artist\n$url"))
    }
}
