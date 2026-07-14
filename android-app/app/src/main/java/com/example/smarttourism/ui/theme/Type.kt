package com.example.smarttourism.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.smarttourism.R

internal val FiraSans = FontFamily(
    Font(R.font.fira_sans_regular, FontWeight.Normal),
    Font(R.font.fira_sans_medium, FontWeight.Medium),
    Font(R.font.fira_sans_semibold, FontWeight.SemiBold),
    Font(R.font.fira_sans_bold, FontWeight.Bold),
)

private val base = Typography()

val Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
    bodyMedium = base.bodyMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
    bodySmall = base.bodySmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Normal),
    labelLarge = base.labelLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
)
