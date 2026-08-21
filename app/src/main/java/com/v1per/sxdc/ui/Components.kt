package com.v1per.sxdc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.v1per.sxdc.ui.theme.Spacing

/**
 * Screen title with a short accent rule under it.
 *
 * The rule is the one piece of pure decoration in the app, and it is deliberately the accent
 * colour: it marks "this is an sXdc screen" once per screen, so the accent never has to be
 * sprayed across headings to establish the theme.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(text = text, style = MaterialTheme.typography.headlineSmall)
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Small all-caps label that heads a [Panel]. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Hairline-bordered container.
 *
 * Border rather than elevation: drop shadows read as mush against a near-black background, so the
 * outline is what separates a panel from the page. [borderColor] lets a failure panel state itself
 * in [MaterialTheme.colorScheme.error] without tinting its whole fill.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
        .border(1.dp, borderColor, MaterialTheme.shapes.small)
        .padding(Spacing.Item),
    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    content = content,
)
