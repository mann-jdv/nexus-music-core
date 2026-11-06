package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LocalAlbum
import com.maxrave.domain.data.entities.LocalArtist
import com.maxrave.domain.data.entities.LocalFolderEntity
import com.maxrave.domain.data.entities.LocalScanResult
import com.maxrave.domain.data.entities.LocalTrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Music files the user owns, read from folders they picked. Nothing here touches the network.
 */
interface LocalMusicRepository {
    val folders: Flow<List<LocalFolderEntity>>
    val tracks: Flow<List<LocalTrackEntity>>
    val albums: Flow<List<LocalAlbum>>
    val artists: Flow<List<LocalArtist>>

    /** True while a scan is running, so the UI can show it and refuse a second one. */
    val isScanning: StateFlow<Boolean>

    fun tracksInFolder(folderUri: String): Flow<List<LocalTrackEntity>>

    fun tracksOfAlbum(
        album: String,
        artist: String?,
    ): Flow<List<LocalTrackEntity>>

    fun tracksOfArtist(artist: String): Flow<List<LocalTrackEntity>>

    fun search(query: String): Flow<List<LocalTrackEntity>>

    suspend fun getTrack(id: String): LocalTrackEntity?

    /**
     * Remembers [uri] (keeping access to it across restarts on Android) and scans it. Returns null
     * when the folder could not be opened.
     */
    suspend fun addFolder(uri: String): LocalScanResult?

    /** Forgets the folder and every track read from it. The files themselves are not touched. */
    suspend fun removeFolder(uri: String)

    /**
     * Re-reads [folderUri], or every folder when null: new and changed files are read, files that
     * are gone are removed, files whose size and modification time still match are kept as they are.
     */
    suspend fun scan(folderUri: String? = null): LocalScanResult
}
