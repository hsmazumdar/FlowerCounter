package com.flowercounter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Leaf = Color(0xFF2E7D32)
private val LeafLight = Color(0xFF81C784)

@Composable
fun FlowerCounterTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = LeafLight, secondary = LeafLight)
    } else {
        lightColorScheme(primary = Leaf, secondary = Leaf)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
