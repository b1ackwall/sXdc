package com.v1per.sxdc.download

import com.v1per.sxdc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Delivery into the destination: temp name, verify, rename, and never a partial audio file. */
class StagedFileDeliveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tree = FakeDestinationTree()
    private val logLines = mutableListOf<Pair<Boolean, String>>()
    private val delivery = StagedFileDelivery(
        strings = FakeStringProvider(),
        log = { isError, message, _ -> logLines += isError to message },
        newTempId = { "uuid" },
    )

    private val tempName = ".sxdc-uuid.partial"

    private fun staged(name: String = "Song.flac", content: String = "fLaC new audio bytes"): File =
        File(tmp.root, name).apply { writeText(content) }

    private fun deliver(source: File = staged(), expected: Long = source.length()) =
        delivery.deliver(tree, source, expected)

    private fun msg(id: Int) = FakeStringProvider.render(id)

    private fun hasAudioExtension(name: String) =
        name.substringAfterLast('.', "").lowercase() in StagedFileDelivery.AUDIO_EXTENSIONS

    // ------------------------------------------------------------------ temp document

    @Test
    fun tempDocument_isDotSxdcPartial_createdAsOctetStream() {
        deliver()

        val (name, mime) = tree.createdMimeTypes.single()
        assertTrue(name.startsWith(".sxdc-"))
        assertTrue(name.endsWith(".partial"))
        assertFalse(hasAudioExtension(name))
        assertEquals("application/octet-stream", mime)
    }

    @Test
    fun tempDocument_usesAFreshRandomIdByDefault() {
        val names = (1..2).map {
            val t = FakeDestinationTree()
            StagedFileDelivery(FakeStringProvider()).deliver(t, staged(), staged().length())
            t.createdMimeTypes.single().first
        }
        assertTrue(names[0] != names[1])
    }

    @Test
    fun duringCopy_noDocumentHasTheFinalNameOrAnAudioExtension() {
        tree.seed("Other.flac")
        val seenMidCopy = mutableListOf<List<String>>()
        tree.onWrite = { seenMidCopy += it.names() }

        deliver(staged(content = "x".repeat(20_000)))

        assertTrue(seenMidCopy.isNotEmpty())
        seenMidCopy.forEach { names ->
            assertFalse("final name visible mid-copy: $names", "Song.flac" in names)
            assertEquals(listOf("Other.flac"), names.filter(::hasAudioExtension))
        }
    }

    // ------------------------------------------------------------------ success

    @Test
    fun success_onlyTheFinalDocumentRemains_withTheStagedBytes() {
        val result = deliver(staged(content = "the audio"))

        assertEquals(MoveResult.Moved("Song.flac"), result)
        assertEquals(listOf("Song.flac"), tree.names())
        assertEquals("the audio", tree.content("Song.flac"))
        assertTrue(tree.deleteCalls.isEmpty())
    }

    @Test
    fun success_reportsTheNameTheProviderEndedUpWith() {
        // A race: something took the name between our listing and the rename.
        tree.renamedName = { it.replace(".flac", " (7).flac") }

        assertEquals(MoveResult.Moved("Song (7).flac"), deliver())
        assertEquals(listOf("Song (7).flac"), tree.names())
    }

    // ------------------------------------------------------------------ failures leave nothing

    @Test
    fun shortCopy_leavesNothing_andFailsQueueWideWithStorage() {
        val source = staged()

        val result = deliver(source, expected = source.length() + 1)

        assertEquals(MoveResult.Failed(msg(R.string.error_storage), queueWide = true), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun copyException_leavesNothing_andFailsWithCopyFailed() {
        tree.writeThrowsAfter = 0

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_copy_failed)), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun copyOutOfSpace_leavesNothing_andFailsQueueWideWithStorage() {
        tree.writeThrowsAfter = 4
        tree.writeError = IOException("write failed", IOException("ENOSPC (No space left on device)"))

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_storage), queueWide = true), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun renameFailure_leavesNothing() {
        tree.renameReturnsNull = true

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_copy_failed)), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun renameException_leavesNothing() {
        tree.renameThrows = IllegalStateException("provider exploded")

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_copy_failed)), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun renameThatKeepsATempName_isAFailure_andLeavesNothing() {
        tree.renamedName = { tempName }

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_copy_failed)), result)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun deleteFailure_isLoggedAsAnError_andDoesNotThrow() {
        tree.renameReturnsNull = true
        tree.deleteReturnsFalse = true

        val result = deliver()

        assertTrue(result is MoveResult.Failed)
        assertEquals(1, tree.deleteCalls.size)
        assertTrue(logLines.any { (isError, line) -> isError && line.endsWith(": false") })
    }

    @Test
    fun deleteException_isLogged_andDoesNotThrow() {
        tree.renameReturnsNull = true
        tree.deleteThrows = IllegalStateException("gone")

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_copy_failed)), result)
        assertTrue(logLines.any { (isError, line) -> isError && "threw" in line })
        assertTrue(logLines.any { (isError, line) -> isError && line.endsWith(": false") })
    }

    @Test
    fun successfulCleanup_isLogged() {
        tree.renameReturnsNull = true

        deliver()

        assertTrue(
            logLines.any { (_, line) -> line.startsWith("Deleted temporary document") && line.endsWith(": true") },
        )
    }

    // ------------------------------------------------------------------ existing documents

    @Test
    fun existingFinalName_isNeverReplaced_andTheNewFileGetsOne() {
        tree.seed("Song.flac", content = "old bytes")

        val result = deliver(staged(content = "new bytes"))

        assertEquals(MoveResult.Moved("Song (1).flac"), result)
        assertEquals("old bytes", tree.content("Song.flac"))
        assertEquals("new bytes", tree.content("Song (1).flac"))
    }

    @Test
    fun secondCollision_getsTwo() {
        tree.seed("Song.flac", content = "first")
        tree.seed("Song (1).flac", content = "second")

        val result = deliver(staged(content = "third"))

        assertEquals(MoveResult.Moved("Song (2).flac"), result)
        assertEquals("first", tree.content("Song.flac"))
        assertEquals("second", tree.content("Song (1).flac"))
        assertEquals(listOf("Song.flac", "Song (1).flac", "Song (2).flac"), tree.names())
    }

    @Test
    fun collision_isCaseInsensitive() {
        tree.seed("SONG.FLAC", content = "upper")
        tree.seed("song (1).flac", content = "lower")

        assertEquals(MoveResult.Moved("Song (2).flac"), deliver())
        assertEquals("upper", tree.content("SONG.FLAC"))
        assertEquals("lower", tree.content("song (1).flac"))
    }

    @Test
    fun existingDocuments_areNeverDeletedOrWritten_onAnyFailure() {
        tree.seed("Song.flac", content = "keep me")
        tree.renameReturnsNull = true

        deliver()

        assertEquals(listOf("Song.flac"), tree.names())
        assertEquals("keep me", tree.content("Song.flac"))
    }

    @Test
    fun freeName_handlesNamesWithoutAnExtension() {
        assertEquals("Song", StagedFileDelivery.freeName("Song", emptyList()))
        assertEquals("Song (1)", StagedFileDelivery.freeName("Song", listOf("song")))
        assertEquals(".flac (1)", StagedFileDelivery.freeName(".flac", listOf(".flac")))
    }

    // ------------------------------------------------------------------ provider refusals

    @Test
    fun providerAlteredTempName_endingInFlac_isRejectedAndDeleted() {
        tree.createdName = { "$it.flac" }

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_create_document), queueWide = true), result)
        assertTrue(tree.names().isEmpty())
        assertTrue(tree.docs.values.none { it.bytes.size() > 0 })
    }

    @Test
    fun providerAlteredTempName_withUpperCaseAudioExtension_isRejected() {
        tree.createdName = { "$it.MP3" }

        assertTrue(deliver() is MoveResult.Failed)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun providerAlteredTempName_withoutOurPrefix_isRejected() {
        tree.createdName = { "sxdc-uuid.partial" }

        assertTrue(deliver() is MoveResult.Failed)
        assertTrue(tree.names().isEmpty())
    }

    @Test
    fun noRenameSupport_failsWithTheNewMessage_andLeavesNothing() {
        tree.renameSupported = false
        val copied = mutableListOf<Int>()
        tree.onWrite = { copied += 1 }

        val result = deliver()

        assertEquals(MoveResult.Failed(msg(R.string.error_rename_unsupported), queueWide = true), result)
        assertTrue(tree.names().isEmpty())
        assertTrue("copied into a temp that can never be renamed", copied.isEmpty())
    }

    @Test
    fun listingFailure_failsWithoutCreatingAnything() {
        tree.listReturnsNull = true

        assertEquals(MoveResult.Failed(msg(R.string.error_create_document)), deliver())
        assertTrue(tree.createdMimeTypes.isEmpty())
    }

    @Test
    fun listingException_failsWithoutCreatingAnything() {
        tree.listThrows = SecurityException("grant gone")

        assertEquals(MoveResult.Failed(msg(R.string.error_create_document)), deliver())
        assertTrue(tree.createdMimeTypes.isEmpty())
    }

    @Test
    fun createRefused_failsWithCreateDocument() {
        tree.createReturnsNull = true

        assertEquals(MoveResult.Failed(msg(R.string.error_create_document)), deliver())
        assertTrue(tree.names().isEmpty())
    }

    // ------------------------------------------------------------------ partial sweep

    @Test
    fun sweep_deletesOnlySxdcPartialChildren_andReturnsTheCount() {
        tree.seed(".sxdc-a.partial")
        tree.seed(".sxdc-b.partial")
        tree.seed(".sxdc-x.flac")
        tree.seed("x.partial")
        tree.seed("Song.flac")
        tree.seed("notes.txt")
        tree.seed(".sxdc-dir.partial", isDirectory = true)
        tree.seed("Album", isDirectory = true)

        val deleted = delivery.sweepPartials(tree)

        assertEquals(2, deleted)
        assertEquals(
            listOf(".sxdc-x.flac", "x.partial", "Song.flac", "notes.txt", ".sxdc-dir.partial", "Album"),
            tree.names(),
        )
    }

    @Test
    fun sweep_emptyFolder_returnsZero() {
        assertEquals(0, delivery.sweepPartials(tree))
    }

    @Test
    fun sweep_listingFailure_returnsZero_andDoesNotThrow() {
        tree.listThrows = SecurityException("grant gone")
        assertEquals(0, delivery.sweepPartials(tree))

        tree.listThrows = null
        tree.listReturnsNull = true
        assertEquals(0, delivery.sweepPartials(tree))
    }

    @Test
    fun sweep_deleteFailures_areNotCounted_andDoNotThrow() {
        tree.seed(".sxdc-a.partial")
        tree.seed(".sxdc-b.partial")
        tree.deleteReturnsFalse = true
        assertEquals(0, delivery.sweepPartials(tree))

        tree.deleteReturnsFalse = false
        tree.deleteThrows = IllegalStateException("gone")
        assertEquals(0, delivery.sweepPartials(tree))
        assertEquals(4, tree.deleteCalls.size)
    }

    @Test
    fun sweep_removesWhatAFailedDeliveryCouldNotDelete() {
        tree.renameReturnsNull = true
        tree.deleteReturnsFalse = true
        deliver()
        assertEquals(listOf(tempName), tree.names())

        tree.deleteReturnsFalse = false
        assertEquals(1, delivery.sweepPartials(tree))
        assertTrue(tree.names().isEmpty())
    }
}
