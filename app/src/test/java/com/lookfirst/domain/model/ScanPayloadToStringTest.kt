package com.lookfirst.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal

/** No variant of [ScanPayload] may print scanned content in `toString()` (audit S-12). */
class ScanPayloadToStringTest {
    private val sentinel = "SENTINEL9Zq"

    private fun u() = Untrusted(sentinel)

    private val payloads: List<ScanPayload> = listOf(
        ScanPayload.Url(
            u(), sentinel, u(), u(), 4242, HostKind.DOMAIN, u(), u(), u(), false, setOf(UrlIssue.BACKSLASH),
        ),
        ScanPayload.Wifi(u(), u(), WifiSecurity.WPA, u(), false),
        ScanPayload.Sms(u(), u(), u()),
        ScanPayload.Tel(u(), u()),
        ScanPayload.Mail(u(), listOf(u()), listOf(u()), listOf(u()), u(), u()),
        ScanPayload.Geo(u(), 48.123456, 2.654321, u()),
        ScanPayload.Otpauth(u(), sentinel, u(), u(), u(), u(), listOf(QueryParam(sentinel, u()))),
        ScanPayload.Epc(
            u(), sentinel, u(), u(), u(), BigDecimal("123.45"), sentinel, u(), setOf(EpcIssue.BAD_BIC),
        ),
        ScanPayload.Crypto(
            u(), sentinel, u(), u(), u(), u(), u(), u(), u(), u(), u(), listOf(QueryParam(sentinel, u())),
        ),
        ScanPayload.AppLink(u(), sentinel),
        ScanPayload.DangerousScheme(u(), sentinel),
        ScanPayload.Text(u()),
        ScanPayload.Unknown(u(), sentinel),
    )

    @Test
    fun everySubtypeIsCovered() {
        val declared = ScanPayload::class.java.classes.filter { ScanPayload::class.java.isAssignableFrom(it) }
        assertEquals(declared.map { it.simpleName }.toSet(), payloads.map { it::class.java.simpleName }.toSet())
    }

    @Test
    fun noSubtypePrintsScannedContent() {
        for (payload in payloads) {
            val text = payload.toString()
            assertFalse("$text leaks the sentinel", text.contains(sentinel))
            for (digits in listOf("4242", "48.123", "2.654", "123.45")) {
                assertFalse("$text leaks $digits", text.contains(digits))
            }
        }
    }

    /** No `else`: a new [ScanPayload] subtype breaks the compilation here, until it is added to [payloads]. */
    @Suppress("unused")
    private fun mustListEverySubtype(payload: ScanPayload): Unit = when (payload) {
        is ScanPayload.Url,
        is ScanPayload.Wifi,
        is ScanPayload.Sms,
        is ScanPayload.Tel,
        is ScanPayload.Mail,
        is ScanPayload.Geo,
        is ScanPayload.Otpauth,
        is ScanPayload.Epc,
        is ScanPayload.Crypto,
        is ScanPayload.AppLink,
        is ScanPayload.DangerousScheme,
        is ScanPayload.Text,
        is ScanPayload.Unknown,
        -> Unit
    }
}
