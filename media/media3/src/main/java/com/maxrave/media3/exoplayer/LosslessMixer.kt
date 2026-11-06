package com.maxrave.media3.exoplayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutputProvider

/** Requests only an exact format match. USB capabilities are not a measurement of the system mixer. */
internal class LosslessMixer(context: Context, attributes: AudioAttributes) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = attributes.audioAttributesV21.audioAttributes
    private var selected: AudioDeviceInfo? = null

    /** MediaCodec commonly decodes FLAC to float, while USB HALs advertise integer PCM only. */
    fun selectEncoding(format: Format, sourceBits: Int?, preferred: AudioDeviceInfo?): Int {
        if (Build.VERSION.SDK_INT < 34) return format.pcmEncoding
        return runCatching {
            val device = preferred ?: manager.getAudioDevicesForAttributes(attributes).singleOrNull()
                ?: return format.pcmEncoding
            val inputBits = pcmBitDepth(format.pcmEncoding) ?: return format.pcmEncoding
            // Unquantized floating-point sources must remain float. Integer lossless sources of
            // <=24 bits are represented exactly by the decoder's float output.
            val requiredBits = if (sourceBits != null && sourceBits in 1..inputBits) sourceBits else inputBits
            manager.getSupportedMixerAttributes(device).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == format.sampleRate && it.format.channelCount == format.channelCount &&
                    (pcmBitDepth(it.format.encoding) ?: 0) >= requiredBits &&
                    (format.pcmEncoding != C.ENCODING_PCM_FLOAT || sourceBits in 1..24 || it.format.encoding == C.ENCODING_PCM_FLOAT)
            }?.format?.encoding ?: format.pcmEncoding
        }.getOrDefault(format.pcmEncoding)
    }

    /** Called on the playback thread from the sink, before the AudioTrack exists — the order the platform requires. */
    @Synchronized
    fun configure(config: AudioOutputProvider.OutputConfig, preferred: AudioDeviceInfo?): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return runCatching {
            val device = preferred ?: manager.getAudioDevicesForAttributes(attributes).singleOrNull()
            if (device == null || device.type !in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)) {
                clear()
                return false
            }
            if (selected?.id != device.id) clear()
            val match = manager.getSupportedMixerAttributes(device).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == config.sampleRate &&
                    it.format.encoding == config.encoding &&
                    it.format.channelMask == config.channelMask
            } ?: run { clear(); return false }
            manager.setPreferredMixerAttributes(attributes, device, match).also { accepted ->
                if (accepted) selected = device else clear()
            }
        }.getOrDefault(false)
    }

    /** What happens after the AudioTrack, as far as the platform will say. */
    data class OutputStatus(val bitPerfect: Boolean, val mixerSampleRate: Int?, val description: String)

    /**
     * Bit-perfect only when Android 14+ holds our accepted BIT_PERFECT mixer attributes for the
     * device in use. Otherwise the track goes through the system mixer: on the built-in speaker and
     * wired outputs that runs at the primary output rate (PROPERTY_OUTPUT_SAMPLE_RATE), which is
     * where a 44.1/96 kHz file gets resampled. USB and Bluetooth mixers run at rates the platform
     * does not report, so those stay unknown rather than guessed.
     */
    @Synchronized
    fun status(trackSampleRate: Int, preferred: AudioDeviceInfo?): OutputStatus {
        val device = runCatching {
            preferred ?: if (Build.VERSION.SDK_INT >= 33) manager.getAudioDevicesForAttributes(attributes).firstOrNull() else null
        }.getOrNull()
        if (Build.VERSION.SDK_INT >= 34) {
            val held = selected?.let { sel ->
                runCatching { manager.getPreferredMixerAttributes(attributes, sel) }.getOrNull()
            }
            if (held != null && held.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                held.format.sampleRate == trackSampleRate && (device == null || device.id == selected?.id)
            ) {
                return OutputStatus(true, trackSampleRate, "USB bit-perfect mixer")
            }
        }
        return when (device?.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                OutputStatus(false, null, "USB via system mixer (rate not reported)")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                OutputStatus(false, null, "Bluetooth (re-encoded by the headset codec)")
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC ->
                OutputStatus(false, null, "HDMI via system mixer")
            else -> OutputStatus(
                false,
                manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it > 0 },
                if (device?.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device?.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    "Wired via system mixer"
                } else {
                    "System mixer"
                },
            )
        }
    }

    @Synchronized
    fun clear() {
        if (Build.VERSION.SDK_INT >= 34) {
            selected?.let { runCatching { manager.clearPreferredMixerAttributes(attributes, it) } }
        }
        selected = null
    }
}

internal fun pcmBitDepth(encoding: Int): Int? = when (encoding) {
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> 24 // float has 24 significant binary digits
    C.ENCODING_PCM_32BIT -> 32
    else -> null
}
