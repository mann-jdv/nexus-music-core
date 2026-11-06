package com.maxrave.data.repository

import com.maxrave.common.LocalMedia
import com.maxrave.data.db.LocalMusicDao
import com.maxrave.data.localmusic.LocalFileRef
import com.maxrave.data.localmusic.LocalMediaScanner
import com.maxrave.domain.data.entities.LocalAlbum
import com.maxrave.domain.data.entities.LocalArtist
import com.maxrave.domain.data.entities.LocalFolderEntity
import com.maxrave.domain.data.entities.LocalScanResult
import com.maxrave.domain.data.entities.LocalTrackEntity
import com.maxrave.domain.repository.LocalMusicRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class LocalMusicRepositoryImpl(
    private val dao: LocalMusicDao,
    private val scanner: LocalMediaScanner,
) : LocalMusicRepository {
    override val folders: Flow<List<LocalFolderEntity>> = dao.folders()
    override val tracks: Flow<List<LocalTrackEntity>> = dao.tracks()
    override val albums: Flow<List<LocalAlbum>> = dao.albums()
    override val artists: Flow<List<LocalArtist>> = dao.artists()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // One scan at a time: two over the same folder would race each other's upserts and deletes.
    private val scanLock = Mutex()

    override fun tracksInFolder(folderUri: String) = dao.tracksInFolder(folderUri)

    override fun tracksOfAlbum(
        album: String,
        artist: String?,
    ) = dao.tracksOfAlbum(album, artist)

    override fun tracksOfArtist(artist: String) = dao.tracksOfArtist(artist)

    override fun search(query: String): Flow<List<LocalTrackEntity>> = dao.search(query.trim(), SEARCH_LIMIT)

    override suspend fun getTrack(id: String): LocalTrackEntity? = dao.track(id)

    override suspend fun addFolder(uri: String): LocalScanResult? =
        withContext(Dispatchers.IO) {
            if (!scanner.persistAccess(uri)) {
                Logger.w(TAG, "addFolder: could not keep access to $uri")
            }
            val name = scanner.folderName(uri) ?: return@withContext null
            if (dao.folder(uri) == null) {
                dao.upsertFolder(LocalFolderEntity(uri = uri, name = name, addedAt = nowMillis()))
            }
            scan(uri)
        }

    override suspend fun removeFolder(uri: String) =
        withContext(Dispatchers.IO) {
            scanLock.withLock {
                dao.deleteTracksInFolder(uri)
                dao.deleteFolder(uri)
            }
            scanner.releaseAccess(uri)
        }

    override suspend fun scan(folderUri: String?): LocalScanResult =
        withContext(Dispatchers.IO) {
            scanLock.withLock {
                _isScanning.value = true
                try {
                    val folders = dao.allFolders().filter { folderUri == null || it.uri == folderUri }
                    var total = LocalScanResult(folders = folders.size, added = 0, updated = 0, removed = 0, unchanged = 0, unreadable = 0)
                    for (folder in folders) {
                        val result = scanFolder(folder)
                        total =
                            total.copy(
                                added = total.added + result.added,
                                updated = total.updated + result.updated,
                                removed = total.removed + result.removed,
                                unchanged = total.unchanged + result.unchanged,
                                unreadable = total.unreadable + result.unreadable,
                                inaccessibleFolders = total.inaccessibleFolders + result.inaccessibleFolders,
                            )
                    }
                    Logger.i(TAG, "scan: $total")
                    total
                } finally {
                    _isScanning.value = false
                }
            }
        }

    private suspend fun scanFolder(folder: LocalFolderEntity): LocalScanResult {
        val files = scanner.listFiles(folder.uri)
        if (files == null) {
            // Access lost (grant revoked, drive unplugged). Keep the rows: the folder may come back,
            // and wiping a library because a USB disk was unplugged would be worse than a stale list.
            Logger.w(TAG, "scanFolder: cannot open ${folder.uri}")
            return LocalScanResult(1, 0, 0, 0, 0, 0, inaccessibleFolders = listOf(folder.uri))
        }
        val existing = dao.tracksInFolderOnce(folder.uri).associateBy { it.uri }
        val seen = HashSet<String>(files.size)
        val toRead = ArrayList<LocalFileRef>()
        var unchanged = 0
        for (file in files) {
            seen += file.uri
            val old = existing[file.uri]
            if (old != null && old.size == file.size && old.lastModified == file.lastModified) {
                unchanged++
            } else {
                toRead += file
            }
        }

        // Reading tags is I/O bound and independent per file; a few at once roughly halves a first
        // scan without flooding the content provider.
        val permits = Semaphore(READ_PARALLELISM)
        val now = nowMillis()
        val read =
            coroutineScope {
                toRead
                    .map { file ->
                        async {
                            permits.withPermit {
                                val meta = runCatching { scanner.read(file) }.getOrNull() ?: return@withPermit null
                                val old = existing[file.uri]
                                LocalTrackEntity(
                                    id = LocalMedia.idFor(file.uri),
                                    uri = file.uri,
                                    folderUri = folder.uri,
                                    fileName = file.name,
                                    title = meta.title?.takeIf { it.isNotBlank() } ?: file.name.substringBeforeLast('.'),
                                    artist = meta.artist?.takeIf { it.isNotBlank() },
                                    album = meta.album?.takeIf { it.isNotBlank() },
                                    albumArtist = meta.albumArtist?.takeIf { it.isNotBlank() },
                                    trackNumber = meta.trackNumber,
                                    discNumber = meta.discNumber,
                                    year = meta.year,
                                    durationMs = meta.durationMs,
                                    sampleRate = meta.sampleRate,
                                    bitDepth = meta.bitDepth,
                                    bitrate = meta.bitrate,
                                    codec = meta.codec,
                                    lossless = meta.lossless,
                                    mimeType = meta.mimeType ?: file.mimeType,
                                    size = file.size,
                                    lastModified = file.lastModified,
                                    artworkUri = meta.artworkUri,
                                    addedAt = old?.addedAt ?: now,
                                )
                            }
                        }
                    }.awaitAll()
            }
        val tracks = read.filterNotNull()
        tracks.chunked(BATCH).forEach { dao.upsertTracks(it) }

        val gone = existing.keys.filter { it !in seen }.mapNotNull { existing[it]?.id }
        gone.chunked(BATCH).forEach { dao.deleteTracks(it) }
        dao.markScanned(folder.uri, nowMillis())

        val added = tracks.count { existing[it.uri] == null }
        return LocalScanResult(
            folders = 1,
            added = added,
            updated = tracks.size - added,
            removed = gone.size,
            unchanged = unchanged,
            unreadable = toRead.size - tracks.size,
        )
    }

    private companion object {
        const val TAG = "LocalMusicRepository"
        const val READ_PARALLELISM = 4

        // Under SQLite's 999 bound-variable limit for the IN (...) delete.
        const val BATCH = 500
        const val SEARCH_LIMIT = 50
    }
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
