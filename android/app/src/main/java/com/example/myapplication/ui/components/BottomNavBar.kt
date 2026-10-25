package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.SignalBorder
import com.example.myapplication.ui.theme.SurfaceCard
import com.example.myapplication.ui.theme.TextSecondary

sealed class NavTab(val route: String, val title: String, val icon: ImageVector) {
    object Home : NavTab("home", "Home", Icons.Default.Home)
    object Discover : NavTab("discover", "Search", Icons.Default.Search)
    object Library : NavTab("library", "Library", Icons.Default.LibraryMusic)
}

@Composable
fun BottomNavBar(
    currentRoute: String,
    onTabSelected: (NavTab) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tabs = listOf(NavTab.Home, NavTab.Discover, NavTab.Library)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.96f))
            .border(1.dp, colors.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val isSelected = currentRoute == tab.route

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSelected) colors.primary.copy(alpha = 0.12f) else Color.Transparent
                    )
                    .clickable { onTabSelected(tab) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = tab.title,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) colors.primary else colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}
