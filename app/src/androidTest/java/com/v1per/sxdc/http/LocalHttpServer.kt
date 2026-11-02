package com.v1per.sxdc.http

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * - anything else: 404
 */
class LocalHttpServer : Closeable {

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(HOST))

    val wav: ByteArray = sineWav()

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
        val (status, type, body) = route(target.substringBefore('?'))
        val head = "HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD") write(body)
            flush()
        }
    }

    private fun route(path: String): Triple<Int, String, ByteArray> {
        val feedItems = Regex("/feed/(\\d+)\\.xml").matchEntire(path)?.groupValues?.get(1)?.toInt()
        return when {
            path.endsWith(".wav") -> Triple(200, "audio/wav", wav)
            feedItems != null -> Triple(200, "application/rss+xml", rss(feedItems).toByteArray())
            path.startsWith("/status/") -> Triple(path.removePrefix("/status/").toInt(), "text/plain", ByteArray(0))
            else -> Triple(404, "text/plain", ByteArray(0))
        }
    }

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

        /** 16-bit mono PCM WAV: a 44-byte header, then the samples. */
        fun sineWav(): ByteArray {
            val samples = (SAMPLE_RATE * SECONDS).toInt()
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
    }
}
