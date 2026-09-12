package dev.jaronwilson.modes

import dev.jaronwilson.modes.launcher.EdgePanels
import dev.jaronwilson.modes.launcher.EdgeTarget
import dev.jaronwilson.modes.nfc.NdefPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes another phone reads on a tap, and what a screen edge does.
 *
 * The NDEF layout is worth pinning down because its failure mode is silent: a
 * byte wrong and the tap simply does nothing, with no error anywhere to read.
 */
class NfcAndEdgeTest {

    // ---- NDEF ----

    @Test
    fun `an https url is abbreviated to one prefix byte`() {
        val record = NdefPayload.uriRecord("https://jaronwilson.org")
        // D1: message begin, end, short record, well-known type.
        assertEquals(0xD1.toByte(), record[0])
        assertEquals(1.toByte(), record[1])    // type length
        assertEquals('U'.code.toByte(), record[3])
        assertEquals(0x04.toByte(), record[4]) // the "https://" prefix
        assertEquals("jaronwilson.org", String(record.copyOfRange(5, record.size)))
    }

    @Test
    fun `payload length counts the prefix byte`() {
        val record = NdefPayload.uriRecord("https://a.co")
        val declared = record[2].toInt()
        assertEquals(declared, record.size - 4)
    }

    @Test
    fun `an unabbreviated scheme falls back to a zero prefix`() {
        val record = NdefPayload.uriRecord("ftp://example.com")
        assertEquals(0x00.toByte(), record[4])
        assertEquals("ftp://example.com", String(record.copyOfRange(5, record.size)))
    }

    @Test
    fun `the ndef file is the message behind a two byte length`() {
        val file = NdefPayload.ndefFile("https://jaronwilson.org")
        val declared = ((file[0].toInt() and 0xFF) shl 8) or (file[1].toInt() and 0xFF)
        assertEquals(declared, file.size - 2)
    }

    @Test
    fun `the capability container is fifteen bytes and read only`() {
        val cc = NdefPayload.capabilityContainer
        assertEquals(15, cc.size)
        assertEquals(0x00.toByte(), cc[13])          // read access granted
        assertEquals(0xFF.toByte(), cc[14])          // write access denied
    }

    @Test
    fun `a read returns the slice asked for, then success`() {
        val file = NdefPayload.ndefFile("https://jaronwilson.org")
        val out = NdefPayload.readBinary(file, 0, 4)
        assertEquals(6, out.size)
        assertArrayEquals(file.copyOfRange(0, 4), out.copyOfRange(0, 4))
        assertArrayEquals(NdefPayload.SW_OK, out.copyOfRange(4, 6))
    }

    @Test
    fun `a read past the end returns what exists rather than failing`() {
        val file = NdefPayload.ndefFile("https://a.co")
        val out = NdefPayload.readBinary(file, 0, 255)
        assertEquals(file.size + 2, out.size)
    }

    @Test
    fun `a read starting past the end is refused`() {
        val file = NdefPayload.ndefFile("https://a.co")
        assertArrayEquals(NdefPayload.SW_WRONG_PARAMS, NdefPayload.readBinary(file, file.size + 1, 4))
    }

    @Test
    fun `the select for the ndef application is recognised`() {
        assertTrue(NdefPayload.isSelectApp(NdefPayload.SELECT_NDEF_APP))
        assertTrue(!NdefPayload.isSelectApp(byteArrayOf(0x00, 0xA4.toByte(), 0x04)))
    }

    @Test
    fun `selects name the capability and ndef files`() {
        val cc = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x03)
        val ndef = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)
        assertArrayEquals(NdefPayload.FILE_CC, NdefPayload.selectedFile(cc))
        assertArrayEquals(NdefPayload.FILE_NDEF, NdefPayload.selectedFile(ndef))
        assertNull(NdefPayload.selectedFile(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x0F)))
    }

    @Test
    fun `a read binary is parsed into offset and length`() {
        val (offset, length) = NdefPayload.readRequest(byteArrayOf(0x00, 0xB0.toByte(), 0x01, 0x02, 0x0F))!!
        assertEquals(0x0102, offset)
        assertEquals(15, length)
        assertNull(NdefPayload.readRequest(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07)))
    }

    // ---- screen edges ----

    @Test
    fun `a bare host becomes https, never http`() {
        assertEquals("https://finance.jaronwilson.dev", EdgePanels.normalise("finance.jaronwilson.dev"))
        assertEquals("https://example.com", EdgePanels.normalise("http://example.com"))
        assertEquals("https://example.com", EdgePanels.normalise("  https://example.com  "))
    }

    @Test
    fun `a title is taken from the host when none is given`() {
        assertEquals("jaronwilson.dev", EdgePanels.hostOf("https://www.jaronwilson.dev/x/y"))
        assertEquals("finance.jaronwilson.dev", EdgePanels.hostOf("finance.jaronwilson.dev"))
    }

    @Test
    fun `an edge target survives a round trip`() {
        val site = EdgeTarget.Site("https://jaronwilson.org", "jaronwilson.org")
        assertEquals(site, EdgePanels.decode(EdgePanels.encode(site)))
        val app = EdgeTarget.App("com.infonow.bofa")
        assertEquals(app, EdgePanels.decode(EdgePanels.encode(app)))
    }

    @Test
    fun `nothing configured decodes to nothing`() {
        assertNull(EdgePanels.decode(""))
        assertNull(EdgePanels.decode("rubbish"))
        assertNull(EdgePanels.encode(null).let { EdgePanels.decode(it) })
    }
}
