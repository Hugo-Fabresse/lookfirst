package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StrictJsonTest {
    private fun parse(text: String) = StrictJson.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesPlainObjects() {
        val value = parse("""{"a":1,"b":[true,null,"x\n\u0041"],"c":{"d":-5}}""") as Json.Obj
        assertEquals(Json.Num(1), value.fields["a"])
        assertEquals(Json.Arr(listOf(Json.Bool(true), Json.Null, Json.Str("x\nA"))), value.fields["b"])
    }

    @Test
    fun refusesDuplicateKeys() {
        assertNull(parse("""{"a":1,"a":2}"""))
        assertNull(parse("""{"x":{"a":1,"a":1}}"""))
    }

    @Test
    fun refusesWhatIsNotStrictJson() {
        val bad = listOf(
            "", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "{\"a\":01}", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":+1}",
            "{\"a\":NaN}", "{\"a\":1} x", "{\"a\":1}{}", "{\"a\":\"\u0001\"}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}",
            "{\"a\":1234567890123456789}", "{a:1}", "{\"a\" 1}", "[1 2]", "\"unterminated",
        )
        bad.forEach { assertNull("accepted: $it", parse(it)) }
    }

    @Test
    fun refusesInvalidUtf8() {
        assertNull(
            StrictJson.parse(
                byteArrayOf('['.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ']'.code.toByte()),
            ),
        )
    }

    @Test
    fun boundsDepthAndNodeCount() {
        assertNotNull(parse("[".repeat(5) + "]".repeat(5)))
        assertNull(parse("[".repeat(50) + "]".repeat(50)))
        assertNull(parse("[" + "1,".repeat(5_000) + "1]"))
    }

    @Test
    fun hostileInputsAreFastAndNeverThrow() {
        val start = System.nanoTime()
        val inputs = listOf(
            "[".repeat(100_000),
            "{\"a\":".repeat(100_000),
            "\"" + "a".repeat(100_000),
            "9".repeat(100_000),
            "[" + "\"\\u00".repeat(30_000),
        )
        inputs.forEach { assertNull(parse(it)) }
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(42)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(200)).also(random::nextBytes)
            StrictJson.parse(bytes)
        }
        val alphabet = "{}[]\",:0123456789-truefalsn\\u ".toCharArray()
        repeat(2_000) {
            val text = String(CharArray(random.nextInt(120)) { alphabet[random.nextInt(alphabet.size)] })
            StrictJson.parse(text.toByteArray())
        }
    }
}
