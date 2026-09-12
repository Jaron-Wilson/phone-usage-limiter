package dev.jaronwilson.modes.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Makes the phone read like an NFC tag holding one link.
 *
 * Tap it to another phone, or to any reader, and it sees a Type 4 Tag with
 * your portfolio or your LinkedIn on it. Nothing has to be installed at the
 * other end, because this is the same thing a printed NFC sticker is.
 *
 * Read only, by the capability container, so nobody can write to your phone by
 * touching it.
 */
class TapCardService : HostApduService() {

    private var ndefFile: ByteArray = ByteArray(0)
    private var selected: ByteArray? = null

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null) return NdefPayload.SW_NOT_FOUND

        if (NdefPayload.isSelectApp(apdu)) {
            // Read the link at the moment of the tap, so changing it in the app
            // takes effect on the very next tap with no service restart.
            ndefFile = NdefPayload.ndefFile(currentUrl())
            selected = null
            return NdefPayload.SW_OK
        }

        NdefPayload.selectedFile(apdu)?.let { file ->
            selected = file
            return NdefPayload.SW_OK
        }

        NdefPayload.readRequest(apdu)?.let { (offset, length) ->
            val file = when {
                selected.contentEquals(NdefPayload.FILE_CC) -> NdefPayload.capabilityContainer
                selected.contentEquals(NdefPayload.FILE_NDEF) -> ndefFile
                else -> return NdefPayload.SW_NOT_FOUND
            }
            return NdefPayload.readBinary(file, offset, length)
        }

        return NdefPayload.SW_NOT_FOUND
    }

    override fun onDeactivated(reason: Int) {
        selected = null
    }

    private fun currentUrl(): String = runCatching {
        AppGraph.ensure(applicationContext)
        runBlocking { AppGraph.repo.settings.tapCardUrl.first() }
    }.onFailure { Log.w("TapCardService", "could not read the tap link", it) }
        .getOrDefault("")
        .ifBlank { "https://jaronwilson.org" }
}
