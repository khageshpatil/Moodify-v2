package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopHeader(
    onSearchClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onAboutClick: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) { }
            Text(text = "Moodify", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.surfaceVariant, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }

            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.surfaceVariant, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Listening history",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onAboutClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Transparent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
