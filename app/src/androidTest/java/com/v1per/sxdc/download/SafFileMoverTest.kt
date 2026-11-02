package com.v1per.sxdc.download

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.saf.TestSafFolder
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The real SafFileMover and SafDestinationTree over TestDocumentsProvider: a separate process and
 * uid reached through a persisted SAF grant, as a user's folder is (#29). The delivery rules
 * themselves are covered on the JVM by StagedFileDeliveryTest; the results asserted here are the
 * same MoveResult values.
 */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class SafFileMoverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = TestSafFolder()
    private val staging = TemporaryFolder(context.cacheDir)

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestSettingsRule()).around(folder).around(staging)

    private val strings = ContextStringProvider(context)
    private val mover = SafFileMover(context, strings)

    private fun msg(id: Int) = strings.get(id)

    private fun staged(name: String, size: Int): File =
        staging.newFile(name).apply { writeBytes(ByteArray(size) { (it * 7).toByte() }) }

    private fun job(file: File, size: Long? = file.length()) =
        JobResult(ok = true, path = file.path, title = "t", size = size, kind = null, error = null, trace = null)

    @Test
    fun move_deliversTheExactBytes_underTheStagedName_andClearsTheStagedFile() {
        val tree = folder.open()
        val source = staged(SONG, 4096)
        val bytes = source.readBytes()

        assertEquals(MoveResult.Moved(SONG), mover.move(job(source), tree.toString()))

        assertEquals(listOf(SONG), folder.names(tree))
        assertArrayEquals(bytes, folder.read(tree, SONG))
        assertFalse("staged file was not cleared", source.exists())
    }

    @Test
    fun sameNameTwice_theSecondGetsASuffix_andTheFirstIsUntouched() {
        val tree = folder.open()
        val firstSource = staged(SONG, 100)
        val first = firstSource.readBytes()
        mover.move(job(firstSource), tree.toString())
        val second = staged(SONG, 200)

        assertEquals(MoveResult.Moved(SONG_SUFFIXED), mover.move(job(second), tree.toString()))

        assertEquals(listOf(SONG_SUFFIXED, SONG).sorted(), folder.names(tree))
        assertArrayEquals(first, folder.read(tree, SONG))
        assertEquals(200, folder.read(tree, SONG_SUFFIXED).size)
    }

    @Test
    fun sizeMismatch_failsClosedWithStorage_queueWide_andLeavesNothing() {
        val tree = folder.open()
        val source = staged(SONG, 100)

        val result = mover.move(job(source, size = 101), tree.toString())

        assertEquals(MoveResult.Failed(msg(R.string.error_storage), queueWide = true), result)
        assertEquals(emptyList<String>(), folder.names(tree))
        assertTrue("staged file must survive a failed move", source.exists())
    }

    @Test
    fun renameUnsupported_failsClosed_queueWide_andLeavesNothing() {
        val tree = folder.open(supportsRename = false)
        val source = staged(SONG, 10)

        val result = mover.move(job(source), tree.toString())

        assertEquals(MoveResult.Failed(msg(R.string.error_rename_unsupported), queueWide = true), result)
        assertEquals(emptyList<String>(), folder.names(tree))
        assertTrue(source.exists())
    }

    @Test
    fun missingStagedFile_failsForThisJobOnly() {
        val tree = folder.open()
        val missing = File(staging.root, "gone.flac")

        val result = mover.move(job(missing, size = 1), tree.toString())

        assertEquals(MoveResult.Failed(msg(R.string.error_staged_file_missing)), result)
        assertEquals(emptyList<String>(), folder.names(tree))
    }

    @Test
    fun treeWithoutAGrant_failsQueueWideWithFolderLost() {
        val source = staged(SONG, 10)

        val result = mover.move(job(source), folder.ungrantedTree().toString())

        assertEquals(MoveResult.Failed(DownloadPreconditions.REASON_FOLDER_LOST, queueWide = true), result)
        assertTrue(source.exists())
    }

    @Test
    fun sweepPartials_deletesOnlySxdcPartials_inTheDestination() {
        val tree = folder.open()
        val kept = listOf("keep.flac", ".sxdc-notes.txt", "other.partial")
        (kept + listOf(".sxdc-a.partial", ".sxdc-b.partial")).forEach { folder.create(tree, it) }

        assertEquals(2, mover.sweepPartials())

        assertEquals(kept.sorted(), folder.names(tree))
    }

    @Test
    fun sweepPartials_withNoDestinationConfigured_touchesNothing() {
        val tree = folder.open(asDestination = false)
        folder.create(tree, ".sxdc-a.partial")

        assertEquals(0, mover.sweepPartials())

        assertEquals(listOf(".sxdc-a.partial"), folder.names(tree))
    }

    @Test
    fun close_releasesAndRevokesEveryGrant() {
        val tree = folder.open()
        assertEquals(1, folder.persistedGrants().size)

        folder.close()

        assertEquals(emptyList<Any>(), folder.persistedGrants())
        for (mode in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkCallingOrSelfUriPermission(tree, mode))
        }
    }

    private companion object {
        const val SONG = "Song [abc123].flac"
        const val SONG_SUFFIXED = "Song [abc123] (1).flac"
    }
}
