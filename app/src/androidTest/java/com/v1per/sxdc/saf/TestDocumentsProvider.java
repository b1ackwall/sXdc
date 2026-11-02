package com.v1per.sxdc.saf;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A flat DocumentsProvider for instrumented tests, over folders in the test app's cache dir.
 *
 * <p>Java, not Kotlin, on purpose: a provider declared in the androidTest manifest belongs to the
 * test app and runs in the test app's own process. That process does not have the Kotlin standard
 * library (it ships in the app's APK, not the test APK), so a Kotlin provider would fail to load.
 * Use framework classes only here.
 *
 * <p>Each root is one folder whose document id is its name; a file in it is {@code root/name}.
 * Roots whose id starts with {@link #NO_RENAME_PREFIX} do not support rename, like a provider
 * without FLAG_SUPPORTS_RENAME. There is no DOCUMENTS_PROVIDER intent filter, so the system
 * file picker never lists this provider.
 *
 * <p>Two custom calls, made by {@code TestSafFolder}: {@link #METHOD_CREATE_ROOT} creates a root
 * and grants the caller's package a persistable read/write grant on its tree;
 * {@link #METHOD_DELETE_ROOT} revokes every grant on that tree and deletes the folder.
 */
public final class TestDocumentsProvider extends DocumentsProvider {

    public static final String METHOD_CREATE_ROOT = "sxdc_test_create_root";
    public static final String METHOD_DELETE_ROOT = "sxdc_test_delete_root";
    public static final String EXTRA_PACKAGE = "package";
    public static final String NO_RENAME_PREFIX = "norename-";

    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    };
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    @Override
    public boolean onCreate() {
        return true;
    }

    private File base() {
        return new File(getContext().getCacheDir(), "test-documents");
    }

    private File fileFor(String documentId) {
        return new File(base(), documentId);
    }

    private static String rootOf(String documentId) {
        int slash = documentId.indexOf('/');
        return slash < 0 ? documentId : documentId.substring(0, slash);
    }

    private static boolean isRoot(String documentId) {
        return documentId.indexOf('/') < 0;
    }

    private static boolean supportsRename(String documentId) {
        return !rootOf(documentId).startsWith(NO_RENAME_PREFIX);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        File[] roots = base().listFiles();
        if (roots == null) return cursor;
        for (File root : roots) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, root.getName())
                .add(Root.COLUMN_DOCUMENT_ID, root.getName())
                .add(Root.COLUMN_TITLE, root.getName())
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY);
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addRow(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = fileFor(parentDocumentId).listFiles();
        if (children == null) throw new FileNotFoundException(parentDocumentId);
        for (File child : children) addRow(cursor, parentDocumentId + "/" + child.getName());
        return cursor;
    }

    private void addRow(MatrixCursor cursor, String documentId) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        String mimeType;
        int flags;
        if (isRoot(documentId)) {
            mimeType = Document.MIME_TYPE_DIR;
            flags = Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            mimeType = "application/octet-stream";
            flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
            if (supportsRename(documentId)) flags |= Document.FLAG_SUPPORTS_RENAME;
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.getName())
            .add(Document.COLUMN_MIME_TYPE, mimeType)
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (isRoot(documentId)) throw new FileNotFoundException("Not a file: " + documentId);
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    /** Never replaces an existing file: a taken name gets " (n)" before its extension. */
    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!isRoot(parentDocumentId)) throw new FileNotFoundException("Not a folder: " + parentDocumentId);
        File file = freeFile(fileFor(parentDocumentId), displayName);
        try {
            if (!file.createNewFile()) throw new FileNotFoundException("Could not create " + file.getName());
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
        return parentDocumentId + "/" + file.getName();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (!supportsRename(documentId)) throw new UnsupportedOperationException("Rename not supported");
        File from = fileFor(documentId);
        File to = freeFile(from.getParentFile(), displayName);
        if (!from.renameTo(to)) throw new IllegalStateException("Rename failed: " + documentId);
        return rootOf(documentId) + "/" + to.getName();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (isRoot(documentId) || !fileFor(documentId).delete()) throw new FileNotFoundException(documentId);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }

    private static File freeFile(File dir, String name) {
        File file = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; file.exists(); n++) file = new File(dir, base + " (" + n + ")" + ext);
        return file;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (METHOD_CREATE_ROOT.equals(method)) {
            File root = fileFor(arg);
            if (!isRoot(arg) || !root.mkdirs()) throw new IllegalStateException("Could not create root " + arg);
            String grantee = extras.getString(EXTRA_PACKAGE);
            context.grantUriPermission(grantee, treeUri(context, arg), GRANT_FLAGS);
            return Bundle.EMPTY;
        }
        if (METHOD_DELETE_ROOT.equals(method)) {
            // From the owner this removes every grant under the tree, persisted ones included.
            context.revokeUriPermission(
                treeUri(context, arg),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            File root = fileFor(arg);
            File[] children = root.listFiles();
            if (children != null) for (File child : children) child.delete();
            root.delete();
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private static Uri treeUri(Context context, String rootId) {
        return DocumentsContract.buildTreeDocumentUri(context.getPackageName() + ".documents", rootId);
    }
}
