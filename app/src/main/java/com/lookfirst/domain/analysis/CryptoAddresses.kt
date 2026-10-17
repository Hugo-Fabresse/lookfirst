package com.lookfirst.domain.analysis

import java.security.MessageDigest
import java.util.Locale

private const val MAX_ADDRESS_CHARS = 100
private const val BASE58_MIN = 25
private const val BASE58_MAX = 35
private const val BASE58_PAYLOAD_BYTES = 25
private const val BASE58_BODY_BYTES = 21
private const val BASE58_RADIX = 58
private const val BYTE_MASK = 0xFF
private const val BECH32_MAX = 90
private const val BECH32_CHECKSUM_CHARS = 6
private const val BECH32_CONST = 1
private const val BECH32M_CONST = 0x2bc830a3
private const val BECH32_SHIFT = 25
private const val BECH32_MASK = 0x1ffffff
private const val ETH_HEX_CHARS = 40
private const val NIBBLE_UPPER_FROM = 8
private const val HEX_RADIX = 16
private const val ETH_PREFIX_LENGTH = 2

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val BECH32_ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

/**
 * Address checksums in pure Kotlin: Base58Check, Bech32 / Bech32m and EIP-55. [verify] answers true or false, or
 * null when no check applies (unknown coin or address family, all-lowercase or all-uppercase Ethereum address,
 * ENS name). Input is bounded and every loop is linear.
 */
@Suppress("MagicNumber")
internal object CryptoAddresses {
    private val BASE58_PREFIXES: Map<String, String> =
        mapOf(
            "bitcoin" to "123mn",
            "litecoin" to "LM3mn2",
            "dogecoin" to "D9A",
            "dash" to "X7",
            "tron" to "T",
        )

    private val BECH32_PREFIXES: Map<String, List<String>> =
        mapOf("bitcoin" to listOf("bc1", "tb1"), "litecoin" to listOf("ltc1", "tltc1"))

    fun verify(coin: String, address: String): Boolean? {
        if (address.length > MAX_ADDRESS_CHARS) return false
        return when {
            coin == "ethereum" -> verifyEthereum(address)
            BECH32_PREFIXES[coin].orEmpty().any { address.lowercase(Locale.ROOT).startsWith(it) } ->
                verifyBech32(address)
            address.isNotEmpty() && address[0] in BASE58_PREFIXES[coin].orEmpty() -> verifyBase58Check(address)
            else -> null
        }
    }

    /** `0x` + 40 hex digits. */
    fun isEthereumAddress(address: String): Boolean = address.length == ETH_PREFIX_LENGTH + ETH_HEX_CHARS &&
        address.startsWith("0x") &&
        address.drop(ETH_PREFIX_LENGTH).all { Character.digit(it, HEX_RADIX) >= 0 && it.code < '\u0080'.code }

    private fun verifyEthereum(address: String): Boolean? {
        if (!address.startsWith("0x")) return null
        if (!isEthereumAddress(address)) return false
        val body = address.substring(ETH_PREFIX_LENGTH)
        val hasLower = body.any { it in 'a'..'f' }
        val hasUpper = body.any { it in 'A'..'F' }
        if (!(hasLower && hasUpper)) return null
        val hash = hex(Keccak256.hash(body.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)))
        return body.indices.all { i ->
            val c = body[i]
            c.isDigit() || (Character.digit(hash[i], HEX_RADIX) >= NIBBLE_UPPER_FROM) == c.isUpperCase()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private fun verifyBase58Check(address: String): Boolean {
        if (address.length !in BASE58_MIN..BASE58_MAX) return false
        val bytes = IntArray(BASE58_PAYLOAD_BYTES)
        for (c in address) {
            var carry = BASE58_ALPHABET.indexOf(c)
            if (carry < 0) return false
            for (i in bytes.indices.reversed()) {
                carry += bytes[i] * BASE58_RADIX
                bytes[i] = carry and BYTE_MASK
                carry = carry shr Byte.SIZE_BITS
            }
            if (carry != 0) return false
        }
        val raw = ByteArray(BASE58_PAYLOAD_BYTES) { bytes[it].toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val check = digest.digest(digest.digest(raw.copyOfRange(0, BASE58_BODY_BYTES)))
        return (0 until BASE58_PAYLOAD_BYTES - BASE58_BODY_BYTES).all { check[it] == raw[BASE58_BODY_BYTES + it] }
    }

    private fun verifyBech32(address: String): Boolean {
        val lower = address.lowercase(Locale.ROOT)
        val mixedCase = address != lower && address != address.uppercase(Locale.ROOT)
        val separator = lower.lastIndexOf('1')
        val hrp = if (separator < 1) "" else lower.substring(0, separator)
        val data = lower.substring(separator + 1).map { BECH32_ALPHABET.indexOf(it) }
        val checks =
            listOf(
                address.length <= BECH32_MAX,
                !mixedCase,
                hrp.isNotEmpty(),
                data.size >= BECH32_CHECKSUM_CHARS,
                data.none { it < 0 },
                hrp.all { it.code in '!'.code..'~'.code },
            )
        if (!checks.all { it }) return false
        val expected = if (data[0] == 0) BECH32_CONST else BECH32M_CONST
        return polymod(hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + data) == expected
    }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr BECH32_SHIFT
            chk = ((chk and BECH32_MASK) shl 5) xor v
            for (i in BECH32_GENERATORS.indices) {
                if ((top shr i) and 1 == 1) chk = chk xor BECH32_GENERATORS[i]
            }
        }
        return chk
    }
}
