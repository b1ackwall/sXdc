package com.v1per.sxdc.download

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagingFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Folders made read-only by a test; restored in [restorePermissions] so cleanup works. */
    private val lockedDirs = mutableListOf<File>()

    @After
    fun restorePermissions() {
        lockedDirs.forEach { it.setWritable(true, false) }
    }

    private fun create(name: String): File = tmp.newFile(name)

    @Test
    fun clear_deletesTheGivenFile() {
        val file = create("song.flac")

        StagingFiles.clear(file)

        assertFalse(file.exists())
    }

    @Test
    fun clear_deletesThumbnailAndPartSidecarsWithSameStem() {
        val file = create("song.flac")
        val sidecars = listOf("jpg", "jpeg", "png", "webp", "part").map { create("song.$it") }

        StagingFiles.clear(file)

        sidecars.forEach { assertFalse("${it.name} should be deleted", it.exists()) }
    }

    @Test
    fun clear_leavesUnrelatedFilesUntouched() {
        val file = create("song.flac")
        val otherStemFlac = create("other.flac")
        val otherStemJpg = create("other.jpg")
        val sameStemMp3 = create("song.mp3")
        val sameStemTxt = create("song.txt")
        val prefixStemJpg = create("song2.jpg")

        StagingFiles.clear(file)

        listOf(otherStemFlac, otherStemJpg, sameStemMp3, sameStemTxt, prefixStemJpg).forEach {
            assertTrue("${it.name} should survive", it.exists())
        }
    }

    @Test
    fun clear_multiDotName_usesEverythingBeforeLastDotAsStem() {
        val file = create("a.b.c.flac")
        val sidecar = create("a.b.c.jpg")
        val shorterStem = create("a.jpg")
        val midStem = create("a.b.jpg")

        StagingFiles.clear(file)

        assertFalse(file.exists())
        assertFalse(sidecar.exists())
        assertTrue(shorterStem.exists())
        assertTrue(midStem.exists())
    }

    @Test
    fun clear_stemWithSpacesAndBrackets_deletesTheFileAndItsSidecars() {
        val file = create("Song [abc123_-XYZ].flac")
        val sidecars = listOf("jpg", "jpeg", "png", "webp", "part").map { create("Song [abc123_-XYZ].$it") }
        val otherId = create("Song [other].jpg")
        val noId = create("Song.jpg")
        val prefixOnly = create("Song [abc123_-XYZ] (1).jpg")

        StagingFiles.clear(file)

        assertFalse(file.exists())
        sidecars.forEach { assertFalse("${it.name} should be deleted", it.exists()) }
        listOf(otherId, noId, prefixOnly).forEach { assertTrue("${it.name} should survive", it.exists()) }
    }

    @Test
    fun clear_dotInsideTheBracketedId_stillUsesEverythingBeforeTheLastDotAsStem() {
        val file = create("Song [a.b].flac")
        val sidecar = create("Song [a.b].webp")
        val cutAtIdDot = create("Song [a.webp")

        StagingFiles.clear(file)

        assertFalse(file.exists())
        assertFalse(sidecar.exists())
        assertTrue(cutAtIdDot.exists())
    }

    @Test
    fun clear_nameWithoutExtension_usesWholeNameAsStem() {
        val file = create("song")
        val sidecar = create("song.png")
        val unrelated = create("song.mp3")

        StagingFiles.clear(file)

        assertFalse(file.exists())
        assertFalse(sidecar.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun clear_missingFile_doesNotThrowAndLeavesUnrelatedFiles() {
        val missing = File(tmp.root, "gone.flac")
        val unrelated = create("other.flac")
        val sameStemOtherExt = create("gone.mp3")

        StagingFiles.clear(missing)

        assertFalse(missing.exists())
        assertTrue(unrelated.exists())
        assertTrue(sameStemOtherExt.exists())
    }

    /**
     * Pins CURRENT behaviour: sidecar lookup is case-sensitive, so an upper-case .JPG survives.
     * Skipped on a case-insensitive filesystem, where the lookup would match regardless.
     */
    @Test
    fun clear_upperCaseSidecarExtension_pinsCurrentCaseSensitiveBehavior() {
        val probe = create("probe.jpg")
        assumeFalse("filesystem is case-insensitive", File(tmp.root, "probe.JPG").exists())
        probe.delete()

        val file = create("song.flac")
        val upper = create("song.JPG")

        StagingFiles.clear(file)

        assertFalse(file.exists())
        assertTrue(upper.exists())
    }

    /**
     * Pins CURRENT behaviour: yt-dlp names partial output `<title>.<ext>.part` (for example
     * `song.webm.part`), but clear() only looks for `<stem>.part`, so real partials survive.
     * Issue candidate.
     */
    @Test
    fun clear_ytDlpStylePartFile_pinsCurrentBehaviorOfNotDeletingIt() {
        val file = create("song.flac")
        val fullNamePart = create("song.flac.part")
        val sourceContainerPart = create("song.webm.part")

        StagingFiles.clear(file)

        assertTrue(fullNamePart.exists())
        assertTrue(sourceContainerPart.exists())
    }

    @Test
    fun clear_whenDeletionIsImpossible_doesNotThrow() {
        val dir = tmp.newFolder("locked")
        val file = File(dir, "song.flac").apply { createNewFile() }
        val sidecar = File(dir, "song.jpg").apply { createNewFile() }
        val probe = File(dir, "probe").apply { createNewFile() }

        assertTrue(dir.setWritable(false, false))
        lockedDirs += dir
        assumeFalse("process can delete from a read-only folder (root?)", probe.delete())

        StagingFiles.clear(file)

        assertTrue(file.exists())
        assertTrue(sidecar.exists())
    }
}
