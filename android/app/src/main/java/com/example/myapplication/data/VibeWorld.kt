package com.example.myapplication.data

import androidx.compose.ui.graphics.Color
import com.example.myapplication.R

data class VibeWorld(
    val id: String,
    val title: String,
    val subtitle: String,
    val accentColor: Color,
    val searchQuery: String,
    val imageRes: Int
)

val DEFAULT_VIBE_WORLDS = listOf(
    VibeWorld("truck-wala", "Truck Wala", "Long roads, good songs.", Color(0xFFE07A3D), "truck driver highway hindi songs playlist", R.drawable.vibe_truck_wala),
    VibeWorld("deluxe-saloon", "Deluxe Saloon", "Stories, people, old songs.", Color(0xFFC4785A), "old hindi songs playlist", R.drawable.vibe_deluxe_saloon),
    VibeWorld("ganpati-bappa", "Ganpati Bappa", "Celebration, nostalgia, togetherness.", Color(0xFFE2A23A), "ganpati bappa morya songs playlist", R.drawable.vibe_ganpati),
    VibeWorld("baarish", "Baarish", "Wet streets, slow songs.", Color(0xFF7AA0C4), "monsoon hindi songs playlist", R.drawable.vibe_baarish),
    VibeWorld("local-train", "Local", "Doors open, city moving.", Color(0xFFD4A24A), "mumbai local train songs playlist", R.drawable.vibe_local),
    VibeWorld("raat-baki", "Raat Abhi Baaki", "One more song after midnight.", Color(0xFFC48A6A), "late night hindi songs playlist", R.drawable.vibe_raat),
    VibeWorld("qawwali", "Qawwali", "Lamps, voice, together.", Color(0xFFC9A24A), "qawwali songs playlist", R.drawable.vibe_qawwali),
    VibeWorld("sunday-ghar", "Ghar", "Fan, lunch, old radio.", Color(0xFFC47A4A), "family hindi songs playlist", R.drawable.vibe_ghar)
)
