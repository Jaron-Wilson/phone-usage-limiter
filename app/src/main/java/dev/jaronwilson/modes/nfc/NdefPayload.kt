package dev.jaronwilson.modes.nfc

/**
 * Building the bytes another phone reads when it taps yours.
 *
 * Android has no "send this link on tap" any more: Beam was removed in
 * Android 14. What still works is pretending to be a card. A reader that
 * touches this phone sees an NFC Forum Type 4 Tag holding one NDEF URI record,
 * which is precisely what an NFC sticker with your portfolio on it looks like,
 * so every phone and every reader already knows what to do with it.
 *
 * Kept pure so the byte layout can be tested without a radio, which matters:
 * the failure mode of getting it wrong is a tap that silently does nothing.
 */
object NdefPayload {

    /** Prefixes the URI record spec lets you abbreviate to a single byte. */
    private val PREFIXES = listOf(
        0x01.toByte() to "http://www.",
        0x02.toByte() to "https://www.",
        0x03.toByte() to "http://",
        0x04.toByte() to "https://",
        0x05.toByte() to "tel:",
        0x06.toByte() to "mailto:"
    )

    /** The URI record: header, type, then the abbreviated URI. */
    fun uriRecord(uri: String): ByteArray {
        val match = PREFIXES.firstOrNull { uri.startsWith(it.second) }
        val code = match?.first ?: 0x00
        val rest = match?.let { uri.removePrefix(it.second) } ?: uri
        val body = byteArrayOf(code) + rest.toByteArray(Charsets.UTF_8)

        // MB + ME + SR, TNF = 0x01 (well known). Short record: one length byte.
        return byteArrayOf(
            0xD1.toByte(),
            0x01,
            body.size.toByte(),
            'U'.code.toByte()
        ) + body
    }

    /** The NDEF file: a two-byte length, then the message. */
    fun ndefFile(uri: String): ByteArray {
        val message = uriRecord(uri)
        return byteArrayOf(
            ((message.size shr 8) and 0xFF).toByte(),
            (message.size and 0xFF).toByte()
        ) + message
    }

    /**
     * The capability container, telling a reader where the NDEF file is and
     * that it is read-only. Fifteen bytes, fixed but for nothing here.
     */
    val capabilityContainer: ByteArray = byteArrayOf(
        0x00, 0x0F,                   // CC length
        0x20,                         // mapping version 2.0
        0x00, 0x3B,                   // max read
        0x00, 0x34,                   // max write
        0x04, 0x06,                   // NDEF file control TLV
        0xE1.toByte(), 0x04,          // file id
        0x00, 0xFF.toByte(),          // max NDEF size
        0x00,                         // read access: granted
        0xFF.toByte()                 // write access: denied
    )

    /** A slice of a file, as READ BINARY asks for it. */
    fun readBinary(file: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset < 0 || offset > file.size) return SW_WRONG_PARAMS
        val end = minOf(offset + length, file.size)
        return file.copyOfRange(offset, end) + SW_OK
    }

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    val SW_WRONG_PARAMS = byteArrayOf(0x6B.toByte(), 0x00)

    // ---- the four commands a Type 4 read involves ----
    val SELECT_NDEF_APP = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00
    )
    val FILE_CC = byteArrayOf(0xE1.toByte(), 0x03)
    val FILE_NDEF = byteArrayOf(0xE1.toByte(), 0x04)

    fun isSelectApp(apdu: ByteArray): Boolean =
        apdu.size >= SELECT_NDEF_APP.size &&
            apdu.copyOfRange(0, SELECT_NDEF_APP.size).contentEquals(SELECT_NDEF_APP)

    /** Which file a SELECT names, or null if the command is something else. */
    fun selectedFile(apdu: ByteArray): ByteArray? {
        if (apdu.size < 7) return null
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return null
        if (apdu[2] != 0x00.toByte()) return null
        val id = apdu.copyOfRange(5, 7)
        return when {
            id.contentEquals(FILE_CC) -> FILE_CC
            id.contentEquals(FILE_NDEF) -> FILE_NDEF
            else -> null
        }
    }

    /** Offset and length of a READ BINARY, or null. */
    fun readRequest(apdu: ByteArray): Pair<Int, Int>? {
        if (apdu.size < 5) return null
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xB0.toByte()) return null
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val length = apdu[4].toInt() and 0xFF
        return offset to length
    }
}
