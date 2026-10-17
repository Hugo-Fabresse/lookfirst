package com.lookfirst.data.scan.camera

import androidx.camera.core.ImageAnalysis
import com.lookfirst.domain.scan.BarcodeDecoder
import javax.inject.Inject

/** Creates the frame analyzer of one scan session. The UI depends on this interface, not on the implementation. */
fun interface QrAnalyzerFactory {
    fun create(onCodeRead: (String) -> Unit): ImageAnalysis.Analyzer
}

class DefaultQrAnalyzerFactory @Inject constructor(private val decoder: BarcodeDecoder) : QrAnalyzerFactory {
    override fun create(onCodeRead: (String) -> Unit): ImageAnalysis.Analyzer = QrImageAnalyzer(decoder, onCodeRead)
}
