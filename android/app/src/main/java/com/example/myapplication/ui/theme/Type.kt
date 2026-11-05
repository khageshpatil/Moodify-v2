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

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Jakarta,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/**
 * One type scale for the whole app. Display/headline styles are tight and
 * heavy for an editorial feel; body and label styles stay calm.
 */
val MoodifyTypography = Typography(
    displayLarge = style(52, 56, FontWeight.ExtraBold, -0.035),
    displayMedium = style(42, 46, FontWeight.ExtraBold, -0.03),
    displaySmall = style(34, 38, FontWeight.ExtraBold, -0.025),
    headlineLarge = style(30, 36, FontWeight.Bold, -0.02),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.02),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.015),
    titleLarge = style(20, 26, FontWeight.Bold, -0.01),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.005),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.0),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.0),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.0),
    bodySmall = style(12, 16, FontWeight.Medium, 0.005),
    labelLarge = style(14, 20, FontWeight.SemiBold, 0.005),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.01),
    labelSmall = style(11, 14, FontWeight.Bold, 0.06),
)
