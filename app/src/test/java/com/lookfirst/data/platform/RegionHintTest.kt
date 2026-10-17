package com.lookfirst.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionHintTest {
    @Test
    fun theSimCountryComesFirstInUpperCase() {
        assertEquals("FR", pickRegion("fr", "US"))
    }

    @Test
    fun theLocaleIsUsedWhenThereIsNoSim() {
        assertEquals("DE", pickRegion("", "DE"))
        assertEquals("DE", pickRegion(null, "de"))
    }

    @Test
    fun anythingThatIsNotATwoLetterCodeIsIgnored() {
        assertNull(pickRegion("", null, "  ", "FRA", "1", "F1", "ÉÉ"))
        assertEquals("BE", pickRegion("FRA", "BE"))
    }
}
