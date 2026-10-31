package com.v1per.sxdc.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

class StagingSweeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Folders whose permissions a test changed; restored so TemporaryFolder can clean up. */
    private val lockedDirs = mutableListOf<File>()

    @After
    fun restorePermissions() {
        lockedDirs.forEach {
            it.setReadable(true, false)
            it.setWritable(true, false)
            it.setExecutable(true, false)
        }
    }

    private fun stagingDir(): File = tmp.newFolder("staging")

    private fun File.child(name: String, text: String = "x"): File = File(this, name).apply { writeText(text) }

    /** Creates [link] -> [target], skipping the test where the filesystem cannot do symlinks. */
    private fun symlink(link: File, target: File) {
        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            false
        }
        assumeTrue("symbolic links are not supported here", created)
    }

    private fun File.existsNoFollow() = Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun deletesEveryFileIncludingDotfilesAndYtDlpLeftovers() {
        val dir = stagingDir()
        val names = listOf(
            "Song.webm.part", "Song.webm.ytdl", "Song.webm.part-Frag3", "Song.temp.flac", "Song.meta",
            "Song.webm", "Song.webp", "Song.png", "Song.flac", ".hidden",
        )
        names.forEach { dir.child(it) }

        assertEquals(names.size, StagingSweeper.sweep(dir))

        assertTrue(dir.isDirectory)
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun leavesSubdirectoriesAndTheirContentsAlone() {
        val dir = stagingDir()
        dir.child("top.flac")
        val sub = File(dir, "sub").apply { mkdir() }
        val nested = sub.child("nested.flac")
        val emptySub = File(dir, "empty").apply { mkdir() }

        assertEquals(1, StagingSweeper.sweep(dir))

        assertTrue(sub.isDirectory)
        assertTrue(nested.isFile)
        assertTrue(emptySub.isDirectory)
    }

    @Test
    fun deletesASymlinkToAFileButNotItsTarget() {
        val dir = stagingDir()
        val target = tmp.newFile("outside.flac").apply { writeText("keep me") }
        val link = File(dir, "link.flac")
        symlink(link, target)

        assertEquals(1, StagingSweeper.sweep(dir))

        assertFalse(link.existsNoFollow())
        assertEquals("keep me", target.readText())
    }

    @Test
    fun deletesASymlinkToADirectoryButNotTheDirectoryOrItsContents() {
        val dir = stagingDir()
        val targetDir = tmp.newFolder("outsideDir")
        val inside = targetDir.child("inside.flac")
        val link = File(dir, "linkDir")
        symlink(link, targetDir)

        assertEquals(1, StagingSweeper.sweep(dir))

        assertFalse(link.existsNoFollow())
        assertTrue(inside.isFile)
    }

    @Test
    fun deletesADanglingSymlink() {
        val dir = stagingDir()
        val link = File(dir, "dangling")
        symlink(link, File(tmp.root, "does-not-exist"))

        assertEquals(1, StagingSweeper.sweep(dir))

        assertFalse(link.existsNoFollow())
    }

    @Test
    fun aSymlinkedStagingDirectoryIsNotSwept() {
        val realDir = tmp.newFolder("real")
        val file = realDir.child("a.flac")
        val linkDir = File(tmp.root, "linkToReal")
        symlink(linkDir, realDir)

        assertEquals(0, StagingSweeper.sweep(linkDir))

        assertTrue(file.isFile)
    }

    @Test
    fun returnsTheNumberDeleted() {
        val dir = stagingDir()
        repeat(3) { dir.child("f$it") }
        File(dir, "sub").mkdir()

        assertEquals(3, StagingSweeper.sweep(dir))
        assertEquals(0, StagingSweeper.sweep(dir))
    }

    @Test
    fun emptyDirectoryReturnsZero() {
        assertEquals(0, StagingSweeper.sweep(stagingDir()))
    }

    @Test
    fun missingDirectoryReturnsZero() {
        assertEquals(0, StagingSweeper.sweep(File(tmp.root, "missing")))
    }

    @Test
    fun pathThatIsAFileReturnsZeroAndKeepsTheFile() {
        val file = tmp.newFile("not-a-dir.flac")

        assertEquals(0, StagingSweeper.sweep(file))

        assertTrue(file.isFile)
    }

    @Test
    fun readOnlyDirectoryDoesNotThrow() {
        val dir = stagingDir()
        val file = dir.child("stuck.flac")
        val probe = dir.child("probe")
        lockedDirs += dir
        assertTrue(dir.setWritable(false, false))
        assumeFalse("process can delete from a read-only folder (root?)", probe.delete())

        assertEquals(0, StagingSweeper.sweep(dir))

        assertTrue(file.isFile)
    }

    @Test
    fun unreadableDirectoryDoesNotThrow() {
        val dir = stagingDir()
        val file = dir.child("hidden.flac")
        lockedDirs += dir
        assertTrue(dir.setReadable(false, false))
        assumeTrue("process can list an unreadable folder (root?)", dir.listFiles() == null)

        assertEquals(0, StagingSweeper.sweep(dir))

        dir.setReadable(true, false)
        assertTrue(file.isFile)
    }
}
