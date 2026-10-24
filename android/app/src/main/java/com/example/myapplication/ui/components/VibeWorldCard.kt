package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.ui.theme.DarkBackground
import com.example.myapplication.ui.theme.TextPrimary

@Composable
fun VibeWorldCard(
    vibe: VibeWorld,
    onClick: (VibeWorld) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    hero: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .clickable { onClick(vibe) }
    ) {
        AsyncImage(
            model = vibe.imageRes,
            contentDescription = vibe.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            DarkBackground.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        Text(
            text = vibe.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            color = TextPrimary,
            fontFamily = FontFamily.Serif,
            fontSize = if (hero) 28.sp else 20.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = if (hero) 30.sp else 23.sp
        )
    }
}
