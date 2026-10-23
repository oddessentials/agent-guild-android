package com.oddessentials.agentguild.scan

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** QR codes through ML Kit's bundled barcode model, which needs no Google Play services. */
object QrReader {
    fun client(): BarcodeScanner =
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    /** The texts of the QR codes in [image]. */
    fun read(scanner: BarcodeScanner, image: InputImage): Task<List<String>> =
        scanner.process(image).continueWith { task -> task.result.mapNotNull { it.rawValue } }

    fun read(scanner: BarcodeScanner, bitmap: Bitmap) = read(scanner, InputImage.fromBitmap(bitmap, 0))
}
