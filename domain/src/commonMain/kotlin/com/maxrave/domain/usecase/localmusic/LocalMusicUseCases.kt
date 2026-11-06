package com.maxrave.domain.usecase.localmusic

import com.maxrave.domain.data.entities.LocalAlbum
import com.maxrave.domain.data.entities.LocalArtist
import com.maxrave.domain.data.entities.LocalFolderEntity
import com.maxrave.domain.data.entities.LocalScanResult
import com.maxrave.domain.data.entities.LocalTrackEntity
import com.maxrave.domain.repository.LocalMusicRepository
import kotlinx.coroutines.flow.Flow

/*
 * Use cases for local file playback. Thin on purpose: the repository already owns the rules (what a
 * rescan keeps, what removing a folder deletes); these give the UI one named entry point per action
 * so it never reaches for the repository directly.
 */

class AddLocalFolderUseCase(
    private val repository: LocalMusicRepository,
) {
    /** Adds and scans the folder; null when it could not be opened. */
    suspend operator fun invoke(uri: String): LocalScanResult? = repository.addFolder(uri)
}

class RemoveLocalFolderUseCase(
    private val repository: LocalMusicRepository,
) {
    suspend operator fun invoke(uri: String) = repository.removeFolder(uri)
}

class ScanLocalMusicUseCase(
    private val repository: LocalMusicRepository,
) {
    /** Rescans one folder, or all of them when [folderUri] is null. */
    suspend operator fun invoke(folderUri: String? = null): LocalScanResult = repository.scan(folderUri)
}

class GetLocalFoldersUseCase(
    private val repository: LocalMusicRepository,
) {
    operator fun invoke(): Flow<List<LocalFolderEntity>> = repository.folders
}

class GetLocalTracksUseCase(
    private val repository: LocalMusicRepository,
) {
    operator fun invoke(): Flow<List<LocalTrackEntity>> = repository.tracks

    fun inFolder(folderUri: String): Flow<List<LocalTrackEntity>> = repository.tracksInFolder(folderUri)

    fun ofAlbum(
        album: String,
        artist: String?,
    ): Flow<List<LocalTrackEntity>> = repository.tracksOfAlbum(album, artist)

    fun ofArtist(artist: String): Flow<List<LocalTrackEntity>> = repository.tracksOfArtist(artist)
}

class GetLocalAlbumsUseCase(
    private val repository: LocalMusicRepository,
) {
    operator fun invoke(): Flow<List<LocalAlbum>> = repository.albums
}

class GetLocalArtistsUseCase(
    private val repository: LocalMusicRepository,
) {
    operator fun invoke(): Flow<List<LocalArtist>> = repository.artists
}

class SearchLocalTracksUseCase(
    private val repository: LocalMusicRepository,
) {
    operator fun invoke(query: String): Flow<List<LocalTrackEntity>> = repository.search(query)
}
