package com.lookfirst.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoAddressesTest {
    private fun keccak(text: String) =
        Keccak256.hash(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun keccakMatchesKnownDigestsNotSha3() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", keccak(""))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", keccak("abc"))
        // SHA3-256 of "" is a7ffc6f8...; the padding differs, so a JDK shortcut would fail here.
        assertFalse(keccak("").startsWith("a7ffc6f8"))
    }

    @Test
    fun keccakHandlesBlockBoundaries() {
        // 135, 136 and 137 bytes cross the 136-byte rate: padding in one byte, a full extra block, and a spill.
        val digests = listOf(135, 136, 137, 272).map { keccak("a".repeat(it)) }
        assertEquals(digests.size, digests.toSet().size)
        digests.forEach { assertEquals(64, it.length) }
    }

    @Test
    fun eip55OfficialVectors() {
        listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        ).forEach { assertEquals(it, true, CryptoAddresses.verify("ethereum", it)) }
    }

    @Test
    fun eip55DetectsAChangedLetterCase() {
        assertEquals(false, CryptoAddresses.verify("ethereum", "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        assertEquals(false, CryptoAddresses.verify("ethereum", "0xd1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"))
    }

    @Test
    fun ethereumWithoutChecksumOrOutsideTheFormat() {
        assertNull(CryptoAddresses.verify("ethereum", "0xd1220a0cf47c7b9be7a2e6ba89f429762e7b9adb"))
        assertNull(CryptoAddresses.verify("ethereum", "0xD1220A0CF47C7B9BE7A2E6BA89F429762E7B9ADB"))
        assertNull(CryptoAddresses.verify("ethereum", "vitalik.eth"))
        assertEquals(false, CryptoAddresses.verify("ethereum", "0xzz20a0cf47c7b9be7a2e6ba89f429762e7b9adb"))
        assertEquals(false, CryptoAddresses.verify("ethereum", "0xd1220a0cf47c7b9be7a2e6ba89f4297"))
    }

    @Test
    fun base58CheckVectors() {
        listOf(
            "bitcoin" to "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "bitcoin" to "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            "bitcoin" to "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
            "tron" to "TJRyWwFs9wTFGZg3JbrVriFbNfCug5tDeC",
            "dogecoin" to "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L",
            "dash" to "XdAUmwtig27HBG6WfYyHAzP8n6XC9jESEw",
        ).forEach { (coin, address) -> assertEquals(address, true, CryptoAddresses.verify(coin, address)) }
        assertEquals(false, CryptoAddresses.verify("bitcoin", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"))
        assertEquals(false, CryptoAddresses.verify("bitcoin", "1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf0a"))
        assertEquals(false, CryptoAddresses.verify("bitcoin", "1A1zP1eP5"))
    }

    @Test
    fun bech32AndBech32mVectors() {
        assertEquals(true, CryptoAddresses.verify("bitcoin", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertEquals(true, CryptoAddresses.verify("bitcoin", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"))
        assertEquals(true, CryptoAddresses.verify("bitcoin", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        val taproot = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        assertEquals(true, CryptoAddresses.verify("bitcoin", taproot))
        assertEquals(false, CryptoAddresses.verify("bitcoin", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))
        assertEquals(false, CryptoAddresses.verify("bitcoin", "bc1qw508d6qejxtdg4y5R3zarvary0c5xw7kv8f3t4"))
        assertEquals(false, CryptoAddresses.verify("bitcoin", "bc1q"))
    }

    @Test
    fun unknownFamiliesAreNotChecked() {
        assertNull(CryptoAddresses.verify("monero", "anything"))
        assertNull(CryptoAddresses.verify("bitcoin", "zzz"))
        assertTrue(CryptoAddresses.verify("bitcoin", "1".repeat(500)) == false)
    }
}
