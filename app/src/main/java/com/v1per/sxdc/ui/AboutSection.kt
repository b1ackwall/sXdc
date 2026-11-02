package com.v1per.sxdc.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v1per.sxdc.BuildConfig
import com.v1per.sxdc.R

/** Settings footer: the entry to the third-party licenses screen and the app version. */
@Composable
fun AboutSection(onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        SectionLabel(stringResource(R.string.settings_about_heading), modifier = Modifier.semantics { heading() })
        OutlinedButton(
            onClick = onOpenLicenses,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.settings_licenses))
        }
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
