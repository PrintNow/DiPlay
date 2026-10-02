package com.shilapi.xcertplay.compat

/**
 * RFC 4648 Base64 with the semantics of `java.util.Base64`'s basic and MIME codecs.
 *
 * `java.util.Base64` needs API 26 and `android.util.Base64` is a stub in JVM unit tests, so the
 * protocol code uses this instead.
 */
object Base64Compat {
    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /** Like `Base64.getEncoder().encodeToString(bytes)`. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 3 <= bytes.size) {
            val chunk = (bytes[index].toInt() and 0xff shl 16) or
                (bytes[index + 1].toInt() and 0xff shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            out.append(ALPHABET[chunk ushr 18 and 0x3f]).append(ALPHABET[chunk ushr 12 and 0x3f])
                .append(ALPHABET[chunk ushr 6 and 0x3f]).append(ALPHABET[chunk and 0x3f])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val chunk = bytes[index].toInt() and 0xff shl 16
                out.append(ALPHABET[chunk ushr 18 and 0x3f]).append(ALPHABET[chunk ushr 12 and 0x3f])
                    .append("==")
            }
            2 -> {
                val chunk = (bytes[index].toInt() and 0xff shl 16) or (bytes[index + 1].toInt() and 0xff shl 8)
                out.append(ALPHABET[chunk ushr 18 and 0x3f]).append(ALPHABET[chunk ushr 12 and 0x3f])
                    .append(ALPHABET[chunk ushr 6 and 0x3f]).append('=')
            }
        }
        return out.toString()
    }

    /** Like `Base64.getMimeEncoder(lineLength, separator).encodeToString(bytes)`. */
    fun encodeMime(bytes: ByteArray, lineLength: Int = 76, separator: String = "\r\n"): String {
        val lineChars = lineLength / 4 * 4
        val encoded = encode(bytes)
        if (lineChars <= 0 || encoded.length <= lineChars) return encoded
        return encoded.chunked(lineChars).joinToString(separator)
    }

    /**
     * Like `Base64.getDecoder().decode(text)`: rejects characters outside the alphabet and
     * malformed padding with [IllegalArgumentException]; trailing padding is optional.
     */
    fun decode(text: String): ByteArray = decode(text, mime = false)

    /** Like `Base64.getMimeDecoder().decode(text)`: characters outside the alphabet are skipped. */
    fun decodeMime(text: String): ByteArray = decode(text.length, { text[it] }, mime = true)

    /** [decodeMime] over ASCII bytes, so key material never becomes an immutable String. */
    fun decodeMime(ascii: ByteArray): ByteArray =
        decode(ascii.size, { (ascii[it].toInt() and 0xff).toChar() }, mime = true)

    private fun decode(text: String, mime: Boolean): ByteArray = decode(text.length, { text[it] }, mime)

    private fun decode(length: Int, charAt: (Int) -> Char, mime: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(length / 4 * 3)
        var bits = 0
        var count = 0
        var index = 0
        while (index < length) {
            val char = charAt(index++)
            if (char == '=') {
                // One '=' after three sextets, or "==" after two; nothing but skipped chars after.
                if (count < 2 || count == 2 && nextSignificant(length, charAt, index, mime) != '=') {
                    throw IllegalArgumentException("Input byte array has wrong 4-byte ending unit")
                }
                if (count == 2) {
                    while (charAt(index) != '=') index++
                    index++
                }
                if (nextSignificant(length, charAt, index, mime) != null) {
                    throw IllegalArgumentException("Input byte array has incorrect ending byte at $index")
                }
                break
            }
            val value = if (char.code < 128) VALUES[char.code] else -1
            if (value < 0) {
                if (mime) continue
                throw IllegalArgumentException("Illegal base64 character ${Integer.toHexString(char.code)}")
            }
            bits = bits shl 6 or value
            count++
            if (count == 4) {
                out.write(bits ushr 16 and 0xff)
                out.write(bits ushr 8 and 0xff)
                out.write(bits and 0xff)
                bits = 0
                count = 0
            }
        }
        when (count) {
            1 -> throw IllegalArgumentException("Last unit does not have enough valid bits")
            2 -> out.write(bits ushr 4 and 0xff)
            3 -> {
                out.write(bits ushr 10 and 0xff)
                out.write(bits ushr 2 and 0xff)
            }
        }
        return out.toByteArray()
    }

    /** The next character at or after [from] that the decoder would not skip, or null at the end. */
    private fun nextSignificant(length: Int, charAt: (Int) -> Char, from: Int, mime: Boolean): Char? {
        var index = from
        while (index < length) {
            val char = charAt(index++)
            if (!mime || char == '=' || char.code < 128 && VALUES[char.code] >= 0) return char
        }
        return null
    }
}
