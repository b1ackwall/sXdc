package com.v1per.sxdc.media

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Real device, real binaries. Reads and executes from the app's ffmpeg-bin and nativeLibraryDir
 * only; all scratch output goes to a test-only folder under cacheDir.
 *
 * FfmpegBinaries.install() itself may rebuild filesDir/ffmpeg-bin once after the test APK
 * install (its stamp tracks lastUpdateTime) -- that is production behaviour, the same thing the
 * app does on its next launch. The test never touches that directory directly.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegBinariesDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = File(context.cacheDir, "ffmpeg-device-test")
        scratch.deleteRecursively()
        assertTrue(scratch.mkdirs())
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    @Test
    fun nativeLibraryDir_containsFfmpegAndFfprobe() {
        listOf("libffmpeg.so", "libffprobe.so").forEach { name ->
            val f = File(nativeDir, name)
            assertTrue("$f missing (useLegacyPackaging off?)", f.isFile)
            assertTrue("$f is empty", f.length() > 0)
        }
    }

    @Test
    fun install_succeeds_andIsIdempotent() {
        val first = FfmpegBinaries.install(context)
        val second = FfmpegBinaries.install(context)

        assertEquals(FfmpegBinaries.Source.NATIVE_SYMLINK, first.source)
        assertEquals(File(context.filesDir, "ffmpeg-bin").absolutePath, first.binDirPath)
        assertEquals("ffmpeg", first.ffmpeg.name)
        assertEquals("ffprobe", first.ffprobe.name)
        assertEquals(first, second)
        assertEquals(first, FfmpegBinaries.installedOrNull())
    }

    @Test
    fun installedEntries_areExecutableSymlinksIntoNativeLibraryDir() {
        val install = FfmpegBinaries.install(context)
        val nativeCanonical = nativeDir.canonicalPath

        listOf(install.ffmpeg to "libffmpeg.so", install.ffprobe to "libffprobe.so").forEach { (entry, lib) ->
            val path = entry.toPath()
            assertTrue("$entry missing", entry.exists())
            assertTrue("$entry not a symlink", Files.isSymbolicLink(path))
            assertTrue("$entry not executable", entry.canExecute())

            val target = Files.readSymbolicLink(path).toFile()
            assertEquals(lib, target.name)
            assertEquals(nativeCanonical, target.canonicalFile.parentFile!!.canonicalPath)
        }
    }

    @Test
    fun ffmpegAndFfprobe_printVersion() {
        val install = FfmpegBinaries.install(context)

        val ffmpeg = run(install.ffmpegPath, "-version")
        assertEquals(ffmpeg.output, 0, ffmpeg.exitCode)
        assertTrue(ffmpeg.output, ffmpeg.output.lineSequence().first().startsWith("ffmpeg version"))

        val ffprobe = run(install.ffprobePath, "-version")
        assertEquals(ffprobe.output, 0, ffprobe.exitCode)
        assertTrue(ffprobe.output, ffprobe.output.lineSequence().first().startsWith("ffprobe version"))
    }

    @Test
    fun ffmpeg_hasFlacEncoder() {
        val install = FfmpegBinaries.install(context)
        val result = run(install.ffmpegPath, "-hide_banner", "-encoders")
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("flac"))
    }

    @Test
    fun ffmpeg_convertsWavToFlac_andFfprobeReadsIt() {
        val install = FfmpegBinaries.install(context)
        val wav = File(scratch, "in.wav").apply { writeBytes(sineWav()) }
        val flac = File(scratch, "out.flac")

        val convert = run(install.ffmpegPath, "-y", "-i", wav.absolutePath, flac.absolutePath)
        assertEquals(convert.output, 0, convert.exitCode)
        assertTrue("no output file", flac.isFile)
        assertArrayEquals("fLaC".toByteArray(Charsets.US_ASCII), flac.readBytes().copyOf(4))

        val probe = run(
            install.ffprobePath, "-v", "error",
            "-show_entries", "stream=codec_name", "-of", "default=nw=1", flac.absolutePath,
        )
        assertEquals(probe.output, 0, probe.exitCode)
        assertEquals("codec_name=flac", probe.output.trim())
    }

    private data class Result(val exitCode: Int, val output: String)

    /** Output goes to a scratch file so a large listing can never block on a full pipe. */
    private fun run(vararg cmd: String): Result {
        val out = File.createTempFile("proc", ".txt", scratch)
        val process = ProcessBuilder(*cmd)
            .directory(scratch)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Timed out after 30s: ${cmd.joinToString(" ")}")
        }
        return Result(process.exitValue(), out.readText())
    }

    /** 0.5 s of a 440 Hz sine, mono, 16-bit PCM, 8 kHz, with a standard 44-byte RIFF header. */
    private fun sineWav(): ByteArray {
        val sampleRate = 8_000
        val samples = sampleRate / 2
        val dataSize = samples * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate
        buf.putShort(2) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440 * i / sampleRate) * 8_000).roundToInt()
            buf.putShort(v.toShort())
        }
        return buf.array()
    }
}
