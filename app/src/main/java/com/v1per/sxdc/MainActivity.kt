package com.v1per.sxdc

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.v1per.sxdc.media.FfmpegBinaries
import com.v1per.sxdc.ui.theme.SXdcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        val verifyModule = py.getModule("sxdc_verify")
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

        val pythonStatus = "$greeting (2+2=$sum) yt-dlp $ytDlpVersion\n$ffmpegStatus"

        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = pythonStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SXdcTheme {
        Greeting("Android")
    }
}