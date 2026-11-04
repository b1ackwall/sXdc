package com.v1per.sxdc.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.saf.TestSafFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** The rename probe, its cache and the halt it lifts, over TestDocumentsProvider with a real grant. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class RenameProbeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = TestSafFolder()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestSettingsRule()).around(folder)

    @Test
    fun folderWithRename_isSupported_cached_andLeavesNothingBehind() {
        val tree = folder.open(supportsRename = true)

        assertEquals(RenameSupport.SUPPORTED, RenameProbe.probeAndStore(context, tree))

        assertEquals(RenameSupport.SUPPORTED, RenameSupportStore.read(context, tree))
        assertEquals(emptyList<String>(), folder.names(tree))
    }

    @Test
    fun folderWithoutRename_isUnsupported_cached_andLeavesNothingBehind() {
        val tree = folder.open(supportsRename = false)

        assertEquals(RenameSupport.UNSUPPORTED, RenameProbe.probeAndStore(context, tree))

        assertEquals(RenameSupport.UNSUPPORTED, RenameSupportStore.read(context, tree))
        assertEquals(emptyList<String>(), folder.names(tree))
    }

    @Test
    fun neverProbed_readsUnknown() {
        val tree = folder.open()

        assertEquals(RenameSupport.UNKNOWN, RenameSupportStore.read(context, tree))
    }

    @Test
    fun folderWithoutAGrant_probesAsUnknown_andDoesNotThrow() {
        val tree = folder.ungrantedTree()

        assertEquals(RenameSupport.UNKNOWN, RenameProbe.probeAndStore(context, tree))
    }

    @Test
    fun preconditions_refuseAFolderCachedAsUnsupported_andNotAnUnknownOne() {
        val tree = folder.open(supportsRename = false)
        RenameSupportStore.write(context, tree, RenameSupport.UNKNOWN)
        assertTrue(failedReasons().none { it == DownloadPreconditions.REASON_FOLDER_UNSAFE })

        RenameProbe.probeAndStore(context, tree)

        assertTrue(failedReasons().contains(DownloadPreconditions.REASON_FOLDER_UNSAFE))
    }

    @Test
    fun reprobeOnResume_picksUpAFolderThatNowRenames_butIgnoresAKnownGoodOne() {
        val tree = folder.open(supportsRename = true)
        RenameSupportStore.write(context, tree, RenameSupport.UNSUPPORTED)

        RenameProbe.reprobeIfUnsupported(context)

        assertEquals(RenameSupport.SUPPORTED, RenameSupportStore.read(context, tree))

        // Only a cached "false" is re-measured.
        RenameSupportStore.write(context, tree, RenameSupport.UNKNOWN)
        RenameProbe.reprobeIfUnsupported(context)
        assertEquals(RenameSupport.UNKNOWN, RenameSupportStore.read(context, tree))
    }

    @Test
    fun choosingAFolder_liftsADestinationHalt_butNotAStorageHalt() {
        val tree = folder.open()
        QueueHaltStore.set(context, HaltKind.DESTINATION, 1L)

        RenameProbe.afterFolderChosen(context, tree)

        assertNull(QueueHaltStore.read(context))

        QueueHaltStore.set(context, HaltKind.STORAGE, 1L)
        RenameProbe.afterFolderChosen(context, tree)
        assertNotNull(QueueHaltStore.read(context))
        QueueHaltStore.clear(context)
    }

    private fun failedReasons(): List<String> =
        (DownloadPreconditions.check(context) as? DownloadPreconditions.Result.Failed)?.reasons.orEmpty()
}
