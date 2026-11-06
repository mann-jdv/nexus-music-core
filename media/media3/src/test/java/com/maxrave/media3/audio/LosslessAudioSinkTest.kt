package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.LosslessAudioSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the complete lossless sink up to its output boundary, with a capturing audio device. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LosslessAudioSinkTest {
    @Test fun nativePcmIsByteExactIncludingTheFirst20Milliseconds() {
        for ((encoding, rate, width, floatFallback) in listOf(
            listOf(C.ENCODING_PCM_16BIT, 44100, 2, 0),
            listOf(C.ENCODING_PCM_24BIT, 96000, 3, 0),
            listOf(C.ENCODING_PCM_24BIT, 96000, 3, 1),
        )) {
            val captured = ByteArrayOutputStream()
            var outputRate = 0
            var outputEncoding = 0
            var outputsOpened = 0
            val output = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        captured.write(bytes)
                        true
                    }
                    "getAudioSessionId" -> 1
                    "getSampleRate" -> rate
                    "getBufferSizeInFrames" -> 4096L
                    "getPositionUs" -> 0L
                    "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                    "canReuseAudioOutput" -> true
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as AudioOutput
            val provider = object : AudioOutputProvider {
                override fun getFormatSupport(config: AudioOutputProvider.FormatConfig) = AudioOutputProvider.FormatSupport.Builder()
                    .setFormatSupportLevel(if (floatFallback == 1 && config.format.pcmEncoding == C.ENCODING_PCM_24BIT) AudioOutputProvider.FORMAT_UNSUPPORTED else AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
                override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
                    outputRate = config.format.sampleRate
                    outputEncoding = config.format.pcmEncoding
                    return AudioOutputProvider.OutputConfig.Builder().setEncoding(outputEncoding)
                        .setSampleRate(outputRate).setChannelMask(12).setBufferSize(32768).build()
                }
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput { outputsOpened++; return output }
                override fun addListener(listener: AudioOutputProvider.Listener) = Unit
                override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
                override fun release() = Unit
            }
            val sink = LosslessAudioSink.Builder(org.robolectric.RuntimeEnvironment.getApplication()).setAudioOutputProvider(provider).setEnableFloatOutput(true).build()
            val format = Format.Builder().setSampleMimeType("audio/raw").setChannelCount(2).setSampleRate(rate).setPcmEncoding(encoding).build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            sink.setPlaybackParameters(PlaybackParameters(1.5f, 0.75f))
            sink.setSkipSilenceEnabled(true)
            assertEquals(PlaybackParameters.DEFAULT, sink.playbackParameters)
            assertFalse(sink.skipSilenceEnabled)
            // Deliberately nonzero, arbitrary samples at t=0 expose even a one-frame gain ramp.
            val expected = ByteArray(rate / 10 * 2 * width) { ((it * 73 + 11) % 256).toByte() }
            val input = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.nativeOrder()).apply { put(expected); flip() }
            repeat(10) { if (input.hasRemaining()) sink.handleBuffer(input, 0L, 1) }
            assertFalse(input.hasRemaining())
            assertEquals(rate, outputRate)
            assertEquals(if (floatFallback == 1) C.ENCODING_PCM_FLOAT else encoding, outputEncoding)
            val expectedOutput = if (floatFallback == 1) {
                ByteBuffer.allocate(expected.size / 3 * 4).order(ByteOrder.nativeOrder()).apply {
                    for (i in expected.indices step 3) {
                        val value = ((expected[i].toInt() and 255) or ((expected[i + 1].toInt() and 255) shl 8) or (expected[i + 2].toInt() shl 16))
                        putFloat(value / 8388608f)
                    }
                }.array()
            } else expected
            assertArrayEquals(expectedOutput, captured.toByteArray())
            // Same-format album boundaries retain the output and concatenate samples without a gap.
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            input.rewind()
            repeat(10) { if (input.hasRemaining()) sink.handleBuffer(input, 100_000L, 1) }
            assertEquals(1, outputsOpened)
            assertArrayEquals(expectedOutput + expectedOutput, captured.toByteArray())
            sink.reset()
            sink.release()
        }
    }
}
