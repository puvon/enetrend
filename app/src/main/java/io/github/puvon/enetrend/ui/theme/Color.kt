package io.github.puvon.enetrend.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

internal data class BalanceColors(val positive: Color, val negative: Color)
internal val LightBalanceColors = BalanceColors(Color(0xFFB45335), Color(0xFF286AA6))
internal val DarkBalanceColors = BalanceColors(Color(0xFFFFB49A), Color(0xFF9BCBFF))
internal val LocalBalanceColors = staticCompositionLocalOf { LightBalanceColors }

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
