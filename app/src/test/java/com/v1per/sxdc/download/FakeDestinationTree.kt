package com.v1per.sxdc.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** One document in [FakeDestinationTree]. */
internal class FakeDocument(var name: String, val mimeType: String, val isDirectory: Boolean = false) {
    val bytes = ByteArrayOutputStream()
}

/**
 * In-memory SAF folder. Every provider behaviour delivery has to cope with is a knob here.
 * Documents are keyed by a URI that never changes, so a renamed handle stays valid.
 */
internal class FakeDestinationTree : DestinationTree {
    val docs = LinkedHashMap<String, FakeDocument>()
    private var nextId = 1

    val createdMimeTypes = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()

    var listThrows: Throwable? = null
    var listReturnsNull = false
    var createReturnsNull = false

    /** What the provider turns a requested name into on create (e.g. appending an extension). */
    var createdName: (String) -> String = { it }
    var renameSupported = true
    var renameReturnsNull = false
    var renameThrows: Throwable? = null

    /** What the provider turns a requested name into on rename (e.g. adding a suffix). */
    var renamedName: (String) -> String = { it }
    var deleteReturnsFalse = false
    var deleteThrows: Throwable? = null

    /** Thrown from the output stream after this many bytes have been written. */
    var writeThrowsAfter: Long? = null
    var writeError: IOException = IOException("write failed")

    /** Runs on every write, with the tree as it stands mid-copy. */
    var onWrite: (FakeDestinationTree) -> Unit = {}

    /** Test setup: a document that was already there. Returns its URI. */
    fun seed(name: String, content: String = "existing", isDirectory: Boolean = false): String {
        val uri = "content://fake/${nextId++}"
        docs[uri] = FakeDocument(name, "audio/flac", isDirectory).apply { bytes.write(content.toByteArray()) }
        return uri
    }

    fun names(): List<String> = docs.values.map { it.name }

    fun content(name: String): String = docs.values.single { it.name == name }.bytes.toString(Charsets.UTF_8)

    private fun handle(uri: String) = docs.getValue(uri).let { DestinationDocument(uri, it.name, it.isDirectory) }

    override fun listChildren(): List<DestinationDocument>? {
        listThrows?.let { throw it }
        if (listReturnsNull) return null
        return docs.keys.map(::handle)
    }

    override fun createDocument(name: String, mimeType: String): DestinationDocument? {
        createdMimeTypes += name to mimeType
        if (createReturnsNull) return null
        val uri = "content://fake/${nextId++}"
        docs[uri] = FakeDocument(createdName(name), mimeType)
        return handle(uri)
    }

    override fun supportsRename(document: DestinationDocument): Boolean = renameSupported

    override fun openOutputStream(document: DestinationDocument): OutputStream {
        val doc = docs.getValue(document.uri)
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                val limit = writeThrowsAfter
                if (limit != null && doc.bytes.size() + len > limit) throw writeError
                doc.bytes.write(b, off, len)
                onWrite(this@FakeDestinationTree)
            }
        }
    }

    override fun rename(document: DestinationDocument, newName: String): RenamedDocument? {
        renameThrows?.let { throw it }
        if (renameReturnsNull) return null
        val doc = docs.getValue(document.uri)
        doc.name = renamedName(newName)
        return RenamedDocument(doc.name, document.uri)
    }

    override fun delete(document: DestinationDocument): Boolean {
        deleteCalls += document.uri
        deleteThrows?.let { throw it }
        if (deleteReturnsFalse) return false
        return docs.remove(document.uri) != null
    }
}
