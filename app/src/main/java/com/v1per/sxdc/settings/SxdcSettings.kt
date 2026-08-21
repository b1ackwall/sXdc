package com.v1per.sxdc.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * The single reader/writer for the `sxdc.settings` SharedPreferences file.
 *
 * Task 3 kept a `download_folder_configured` boolean here as a stand-in; that key is gone. The
 * destination is now a real SAF tree URI, and the persisted grant behind it -- not the string in
 * prefs -- is what actually decides whether a download can run.
 */
object SxdcSettings {

    private const val TAG = "SxdcSettings"

    const val PREFS = "sxdc.settings"

    private const val KEY_TREE_URI = "download_tree_uri"
    private const val KEY_YTDLP_EXTRA_ARGS = "ytdlp_extra_args"

    /** Read+write, the pair that has to be taken for the grant to survive a reboot. */
    private const val GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }?.toUri()

    /**
     * True only when the system still holds a persisted *write* grant for [uri].
     *
     * Storing the URI is not the same as still being allowed to use it: the user can revoke the
     * grant, and the folder can be deleted or live on an SD card that is no longer mounted. Every
     * precondition check re-asks the system rather than trusting the prefs entry.
     */
    fun hasWriteGrant(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    /**
     * Persist [uri] as the destination, taking its grant and dropping the previous one.
     *
     * The new grant is taken *before* the old one is released so a failure part-way through
     * leaves the app with a usable folder rather than none. Releasing is skipped when the user
     * re-picks the folder they already had, since that would throw away the grant just taken.
     */
    fun saveTreeUri(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val previous = treeUri(context)

        try {
            resolver.takePersistableUriPermission(uri, GRANT_FLAGS)
        } catch (e: SecurityException) {
            // The picker did not offer a persistable grant for this tree.
            Log.e(TAG, "Could not persist grant for $uri", e)
            return false
        }

        if (previous != null && previous != uri) {
            try {
                resolver.releasePersistableUriPermission(previous, GRANT_FLAGS)
            } catch (e: SecurityException) {
                // Already gone -- nothing to release, and nothing that should block the new pick.
                Log.w(TAG, "No grant to release for $previous", e)
            }
        }

        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        return true
    }

    fun extraArgs(context: Context): String =
        prefs(context).getString(KEY_YTDLP_EXTRA_ARGS, "").orEmpty()

    fun saveExtraArgs(context: Context, value: String) {
        prefs(context).edit().putString(KEY_YTDLP_EXTRA_ARGS, value).apply()
    }
}
