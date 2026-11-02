package com.v1per.sxdc.saf

import android.Manifest
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.TestSxdcApp
import com.v1per.sxdc.settings.SxdcSettings
import java.util.UUID
import org.junit.rules.ExternalResource

/**
 * Fresh folders on [TestDocumentsProvider] that the app holds a real, persisted SAF grant for.
 *
 * [open] creates a root, borrows the shell's MANAGE_DOCUMENTS just long enough for the provider
 * to grant the app read/write access, takes the persistable permission (through
 * SxdcSettings.saveTreeUri when the folder becomes the destination) and returns the tree URI.
 * [close], run after every test, releases the grants, has the provider revoke what is left and
 * delete the folder, and always drops the shell identity again.
 *
 * Use inside TestSettingsRule (RuleChain), which empties the test settings this writes to.
 * Needs API 29+ for adoptShellPermissionIdentity.
 */
class TestSafFolder : ExternalResource() {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val opened = mutableListOf<String>()

    val authority = instrumentation.context.packageName + ".documents"

    private val providerUri: Uri = Uri.parse("content://$authority")

    /**
     * @param supportsRename false for a root whose documents lack FLAG_SUPPORTS_RENAME
     * @param asDestination store it as the app's destination folder in the test settings
     */
    fun open(supportsRename: Boolean = true, asDestination: Boolean = true): Uri {
        check(Build.VERSION.SDK_INT >= 29) { "TestSafFolder needs API 29 or newer" }
        val prefix = if (supportsRename) "" else TestDocumentsProvider.NO_RENAME_PREFIX
        val rootId = prefix + "root-" + UUID.randomUUID()
        val extras = Bundle().apply { putString(TestDocumentsProvider.EXTRA_PACKAGE, context.packageName) }
        callProvider(TestDocumentsProvider.METHOD_CREATE_ROOT, rootId, extras)
        opened += rootId

        val tree = treeUri(rootId)
        if (asDestination) {
            val app = context.applicationContext
            check(app is TestSxdcApp && app.settingsName == TestSxdcApp.TEST_SETTINGS_NAME) {
                "Refusing to set a destination outside the test settings"
            }
            check(SxdcSettings.saveTreeUri(context, tree)) { "Could not persist the grant for $tree" }
        } else {
            resolver.takePersistableUriPermission(tree, READ_WRITE)
        }
        return tree
    }

    /** A tree on this provider the app was never granted. Never created. */
    fun ungrantedTree(): Uri = treeUri("never-granted-" + UUID.randomUUID())

    /** The app's persisted grants on this provider. */
    fun persistedGrants(): List<UriPermission> =
        resolver.persistedUriPermissions.filter { it.uri.authority == authority }

    /** Display names of the documents directly in [tree], sorted. */
    fun names(tree: Uri): List<String> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val cursor = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: error("Could not list $tree")
        return cursor.use { buildList { while (it.moveToNext()) add(it.getString(0)) } }.sorted()
    }

    fun read(tree: Uri, name: String): ByteArray =
        resolver.openInputStream(documentUri(tree, name))?.use { it.readBytes() } ?: error("Could not open $name")

    /** Writes a document straight through SAF, the way another app would have left it. */
    fun create(tree: Uri, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = DocumentsContract.createDocument(resolver, root, "application/octet-stream", name)
            ?: error("Could not create $name")
        resolver.openOutputStream(created, "w")?.use { it.write(bytes) } ?: error("Could not write $name")
    }

    /** Releases and revokes every grant, and deletes every folder this rule opened. Idempotent. */
    fun close() {
        // Every root is cleaned up even if one fails; the first failure is rethrown at the end.
        val failures = opened.mapNotNull { rootId ->
            val tree = treeUri(rootId)
            try {
                resolver.releasePersistableUriPermission(tree, READ_WRITE)
            } catch (e: SecurityException) {
                Log.i(TAG, "No persisted grant left for $tree: ${e.message}")
            }
            runCatching { callProvider(TestDocumentsProvider.METHOD_DELETE_ROOT, rootId, null) }.exceptionOrNull()
        }
        opened.clear()
        failures.firstOrNull()?.let { throw it }
    }

    override fun after() = close()

    private fun treeUri(rootId: String): Uri = DocumentsContract.buildTreeDocumentUri(authority, rootId)

    private fun documentUri(tree: Uri, name: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree) + "/" + name)

    /** One custom call to the provider, made with the shell's MANAGE_DOCUMENTS, which is always dropped again. */
    private fun callProvider(method: String, rootId: String, extras: Bundle?) {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            resolver.call(providerUri, method, rootId, extras)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val TAG = "TestSafFolder"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
