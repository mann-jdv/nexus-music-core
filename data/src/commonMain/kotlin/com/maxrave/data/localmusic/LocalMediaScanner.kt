package com.maxrave.data.localmusic

/**
 * The platform half of local file playback: finding audio files under a folder and reading what is
 * in them. Bound per platform in the loader (Storage Access Framework + MediaMetadataRetriever on
 * Android, java.io + jaudiotagger on Desktop, nothing on iOS), so the repository above it is common
 * code.
 */
interface LocalMediaScanner {
    /** Keeps access to [folderUri] across restarts; false when the platform refused. */
    suspend fun persistAccess(folderUri: String): Boolean

    suspend fun releaseAccess(folderUri: String)

    /** A human name for the folder, or null when it cannot be opened. */
    suspend fun folderName(folderUri: String): String?

    /** Every audio file under [folderUri], recursively; null when the folder cannot be opened. */
    suspend fun listFiles(folderUri: String): List<LocalFileRef>?

    /** Tags, audio header and cover of one file; null when it cannot be read at all. */
    suspend fun read(file: LocalFileRef): LocalFileMetadata?
}

data class LocalFileRef(
    /** `content://` document URI on Android, absolute path on Desktop. */
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
)

data class LocalFileMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val bitrate: Int?,
    val codec: String?,
    val lossless: Boolean,
    val mimeType: String?,
    /** `file:` URI of the extracted cover. */
    val artworkUri: String?,
)

/** Scanner for platforms without local playback (iOS): no folders can be opened. */
object NoLocalMediaScanner : LocalMediaScanner {
    override suspend fun persistAccess(folderUri: String) = false

    override suspend fun releaseAccess(folderUri: String) = Unit

    override suspend fun folderName(folderUri: String): String? = null

    override suspend fun listFiles(folderUri: String): List<LocalFileRef>? = null

    override suspend fun read(file: LocalFileRef): LocalFileMetadata? = null
}

/** "03/12" → 3, "3" → 3, "" → null. */
internal fun parseLeadingNumber(raw: String?): Int? = raw?.trim()?.substringBefore('/')?.toIntOrNull()

/** "2019-04-05" → 2019, "2019" → 2019. */
internal fun parseYear(raw: String?): Int? = raw?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..9999 }

/** Codec name and whether it is lossless, from a codec MIME type or a free-form encoder name. */
internal fun classifyCodec(
    raw: String?,
    extension: String,
): Pair<String?, Boolean> {
    val value = raw?.lowercase().orEmpty()
    return when {
        "flac" in value -> "FLAC" to true
        "alac" in value || "apple lossless" in value -> "ALAC" to true
        "aif" in value -> "AIFF" to true
        "audio/raw" in value || "pcm" in value || "wav" in value -> "WAV" to true
        "opus" in value -> "Opus" to false
        "vorbis" in value -> "Vorbis" to false
        "mpeg" in value || "mp3" in value -> "MP3" to false
        "aac" in value || "mp4a" in value -> "AAC" to false
        else ->
            when (extension) {
                "flac" -> "FLAC" to true
                "wav", "wave" -> "WAV" to true
                "aif", "aiff", "aifc" -> "AIFF" to true
                "alac" -> "ALAC" to true
                "mp3" -> "MP3" to false
                "aac" -> "AAC" to false
                "opus" -> "Opus" to false
                "ogg", "oga" -> "Vorbis" to false
                // m4a/mp4 without a readable codec: could be AAC or ALAC, so claim neither.
                else -> null to false
            }
    }
}
