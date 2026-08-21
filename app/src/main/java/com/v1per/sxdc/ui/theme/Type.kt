package com.v1per.sxdc.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.v1per.sxdc.R

/**
 * Outfit, bundled as the single variable font file rather than one static file per weight.
 *
 * The variation settings are not optional. `Outfit[wght].ttf` declares one `wght` axis spanning
 * 100..900 whose *default instance is 100* -- so a plain `Font(R.font.outfit_variable, weight)`
 * renders every style as hairline Thin, ignoring the FontWeight entirely. Pinning the axis per
 * weight is what makes the family behave. Variable axes need API 26+; minSdk is 28.
 */
@OptIn(ExperimentalTextApi::class)
private fun outfit(weight: FontWeight) = Font(
    resId = R.font.outfit_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Outfit = FontFamily(
    outfit(FontWeight.Normal),
    outfit(FontWeight.Medium),
    outfit(FontWeight.SemiBold),
    outfit(FontWeight.Bold),
)

/**
 * Only the styles the three screens actually use are overridden; the rest inherit Material's
 * metrics with the family swapped in.
 *
 * The slight negative tracking on headlines and the wide tracking on labels are the whole
 * typographic idea here: tight titles, spaced-out button/label caps.
 */
private val Base = Typography()

val Typography = with(Base) {
    copy(
        headlineSmall = headlineSmall.copy(
            fontFamily = Outfit,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
        ),
        titleMedium = titleMedium.copy(
            fontFamily = Outfit,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        ),
        bodyMedium = bodyMedium.copy(fontFamily = Outfit),
        bodySmall = bodySmall.copy(fontFamily = Outfit, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(
            fontFamily = Outfit,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        ),
        labelSmall = labelSmall.copy(
            fontFamily = Outfit,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        ),
    )
}
