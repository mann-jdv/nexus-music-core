package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LosslessPcmAudioProcessorTest {
    private fun convert(bytes: ByteArray, source: Int, target: Int): ByteArray {
        val processor = LosslessPcmAudioProcessor().apply { targetEncoding = target }
        processor.configure(AudioFormat(96000, 2, source))
        processor.flush()
        processor.queueInput(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() })
        val output = processor.output
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    @Test fun usbIntegerPackingRetainsEverySourceBit() {
        for ((encoding, width) in listOf(C.ENCODING_PCM_16BIT to 2, C.ENCODING_PCM_24BIT to 3)) {
            val samples = ByteArray(20000 * width) { ((it * 73 + 11) % 256).toByte() }
            // Include full-scale negative, positive and zero frames as well as arbitrary samples.
            val extremes = if (width == 2) byteArrayOf(0, -128, -1, 127, 0, 0, 1, 0)
                else byteArrayOf(0, 0, -128, -1, -1, 127, 0, 0, 0, 1, 0, 0)
            extremes.copyInto(samples)
            val floats = convert(samples, encoding, C.ENCODING_PCM_FLOAT)
            assertArrayEquals(samples, convert(floats, C.ENCODING_PCM_FLOAT, encoding))
            val wide = convert(samples, encoding, C.ENCODING_PCM_32BIT)
            assertArrayEquals(samples, convert(wide, C.ENCODING_PCM_32BIT, encoding))
        }
    }
}
