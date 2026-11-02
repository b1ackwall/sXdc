package com.v1per.sxdc.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.v1per.sxdc.http.LocalHttpServer
import com.v1per.sxdc.media.FfmpegBinaries
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PythonDownloadBridge through Chaquopy and the real bundled yt-dlp and ffmpeg, offline: every
 * URL is on [LocalHttpServer] at 127.0.0.1 (#29). Downloads land in a test-only folder under the
 * app's cache dir, deleted after each test.
 *
 * Nothing here asserts how HTTP 403 or 429 is classified (#64).
 */
@RunWith(AndroidJUnit4::class)
class PythonDownloadBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bridge = PythonDownloadBridge()
    private val outputDir = File(context.cacheDir, "bridge-test-" + UUID.randomUUID())

    @After
    fun deleteOutput() {
        outputDir.deleteRecursively()
    }

    private fun download(url: String): JobResult =
        bridge.download(url, outputDir.absolutePath, FfmpegBinaries.install(context).binDirPath)

    @Test
    fun dedupKeys_sameVideoInSeveralShapes_givesOneKey() {
        val shapes = listOf(
            "https://www.youtube.com/watch?v=$VIDEO_ID",
            "https://youtu.be/$VIDEO_ID?si=tracking",
            "https://m.youtube.com/watch?v=$VIDEO_ID&t=42",
            "https://music.youtube.com/watch?v=$VIDEO_ID&list=RDAMVM",
            "https://www.youtube.com/shorts/$VIDEO_ID/",
        )

        assertEquals(List(shapes.size) { "youtube:$VIDEO_ID" }, bridge.dedupKeys(shapes))
    }

    @Test
    fun dedupKeys_otherText_isKeyedByTheNormalizedUrl_inOrder() {
        val keys = bridge.dedupKeys(listOf("https://Example.COM/a/?x=1#frag", "not a url"))

        assertEquals(listOf("url:https://example.com/a?x=1", "url:not a url"), keys)
    }

    @Test
    fun expandUrls_textThatIsNotAUrl_fallsBackToOneUntitledTrack() {
        assertEquals(listOf(Track("not a url", null)), bridge.expandUrls("not a url"))
    }

    @Test
    fun expandUrls_directMediaUrl_isOneUntitledTrack() {
        val url = server.url("/sxdc-tone.wav")

        assertEquals(listOf(Track(url, null)), bridge.expandUrls(url))
    }

    @Test
    fun expandUrls_rssFeed_givesOneTitledTrackPerItem() {
        val enclosures = (1..3).map { server.url("/track-$it.wav") }

        val tracks = bridge.expandUrls(server.url("/feed/3.xml"))

        assertEquals((1..3).map { "Track $it" }, tracks.map { it.title })
        // yt-dlp's RSS entries carry a "#__youtubedl_smuggle=..." fragment after the enclosure URL.
        assertEquals(enclosures, tracks.map { it.url.substringBefore('#') })
        // The fragment must not hide a duplicate: the keys drop it.
        assertEquals(bridge.dedupKeys(enclosures), bridge.dedupKeys(tracks.map { it.url }))
    }

    @Test
    fun download_connectionRefused_failsWithTheNetworkKind() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = download("http://127.0.0.1:$closedPort/sxdc-tone.wav")

        assertFalse(result.ok)
        assertNull(result.path)
        assertEquals(ErrorKinds.NETWORK, result.kind)
    }

    @Test
    fun download_httpErrorStatus_failsWithoutOutput() {
        // Only that it fails: the kind for these statuses is #64's to settle.
        for (code in listOf(404, 403)) {
            val result = download(server.url("/status/$code"))

            assertFalse("HTTP $code", result.ok)
            assertNull("HTTP $code", result.path)
            assertNotNull("HTTP $code", result.kind)
        }
    }

    @Test
    fun download_localWav_becomesATaggedFlacNamedTitleAndId() {
        val result = download(server.url("/sxdc-tone.wav"))

        assertTrue("download failed: ${result.kind} ${result.error}", result.ok)
        val path = result.path
        assertNotNull(path)
        val file = File(path!!)
        assertEquals(outputDir.canonicalFile, file.canonicalFile.parentFile)
        assertEquals("sxdc-tone [sxdc-tone].flac", file.name)
        assertEquals("sxdc-tone", result.title)
        assertEquals(file.length(), result.size)
        assertArrayEquals("fLaC".toByteArray(), file.readBytes().copyOf(4))
        assertEquals(listOf("sxdc-tone"), flacTag(file, "title"))
    }

    /** A Vorbis comment from [file], read with the bundled mutagen; null if absent. */
    private fun flacTag(file: File, key: String): List<String>? {
        val flac = Python.getInstance().getModule("mutagen.flac").callAttr("FLAC", file.path)
        return flac.callAttr("get", key)?.asList()?.map { it.toString() }
    }

    companion object {
        private const val VIDEO_ID = "dQw4w9WgXcQ"

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
