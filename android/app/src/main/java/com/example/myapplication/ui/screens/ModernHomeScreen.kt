package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.DEFAULT_VIBE_WORLDS
import com.example.myapplication.data.CatalogSection
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.Track
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.ui.components.IntentComposer
import com.example.myapplication.ui.components.CatalogShelves
import com.example.myapplication.ui.components.VibeWorldCard
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary

@Composable
fun ModernHomeScreen(
    onIntent: (String) -> Unit,
    onVibeSelect: (VibeWorld) -> Unit,
    catalogSections: List<CatalogSection> = emptyList(),
    currentTrack: Track? = null,
    onTrackSelect: (Track) -> Unit = {},
    onBrowseClick: (BrowseEndpoint) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 150.dp)
    ) {
        item {
            IntentComposer(onSubmit = onIntent)
        }

        item {
            Spacer(modifier = Modifier.height(54.dp))
            SectionHeading()
            Spacer(modifier = Modifier.height(18.dp))
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VibeWorldCard(
                    vibe = DEFAULT_VIBE_WORLDS[0],
                    onClick = onVibeSelect,
                    height = 220.dp,
                    hero = true
                )
                DEFAULT_VIBE_WORLDS.drop(1).take(2).forEach { vibe ->
                    VibeWorldCard(vibe = vibe, onClick = onVibeSelect, height = 130.dp)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "•  •  •  •  •  •  •  •",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        if (catalogSections.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(34.dp))
                CatalogShelves(
                    sections = catalogSections.take(5),
                    currentTrack = currentTrack,
                    onTrackClick = onTrackSelect,
                    onBrowseClick = onBrowseClick
                )
            }
        }
    }
}

@Composable
private fun SectionHeading() {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "01", color = PrimaryPurple, fontSize = 11.sp)
        Column {
            Text(
                text = "Choose a world",
                color = TextPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                lineHeight = 28.sp
            )
            Text(text = "enter a feeling, not a genre", color = TextSecondary, fontSize = 11.sp)
        }
    }
}
