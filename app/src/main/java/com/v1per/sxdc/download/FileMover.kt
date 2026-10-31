package com.v1per.sxdc.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.sxdc.R
import com.v1per.sxdc.settings.SxdcSettings
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal sealed interface MoveResult {
    /** @param displayName the name the provider actually assigned, which may not be ours. */
    data class Moved(val displayName: String) : MoveResult

    /**
     * @param queueWide true when the cause is the destination itself rather than this file --
     *   the folder grant being gone, the volume being full, or a provider that cannot rename.
     *   Every remaining job would fail the same way, so the queue stops rather than turning one
     *   problem into N identical notifications.
     */
    data class Failed(val message: String, val queueWide: Boolean = false) : MoveResult
}

internal interface FileMover {
    /** @param destination the SAF tree URI in its string form */
    fun move(result: JobResult, destination: String): MoveResult

    /**
     * Delete temporary documents a killed or stopped run left in the configured destination.
     *
     * Must never run while a move is in progress. Never throws.
     * @return how many were deleted; 0 when no destination is configured or it is inaccessible
     */
    fun sweepPartials(): Int
}

internal class SafFileMover(
    private val context: Context,
    private val strings: StringProvider,
) : FileMover {

    private val delivery = StagedFileDelivery(strings)

    /**
     * Copy the staged file into the user's SAF folder through [StagedFileDelivery], then drop
     * the staged copy.
     *
     * A copy, not a rename: the destination is a `content://` document behind a provider, so
     * there is no path for File.renameTo() to target. The source is deleted only once the file
     * is delivered; a failure leaves the staged file untouched.
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

        val moved = delivery.deliver(SafDestinationTree(context, treeUri), source, result.size ?: source.length())
        // Only now is the staged copy expendable.
        if (moved is MoveResult.Moved) StagingFiles.clear(source)
        return moved
    }

    override fun sweepPartials(): Int = try {
        val treeUri = SxdcSettings.treeUri(context)
        if (treeUri == null || !SxdcSettings.hasWriteGrant(context, treeUri)) {
            0
        } else {
            delivery.sweepPartials(SafDestinationTree(context, treeUri)).also {
                if (it > 0) Log.i(TAG, "Swept $it leftover partial document(s)")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Partial sweep failed", e)
        0
    }

    private companion object {
        const val TAG = "DownloadService"
    }
}

/** [DestinationTree] over one SAF tree, through DocumentsContract directly. */
private class SafDestinationTree(context: Context, private val treeUri: Uri) : DestinationTree {

    private val resolver = context.contentResolver
    private val rootUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /**
     * A direct child query rather than DocumentFile.listFiles(), which swallows a provider
     * failure and returns an empty array -- indistinguishable from an empty folder.
     */
    override fun listChildren(): List<DestinationDocument>? {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(rootUri))
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val children = mutableListOf<DestinationDocument>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                children += DestinationDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                    name = cursor.getString(1),
                    isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                )
            }
            children
        }
    }

    override fun createDocument(name: String, mimeType: String): DestinationDocument? {
        val created = DocumentsContract.createDocument(resolver, rootUri, mimeType, name) ?: return null
        return DestinationDocument(created.toString(), displayName(created))
    }

    override fun supportsRename(document: DestinationDocument): Boolean {
        val flags = resolver.query(Uri.parse(document.uri), arrayOf(Document.COLUMN_FLAGS), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }
            ?: return false
        return (flags and Document.FLAG_SUPPORTS_RENAME) != 0
    }

    override fun openOutputStream(document: DestinationDocument): OutputStream =
        resolver.openOutputStream(Uri.parse(document.uri), "w")
            ?: throw IOException("openOutputStream returned null")

    override fun rename(document: DestinationDocument, newName: String): RenamedDocument? {
        // Returns the original URI when the provider kept the document id, null on failure.
        val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(document.uri), newName) ?: return null
        return RenamedDocument(displayName(renamed) ?: newName, renamed.toString())
    }

    override fun delete(document: DestinationDocument): Boolean =
        DocumentsContract.deleteDocument(resolver, Uri.parse(document.uri))

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
}
