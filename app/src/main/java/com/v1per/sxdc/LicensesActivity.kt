package com.v1per.sxdc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.v1per.sxdc.licenses.ComponentsTsv
import com.v1per.sxdc.licenses.LicenseComponent
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.SectionLabel
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "LicensesActivity"

/** Minimum touch target for rows and links. */
private val MinTouchTarget = 48.dp

/**
 * Third-party notices: the list in assets/licenses/components.tsv, and the full text of each
 * license from assets/licenses/ (copied there from the repo's licenses/ by the build).
 */
@OptIn(ExperimentalMaterial3Api::class)
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                // Index into the parsed list; -1 is the list itself. Survives rotation.
                var selected by rememberSaveable { mutableIntStateOf(-1) }
                val components = rememberComponents()
                val detail = when (components) {
                    is Loaded -> components.value.getOrNull(selected)
                    else -> null
                }
                val closeDetail = { selected = -1 }
                BackHandler(enabled = detail != null, onBack = closeDetail)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = detail?.name ?: stringResource(R.string.licenses_title),
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { if (detail != null) closeDetail() else finish() }) {
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
                    val modifier = Modifier.padding(padding)
                    if (detail != null) {
                        LicenseDetail(detail, modifier)
                    } else {
                        LicenseList(components, onOpen = { selected = it }, modifier = modifier)
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LicensesActivity::class.java)
    }
}

/** Loading state of one asset. */
private sealed interface AssetState<out T>
private data object Loading : AssetState<Nothing>
private data class Loaded<T>(val value: T) : AssetState<T>
private data class Failed(val name: String) : AssetState<Nothing>

private fun readAsset(context: Context, name: String): String =
    context.assets.open("${ComponentsTsv.ASSET_DIR}/$name").bufferedReader().use { it.readText() }

@Composable
private fun rememberComponents(): AssetState<List<LicenseComponent>> {
    val context = LocalContext.current
    val state by produceState<AssetState<List<LicenseComponent>>>(Loading) {
        value = withContext(Dispatchers.IO) {
            try {
                Loaded(ComponentsTsv.parse(readAsset(context, "components.tsv")))
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't read components.tsv", e)
                Failed("components.tsv")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Couldn't parse components.tsv", e)
                Failed("components.tsv")
            }
        }
    }
    return state
}

@Composable
private fun LicenseList(
    components: AssetState<List<LicenseComponent>>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.Item),
    ) {
        AppNotice()
        Panel {
            SectionLabel(
                stringResource(R.string.licenses_components_heading),
                modifier = Modifier.semantics { heading() },
            )
            when (components) {
                Loading -> Unit
                is Failed -> ErrorText(components.name)
                is Loaded -> components.value.forEachIndexed { index, component ->
                    ComponentRow(component, onClick = { onOpen(index) })
                }
            }
        }
    }
}

@Composable
private fun AppNotice() {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.licenses_source_url)
    Panel {
        SectionLabel(stringResource(R.string.licenses_app_heading), modifier = Modifier.semantics { heading() })
        Text(text = stringResource(R.string.licenses_app_notice), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = sourceUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(
                    onClickLabel = stringResource(R.string.licenses_source_open),
                    role = Role.Button,
                ) { uriHandler.openUri(sourceUrl) }
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Text(
            text = stringResource(R.string.licenses_source_offer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComponentRow(component: LicenseComponent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(
                onClickLabel = stringResource(R.string.licenses_open_text),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = Spacing.Tight),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = component.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.licenses_row_summary, component.version, component.license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseDetail(component: LicenseComponent, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.Item),
    ) {
        Text(
            text = stringResource(R.string.licenses_row_summary, component.version, component.license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (component.notes.isNotEmpty()) {
            Text(text = component.notes, style = MaterialTheme.typography.bodySmall)
        }
        if (component.files.isEmpty()) {
            Text(text = stringResource(R.string.licenses_no_text), style = MaterialTheme.typography.bodyMedium)
        }
        component.files.forEach { LicenseText(it) }
    }
}

@Composable
private fun LicenseText(name: String) {
    val context = LocalContext.current
    val state by produceState<AssetState<String>>(Loading, name) {
        value = withContext(Dispatchers.IO) {
            try {
                Loaded(readAsset(context, name))
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't read license text $name", e)
                Failed(name)
            }
        }
    }
    when (val s = state) {
        Loading -> Unit
        is Failed -> ErrorText(s.name)
        is Loaded -> SelectionContainer {
            Text(
                text = s.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(Spacing.Tight),
            )
        }
    }
}

@Composable
private fun ErrorText(name: String) {
    Text(
        text = stringResource(R.string.licenses_load_error, name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
