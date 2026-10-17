package com.lookfirst.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lookfirst.domain.verdict.VerdictLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictPaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(a.luminance(), b.luminance()).toDouble()
        val low = minOf(a.luminance(), b.luminance()).toDouble()
        return (high + 0.05) / (low + 0.05)
    }

    @Test
    fun textOnEveryBannerReachesAaInLightAndDarkThemes() {
        for ((name, palette) in listOf("light" to LightVerdictPalette, "dark" to DarkVerdictPalette)) {
            for (level in VerdictLevel.entries) {
                val colors = palette.of(level)
                val ratio = contrast(colors.container, colors.onContainer)
                assertTrue("$name $level contrast $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun noLevelIsGreen() {
        for (palette in listOf(LightVerdictPalette, DarkVerdictPalette)) {
            for (level in VerdictLevel.entries) {
                val c = palette.of(level).container
                assertTrue("$level looks green", !(c.green > c.red && c.green > c.blue))
            }
        }
    }

    @Test
    fun theFourContainersDiffer() {
        for (palette in listOf(LightVerdictPalette, DarkVerdictPalette)) {
            val containers = VerdictLevel.entries.map { palette.of(it).container }
            assertTrue(containers.toSet().size == 4)
        }
    }
}
