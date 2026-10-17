package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The list indexes live in `filesDir`; the backup configuration must keep that domain out of every backup and transfer. */
class BackupRulesTest {
    @Test
    fun filesDomainIsExcludedFromBackupAndDeviceTransfer() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue("$section must exclude domain=\"file\"", body.contains("""<exclude domain="file" />"""))
        }
    }

    @Test
    fun applicationBackupIsOff() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertEquals(1, Regex("""android:fullBackupContent="false"""").findAll(manifest).count())
    }
}
