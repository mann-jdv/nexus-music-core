package com.maxrave.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.maxrave.domain.data.entities.LocalAlbum
import com.maxrave.domain.data.entities.LocalArtist
import com.maxrave.domain.data.entities.LocalFolderEntity
import com.maxrave.domain.data.entities.LocalTrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Local file playback (Nexus Music, Phase 4a). Albums and artists are not tables of their own: they
 * are grouped from `local_track` on read, so a rescan can never leave one pointing at files that
 * are gone.
 *
 * Artist identity is `COALESCE(NULLIF(albumArtist, ''), artist)`: the album artist when the file
 * names one, so a compilation's tracks group under it rather than scattering by guest.
 */
@Dao
interface LocalMusicDao {
    // ---------- folders ----------

    @Query("SELECT * FROM local_folder ORDER BY name COLLATE NOCASE")
    fun folders(): Flow<List<LocalFolderEntity>>

    @Query("SELECT * FROM local_folder")
    suspend fun allFolders(): List<LocalFolderEntity>

    @Query("SELECT * FROM local_folder WHERE uri = :uri")
    suspend fun folder(uri: String): LocalFolderEntity?

    @Upsert
    suspend fun upsertFolder(folder: LocalFolderEntity)

    @Query("DELETE FROM local_folder WHERE uri = :uri")
    suspend fun deleteFolder(uri: String)

    @Query("UPDATE local_folder SET lastScannedAt = :at WHERE uri = :uri")
    suspend fun markScanned(
        uri: String,
        at: Long,
    )

    // ---------- tracks ----------

    @Query("SELECT * FROM local_track ORDER BY title COLLATE NOCASE")
    fun tracks(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_track WHERE folderUri = :folderUri ORDER BY fileName COLLATE NOCASE")
    fun tracksInFolder(folderUri: String): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_track WHERE folderUri = :folderUri")
    suspend fun tracksInFolderOnce(folderUri: String): List<LocalTrackEntity>

    @Query(
        "SELECT * FROM local_track WHERE album = :album AND COALESCE(NULLIF(albumArtist, ''), artist) IS :artist " +
            "ORDER BY discNumber, trackNumber, title COLLATE NOCASE",
    )
    fun tracksOfAlbum(
        album: String,
        artist: String?,
    ): Flow<List<LocalTrackEntity>>

    @Query(
        "SELECT * FROM local_track WHERE COALESCE(NULLIF(albumArtist, ''), artist) = :artist OR artist = :artist " +
            "ORDER BY album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE",
    )
    fun tracksOfArtist(artist: String): Flow<List<LocalTrackEntity>>

    @Query(
        "SELECT * FROM local_track WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' " +
            "OR album LIKE '%' || :query || '%' OR albumArtist LIKE '%' || :query || '%' " +
            "ORDER BY title COLLATE NOCASE LIMIT :limit",
    )
    fun search(
        query: String,
        limit: Int,
    ): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_track WHERE id = :id")
    suspend fun track(id: String): LocalTrackEntity?

    @Upsert
    suspend fun upsertTracks(tracks: List<LocalTrackEntity>)

    @Query("DELETE FROM local_track WHERE id IN (:ids)")
    suspend fun deleteTracks(ids: List<String>)

    @Query("DELETE FROM local_track WHERE folderUri = :folderUri")
    suspend fun deleteTracksInFolder(folderUri: String)

    // ---------- groupings ----------

    @Query(
        "SELECT album AS name, COALESCE(NULLIF(albumArtist, ''), artist) AS artist, COUNT(*) AS trackCount, " +
            "MAX(year) AS year, MAX(artworkUri) AS artworkUri FROM local_track " +
            "WHERE album IS NOT NULL AND album != '' " +
            "GROUP BY album, COALESCE(NULLIF(albumArtist, ''), artist) ORDER BY album COLLATE NOCASE",
    )
    fun albums(): Flow<List<LocalAlbum>>

    @Query(
        "SELECT COALESCE(NULLIF(albumArtist, ''), artist) AS name, COUNT(*) AS trackCount, " +
            "COUNT(DISTINCT album) AS albumCount, MAX(artworkUri) AS artworkUri FROM local_track " +
            "WHERE COALESCE(NULLIF(albumArtist, ''), artist) IS NOT NULL AND COALESCE(NULLIF(albumArtist, ''), artist) != '' " +
            "GROUP BY COALESCE(NULLIF(albumArtist, ''), artist) ORDER BY name COLLATE NOCASE",
    )
    fun artists(): Flow<List<LocalArtist>>
}
