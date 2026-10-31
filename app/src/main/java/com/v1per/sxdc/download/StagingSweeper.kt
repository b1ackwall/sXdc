package com.v1per.sxdc.download

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Empties the staging folder of whatever yt-dlp left behind: `.part`, `.ytdl` and `-FragN` files,
 * `.temp.flac`, `.meta`, original audio and thumbnails from failed jobs.
 *
 * Unlike [StagingFiles], this is a sweep of the whole folder, so it is only safe while no download
 * is in flight -- QueueProcessor calls it under its lock, after the drain has finished.
 *
 * Top level only: never recurses, never removes a directory, and removes a symbolic link itself
 * rather than what it points at. A path that is missing, not a directory, or a symlink to one is
 * left alone. Never throws; a file that cannot be deleted is logged and skipped.
 */
internal object StagingSweeper {

    private const val TAG = "StagingSweeper"

    /** @return how many entries were deleted */
    fun sweep(dir: File): Int =
        if (attributesOf(dir.toPath())?.isDirectory == true) {
            listEntries(dir)?.count { deleteIfFileOrLink(it.toPath()) } ?: 0
        } else {
            0
        }

    /** File.listFiles reports failure as null instead of throwing mid-iteration. */
    private fun listEntries(dir: File): Array<File>? = try {
        dir.listFiles().also { if (it == null) Log.w(TAG, "Could not list ${dir.path}") }
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to list ${dir.path}", e)
        null
    }

    private fun deleteIfFileOrLink(path: Path): Boolean {
        val attrs = attributesOf(path)
        val deletable = attrs != null && (attrs.isRegularFile || attrs.isSymbolicLink)
        return deletable && delete(path)
    }

    private fun delete(path: Path): Boolean = try {
        // Files.delete never follows a link: it removes the link, not its target.
        Files.delete(path)
        true
    } catch (e: IOException) {
        Log.w(TAG, "Could not delete $path", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to delete $path", e)
        false
    }

    /** The entry's own attributes (a link is reported as a link), or null if unreadable. */
    private fun attributesOf(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        Log.w(TAG, "Could not read attributes of $path", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to read attributes of $path", e)
        null
    }
}
