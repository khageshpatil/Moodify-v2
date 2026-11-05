package com.moodify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.designsystem.MoodOptions
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Spacing
import java.util.Locale

private val REGION_CHOICES = listOf("US", "GB", "IN", "CA", "AU", "DE", "FR", "ES", "BR", "MX", "JP", "KR")
private val LANGUAGE_CHOICES = listOf("en" to "English", "hi" to "हिन्दी", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "ja" to "日本語", "ko" to "한국어")

/**
 * First-run setup: a welcome, the listener's region and language (pre-filled
 * from the phone), and the moods they reach for most.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onFinish: (language: String, region: String, moods: List<String>) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val deviceLocale = remember { Locale.getDefault() }
    var region by rememberSaveable {
        mutableStateOf(deviceLocale.country.uppercase().takeIf { it.length == 2 } ?: "US")
    }
    var language by rememberSaveable {
        mutableStateOf(deviceLocale.language.lowercase().takeIf { it.isNotBlank() } ?: "en")
    }
    val moods = remember { mutableStateListOf<String>() }

    BackHandler(enabled = step > 0) { step-- }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AuraGlow(height = 560.dp, intensity = 1.4f, color = Color(0xFF8B5CF6))
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
        ) {
            StepDots(step = step, count = 3)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn())
                        .togetherWith(slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "onboardingStep",
            ) { current ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> RegionStep(region, language, onRegion = { region = it }, onLanguage = { language = it })
                        else -> MoodStep(moods)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step == 2) {
                    TextButton(onClick = { onFinish(language, region, moods.toList()) }) { Text("Skip") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (step < 2) step++ else onFinish(language, region, moods.toList()) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.xxl, vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        when (step) {
                            0 -> "Get started"
                            1 -> "Continue"
                            else -> if (moods.isEmpty()) "Start listening" else "Start listening · ${moods.size}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Int, count: Int) {
    Row(Modifier.padding(vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val width by animateFloatAsState(if (index == step) 28f else 8f, label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth().padding(top = 96.dp)) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF5B34E8), Color(0xFFF97366)))),
            contentAlignment = Alignment.Center,
        ) {
            EqualizerBars(playing = true, color = Color.White, barWidth = 6.dp, modifier = Modifier.size(40.dp, 28.dp))
        }
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            "Music that\nmatches your mood.",
            style = MaterialTheme.typography.displayMedium.merge(
                TextStyle(brush = Brush.linearGradient(listOf(onBackground, onBackground, MaterialTheme.colorScheme.primary))),
            ),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Tell Moodify how you feel and it finds the soundtrack — then keeps it going with songs you'll love.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionStep(region: String, language: String, onRegion: (String) -> Unit, onLanguage: (String) -> Unit) {
    val regions = remember(region) { (listOf(region) + REGION_CHOICES).distinct() }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xxl)) {
        Text("Where are you listening?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Charts, new releases and search follow your country and language.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        Text("Country", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            regions.forEach { code ->
                FilterChip(selected = region == code, onClick = { onRegion(code) }, label = { Text("${flag(code)} $code") }, shape = CircleShape)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Text("Language", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LANGUAGE_CHOICES.forEach { (code, name) ->
                FilterChip(selected = language == code, onClick = { onLanguage(code) }, label = { Text(name) }, shape = CircleShape)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodStep(selected: MutableList<String>) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xxl)) {
        Text("What moves you?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Pick a few moods. They'll sit up front on Home.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            MoodOptions.forEach { mood ->
                val isSelected = mood.id in selected
                Row(
                    Modifier
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Brush.horizontalGradient(listOf(mood.hue, mood.hue.copy(alpha = 0.7f)))
                            else Brush.horizontalGradient(listOf(mood.hue.copy(alpha = 0.16f), mood.hue.copy(alpha = 0.08f))),
                        )
                        .border(1.dp, mood.hue.copy(alpha = if (isSelected) 0f else 0.4f), CircleShape)
                        .pressable(pressedScale = 0.93f, haptic = true, role = Role.Checkbox) {
                            if (isSelected) selected.remove(mood.id) else selected.add(mood.id)
                        }
                        .semantics { this.selected = isSelected; role = Role.Checkbox }
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isSelected) Icons.Rounded.Check else mood.icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else mood.hue,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        mood.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun flag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 'A'.code
    return code.uppercase().map { String(Character.toChars(base + it.code)) }.joinToString("")
}
