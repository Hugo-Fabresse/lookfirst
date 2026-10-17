package com.lookfirst.data.scan.camera

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.lookfirst.domain.scan.BarcodeDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class QrImageAnalyzerTest {
    private var closed = 0

    private inline fun <reified T> proxyOf(crossinline handler: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            handler(method.name)
        } as T

    /** A 4x2 frame with a padded row stride, as ImageProxy fakes (no mocking library needed). */
    private fun frame(): ImageProxy {
        val plane = proxyOf<ImageProxy.PlaneProxy> { name ->
            when (name) {
                "getRowStride" -> 6
                "getPixelStride" -> 1
                "getBuffer" -> ByteBuffer.wrap(ByteArray(12))
                else -> null
            }
        }
        val info = proxyOf<ImageInfo> { name -> if (name == "getRotationDegrees") 90 else null }
        return proxyOf<ImageProxy> { name ->
            when (name) {
                "close" -> {
                    closed++
                    null
                }
                "getWidth" -> 4
                "getHeight" -> 2
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> info
                else -> null
            }
        }
    }

    @Test
    fun reportsTheFirstCodeOnceAndClosesEveryFrame() {
        val reads = mutableListOf<String>()
        val analyzer = QrImageAnalyzer({ "payload" }, reads::add)
        repeat(5) { analyzer.analyze(frame()) }
        assertEquals(listOf("payload"), reads)
        assertEquals(5, closed)
    }

    @Test
    fun aCrashingDecoderIsNotARead() {
        val reads = mutableListOf<String>()
        val analyzer = QrImageAnalyzer(BarcodeDecoder { error("hostile") }, reads::add)
        repeat(3) { analyzer.analyze(frame()) }
        assertEquals(emptyList<String>(), reads)
        assertEquals(3, closed)
    }
}
