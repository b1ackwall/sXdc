package com.v1per.sxdc.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameProbeTest {
    private val tree = FakeDestinationTree()

    private fun probe() = RenameProbe.probe(tree) { "id" }

    @Test
    fun renameSupported_isSupported_andTheProbeDocumentIsDeleted() {
        assertEquals(RenameSupport.SUPPORTED, probe())
        assertEquals(emptyList<String>(), tree.names())
        assertEquals(1, tree.deleteCalls.size)
    }

    @Test
    fun renameUnsupported_isUnsupported_andTheProbeDocumentIsDeleted() {
        tree.renameSupported = false

        assertEquals(RenameSupport.UNSUPPORTED, probe())
        assertEquals(emptyList<String>(), tree.names())
    }

    @Test
    fun theProbeDocument_usesAnInertNameTheSweepWouldRemove() {
        probe()

        val (name, mime) = tree.createdMimeTypes.single()
        assertTrue(StagedFileDelivery.isPartialName(name))
        assertTrue(StagedFileDelivery.isSafeTempName(name))
        assertEquals(StagedFileDelivery.MIME_TEMP, mime)
    }

    @Test
    fun providerRefusesToCreate_isUnknown_andDeletesNothing() {
        tree.createReturnsNull = true

        assertEquals(RenameSupport.UNKNOWN, probe())
        assertTrue(tree.deleteCalls.isEmpty())
    }

    @Test
    fun createThrows_isUnknown() {
        tree.createThrows = IllegalStateException("provider exploded")

        assertEquals(RenameSupport.UNKNOWN, probe())
    }

    @Test
    fun flagQueryThrows_isUnknown_andTheDocumentIsStillDeleted() {
        tree.supportsRenameThrows = IllegalStateException("provider exploded")

        assertEquals(RenameSupport.UNKNOWN, probe())
        assertEquals(emptyList<String>(), tree.names())
    }

    @Test
    fun deleteFailing_doesNotChangeTheAnswer_andNeverThrows() {
        tree.deleteReturnsFalse = true
        assertEquals(RenameSupport.SUPPORTED, probe())

        tree.deleteThrows = IllegalStateException("gone")
        tree.renameSupported = false
        assertEquals(RenameSupport.UNSUPPORTED, probe())
    }

    @Test
    fun cachedIds_roundTrip_andAnythingElseIsUnknown() {
        RenameSupport.entries.forEach { assertEquals(it, RenameSupport.fromId(it.id)) }
        assertEquals(RenameSupport.UNKNOWN, RenameSupport.fromId(null))
        assertEquals(RenameSupport.UNKNOWN, RenameSupport.fromId("maybe"))
    }

    @Test
    fun preconditions_blockOnlyOnAKnownUnsupported() {
        assertEquals(
            DownloadPreconditions.REASON_FOLDER_UNSAFE,
            DownloadPreconditions.renameReason(RenameSupport.UNSUPPORTED),
        )
        assertNull(DownloadPreconditions.renameReason(RenameSupport.UNKNOWN))
        assertNull(DownloadPreconditions.renameReason(RenameSupport.SUPPORTED))
    }
}
