"""A minimal offline HTTP server for the real-yt-dlp tests (#48).

Python port of the instrumented LocalHttpServer (app/src/androidTest/.../http/LocalHttpServer.kt),
on 127.0.0.1 with an ephemeral port. One response per connection. Routes:

- ``/<name>.wav``: a generated 2-second sine tone (``audio/wav``)
- ``/long/<name>.wav``: the same tone for 8 seconds, so its FLAC has several frames
- ``/status/<code>``: an empty body with that status
- ``/media/<code>/<name>``: the tone on the first request for that exact path, then an empty
  ``<code>`` on every later one (the generic extractor fetches the page, then the media)
- ``/truncated/<name>``: announces the full tone, sends half of it, then closes
- ``/page/<thumb>/<name>.html``: a ``<video src=... poster=...>`` page; the generic extractor
  turns the poster into the thumbnail, which is how a cover reaches EmbedThumbnail offline
- ``/thumb/ok.jpg`` (a real JPEG), ``/thumb/webp.jpg`` (a WebP named .jpg), ``/thumb/bogus.jpg``
  (an HTML error page named .jpg), all served as ``image/jpeg``
- anything else: 404
"""

import io
import math
import re
import struct
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SAMPLE_RATE = 8000
SHORT_SECONDS = 2
LONG_SECONDS = 8
FREQUENCY = 440.0
AMPLITUDE = 8000

BOGUS_IMAGE = (
    b"<!DOCTYPE html><html><head><title>404 Not Found</title></head><body>Not Found</body></html>\n"
)


def sine_wav(seconds):
    """16-bit mono PCM WAV of a sine tone, built with the standard library."""
    frames = b"".join(
        struct.pack("<h", int(AMPLITUDE * math.sin(2 * math.pi * FREQUENCY * n / SAMPLE_RATE)))
        for n in range(SAMPLE_RATE * seconds)
    )
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(frames)
    return buf.getvalue()


class _Reply:
    def __init__(self, status, content_type, body, sent=None):
        self.status = status
        self.content_type = content_type
        self.body = body
        self.sent = len(body) if sent is None else sent


class LocalServer:
    """Start with ``start()``, stop with ``close()``; ``url(path)`` builds an absolute URL."""

    def __init__(self, jpeg, webp):
        self.wav = sine_wav(SHORT_SECONDS)
        self.long_wav = sine_wav(LONG_SECONDS)
        self.jpeg = jpeg
        self.webp = webp
        self._hits = {}
        self._lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def _serve(self, with_body):
                reply = outer._route(self.path.split("?")[0])
                head = (
                    f"HTTP/1.1 {reply.status} X\r\nContent-Type: {reply.content_type}\r\n"
                    f"Content-Length: {len(reply.body)}\r\nConnection: close\r\n\r\n"
                )
                try:
                    self.wfile.write(head.encode("latin-1"))
                    if with_body:
                        self.wfile.write(reply.body[: reply.sent])
                    self.wfile.flush()
                except OSError:
                    pass
                self.close_connection = True

            def do_GET(self):
                self._serve(True)

            def do_HEAD(self):
                self._serve(False)

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._httpd.daemon_threads = True
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)

    def start(self):
        self._thread.start()
        return self

    def close(self):
        self._httpd.shutdown()
        self._httpd.server_close()

    @property
    def port(self):
        return self._httpd.server_address[1]

    def url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def _route(self, path):
        page = re.fullmatch(r"/page/(\w+)/([\w-]+)\.html", path)
        media = re.fullmatch(r"/media/(\d+)/.+", path)
        if path.startswith("/truncated/"):
            return _Reply(200, "audio/wav", self.wav, sent=len(self.wav) // 2)
        if path.startswith("/long/"):
            return _Reply(200, "audio/wav", self.long_wav)
        if page:
            return _Reply(200, "text/html", self._poster_page(page[1], page[2]).encode())
        if path == "/thumb/ok.jpg":
            return _Reply(200, "image/jpeg", self.jpeg)
        if path == "/thumb/webp.jpg":
            return _Reply(200, "image/jpeg", self.webp)
        if path == "/thumb/bogus.jpg":
            return _Reply(200, "image/jpeg", BOGUS_IMAGE)
        if media:
            with self._lock:
                self._hits[path] = self._hits.get(path, 0) + 1
                first = self._hits[path] == 1
            return _Reply(200, "audio/wav", self.wav) if first else _empty(int(media[1]))
        if path.endswith(".wav"):
            return _Reply(200, "audio/wav", self.wav)
        if path.startswith("/status/"):
            return _empty(int(path.removeprefix("/status/")))
        return _empty(404)

    def _poster_page(self, thumb, name):
        return (
            f"<!DOCTYPE html><html><head><title>{name}</title></head><body>"
            f'<video src="{self.url(f"/{name}.wav")}" poster="{self.url(f"/thumb/{thumb}.jpg")}">'
            "</video></body></html>"
        )


def _empty(status):
    return _Reply(status, "text/plain", b"")
