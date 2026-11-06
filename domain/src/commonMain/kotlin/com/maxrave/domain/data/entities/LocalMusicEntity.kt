package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail

/**
 * A folder the user handed the app to read music from. [uri] is a `content://` tree URI on Android
 * (with a persisted read grant) and an absolute directory path on Desktop.
 */
@Entity(tableName = "local_folder")
data class LocalFolderEntity(
    @PrimaryKey(autoGenerate = false)
    val uri: String,
    val name: String,
    val addedAt: Long,
    /** Epoch millis of the last completed scan, or 0 before the first one. */
    val lastScannedAt: Long = 0L,
)

/**
 * One audio file found in a [LocalFolderEntity]. [id] is [LocalMedia.idFor] of [uri], so it is the
 * same id the queue and the players use, and it is stable across rescans for as long as the file
 * stays where it is.
 *
 * [size] and [lastModified] are what a rescan compares to decide whether a file has to be read
 * again; a file whose two values still match keeps its row as it is.
 */
@Entity(
    tableName = "local_track",
    indices = [Index(value = ["folderUri"]), Index(value = ["album"]), Index(value = ["artist"]), Index(value = ["title"])],
)
data class LocalTrackEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val uri: String,
    val folderUri: String,
    val fileName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    /** Hz, e.g. 44100 / 96000; null where the platform could not tell. */
    val sampleRate: Int?,
    /** 16 / 24 / 32 for PCM-based formats; null for lossy codecs and where unknown. */
    val bitDepth: Int?,
    /** bits per second, as reported by the container. */
    val bitrate: Int?,
    /** FLAC, ALAC, WAV, AIFF, MP3, AAC, Opus, Vorbis … */
    val codec: String?,
    val lossless: Boolean,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    /** A `file:` URI of the embedded cover, extracted once into app storage; null when there is none. */
    val artworkUri: String?,
    val addedAt: Long,
)

/** An album as grouped from [LocalTrackEntity] rows (by album + album artist). */
data class LocalAlbum(
    val name: String,
    val artist: String?,
    val trackCount: Int,
    val year: Int?,
    val artworkUri: String?,
)

/** An artist as grouped from [LocalTrackEntity] rows (album artist, else track artist). */
data class LocalArtist(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val artworkUri: String?,
)

/** What one scan did. [unreadable] counts files that were found but could not be read at all. */
data class LocalScanResult(
    val folders: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val unreadable: Int,
    /** Folders whose access was lost (Android grant revoked, Desktop folder gone). */
    val inaccessibleFolders: List<String> = emptyList(),
)

/** "24-bit / 96 kHz FLAC", "AAC 256 kbps": the quality line shown under a local track. */
fun LocalTrackEntity.qualityLabel(): String {
    val parts = mutableListOf<String>()
    if (lossless) {
        val depth = bitDepth?.let { "$it-bit" }
        val rate = sampleRate?.let { khz(it) }
        listOfNotNull(depth, rate).joinToString(" / ").takeIf { it.isNotEmpty() }?.let(parts::add)
        codec?.let(parts::add)
    } else {
        codec?.let(parts::add)
        bitrate?.takeIf { it > 0 }?.let { parts.add("${it / 1000} kbps") }
    }
    return parts.joinToString(" ")
}

private fun khz(hz: Int): String {
    val tenths = (hz + 50) / 100
    return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10}.${tenths % 10} kHz"
}

private fun LocalTrackEntity.thumbnails(): List<Thumbnail>? = artworkUri?.let { listOf(Thumbnail(height = 544, url = it, width = 544)) }

private fun LocalTrackEntity.durationText(): String {
    val seconds = durationMs / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The queue's model for this file. Its [Track.videoId] is the local id, which the players route on. */
fun LocalTrackEntity.toTrack(): Track =
    Track(
        album = album?.let { Album(id = "", name = it) },
        artists = listOf(Artist(id = null, name = artist ?: albumArtist ?: "")),
        duration = durationText(),
        durationSeconds = (durationMs / 1000).toInt(),
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = thumbnails(),
        title = title,
        videoId = id,
        // Never a video: keeps every "is this a video?" check on the audio path.
        videoType = MERGING_DATA_TYPE.SONG,
        category = null,
        feedbackTokens = null,
        resultType = null,
        year = year?.toString(),
    )

/** The song row the rest of the app (history, likes, playlists) keys on. */
fun LocalTrackEntity.toSongEntity(): SongEntity =
    SongEntity(
        videoId = id,
        albumId = null,
        albumName = album,
        artistId = null,
        artistName = listOf(artist ?: albumArtist ?: ""),
        duration = durationText(),
        durationSeconds = (durationMs / 1000).toInt(),
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = artworkUri,
        title = title,
        videoType = MERGING_DATA_TYPE.SONG,
        category = null,
        resultType = null,
        liked = false,
        totalPlayTime = 0,
        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
    )
