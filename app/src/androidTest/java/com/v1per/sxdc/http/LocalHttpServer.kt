package com.v1per.sxdc.http

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for offline tests. One response per connection.
 *
 * - `/<name>.wav`: [wav], a short generated sine tone (`audio/wav`)
 * - `/feed/<n>.xml`: an RSS feed with n items, item i titled "Track i" with an enclosure at
 *   `/track-i.wav`
 * - `/status/<code>`: an empty-bodied response with that status code
 * - `/media/<code>/<name>`: [wav] on the first request for that exact path, then an empty-bodied
 *   <code> on every later one. yt-dlp's generic extractor fetches the page once and the download
 *   fetches it again, so the status lands on the media request, not the page. Use a fresh <name>
 *   per call.
 * - `/truncated/<name>`: announces [wav]'s full length but sends only half of it, then closes
 * - `/long/<name>.wav`: [longWav], the same tone for [LONG_SECONDS], so its FLAC has many frames
 * - `/page/<thumb>/<name>.html`: a page whose `<video>` plays `/<name>.wav` with the poster
 *   `/thumb/<thumb>.jpg`. yt-dlp's generic extractor makes the poster the thumbnail, which is how
 *   a cover reaches EmbedThumbnail offline (#47).
 * - `/thumb/ok.jpg` ([jpeg]), `/thumb/webp.jpg` ([webp], a WebP named .jpg) and
 *   `/thumb/bogus.jpg` (an HTML error page named .jpg), all served as `image/jpeg`
 * - anything else: 404
 */
class LocalHttpServer : Closeable {

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(HOST))

    val wav: ByteArray = sineWav()

    val longWav: ByteArray by lazy { sineWav(LONG_SECONDS) }

    val jpeg: ByteArray by lazy { image(Bitmap.CompressFormat.JPEG) }

    val webp: ByteArray by lazy {
        image(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            },
        )
    }

    val bogusImage: ByteArray =
        "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body>Not Found</body></html>\n"
            .toByteArray()

    private val hits = ConcurrentHashMap<String, Int>()

    init {
        thread(isDaemon = true, name = "LocalHttpServer") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    fun url(path: String): String = "http://$HOST:${socket.localPort}$path"

    override fun close() = socket.close()

    private fun serve(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit // Headers are not needed.
        val (method, target) = requestLine.split(" ")
        val reply = route(target.substringBefore('?'))
        val head = "HTTP/1.1 ${reply.status} X\r\nContent-Type: ${reply.type}\r\n" +
            "Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD") write(reply.body, 0, reply.sent)
            flush()
        }
    }

    /** [sent] below [body]'s size cuts the body short after an honest Content-Length. */
    private class Reply(val status: Int, val type: String, val body: ByteArray, val sent: Int = body.size)

    private fun route(path: String): Reply {
        val feedItems = Regex("/feed/(\\d+)\\.xml").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val mediaStatus = Regex("/media/(\\d+)/.+").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val page = Regex("/page/(\\w+)/([\\w-]+)\\.html").matchEntire(path)?.groupValues
        return when {
            path.startsWith("/truncated/") -> Reply(200, "audio/wav", wav, sent = wav.size / 2)
            path.startsWith("/long/") -> Reply(200, "audio/wav", longWav)
            page != null -> Reply(200, "text/html", posterPage(thumb = page[1], name = page[2]).toByteArray())
            path == "/thumb/ok.jpg" -> Reply(200, "image/jpeg", jpeg)
            path == "/thumb/webp.jpg" -> Reply(200, "image/jpeg", webp)
            path == "/thumb/bogus.jpg" -> Reply(200, "image/jpeg", bogusImage)
            mediaStatus != null ->
                if (hits.merge(path, 1, Int::plus) == 1) Reply(200, "audio/wav", wav) else empty(mediaStatus)
            path.endsWith(".wav") -> Reply(200, "audio/wav", wav)
            feedItems != null -> Reply(200, "application/rss+xml", rss(feedItems).toByteArray())
            path.startsWith("/status/") -> empty(path.removePrefix("/status/").toInt())
            else -> empty(404)
        }
    }

    private fun empty(status: Int) = Reply(status, "text/plain", ByteArray(0))

    private fun posterPage(thumb: String, name: String): String =
        "<!DOCTYPE html><html><head><title>$name</title></head><body>" +
            "<video src=\"${url("/$name.wav")}\" poster=\"${url("/thumb/$thumb.jpg")}\"></video></body></html>"

    private fun rss(items: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\"><channel><title>Test feed</title>")
        append("<link>${url("/")}</link><description>Test feed</description>")
        for (i in 1..items) {
            append("<item><title>Track $i</title><guid>track-$i</guid>")
            append("<enclosure url=\"${url("/track-$i.wav")}\" length=\"${wav.size}\" type=\"audio/wav\"/></item>")
        }
        append("</channel></rss>")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val BACKLOG = 16
        const val TIMEOUT_MS = 10_000
        const val SAMPLE_RATE = 8_000
        const val SECONDS = 0.5
        const val FREQUENCY = 440.0
        const val AMPLITUDE = 8_000.0
        const val LONG_SECONDS = 8.0
        const val IMAGE_SIZE = 64
        const val IMAGE_QUALITY = 90

        /** 16-bit mono PCM WAV: a 44-byte header, then the samples. */
        fun sineWav(seconds: Double = SECONDS): ByteArray {
            val samples = (SAMPLE_RATE * seconds).toInt()
            val data = samples * 2
            return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
                put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
                put("data".toByteArray()).putInt(data)
                for (n in 0 until samples) {
                    putShort((AMPLITUDE * sin(2 * PI * FREQUENCY * n / SAMPLE_RATE)).toInt().toShort())
                }
            }.array()
        }

        /** A small gradient, so the encoder has something to encode. */
        fun image(format: Bitmap.CompressFormat): ByteArray {
            val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            for (x in 0 until IMAGE_SIZE) {
                for (y in 0 until IMAGE_SIZE) {
                    bitmap.setPixel(x, y, Color.rgb(x * 4, y * 4, 128))
                }
            }
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(format, IMAGE_QUALITY, out)) { "could not encode $format" }
                out.toByteArray()
            }
        }
    }
}
