package com.maxrave.domain.data.player

import com.maxrave.domain.data.entities.LocalTrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackFidelityTest {
    private fun track(rate: Int = 44_100, bits: Int? = 16) = LocalTrackEntity(
        id = "local:test", uri = "file:///test.flac", folderUri = "file:///", fileName = "test.flac",
        title = "Track", artist = "Artist", album = "Album", albumArtist = "Artist",
        trackNumber = 1, discNumber = 1, year = null, durationMs = 1000,
        sampleRate = rate, bitDepth = bits, bitrate = null, codec = "FLAC", lossless = true,
        mimeType = "audio/flac", size = 1, lastModified = 0, artworkUri = null, addedAt = 0,
    )

    @Test fun cdAndHiResLabels() {
        assertEquals("FLAC · 44.1 kHz / 16-bit · Lossless", PlaybackFidelity(source = track()).qualityLabel())
        assertEquals("FLAC · 96 kHz / 24-bit · Hi-Res Lossless", PlaybackFidelity(source = track(96_000, 24)).qualityLabel())
        assertNull(PlaybackFidelity().qualityLabel())
    }

    @Test fun bitPerfectOnlyWhenThePlatformAcceptedItAndNothingChangesTheSamples() {
        val clean = PlaybackFidelity(source = track(96_000, 24), outputSampleRate = 96_000, outputBitDepth = 24, bitPerfectOutput = true)
        assertEquals("FLAC · 96 kHz / 24-bit · Hi-Res Lossless · Bit-perfect", clean.qualityLabel())
        assertEquals("Hi-Res Lossless · Bit-perfect", clean.shortLabel())
        assertFalse(clean.copy(processing = true).qualityLabel()!!.contains("Bit-perfect"))
        assertFalse(PlaybackFidelity(source = track(), bypassRequested = true).qualityLabel()!!.contains("Bit-perfect"))
    }

    @Test fun reportsResamplingReductionAndProcessingTogether() {
        val label = PlaybackFidelity(source = track(96_000, 24), outputSampleRate = 48_000, outputBitDepth = 16, processing = true).qualityLabel()!!
        assertTrue(label.contains("processed"))
        assertTrue(label.contains("resampled to 48 kHz"))
        assertTrue(label.contains("output 16-bit"))
        assertEquals("Lossless · resampled to 48 kHz", PlaybackFidelity(source = track(), outputSampleRate = 48_000).shortLabel())
    }

    @Test fun outputLineForTheInfoSheet() {
        val f = PlaybackFidelity(source = track(), bypassRequested = true, outputSampleRate = 48_000, outputBitDepth = 24, outputDescription = "System mixer")
        assertEquals("48 kHz / 24-bit · System mixer · app DSP bypassed", f.outputLabel())
    }

    @Test fun unknownDepthIsNotInventedAnd48kIsNotHiRes() {
        val label = PlaybackFidelity(source = track(48_000, null)).qualityLabel()!!
        assertFalse(label.contains("-bit"))
        assertFalse(label.contains("Hi-Res"))
        assertEquals("44.1 kHz", sampleRateLabel(44_100))
        assertEquals("88.2 kHz", sampleRateLabel(88_200))
    }

    @Test fun gaplessNeedsLosslessAndTheSameIdentifiedAlbum() {
        val current = track()
        assertTrue(current.isGaplessSuccessor(current.copy(id = "next", sampleRate = 96_000, bitDepth = 24)))
        assertFalse(current.isGaplessSuccessor(current.copy(album = "Another")))
        assertFalse(current.isGaplessSuccessor(current.copy(albumArtist = "Other")))
        assertFalse(current.isGaplessSuccessor(current.copy(lossless = false)))
        assertFalse(current.copy(album = null).isGaplessSuccessor(current.copy(album = null)))
        assertFalse(current.isGaplessSuccessor(null))
    }
}
