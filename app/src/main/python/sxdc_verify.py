"""Verification helpers exercised from MainActivity.

Nothing in here touches the network. The ffmpeg checks prove the bundled binaries were
materialised, are executable and actually run; downloading a real URL is a manual on-device test.
"""

import os
import subprocess

PROGRAMS = ("ffmpeg", "ffprobe")

_PROBE_TIMEOUT = 20


def hello():
    return "Chaquopy OK"


def add(a, b):
    return a + b


def yt_dlp_version():
    import yt_dlp
    return yt_dlp.version.__version__


# --------------------------------------------------------------------------- ffmpeg wiring


def build_ydl_opts(bin_dir, outtmpl=None, extra=None, noplaylist=True):
    """Base ydl_opts with ffmpeg pinned to our bundled build.

    ``ffmpeg_location`` accepts a directory, in which case yt-dlp looks inside it for files named
    exactly ``ffmpeg`` and ``ffprobe`` -- which is why FfmpegBinaries publishes that bin directory
    instead of pointing straight at ``libffmpeg.so``. Without this, yt-dlp searches PATH, which on
    Android has no ffmpeg at all, and every merge/extract postprocessor silently fails.

    ``noplaylist`` defaults to True for the download path -- ``expand_urls`` below is the one
    caller that needs it off, so playlist entries actually come back instead of collapsing to a
    single video.
    """
    opts = {
        "ffmpeg_location": bin_dir,
        "outtmpl": outtmpl or "%(title)s.%(ext)s",
        "noplaylist": noplaylist,
        "quiet": True,
        "no_warnings": True,
    }
    if extra:
        opts.update(extra)
    return opts


def ffmpeg_check(bin_dir):
    """Prove each binary is present, executable and invokable at ``bin_dir``.

    Returns a dict keyed by program name; each value has ``path``, ``exists``, ``executable``,
    ``size``, ``symlink_target``, ``version`` and ``error``.
    """
    report = {}
    for name in PROGRAMS:
        path = os.path.join(bin_dir, name)
        entry = {
            "path": path,
            "exists": os.path.exists(path),
            "executable": os.access(path, os.X_OK),
            "size": os.path.getsize(path) if os.path.exists(path) else 0,
            "symlink_target": os.path.realpath(path) if os.path.islink(path) else None,
            "version": None,
            "error": None,
        }
        if entry["exists"]:
            entry["version"], entry["error"] = _run_version(path)
        else:
            entry["error"] = "not found"
        report[name] = entry
    return report


def _run_version(path):
    """Run ``<path> -version`` and return (first line of output, error)."""
    try:
        proc = subprocess.run(
            [path, "-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=_PROBE_TIMEOUT,
        )
    except PermissionError as e:
        # The W^X failure mode: chmod +x looked fine, execve() was refused by SELinux.
        return None, "exec denied (%s) -- binary is not in an executable location" % e
    except OSError as e:
        return None, "exec failed: %s" % e
    except subprocess.TimeoutExpired:
        return None, "timed out after %ss" % _PROBE_TIMEOUT

    out = proc.stdout.decode("utf-8", "replace").strip()
    if proc.returncode != 0:
        return None, "exit %s: %s" % (proc.returncode, out.splitlines()[0] if out else "no output")
    return (out.splitlines()[0] if out else None), None


def ytdlp_ffmpeg_check(bin_dir):
    """Confirm yt-dlp itself resolves our binaries through ``ffmpeg_location``.

    This is the check that matters: it goes through yt-dlp's own postprocessor discovery, which
    execs ffmpeg to read its version. If this reports available, postprocessing will work.
    """
    import yt_dlp
    from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor

    result = {"available": False, "executable": None, "probe_executable": None,
              "versions": {}, "error": None}
    try:
        with yt_dlp.YoutubeDL(build_ydl_opts(bin_dir)) as ydl:
            pp = FFmpegPostProcessor(ydl)
            result["available"] = bool(pp.available)
            result["executable"] = getattr(pp, "executable", None)
            result["probe_executable"] = getattr(pp, "probe_executable", None)
            versions = getattr(pp, "_versions", None) or {}
            result["versions"] = {k: v for k, v in versions.items() if v}
    except Exception as e:  # yt-dlp internals shift between releases; never crash the launch path
        result["error"] = "%s: %s" % (type(e).__name__, e)
    return result


def ffmpeg_report(bin_dir):
    """One-line human-readable summary for the on-device UI."""
    parts = []
    for name, entry in ffmpeg_check(bin_dir).items():
        if entry["version"]:
            parts.append("%s OK (%s)" % (name, entry["version"]))
        else:
            parts.append("%s FAIL [%s]" % (name, entry["error"]))

    yd = ytdlp_ffmpeg_check(bin_dir)
    if yd["error"]:
        parts.append("yt-dlp wiring ERROR [%s]" % yd["error"])
    else:
        parts.append("yt-dlp sees ffmpeg: %s" % ("yes" if yd["available"] else "NO"))
    return " | ".join(parts)
