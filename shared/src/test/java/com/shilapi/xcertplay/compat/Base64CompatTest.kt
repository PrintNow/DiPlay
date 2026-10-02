package com.shilapi.xcertplay.compat

import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Base64CompatTest {
    @Test
    fun matchesJdkForEveryLengthRemainder() {
        val random = Random(4_4)
        for (size in 0..200) {
            val bytes = random.nextBytes(size)
            val expected = Base64.getEncoder().encodeToString(bytes)
            assertEquals(expected, Base64Compat.encode(bytes))
            assertArrayEquals(bytes, Base64Compat.decode(expected))
            assertArrayEquals(bytes, Base64Compat.decode(expected.trimEnd('=')))
            val mime = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes)
            assertEquals(mime, Base64Compat.encodeMime(bytes, 64, "\n"))
            assertEquals(Base64.getMimeEncoder().encodeToString(bytes), Base64Compat.encodeMime(bytes))
            assertArrayEquals(bytes, Base64Compat.decodeMime(mime))
        }
    }

    @Test
    fun mimeDecoderSkipsPemFormatting() {
        val bytes = Random(7).nextBytes(150)
        val pem = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(bytes)
        assertArrayEquals(bytes, Base64Compat.decodeMime(" \t$pem\r\n"))
        assertArrayEquals(Base64.getMimeDecoder().decode("QU*JD\nRA=="), Base64Compat.decodeMime("QU*JD\nRA=="))
    }

    @Test
    fun basicDecoderRejectsWhatTheJdkRejects() {
        for (input in listOf("QUJD\n", "QU JD", "Q", "QUJDR", "=QUJD", "QU=JD", "QUJD====", "QQ=", "QQ==x")) {
            val jdkRejects = runCatching { Base64.getDecoder().decode(input) }.isFailure
            val compatRejects = runCatching { Base64Compat.decode(input) }.isFailure
            assertEquals("input '$input'", jdkRejects, compatRejects)
        }
        try {
            Base64Compat.decode("not base64!")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
