package com.simpmusic.media_jvm.mpv

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the shipped libmpv and generated 16/44.1 + 24/96 FLAC fixtures. No speakers required. */
class LosslessPlaybackIntegrationTest {
    private fun MpvPlayer.property(name: String): String? =
        javaClass.getDeclaredMethod("getPropertyString", String::class.java).apply { isAccessible = true }.invoke(this, name) as String?

    private fun MpvPlayer.set(name: String, value: String) {
        val accepted = javaClass.getDeclaredMethod("setPropertyString", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(this, name, value) as Boolean
        assertTrue("mpv rejected $name=$value", accepted)
    }

    @Test fun bypassPreservesRatesAndRestoresFiltersAndSpeed() {
        val fixtures = System.getenv("NEXUS_LOSSLESS_FIXTURES")
        assumeTrue("Set NEXUS_LOSSLESS_FIXTURES and MPV_BUNDLED_PATH to run native checks", fixtures != null)
        for ((file, rate) in listOf("16-44100.flac" to 44100, "24-96000.flac" to 96000)) {
            System.getenv("MPV_BUNDLED_PATH")?.let { System.setProperty("mpv.bundled.path", it) }
        val player = requireNotNull(MpvPlayer.create())
            try {
                player.set("ao", "null")
                player.setEqualizer(listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), -6f)
                player.setRate(1.25f)
                player.setBitPerfect(true)
                val ready = CountDownLatch(1)
                player.setEventListener(object : MpvPlayerEventAdapter() {
                    override fun playing(player: MpvPlayer) { ready.countDown() }
                })
                player.loadFile(File(fixtures, file).absolutePath, startPaused = false)
                assertTrue("playback never started", ready.await(10, TimeUnit.SECONDS))
                assertEquals(rate, player.property("audio-out-params/samplerate")!!.toInt())
                assertEquals(1.0, player.property("speed")!!.toDouble(), 0.0)
                assertTrue(player.property("af").orEmpty() in listOf("", "[]"))
                assertEquals("0", player.property("audio-samplerate"))
                assertEquals("no", player.property("options/audio-format"))
                assertTrue((player.fidelity(null).outputBitDepth ?: 0) >= if (rate == 96000) 24 else 16)
                assertEquals("weak", player.property("gapless-audio"))
                player.setBitPerfect(false)
                assertEquals(1.25, player.property("speed")!!.toDouble(), 0.0)
                assertTrue(player.property("af").orEmpty().contains("equalizer"))
            } finally { player.release() }
        }
    }

    @Test fun albumSuccessorUsesTheSameNativeHandle() {
        val fixtures = System.getenv("NEXUS_LOSSLESS_FIXTURES")
        assumeTrue(fixtures != null)
        System.getenv("MPV_BUNDLED_PATH")?.let { System.setProperty("mpv.bundled.path", it) }
        val player = requireNotNull(MpvPlayer.create())
        try {
            player.set("ao", "null")
            player.setBitPerfect(true)
            val loaded = CountDownLatch(2)
            player.setEventListener(object : MpvPlayerEventAdapter() {
                override fun fileLoaded(player: MpvPlayer, path: String?) { loaded.countDown() }
            })
            player.loadFile(File(fixtures, "16-44100.flac").absolutePath, startPaused = true)
            assertTrue(player.queueGaplessFile(File(fixtures, "16-44100-next.flac").absolutePath))
            player.play()
            assertTrue("native playlist did not advance", loaded.await(15, TimeUnit.SECONDS))
            assertEquals(44100, player.property("audio-out-params/samplerate")!!.toInt())
        } finally { player.release() }
    }
    @Test fun differentDepthReopensOutputInsteadOfTruncating() {
        val fixtures = System.getenv("NEXUS_LOSSLESS_FIXTURES")
        assumeTrue(fixtures != null)
        System.getenv("MPV_BUNDLED_PATH")?.let { System.setProperty("mpv.bundled.path", it) }
        val player = requireNotNull(MpvPlayer.create())
        try {
            player.set("ao", "null")
            player.setBitPerfect(true)
            val playing = CountDownLatch(2)
            player.setEventListener(object : MpvPlayerEventAdapter() {
                override fun playing(player: MpvPlayer) { playing.countDown() }
            })
            player.loadFile(File(fixtures, "16-44100.flac").absolutePath, startPaused = true)
            assertTrue(player.queueGaplessFile(File(fixtures, "24-44100.flac").absolutePath, compatibleFormat = false))
            player.play()
            assertTrue(playing.await(15, TimeUnit.SECONDS))
            assertTrue((player.fidelity(null).outputBitDepth ?: 0) >= 24)
            assertEquals(44100, player.property("audio-out-params/samplerate")!!.toInt())
        } finally { player.release() }
    }

}
