package com.moodify.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.moodify.app.R

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

private fun style(token: TypeToken) = TextStyle(
    fontFamily = Jakarta,
    fontWeight = token.weight,
    fontSize = token.size.sp,
    lineHeight = token.line.sp,
    letterSpacing = token.tracking.em,
)

/**
 * One type scale for the whole app (values in design-tokens/moodify.tokens.json).
 * Display/headline styles are tight and heavy for an editorial feel; body and
 * label styles stay calm.
 */
val MoodifyTypography = Typography(
    displayLarge = style(TypeScale.displayLarge),
    displayMedium = style(TypeScale.displayMedium),
    displaySmall = style(TypeScale.displaySmall),
    headlineLarge = style(TypeScale.headlineLarge),
    headlineMedium = style(TypeScale.headlineMedium),
    headlineSmall = style(TypeScale.headlineSmall),
    titleLarge = style(TypeScale.titleLarge),
    titleMedium = style(TypeScale.titleMedium),
    titleSmall = style(TypeScale.titleSmall),
    bodyLarge = style(TypeScale.bodyLarge),
    bodyMedium = style(TypeScale.bodyMedium),
    bodySmall = style(TypeScale.bodySmall),
    labelLarge = style(TypeScale.labelLarge),
    labelMedium = style(TypeScale.labelMedium),
    labelSmall = style(TypeScale.labelSmall),
)
