package com.lookfirst.domain.analysis

/**
 * Original Keccak-256 (padding 0x01), the hash behind Ethereum and EIP-55. Not `SHA3-256` of the JDK: that one
 * pads with 0x06 and gives other digests. Checked against the EIP-55 test vectors.
 */
@Suppress("MagicNumber")
internal object Keccak256 {
    private const val LANES = 25
    private const val ROUNDS = 24
    private const val RATE_BYTES = 136
    private const val ROW = 5

    private val ROUND_CONSTANTS: LongArray =
        listOf(
            0x0000000000000001uL,
            0x0000000000008082uL,
            0x800000000000808AuL,
            0x8000000080008000uL,
            0x000000000000808BuL,
            0x0000000080000001uL,
            0x8000000080008081uL,
            0x8000000000008009uL,
            0x000000000000008AuL,
            0x0000000000000088uL,
            0x0000000080008009uL,
            0x000000008000000AuL,
            0x000000008000808BuL,
            0x800000000000008BuL,
            0x8000000000008089uL,
            0x8000000000008003uL,
            0x8000000000008002uL,
            0x8000000000000080uL,
            0x000000000000800AuL,
            0x800000008000000AuL,
            0x8000000080008081uL,
            0x8000000000008080uL,
            0x0000000080000001uL,
            0x8000000080008008uL,
        ).map { it.toLong() }.toLongArray()

    private val ROTATIONS =
        intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)

    private val LANE_ORDER =
        intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)

    fun hash(data: ByteArray): ByteArray {
        val padded = pad(data)
        val state = LongArray(LANES)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until RATE_BYTES / 8) state[i] = state[i] xor readLane(padded, offset + 8 * i)
            permute(state)
            offset += RATE_BYTES
        }
        return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
    }

    private fun pad(data: ByteArray): ByteArray {
        val size = (data.size / RATE_BYTES + 1) * RATE_BYTES
        val out = data.copyOf(size)
        out[data.size] = (out[data.size].toInt() or 0x01).toByte()
        out[size - 1] = (out[size - 1].toInt() or 0x80).toByte()
        return out
    }

    private fun readLane(bytes: ByteArray, at: Int): Long {
        var lane = 0L
        for (i in 7 downTo 0) lane = (lane shl 8) or (bytes[at + i].toLong() and 0xFF)
        return lane
    }

    private fun permute(s: LongArray) {
        for (round in 0 until ROUNDS) {
            val c = LongArray(ROW) { s[it] xor s[it + 5] xor s[it + 10] xor s[it + 15] xor s[it + 20] }
            for (x in 0 until ROW) {
                val d = c[(x + 4) % ROW] xor java.lang.Long.rotateLeft(c[(x + 1) % ROW], 1)
                for (y in 0 until LANES step ROW) s[y + x] = s[y + x] xor d
            }
            var carried = s[1]
            for (i in 0 until ROUNDS) {
                val j = LANE_ORDER[i]
                val next = s[j]
                s[j] = java.lang.Long.rotateLeft(carried, ROTATIONS[i])
                carried = next
            }
            for (y in 0 until LANES step ROW) {
                val row = s.copyOfRange(y, y + ROW)
                for (x in 0 until ROW) s[y + x] = row[x] xor (row[(x + 1) % ROW].inv() and row[(x + 2) % ROW])
            }
            s[0] = s[0] xor ROUND_CONSTANTS[round]
        }
    }
}
