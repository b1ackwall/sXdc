package com.v1per.sxdc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.settings.SxdcSettings
import com.v1per.sxdc.settings.YtDlpCommandPreview
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.SectionLabel
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Authority for the system's built-in local-storage document provider. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Destination folder and yt-dlp options.
 *
 * Standalone rather than a route inside MainActivity because the fix-it screen -- which lives in
 * its own task via ShareReceiverActivity's empty taskAffinity -- has to be able to open it
 * directly without dragging the main task along.
 */
@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = stringResource(R.string.settings_title),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.nav_back),
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {}
                        }
                    },
                ) { padding ->
                    SettingsScreen(Modifier.padding(padding))
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var treeUri by remember { mutableStateOf(SxdcSettings.treeUri(context)) }
    var extraArgs by remember { mutableStateOf(SxdcSettings.extraArgs(context)) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked: Uri? ->
        // Null means the user backed out of the picker, which is not an error.
        if (picked == null) return@rememberLauncherForActivityResult
        if (SxdcSettings.saveTreeUri(context, picked)) {
            treeUri = picked
        } else {
            Toast.makeText(context, R.string.settings_folder_grant_failed, Toast.LENGTH_LONG).show()
        }
    }

    // Local copy: `treeUri` is a delegated property, so it cannot be smart-cast to non-null.
    val selected = treeUri
    val configured = selected != null && SxdcSettings.hasWriteGrant(context, selected)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.Item),
    ) {
        Panel {
            SectionLabel(stringResource(R.string.settings_folder_heading))
            Text(
                text = folderLabel(context, treeUri),
                style = MaterialTheme.typography.bodyMedium,
                // The accent doubles as the "configured" signal, so no separate status chip.
                color = if (configured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // Current tree as the initial location, so re-picking starts where they left off.
            Button(
                onClick = { picker.launch(treeUri) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_choose_folder))
            }
        }

        Panel {
            SectionLabel(stringResource(R.string.settings_ytdlp_heading))
            // Read-only preview of the command that actually runs -- deliberately excludes the
            // extra-parameters field below, since those are saved but not yet wired into a
            // download (see settings_ytdlp_note).
            Text(
                text = YtDlpCommandPreview.render(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(Spacing.Tight),
            )
            OutlinedTextField(
                value = extraArgs,
                onValueChange = {
                    extraArgs = it
                    SxdcSettings.saveExtraArgs(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_ytdlp_label)) },
                singleLine = false,
            )
            Text(
                text = stringResource(R.string.settings_ytdlp_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ClearHistorySection()
        EmergencyStopSection()

        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClearHistorySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }

    Panel {
        SectionLabel(stringResource(R.string.settings_history_heading))
        Button(
            onClick = { showConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_clear_history))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_history_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_history_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            DownloadQueue.dao(context).clearHistory()
                        }
                    }
                }) { Text(stringResource(R.string.settings_clear_history_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.settings_clear_history_cancel))
                }
            },
        )
    }
}

/**
 * Cancels every QUEUED row (DAO `cancelAllPending`). The job already in flight is not
 * interrupted and runs to its natural conclusion. Styled as a distinctly more serious action than
 * "Clear download history" -- error-outlined panel and an error-tinted button, the same visual
 * vocabulary [FixItScreen] uses for "this went wrong," here repurposed for "this is destructive."
 */
@Composable
private fun EmergencyStopSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }

    Panel(borderColor = MaterialTheme.colorScheme.error) {
        SectionLabel(stringResource(R.string.settings_emergency_stop_heading))
        Button(
            onClick = { showConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(stringResource(R.string.settings_emergency_stop))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_emergency_stop_confirm_title)) },
            text = { Text(stringResource(R.string.settings_emergency_stop_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            DownloadQueue.dao(context).cancelAllPending(System.currentTimeMillis())
                        }
                    }
                }) { Text(stringResource(R.string.settings_emergency_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.settings_emergency_stop_cancel))
                }
            },
        )
    }
}

/**
 * A readable path for the chosen tree, falling back to the raw URI when the grant has gone
 * stale -- a folder the user can no longer reach should not silently read as configured.
 *
 * For the system's own local-storage provider, the tree document ID is `<volumeId>:<path>` --
 * `volumeId == "primary"` is internal storage, anything else is an SD card or other local volume
 * identified by that ID. For any other document provider (a different app entirely), there's no
 * generic way to resolve a friendly path, so the provider's own display name is used, with the
 * raw URI as a last resort.
 */
@Composable
private fun folderLabel(context: Context, treeUri: Uri?): String {
    if (treeUri == null) return stringResource(R.string.settings_folder_none)
    if (!SxdcSettings.hasWriteGrant(context, treeUri)) {
        return stringResource(R.string.settings_folder_lost)
    }

    if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":", limit = 2)
        if (parts.size == 2) {
            val (volumeId, relativePath) = parts
            return if (volumeId == "primary") "Internal storage/$relativePath" else "$volumeId:$relativePath"
        }
        // Defensive: the "volumeId:path" convention held at implementation time -- fall back
        // rather than show a malformed string if a future document ID doesn't split cleanly.
    }

    return DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.toString()
}
