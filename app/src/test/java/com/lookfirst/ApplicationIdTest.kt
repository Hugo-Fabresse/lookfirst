package com.lookfirst

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdTest {
    @Test
    fun applicationClassLivesInRootPackage() {
        assertEquals("com.lookfirst", LookFirstApp::class.java.packageName)
    }
}
