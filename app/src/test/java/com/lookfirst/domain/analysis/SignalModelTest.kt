package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SignalModelTest {
    @Test
    fun toStringNeverShowsEvidence() {
        val signal = Signal(SignalCode.USERINFO_AT, evidence = Untrusted("secret-host.example"))
        assertFalse(signal.toString().contains("secret-host"))
    }

    @Test
    fun severityDefaultsToTheCodeSeverityAndMatchesTheUxTable() {
        assertEquals(Severity.DANGEROUS, Signal(SignalCode.BIDI_IN_HOST).severity)
        assertEquals(Severity.SUSPICIOUS, Signal(SignalCode.OTPAUTH_ENROL).severity)
        assertEquals(Severity.CAUTION, Signal(SignalCode.SHORTENER).severity)
        assertEquals(Severity.INFO, Signal(SignalCode.REDIRECT_FINAL).severity)
    }
}
