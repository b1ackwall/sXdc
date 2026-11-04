package com.v1per.sxdc.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.v1per.sxdc.settings.SxdcSettings
import java.util.UUID

/**
 * Whether a destination folder's provider will rename documents (FLAG_SUPPORTS_RENAME), as last
 * measured by [RenameProbe]. [UNKNOWN] is the absence of an answer and never blocks a download.
 */
internal enum class RenameSupport(val id: String) {
    UNKNOWN("unknown"),
    SUPPORTED("true"),
    UNSUPPORTED("false"),
    ;

    companion object {
        /** Anything unrecognised, including null, reads back as [UNKNOWN]. */
        fun fromId(id: String?): RenameSupport = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * The cached [RenameSupport] per tree URI, in the settings preferences. Reads touch no provider,
 * so [DownloadPreconditions] can use it on the main thread.
 */
internal object RenameSupportStore {
    private fun key(treeUri: Uri) = "rename_support:$treeUri"

    fun read(context: Context, treeUri: Uri): RenameSupport =
        RenameSupport.fromId(SxdcSettings.prefs(context).getString(key(treeUri), null))

    fun write(context: Context, treeUri: Uri, support: RenameSupport) {
        SxdcSettings.prefs(context).edit { putString(key(treeUri), support.id) }
    }
}

/**
 * Finds out, before the first download, whether delivery would fail closed for lack of rename.
 *
 * Creates the same kind of temporary document delivery does, reads its flags, and always deletes
 * it. A name that starts with ".sxdc-" and ends in ".partial" is also what the partial sweep
 * removes, so a delete that fails leaves nothing the next run will not clean up.
 *
 * Every function here does provider I/O: call off the main thread.
 */
internal object RenameProbe {
    private const val TAG = "RenameProbe"

    /** Never throws; a probe that cannot decide is [RenameSupport.UNKNOWN]. */
    @Suppress("TooGenericExceptionCaught") // Deliberate: any probe failure means UNKNOWN, never a crash.
    fun probe(tree: DestinationTree, newId: () -> String = { UUID.randomUUID().toString() }): RenameSupport {
        var document: DestinationDocument? = null
        return try {
            document = tree.createDocument(
                StagedFileDelivery.TEMP_PREFIX + "probe-" + newId() + StagedFileDelivery.TEMP_SUFFIX,
                StagedFileDelivery.MIME_TEMP,
            )
            when {
                document == null -> RenameSupport.UNKNOWN
                tree.supportsRename(document) -> RenameSupport.SUPPORTED
                else -> RenameSupport.UNSUPPORTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rename probe could not decide", e)
            RenameSupport.UNKNOWN
        } finally {
            document?.let { discard(tree, it) }
        }
    }

    /** Probes [treeUri] and caches the answer. */
    fun probeAndStore(context: Context, treeUri: Uri): RenameSupport {
        val support = probe(SafDestinationTree(context, treeUri))
        RenameSupportStore.write(context, treeUri, support)
        Log.i(TAG, "Rename support of $treeUri: $support")
        return support
    }

    /**
     * The user chose [treeUri]: measure it, then lift a [HaltKind.DESTINATION] halt, which was
     * about the folder they have just replaced.
     */
    fun afterFolderChosen(context: Context, treeUri: Uri) {
        probeAndStore(context, treeUri)
        if (QueueHaltStore.read(context)?.kind == HaltKind.DESTINATION) QueueHaltStore.clear(context)
    }

    /** On Resume: measure the folder again, but only if it was last found unable to rename. */
    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that skips the reprobe.
    fun reprobeIfUnsupported(context: Context) {
        val treeUri = SxdcSettings.treeUri(context) ?: return
        if (RenameSupportStore.read(context, treeUri) != RenameSupport.UNSUPPORTED) return
        if (!SxdcSettings.hasWriteGrant(context, treeUri)) return
        probeAndStore(context, treeUri)
    }

    @Suppress("TooGenericExceptionCaught") // Deliberate: cleanup of the probe document must never throw.
    private fun discard(tree: DestinationTree, document: DestinationDocument) {
        val deleted = try {
            tree.delete(document)
        } catch (e: Exception) {
            Log.w(TAG, "Deleting the probe document threw", e)
            false
        }
        if (!deleted) Log.w(TAG, "Probe document ${document.uri} was not deleted; the sweep will")
    }
}
