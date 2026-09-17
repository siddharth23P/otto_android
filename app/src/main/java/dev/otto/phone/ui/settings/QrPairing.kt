package dev.otto.phone.ui.settings

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.otto.phone.transport.ServeProtocol

/**
 * The pairing line from `otto serve --qr`, read with Google's code scanner (#16). The scanner is a
 * Play services screen with its own camera access, so Otto asks for no camera permission; on a phone
 * without Play services the pairing line is pasted instead.
 */
object QrPairing {
    const val NOT_A_PAIRING = "that code is not an otto serve pairing line"
    const val UNAVAILABLE = "the QR scanner is not available on this phone — paste the pairing line instead"

    fun scan(context: Context, onLine: (String) -> Unit, onProblem: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        runCatching { GmsBarcodeScanning.getClient(context, options) }
            .onFailure { onProblem(UNAVAILABLE) }
            .getOrNull()
            ?.startScan()
            ?.addOnSuccessListener { code ->
                val text = code.rawValue.orEmpty()
                if (ServeProtocol.parsePairing(text) != null) onLine(text) else onProblem(NOT_A_PAIRING)
            }
            ?.addOnFailureListener { onProblem(UNAVAILABLE) }
        // A cancelled scan is the person changing their mind: nothing to say.
    }
}
