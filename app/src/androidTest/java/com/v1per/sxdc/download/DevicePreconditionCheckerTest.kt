package com.v1per.sxdc.download

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.settings.SxdcSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The folder rules of DevicePreconditionChecker, against the test settings file (#29).
 *
 * The OK result is not covered here: it needs a persisted write grant on a SAF tree, which only a
 * test DocumentsProvider can give. The media-tools failure is not testable without breaking the
 * install. Both cases below assume the bundled ffmpeg is usable, as FfmpegBinariesDeviceTest checks.
 */
@RunWith(AndroidJUnit4::class)
class DevicePreconditionCheckerTest {

    @get:Rule
    val settings = TestSettingsRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val checker = DevicePreconditionChecker(context)

    /** Stores [uri] the way SxdcSettings.saveTreeUri would, but without taking a grant for it. */
    private fun storeFolderWithoutGrant(uri: String) {
        settings.prefs.edit().putString(KEY_TREE_URI, uri).commit()
    }

    @Test
    fun noFolderConfigured_failsWithNoFolder() {
        assertEquals(null, SxdcSettings.treeUri(context))

        assertEquals(PreconditionResult.Failed(listOf(DownloadPreconditions.REASON_NO_FOLDER)), checker.check())
    }

    @Test
    fun blankStoredFolder_countsAsNoFolder() {
        storeFolderWithoutGrant(" ")

        assertEquals(PreconditionResult.Failed(listOf(DownloadPreconditions.REASON_NO_FOLDER)), checker.check())
    }

    @Test
    fun folderWithoutPersistedGrant_failsWithFolderLost() {
        storeFolderWithoutGrant(UNGRANTED_TREE)
        // Proves the key above is the one SxdcSettings reads, and that the grant really is absent.
        assertEquals(UNGRANTED_TREE.toUri(), SxdcSettings.treeUri(context))
        assertFalse(SxdcSettings.hasWriteGrant(context, UNGRANTED_TREE.toUri()))

        assertEquals(PreconditionResult.Failed(listOf(DownloadPreconditions.REASON_FOLDER_LOST)), checker.check())
    }

    private companion object {
        /** SxdcSettings' private key for the destination tree URI. */
        const val KEY_TREE_URI = "download_tree_uri"

        /** A well-formed tree URI the app holds no grant for. Never opened, only compared. */
        const val UNGRANTED_TREE =
            "content://com.android.externalstorage.documents/tree/primary%3ASxdcTestNoGrant"
    }
}
