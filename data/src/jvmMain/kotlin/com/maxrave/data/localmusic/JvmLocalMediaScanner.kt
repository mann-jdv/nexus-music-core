package com.maxrave.data.localmusic

import com.maxrave.common.LocalMedia
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import kotlin.math.roundToLong

/**
 * Desktop scanner: plain java.io for the folder walk, jaudiotagger for tags and audio header.
 *
 * Why jaudiotagger (net.jthink:jaudiotagger 3.0.1, Maven Central): it is the original author's
 * library, used by the commercial taggers SongKong and Jaikoz, and one call reads both the tags and
 * the stream header (sample rate, bits per sample, encoder, lossless flag, precise length) for
 * every format this phase needs: FLAC, MP4/M4A (ALAC and AAC), WAV, AIFF, MP3 and Ogg. Pure Java, so
 * nothing extra to bundle per OS beside libmpv. Its weak point is release cadence (3.0.1 is the
 * latest); the actively maintained fork is JitPack-only, targets Java 25 and drops Android, which
 * does not fit this build.
 *
 * A file jaudiotagger cannot parse (Opus in some builds, a damaged header) is still listed, with
 * what the file name and extension say: mpv will usually play it anyway, and hiding a file the user
 * put in the folder would be the worse failure.
 */
internal class JvmLocalMediaScanner : LocalMediaScanner {
    private val artworkDir by lazy {
        File(System.getProperty("user.home"), ".simpmusic${File.separator}local_artwork").apply { mkdirs() }
    }

    override suspend fun persistAccess(folderUri: String): Boolean = File(folderUri).isDirectory

    override suspend fun releaseAccess(folderUri: String) = Unit

    override suspend fun folderName(folderUri: String): String? = File(folderUri).takeIf { it.isDirectory }?.let { it.name.ifEmpty { it.absolutePath } }

    override suspend fun listFiles(folderUri: String): List<LocalFileRef>? =
        withContext(Dispatchers.IO) {
            val root = File(folderUri)
            if (!root.isDirectory) return@withContext null
            root
                .walkTopDown()
                .onFail { file, e -> Logger.w(TAG, "listFiles: cannot read ${file.path}: ${e.message}") }
                .filter { it.isFile && it.extension.lowercase() in LocalMedia.AUDIO_EXTENSIONS }
                .map { LocalFileRef(uri = it.absolutePath, name = it.name, size = it.length(), lastModified = it.lastModified(), mimeType = null) }
                .toList()
        }

    override suspend fun read(file: LocalFileRef): LocalFileMetadata? =
        withContext(Dispatchers.IO) {
            val source = File(file.uri)
            if (!source.isFile) return@withContext null
            val extension = source.extension.lowercase()
            val audioFile =
                runCatching { AudioFileIO.read(source) }
                    .onFailure { Logger.w(TAG, "read ${file.name}: ${it.message}") }
                    .getOrNull()
            if (audioFile == null) {
                val (codec, lossless) = classifyCodec(null, extension)
                return@withContext LocalFileMetadata(
                    title = null, artist = null, album = null, albumArtist = null, trackNumber = null,
                    discNumber = null, year = null, durationMs = 0L, sampleRate = null, bitDepth = null,
                    bitrate = null, codec = codec, lossless = lossless, mimeType = null, artworkUri = null,
                )
            }
            val header = audioFile.audioHeader
            val tag: Tag? = runCatching { audioFile.tag }.getOrNull()
            fun field(key: FieldKey): String? = tag?.let { t -> runCatching { t.getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() } }
            val (codec, lossless) = classifyCodec(runCatching { header.encodingType }.getOrNull(), extension)
            LocalFileMetadata(
                title = field(FieldKey.TITLE),
                artist = field(FieldKey.ARTIST),
                album = field(FieldKey.ALBUM),
                albumArtist = field(FieldKey.ALBUM_ARTIST),
                trackNumber = parseLeadingNumber(field(FieldKey.TRACK)),
                discNumber = parseLeadingNumber(field(FieldKey.DISC_NO)),
                year = parseYear(field(FieldKey.YEAR)),
                durationMs = runCatching { (header.preciseTrackLength * 1000).roundToLong() }.getOrDefault(0L),
                sampleRate = runCatching { header.sampleRateAsNumber }.getOrNull()?.takeIf { it > 0 },
                bitDepth = runCatching { header.bitsPerSample }.getOrNull()?.takeIf { it > 0 && lossless },
                // jaudiotagger reports kbps.
                bitrate = runCatching { header.bitRateAsNumber }.getOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toInt() },
                codec = codec,
                lossless = lossless,
                mimeType = null,
                artworkUri = runCatching { tag?.firstArtwork?.binaryData }.getOrNull()?.let(::saveArtwork),
            )
        }

    private fun saveArtwork(bytes: ByteArray): String? =
        runCatching {
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = File(artworkDir, "$hash.img")
            if (!file.exists()) file.writeBytes(bytes)
            file.toURI().toString()
        }.getOrNull()

    private companion object {
        const val TAG = "JvmLocalScanner"

        // jaudiotagger logs every frame it parses at INFO through java.util.logging. Held in a field
        // so the configured logger cannot be garbage-collected and come back at its default level.
        val quietJaudiotagger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }
    }

    init {
        quietJaudiotagger.level = Level.OFF
    }
}
