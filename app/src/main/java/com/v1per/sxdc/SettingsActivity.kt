package com.v1per.sxdc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import com.v1per.sxdc.settings.SxdcSettings
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.ScreenTitle
import com.v1per.sxdc.ui.SectionLabel
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing

/**
 * Destination folder and yt-dlp options.
 *
 * Standalone rather than a route inside MainActivity because the fix-it screen -- which lives in
 * its own task via ShareReceiverActivity's empty taskAffinity -- has to be able to open it
 * directly without dragging the main task along.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
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
        ScreenTitle(stringResource(R.string.settings_title))

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
            Text(
                text = stringResource(R.string.settings_folder_restrictions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            SectionLabel(stringResource(R.string.settings_ytdlp_heading))
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
    }
}

/**
 * The provider's own name for the tree, falling back to the raw URI when the grant has gone
 * stale -- a folder the user can no longer reach should not silently read as configured.
 */
@Composable
private fun folderLabel(context: Context, treeUri: Uri?): String {
    if (treeUri == null) return stringResource(R.string.settings_folder_none)
    if (!SxdcSettings.hasWriteGrant(context, treeUri)) {
        return stringResource(R.string.settings_folder_lost)
    }
    return DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.toString()
}
