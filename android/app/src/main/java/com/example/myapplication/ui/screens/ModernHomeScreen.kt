package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.CatalogSection
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.Track
import com.example.myapplication.ui.components.IntentComposer
import com.example.myapplication.ui.components.CatalogShelves
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary

@Composable
fun ModernHomeScreen(
    onIntent: (String) -> Unit,
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

        if (catalogSections.isNotEmpty()) {
            item {
                SectionHeading()
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
    ) {
        Column {
            Text(
                text = "Live catalog",
                color = TextPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                lineHeight = 28.sp
            )
            Text(text = "Fresh shelves from your region", color = TextSecondary, fontSize = 11.sp)
        }
    }
}
