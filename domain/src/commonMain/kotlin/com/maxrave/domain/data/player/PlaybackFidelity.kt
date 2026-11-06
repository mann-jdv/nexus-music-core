package com.maxrave.domain.data.player

import com.maxrave.domain.data.entities.LocalTrackEntity

/**
 * What the file is ([source]) and what actually leaves the app ([outputSampleRate] …), kept apart:
 * a lossless file does not prove a clean output. Every output figure is measured or reported by the
 * platform, never inferred from a setting; a figure nobody reported stays null and is not shown.
 */
data class PlaybackFidelity(
    val source: LocalTrackEntity? = null,
    /** The bit-perfect setting is on and this item is a local lossless file, so app DSP is bypassed. */
    val bypassRequested: Boolean = false,
    /** The rate the audio is finally mixed/played at (Android: system mixer rate; mpv: AO rate). */
    val outputSampleRate: Int? = null,
    val outputBitDepth: Int? = null,
    /** What the decoder handed over, when narrower than the file. */
    val decodedBitDepth: Int? = null,
    /** The app changes the samples: EQ, effects, speed/pitch, crossfade, fades, volume below 100%. */
    val processing: Boolean = false,
    /** The platform accepted an exclusive / bit-perfect path at the source format. */
    val bitPerfectOutput: Boolean = false,
    /** Where the audio goes, for the track info sheet ("USB bit-perfect mixer", "System mixer"). */
    val outputDescription: String? = null,
) {
    /** "FLAC · 96 kHz / 24-bit · Hi-Res Lossless", then the honest output status. */
    fun qualityLabel(): String? {
        val track = source ?: return null
        val parts = mutableListOf<String>()
        track.codec?.takeIf { it.isNotBlank() }?.let(parts::add)
        listOfNotNull(
            track.sampleRate?.takeIf { it > 0 }?.let(::sampleRateLabel),
            track.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
        ).joinToString(" / ").takeIf { it.isNotEmpty() }?.let(parts::add)
        if (track.lossless) {
            parts.add(tier(track))
        } else {
            track.bitrate?.takeIf { it > 0 }?.let { parts.add("${it / 1000} kbps") }
        }
        parts.addAll(statusParts(track))
        return parts.joinToString(" · ")
    }

    /** One short line for tight spots (mini players): "Hi-Res Lossless · resampled to 48 kHz". */
    fun shortLabel(): String? {
        val track = source ?: return null
        val head = if (track.lossless) tier(track) else listOfNotNull(track.codec, track.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" }).joinToString(" ")
        return (listOf(head).filter { it.isNotBlank() } + statusParts(track)).joinToString(" · ").ifBlank { null }
    }

    /** "Output: 48 kHz / 24-bit · System mixer", for the track info sheet. Null when nothing is known. */
    fun outputLabel(): String? {
        source ?: return null
        val format = listOfNotNull(outputSampleRate?.let(::sampleRateLabel), outputBitDepth?.let { "$it-bit" }).joinToString(" / ")
        val parts = listOfNotNull(format.ifBlank { null }, outputDescription?.ifBlank { null })
        val dsp = if (bypassRequested) "app DSP bypassed" else if (processing) "app DSP active" else null
        return (parts + listOfNotNull(dsp)).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun statusParts(track: LocalTrackEntity): List<String> =
        buildList {
            if (processing) add("processed")
            if (outputSampleRate != null && track.sampleRate != null && outputSampleRate != track.sampleRate) {
                add("resampled to ${sampleRateLabel(outputSampleRate)}")
            }
            if (outputBitDepth != null && track.bitDepth != null && outputBitDepth < track.bitDepth) {
                add("output $outputBitDepth-bit")
            }
            if (decodedBitDepth != null && track.bitDepth != null && decodedBitDepth < track.bitDepth &&
                (outputBitDepth == null || decodedBitDepth < outputBitDepth)
            ) {
                add("decoded at $decodedBitDepth-bit")
            }
            if (isEmpty() && bitPerfectOutput && track.lossless) add("Bit-perfect")
        }

    private fun tier(track: LocalTrackEntity) = if ((track.sampleRate ?: 0) > 48_000) "Hi-Res Lossless" else "Lossless"
}

fun sampleRateLabel(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000}.${(hz % 1000).toString().padStart(3, '0').trimEnd('0')} kHz"

/** Unknown albums must never accidentally form a single gapless album. */
fun LocalTrackEntity?.isGaplessSuccessor(next: LocalTrackEntity?): Boolean =
    this != null && next != null && lossless && next.lossless &&
        !album.isNullOrBlank() && album == next.album &&
        (albumArtist ?: artist) == (next.albumArtist ?: next.artist)
