package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.CatalogPage
import com.example.myapplication.data.Track
import com.example.myapplication.ui.components.CatalogShelves
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary

@Composable
fun CatalogDetailScreen(
    page: CatalogPage,
    currentTrack: Track?,
    onTrackSelect: (Track) -> Unit,
    onBrowseClick: (com.example.myapplication.data.BrowseEndpoint) -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 17.dp),
        contentPadding = PaddingValues(bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(22.dp))
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text(text = "← Back", color = colors.primary, fontSize = 13.sp)
            }
            Text(text = page.surface.replaceFirstChar { it.uppercase() }, color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(modifier = Modifier.height(7.dp))
            Text(text = page.title, color = colors.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(text = "A live music collection for the moment.", color = colors.onSurfaceVariant, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(18.dp))
        }
        item {
            CatalogShelves(
                sections = page.sections,
                currentTrack = currentTrack,
                onTrackClick = onTrackSelect,
                onBrowseClick = onBrowseClick
            )
        }
    }
}
