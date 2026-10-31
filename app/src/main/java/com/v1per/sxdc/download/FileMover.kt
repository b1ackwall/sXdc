package com.v1per.sxdc.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.sxdc.R
import java.io.File
import java.io.IOException

internal sealed interface MoveResult {
    /** @param displayName the name the provider actually assigned, which may not be ours. */
    data class Moved(val displayName: String) : MoveResult

    /**
     * @param queueWide true when the cause is the destination itself rather than this file --
     *   the folder grant being gone, or the volume being full. Every remaining job would fail
     *   the same way, so the queue stops rather than turning one problem into N identical
     *   notifications.
     */
    data class Failed(val message: String, val queueWide: Boolean = false) : MoveResult
}

internal interface FileMover {
    /** @param destination the SAF tree URI in its string form */
    fun move(result: JobResult, destination: String): MoveResult
}

internal class SafFileMover(
    private val context: Context,
    private val strings: StringProvider,
) : FileMover {

    /**
     * Copy the staged file into the user's SAF folder, then drop the staged copy.
     *
     * A copy, not a rename: the destination is a `content://` document behind a provider, so
     * there is no path for File.renameTo() to target. The source is deleted only once the byte
     * count is confirmed, and a failure anywhere deletes the half-written destination document
     * while leaving the staged file untouched, so the download never has to be repeated.
     */
    override fun move(result: JobResult, destination: String): MoveResult {
        val treeUri = Uri.parse(destination)
        val source = result.path?.let(::File)
        if (source == null || !source.isFile) {
            return MoveResult.Failed(strings.get(R.string.error_staged_file_missing))
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.canWrite()) {
            // The same condition DownloadPreconditions gates on, noticed later. Queue-wide.
            return MoveResult.Failed(DownloadPreconditions.REASON_FOLDER_LOST, queueWide = true)
        }

        // "audio/flac" is the platform's canonical type for .flac, so the provider appends no
        // second extension. It may still rename to dodge a collision -- hence the readback below.
        val created = tree.createFile(MIME_FLAC, source.name)
            ?: return MoveResult.Failed(strings.get(R.string.error_create_document))

        val expected = result.size ?: source.length()
        val copied = try {
            context.contentResolver.openOutputStream(created.uri).use { out ->
                if (out == null) throw IOException("openOutputStream returned null")
                source.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Copy into ${created.uri} failed", e)
            created.delete()
            // A full volume is a property of the destination, not of this file, so it stops the
            // queue for the same reason a lost folder grant does. Anything else here is specific
            // to this copy and leaves the remaining jobs worth attempting.
            return if (e.isOutOfSpace()) {
                MoveResult.Failed(strings.get(R.string.error_storage), queueWide = true)
            } else {
                MoveResult.Failed(strings.get(R.string.error_copy_failed))
            }
        }

        if (copied != expected) {
            // A short write with no exception is almost always the volume filling up mid-copy.
            Log.e(TAG, "Short copy into ${created.uri}: $copied of $expected bytes")
            created.delete()
            return MoveResult.Failed(strings.get(R.string.error_storage), queueWide = true)
        }

        // Only now is the staged copy expendable.
        val displayName = created.name ?: source.name
        StagingFiles.clear(source)
        Log.i(TAG, "Moved to ${created.uri} as $displayName ($copied bytes)")
        return MoveResult.Moved(displayName)
    }

    /**
     * Whether a copy failure was the volume running out of room.
     *
     * Walks the cause chain because the ENOSPC that matters is usually wrapped: a provider throws
     * IOException over an ErrnoException several frames down. Matching on text is unlovely, but
     * the framework surfaces no typed out-of-space signal through a `content://` write.
     */
    private fun Throwable.isOutOfSpace(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            val text = cause.message?.lowercase()
            if (text != null && OUT_OF_SPACE_MARKERS.any { it in text }) return true
            cause = cause.cause.takeIf { it != cause }
        }
        return false
    }

    private companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadService"
        const val MIME_FLAC = "audio/flac"

        val OUT_OF_SPACE_MARKERS =
            listOf("enospc", "no space left", "disk full", "not enough space")
    }
}
