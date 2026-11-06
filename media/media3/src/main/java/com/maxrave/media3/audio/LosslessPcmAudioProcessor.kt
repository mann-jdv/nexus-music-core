package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Repackages PCM for an exact USB mixer format, without gain, dither or sample-rate conversion.
 * The selector must only choose formats that retain all source precision (float retains 24 bits).
 */
class LosslessPcmAudioProcessor : BaseAudioProcessor() {
    var targetEncoding: Int = C.ENCODING_INVALID

    override fun onConfigure(input: AudioFormat): AudioFormat =
        if (targetEncoding == C.ENCODING_INVALID || targetEncoding == input.encoding) AudioFormat.NOT_SET
        else AudioFormat(input.sampleRate, input.channelCount, targetEncoding)

    override fun queueInput(input: ByteBuffer) {
        val inputWidth = width(inputAudioFormat.encoding)
        val output = replaceOutputBuffer(input.remaining() / inputWidth * width(targetEncoding))
        while (input.remaining() >= inputWidth) {
            val sample = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> input.short.toInt() shl 16
                C.ENCODING_PCM_24BIT -> ((input.get().toInt() and 255) shl 8) or
                    ((input.get().toInt() and 255) shl 16) or (input.get().toInt() shl 24)
                C.ENCODING_PCM_32BIT -> input.int
                C.ENCODING_PCM_FLOAT -> (input.float.toDouble() * 2147483648.0).roundToLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                else -> error("Unsupported lossless PCM input")
            }
            when (targetEncoding) {
                C.ENCODING_PCM_16BIT -> output.putShort((sample shr 16).toShort())
                C.ENCODING_PCM_24BIT -> {
                    output.put((sample shr 8).toByte())
                    output.put((sample shr 16).toByte())
                    output.put((sample shr 24).toByte())
                }
                C.ENCODING_PCM_32BIT -> output.putInt(sample)
                C.ENCODING_PCM_FLOAT -> output.putFloat(sample / 2147483648f)
            }
        }
        output.flip()
    }

    private fun width(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> error("Unsupported lossless PCM encoding")
    }
}
