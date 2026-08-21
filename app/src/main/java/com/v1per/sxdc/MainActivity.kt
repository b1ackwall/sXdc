package com.v1per.sxdc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.v1per.sxdc.media.FfmpegBinaries
import com.v1per.sxdc.ui.theme.SXdcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Python is started in SxdcApp; the share flow can reach the interpreter without this
        // activity ever running.
        val verifyModule = Python.getInstance().getModule("sxdc_verify")
        val greeting = verifyModule.callAttr("hello").toString()
        val sum = verifyModule.callAttr("add", 2, 2).toInt()
        val ytDlpVersion = verifyModule.callAttr("yt_dlp_version").toString()

        val ffmpegStatus = try {
            val install = FfmpegBinaries.install(this)
            val report = verifyModule.callAttr("ffmpeg_report", install.binDirPath).toString()
            "[${install.source}] ${install.binDirPath}\n$report"
        } catch (e: Exception) {
            Log.e("MainActivity", "ffmpeg install failed", e)
            "ffmpeg install FAILED: ${e.message}"
        }

        val status = "$greeting (2+2=$sum) yt-dlp $ytDlpVersion\n$ffmpegStatus"

        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NotificationPermissionRequest()
                        Text(text = status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Proactively requests POST_NOTIFICATIONS once per app launch, showing a short rationale first
 * when the system says one is warranted.
 *
 * A denial is not an error state: downloads still run, they are just invisible outside Android's
 * own Task Manager, so nothing here gates any functionality.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val activity = context as? Activity
    var requested by rememberSaveable { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
    }

    LaunchedEffect(Unit) {
        if (requested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect

        requested = true
        val needsRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (needsRationale) {
            showRationale = true
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.notif_rationale_title)) },
            text = { Text(stringResource(R.string.notif_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notif_rationale_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.notif_rationale_dismiss))
                }
            },
        )
    }
}
