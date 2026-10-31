package com.v1per.sxdc.download

import com.v1per.sxdc.GoldenOptions
import com.v1per.sxdc.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessageMapperTest {

    private val strings = FakeStringProvider()

    private fun map(kind: String?) = ErrorMessageMapper.messageFor(kind, strings)

    @Test
    fun everyKnownKind_mapsToItsString() {
        mapOf(
            ErrorKinds.NETWORK to R.string.error_network,
            ErrorKinds.UNAVAILABLE to R.string.error_unavailable,
            ErrorKinds.UNSUPPORTED to R.string.error_unsupported,
            ErrorKinds.RESTRICTED to R.string.error_restricted,
            ErrorKinds.AUTH to R.string.error_auth,
            ErrorKinds.STORAGE to R.string.error_storage,
            ErrorKinds.MEDIA_TOOLS to R.string.error_media_tools,
            ErrorKinds.NO_OUTPUT to R.string.error_no_output,
            ErrorKinds.ENGINE to R.string.error_engine,
        ).forEach { (kind, id) ->
            assertEquals(kind, FakeStringProvider.render(id), map(kind))
        }
    }

    @Test
    fun nullKind_mapsToUnknown() {
        assertEquals(FakeStringProvider.render(R.string.error_unknown), map(null))
    }

    @Test
    fun unrecognizedKind_mapsToUnknown() {
        assertEquals(FakeStringProvider.render(R.string.error_unknown), map("something_new"))
        assertEquals(FakeStringProvider.render(R.string.error_unknown), map(""))
        // Case-sensitive: an upper-cased known kind is not recognized.
        assertEquals(FakeStringProvider.render(R.string.error_unknown), map("NETWORK"))
    }

    /** Python's "unknown" has no Kotlin constant; it reaches error_unknown through the else branch. */
    @Test
    fun pythonUnknownKind_mapsToUnknown() {
        assertEquals(FakeStringProvider.render(R.string.error_unknown), map("unknown"))
    }

    @Test
    fun errorKindConstants_matchGoldenPythonKinds_exceptUnknown() {
        val kotlinKinds = setOf(
            ErrorKinds.NETWORK, ErrorKinds.UNAVAILABLE, ErrorKinds.UNSUPPORTED,
            ErrorKinds.RESTRICTED, ErrorKinds.AUTH, ErrorKinds.STORAGE,
            ErrorKinds.MEDIA_TOOLS, ErrorKinds.NO_OUTPUT, ErrorKinds.ENGINE,
        )
        assertEquals(GoldenOptions.errorKinds.toSet() - "unknown", kotlinKinds)
        assertEquals(10, GoldenOptions.errorKinds.size)
    }
}
