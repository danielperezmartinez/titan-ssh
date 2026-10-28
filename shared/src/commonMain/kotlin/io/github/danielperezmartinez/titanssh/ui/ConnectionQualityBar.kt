package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielperezmartinez.titanssh.terminal.ConnectionQuality
import io.github.danielperezmartinez.titanssh.terminal.QualityGrade
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.theme.TitanColors

/**
 * [tab]'s connection quality, probed only while this is composed and the app
 * is on screen ([[Indicar cuando la conexión deja de responder]]).
 */
@Composable
internal fun watchedQuality(tab: SessionTab): ConnectionQuality {
    val onScreen = appOnScreen()
    LaunchedEffect(tab, onScreen) {
        if (onScreen) tab.watchQuality()
    }
    val quality by tab.quality.collectAsState()
    return quality
}

/**
 * The status strip's marker for a live connection: three blocks like the
 * icon's cursor, lit from the bottom by [quality]
 * ([[Barra de calidad de la conexión en la franja de estado]]).
 */
@Composable
internal fun ConnectionQualityBar(quality: ConnectionQuality, modifier: Modifier = Modifier) {
    val (lit, color) = when (quality.grade) {
        QualityGrade.GOOD -> 3 to TitanColors.Success
        QualityGrade.SLOW -> 2 to TitanColors.Warning
        QualityGrade.NO_RESPONSE -> 1 to TitanColors.Danger
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
        for (block in 3 downTo 1) {
            Box(
                Modifier
                    .size(width = 6.dp, height = 4.dp)
                    .background(if (block <= lit) color else TitanColors.SurfaceElevated),
            )
        }
    }
}
