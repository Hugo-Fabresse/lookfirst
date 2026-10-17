package com.lookfirst.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lookfirst.domain.verdict.VerdictLevel

/** A banner container and the text / icon colour on it (contrast checked by a test, AA in both themes). */
@Immutable
data class LevelColors(val container: Color, val onContainer: Color)

/**
 * One container per verdict level (ux.md 4.1): red, orange, amber, neutral blue-grey. Never green. They are fixed
 * (not taken from the dynamic palette) so that a level always looks the same; the icon silhouette and the label
 * carry the meaning, the colour only supports it.
 */
@Immutable
data class VerdictPalette(
    val dangerous: LevelColors,
    val suspicious: LevelColors,
    val caution: LevelColors,
    val noRisk: LevelColors,
) {
    fun of(level: VerdictLevel): LevelColors = when (level) {
        VerdictLevel.DANGEROUS -> dangerous
        VerdictLevel.SUSPICIOUS -> suspicious
        VerdictLevel.CAUTION -> caution
        VerdictLevel.NO_RISK_SIGNAL -> noRisk
    }
}

val LightVerdictPalette = VerdictPalette(
    dangerous = LevelColors(Color(0xFFFFDAD6), Color(0xFF410002)),
    suspicious = LevelColors(Color(0xFFFFDCC2), Color(0xFF2E1500)),
    caution = LevelColors(Color(0xFFFFE9A8), Color(0xFF251A00)),
    noRisk = LevelColors(Color(0xFFDCE3EA), Color(0xFF141C22)),
)

val DarkVerdictPalette = VerdictPalette(
    dangerous = LevelColors(Color(0xFF93000A), Color(0xFFFFDAD6)),
    suspicious = LevelColors(Color(0xFF7A3D00), Color(0xFFFFDCC2)),
    caution = LevelColors(Color(0xFF5A4600), Color(0xFFFFE08A)),
    noRisk = LevelColors(Color(0xFF3A4650), Color(0xFFDCE3EA)),
)

val LocalVerdictPalette = staticCompositionLocalOf { LightVerdictPalette }
