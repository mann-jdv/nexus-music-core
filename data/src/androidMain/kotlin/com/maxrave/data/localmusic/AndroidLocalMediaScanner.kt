package com.maxrave.data.localmusic

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.maxrave.common.LocalMedia
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Storage Access Framework scanner. The folder comes from ACTION_OPEN_DOCUMENT_TREE; access is kept
 * with takePersistableUriPermission so it survives restarts.
 *
 * The tree is walked with [DocumentsContract] queries rather than DocumentFile: DocumentFile asks
 * the provider once per property per file (name, type, size, date are four round trips each), which
 * on a few thousand files is the difference between seconds and minutes. One query per directory
 * here returns all of them at once.
 *
 * Tags and cover come from [MediaMetadataRetriever]; the codec, sample rate and (before Android 12)
 * bit depth from [MediaExtractor]'s track format, because the retriever only reports the container
 * MIME type, which cannot tell ALAC from AAC inside an .m4a.
 *
 * AIFF is skipped: Android has neither a Media3 extractor nor a platform retriever for it, so the
 * file could be listed but never played.
 */
internal class AndroidLocalMediaScanner(
    private val context: Context,
) : LocalMediaScanner {
    private val resolver get() = context.contentResolver
    private val artworkDir by lazy { File(context.filesDir, "local_artwork").apply { mkdirs() } }

    override suspend fun persistAccess(folderUri: String): Boolean =
        runCatching {
            resolver.takePersistableUriPermission(Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Logger.w(TAG, "persistAccess: ${it.message}") }
            .isSuccess

    override suspend fun releaseAccess(folderUri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override suspend fun folderName(folderUri: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tree = Uri.parse(folderUri)
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }

    override suspend fun listFiles(folderUri: String): List<LocalFileRef>? =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(folderUri)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext null
            val out = ArrayList<LocalFileRef>()
            val pending = ArrayDeque<String>().apply { add(rootId) }
            var first = true
            while (pending.isNotEmpty()) {
                val parentId = pending.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val cursor =
                    try {
                        resolver.query(children, COLUMNS, null, null, null)
                    } catch (e: Exception) {
                        // The root failing means the grant is gone; a sub-folder failing is skipped.
                        Logger.w(TAG, "listFiles: ${e.message}")
                        if (first) return@withContext null else null
                    }
                first = false
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending += docId
                            continue
                        }
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in LocalMedia.AUDIO_EXTENSIONS || ext in UNSUPPORTED_ON_ANDROID) continue
                        out +=
                            LocalFileRef(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
                                name = name,
                                size = if (c.isNull(3)) 0L else c.getLong(3),
                                lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                                mimeType = mime,
                            )
                    }
                }
            }
            out
        }

    override suspend fun read(file: LocalFileRef): LocalFileMetadata? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(file.uri)
            val extension = file.name.substringAfterLast('.', "").lowercase()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun meta(key: Int) = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
                val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var sampleRate: Int? = null
                var bitDepth: Int? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sampleRate = meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                    bitDepth = meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                }
                val track = readTrackFormat(uri)
                val (codec, lossless) = classifyCodec(track?.mime, extension)
                sampleRate = sampleRate ?: track?.sampleRate
                bitDepth = (bitDepth ?: track?.bitDepth)?.takeIf { lossless }
                LocalFileMetadata(
                    title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    trackNumber = parseLeadingNumber(meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    discNumber = parseLeadingNumber(meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                    year = parseYear(meta(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE)),
                    durationMs = duration,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                    codec = codec,
                    lossless = lossless,
                    mimeType = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    artworkUri = retriever.embeddedPicture?.let(::saveArtwork),
                )
            } catch (e: Exception) {
                Logger.w(TAG, "read ${file.name}: ${e.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    private data class TrackFormat(
        val mime: String?,
        val sampleRate: Int?,
        val bitDepth: Int?,
    )

    private fun readTrackFormat(uri: Uri): TrackFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { format ->
                    TrackFormat(
                        mime = format.getString(MediaFormat.KEY_MIME),
                        sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        bitDepth =
                            when (format.intOrNull(MediaFormat.KEY_PCM_ENCODING)) {
                                android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                                android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                                else -> format.intOrNull("bits-per-sample")
                            },
                    )
                }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** Writes the cover once per distinct image (named by its hash), so an album's tracks share one file. */
    private fun saveArtwork(bytes: ByteArray): String? =
        runCatching {
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = File(artworkDir, "$hash.img")
            if (!file.exists()) file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        }.getOrNull()

    private companion object {
        const val TAG = "AndroidLocalScanner"
        val UNSUPPORTED_ON_ANDROID = setOf("aif", "aiff", "aifc")
        val COLUMNS =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
