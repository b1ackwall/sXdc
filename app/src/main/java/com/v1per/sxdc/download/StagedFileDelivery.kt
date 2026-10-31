package com.v1per.sxdc.download

import android.util.Log
import com.v1per.sxdc.R
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/**
 * One document in the destination folder, as the provider describes it.
 *
 * @param uri the document's `content://` URI in string form -- opaque to [StagedFileDelivery]
 * @param name the display name the provider reports, which may not be the one we asked for
 */
internal data class DestinationDocument(
    val uri: String,
    val name: String?,
    val isDirectory: Boolean = false,
)

/** What a rename ended up as: the name and URI the provider reports after it. */
internal data class RenamedDocument(val name: String, val uri: String)

/**
 * The destination folder, reduced to the handful of operations delivery needs, so the delivery
 * rules can be tested on the plain JVM. SafFileMover implements it over a SAF tree.
 *
 * Any method may throw; [StagedFileDelivery] treats a throw like the failure value.
 */
internal interface DestinationTree {
    /** The folder's direct children, or null if the provider could not list them. */
    fun listChildren(): List<DestinationDocument>?

    /** A new, empty document, or null if the provider refused. Never opens an existing one. */
    fun createDocument(name: String, mimeType: String): DestinationDocument?

    /** Whether the provider will rename [document] (FLAG_SUPPORTS_RENAME). */
    fun supportsRename(document: DestinationDocument): Boolean

    fun openOutputStream(document: DestinationDocument): OutputStream

    /** The resulting name and URI, or null if the provider reported failure. */
    fun rename(document: DestinationDocument, newName: String): RenamedDocument?

    /** Whether the provider reports the document deleted. */
    fun delete(document: DestinationDocument): Boolean
}

/** Where delivery writes its log lines; android.util.Log in production. */
internal fun interface DeliveryLog {
    fun line(isError: Boolean, message: String, error: Throwable?)
}

/**
 * Delivers a finished staged file into the destination without ever exposing a partial file
 * under an audio name, and without ever touching a document that was already there.
 *
 * The file is written under a temporary name that synthX's scanner ignores, verified, and only
 * then renamed to a final name chosen to be free. A failure at any point deletes the temporary
 * document; anything a crash leaves behind is removed by [sweepPartials] at the next queue run.
 * See docs/delivery-safety.md.
 */
internal class StagedFileDelivery(
    private val strings: StringProvider,
    private val log: DeliveryLog = DeliveryLog { isError, message, error ->
        if (isError) Log.e(TAG, message, error) else Log.i(TAG, message, error)
    },
    private val newTempId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Copy [source] into [tree] and return the name the finished file ended up with.
     *
     * @param expected the byte count the copy must reach
     */
    fun deliver(tree: DestinationTree, source: File, expected: Long): MoveResult {
        val existing = runCatching { tree.listChildren() }.getOrNull()
            ?: return fail(R.string.error_create_document, "Could not list the destination folder")
        val finalName = freeName(source.name, existing.mapNotNull { it.name })

        val temp = runCatching { tree.createDocument(TEMP_PREFIX + newTempId() + TEMP_SUFFIX, MIME_TEMP) }
            .onFailure { log.line(true, "Creating the temporary document failed", it) }
            .getOrNull()
            ?: return fail(R.string.error_create_document, "Provider refused the temporary document")

        var delivered = false
        try {
            val outcome = deliverInto(tree, temp, source, expected, finalName)
            delivered = outcome is MoveResult.Moved
            return outcome
        } catch (e: Exception) {
            log.line(true, "Delivery into ${temp.uri} failed", e)
            return MoveResult.Failed(strings.get(R.string.error_copy_failed))
        } finally {
            if (!delivered) discard(tree, temp)
        }
    }

    /** Steps after the temporary document exists. Any non-Moved result has it discarded. */
    private fun deliverInto(
        tree: DestinationTree,
        temp: DestinationDocument,
        source: File,
        expected: Long,
        finalName: String,
    ): MoveResult {
        // Fail closed: a provider that rewrote our name could have made it scannable.
        if (!isSafeTempName(temp.name)) {
            return fail(R.string.error_create_document, "Provider altered the temporary name to ${temp.name}", true)
        }
        // Without rename there is no way to publish the file atomically, and no direct-write fallback.
        if (!tree.supportsRename(temp)) {
            return fail(R.string.error_rename_unsupported, "Destination does not support rename", true)
        }

        val copied = try {
            tree.openOutputStream(temp).use { out -> source.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            log.line(true, "Copy into ${temp.uri} failed", e)
            // A full volume is a property of the destination, not of this file, so it stops the queue.
            return if (e.isOutOfSpace()) {
                MoveResult.Failed(strings.get(R.string.error_storage), queueWide = true)
            } else {
                MoveResult.Failed(strings.get(R.string.error_copy_failed))
            }
        }
        if (copied != expected) {
            // A short write with no exception is almost always the volume filling up mid-copy.
            return fail(R.string.error_storage, "Short copy into ${temp.uri}: $copied of $expected bytes", true)
        }

        val renamed = tree.rename(temp, finalName)
            ?: return fail(R.string.error_copy_failed, "Provider refused to rename ${temp.uri} to $finalName")
        // Accept a suffix the provider added on a race, but never a name the sweep would delete.
        if (isPartialName(renamed.name)) {
            return fail(R.string.error_copy_failed, "Rename left the temporary name ${renamed.name}")
        }

        log.line(false, "Delivered to ${renamed.uri} as ${renamed.name} ($copied bytes)", null)
        return MoveResult.Moved(renamed.name)
    }

    /**
     * Delete every leftover temporary document directly inside [tree]; never throws.
     *
     * Only names this class creates are matched, and folders are never touched.
     * @return how many were deleted
     */
    fun sweepPartials(tree: DestinationTree): Int {
        val children = runCatching { tree.listChildren() }
            .onFailure { log.line(true, "Could not list the destination for the partial sweep", it) }
            .getOrNull() ?: return 0
        return children.count { !it.isDirectory && isPartialName(it.name) && discard(tree, it) }
    }

    /** Deletes [document] and logs whether that worked. Never throws. */
    private fun discard(tree: DestinationTree, document: DestinationDocument): Boolean {
        val deleted = try {
            tree.delete(document)
        } catch (e: Exception) {
            log.line(true, "Deleting ${document.uri} threw", e)
            false
        }
        log.line(!deleted, "Deleted temporary document ${document.uri}: $deleted", null)
        return deleted
    }

    private fun fail(id: Int, reason: String, queueWide: Boolean = false): MoveResult.Failed {
        log.line(true, reason, null)
        return MoveResult.Failed(strings.get(id), queueWide)
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

    internal companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadService"
        const val TEMP_PREFIX = ".sxdc-"
        const val TEMP_SUFFIX = ".partial"
        const val MIME_TEMP = "application/octet-stream"

        /** The extensions synthX's scanner ingests; a temporary name must never end in one. */
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

        val OUT_OF_SPACE_MARKERS = listOf("enospc", "no space left", "disk full", "not enough space")

        /** A name only this class creates, which is exactly what the sweep may delete. */
        fun isPartialName(name: String?): Boolean =
            name != null && name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)

        /** Whether the provider kept the temporary name inert: our prefix, no audio extension. */
        fun isSafeTempName(name: String?): Boolean =
            name != null && name.startsWith(TEMP_PREFIX) &&
                name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS

        /**
         * [original] if no name in [taken] matches it case-insensitively, else the first free
         * "<base> (n).<ext>". Terminates: at most taken.size candidates can be occupied.
         */
        fun freeName(original: String, taken: Collection<String>): String {
            val occupied = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            if (original.lowercase(Locale.ROOT) !in occupied) return original
            val dot = original.lastIndexOf('.')
            val base = if (dot > 0) original.substring(0, dot) else original
            val ext = if (dot > 0) original.substring(dot) else ""
            var n = 1
            while ("$base ($n)$ext".lowercase(Locale.ROOT) in occupied) n++
            return "$base ($n)$ext"
        }
    }
}
