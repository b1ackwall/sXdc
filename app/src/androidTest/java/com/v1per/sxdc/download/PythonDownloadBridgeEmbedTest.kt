package com.v1per.sxdc.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.v1per.sxdc.http.LocalHttpServer
import com.v1per.sxdc.media.FfmpegBinaries
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The cover and the file checks of #45, #46 and #47 through Chaquopy, the bundled yt-dlp, mutagen
 * and ffmpeg, offline. The poster page on [LocalHttpServer] hands yt-dlp's generic extractor a
 * thumbnail, which is the only way a cover reaches EmbedThumbnail without the network.
 */
@RunWith(AndroidJUnit4::class)
class PythonDownloadBridgeEmbedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bridge = PythonDownloadBridge()
    private val outputDir = File(context.cacheDir, "bridge-embed-test-" + UUID.randomUUID())

    @After
    fun deleteOutput() {
        outputDir.deleteRecursively()
    }

    private fun download(url: String): JobResult =
        bridge.download(url, outputDir.absolutePath, FfmpegBinaries.install(context).binDirPath, AbortSignal())

    private fun deliveredFrom(thumb: String): Pair<JobResult, File> {
        val result = download(server.url("/page/$thumb/cover-$thumb.html"))
        assertTrue("download failed: ${result.kind} ${result.error}", result.ok)
        return result to File(result.path!!)
    }

    @Test
    fun bogusThumbnail_isNotEmbedded_andTheResultSaysNoCover() {
        val (result, file) = deliveredFrom("bogus")

        assertEquals(listOf(WarningKinds.NO_COVER), result.warnings)
        assertEquals(emptyList<Picture>(), pictures(file))
        assertNoImagesLeftIn(outputDir)
    }

    @Test
    fun webpNamedJpg_isEmbeddedAsAJpeg() {
        val (result, file) = deliveredFrom("webp")

        assertEquals(emptyList<String>(), result.warnings)
        val picture = pictures(file).single()
        assertEquals("image/jpeg", picture.mime)
        assertArrayEquals(JPEG_MAGIC, picture.head)
        assertNoImagesLeftIn(outputDir)
    }

    @Test
    fun validJpeg_isEmbedded_withNoWarnings() {
        val (result, file) = deliveredFrom("ok")

        assertEquals(emptyList<String>(), result.warnings)
        val picture = pictures(file).single()
        assertEquals("image/jpeg", picture.mime)
        assertArrayEquals(server.jpeg.copyOf(JPEG_MAGIC.size), picture.head)
        // The generic extractor numbers the page's media entries: "cover-ok (1)".
        assertEquals(result.title, flacTag(file, "title")?.single())
        assertTrue(result.title.orEmpty().startsWith("cover-ok"))
    }

    /** sxdc_flac's tail check was measured on desktop ffmpeg only; this is the bundled encoder. */
    @Test
    fun flacFromTheBundledEncoder_passesTheIntegrityCheck_andACutCopyFailsIt() {
        val result = download(server.url("/long/sxdc-long-${UUID.randomUUID()}.wav"))
        assertTrue("download failed: ${result.kind} ${result.error}", result.ok)
        val file = File(result.path!!)
        assertEquals(emptyList<String>(), result.warnings)
        assertEquals("ok", integrity(file))

        val bytes = file.readBytes()
        val cutMidFrame = File(outputDir, "cut-mid.flac").apply { writeBytes(bytes.copyOf(bytes.size - 10)) }
        val cutHalf = File(outputDir, "cut-half.flac").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        assertEquals("truncated", integrity(cutMidFrame))
        assertEquals("truncated", integrity(cutHalf))
    }

    private data class Picture(val mime: String, val head: ByteArray) {
        override fun equals(other: Any?) = other is Picture && mime == other.mime && head.contentEquals(other.head)

        override fun hashCode() = mime.hashCode()
    }

    /** The picture blocks of [file], read with the bundled mutagen, independently of sxdc_flac. */
    private fun pictures(file: File): List<Picture> {
        val flac = Python.getInstance().getModule("mutagen.flac").callAttr("FLAC", file.path)
        return flac.get("pictures")!!.asList().map { picture: PyObject ->
            val data = picture.get("data")!!.toJava(ByteArray::class.java)
            Picture(picture.get("mime").toString(), data.copyOf(JPEG_MAGIC.size))
        }
    }

    private fun flacTag(file: File, key: String): List<String>? {
        val flac = Python.getInstance().getModule("mutagen.flac").callAttr("FLAC", file.path)
        return flac.callAttr("get", key)?.asList()?.map { it.toString() }
    }

    private fun integrity(file: File): String =
        Python.getInstance().getModule("sxdc_flac").callAttr("check_integrity", file.path).toString()

    /** The guard deletes what it rejects, and EmbedThumbnail deletes what it embedded. */
    private fun assertNoImagesLeftIn(dir: File) {
        val images = dir.list().orEmpty().filter { it.substringAfterLast('.') in listOf("jpg", "png", "webp") }
        assertEquals(emptyList<String>(), images)
    }

    companion object {
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        private lateinit var server: LocalHttpServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            server = LocalHttpServer()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server.close()
        }
    }
}
