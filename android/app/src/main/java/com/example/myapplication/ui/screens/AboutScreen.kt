package com.example.myapplication.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.BuildConfig

private const val APK_DOWNLOAD_URL =
    "https://github.com/khageshpatil/Moodify-v2/releases/latest/download/Moodify.apk"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    darkTheme: Boolean = true,
    onDarkThemeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onBackground
                )
            }
            Text(
                text = "About Moodify",
                color = colors.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(20.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = colors.primary)
            Text("Moodify", color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "A mood-first music experience with background playback.",
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            Text("Version ${BuildConfig.VERSION_NAME}", color = colors.onSurfaceVariant, fontSize = 12.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent, RoundedCornerShape(16.dp))
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Appearance", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = darkTheme,
                    onCheckedChange = onDarkThemeChange,
                    modifier = Modifier
                )
            }
            Text(if (darkTheme) "Night Signal" else "Daylight Paper", color = colors.onSurfaceVariant, fontSize = 13.sp)
            Text("Get the latest Android build", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Download the latest Moodify APK from GitHub.",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp
            )
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_DOWNLOAD_URL)))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("Download APK", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            }
        }

        Text(
            "The download opens in your browser. Android may ask you to allow installation from that source.",
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(14.dp)
        )
    }
}
