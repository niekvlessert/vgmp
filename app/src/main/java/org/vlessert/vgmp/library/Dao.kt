package org.vlessert.vgmp.library

import androidx.lifecycle.LiveData
import androidx.room.*

data class GameWithTracks(
    val game: GameEntity,
    val tracks: List<TrackEntity>
)

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: GameEntity): Long

    @Update
    suspend fun updateGame(game: GameEntity)

    @Query("SELECT * FROM games WHERE name LIKE '%' || :query || '%' OR system LIKE '%' || :query || '%' ORDER BY name ASC LIMIT :limit OFFSET :offset")
    suspend fun searchGamesPaginated(query: String, limit: Int, offset: Int): List<GameEntity>

    @Query("SELECT * FROM games WHERE name LIKE '%' || :query || '%' OR system LIKE '%' || :query || '%' ORDER BY name ASC LIMIT 50")
    suspend fun searchGames(query: String): List<GameEntity>

    @Query("SELECT * FROM games ORDER BY name ASC LIMIT :limit OFFSET :offset")
    suspend fun getAllGamesPaginated(limit: Int, offset: Int): List<GameEntity>

    @Query("SELECT * FROM games ORDER BY name ASC")
    suspend fun getAllGames(): List<GameEntity>

    @Query("SELECT * FROM games ORDER BY name ASC")
    fun getAllGamesLive(): LiveData<List<GameEntity>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY name ASC")
    suspend fun getFavoriteGames(): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Query("SELECT * FROM games WHERE folderPath = :path LIMIT 1")
    suspend fun findByPath(path: String): GameEntity?

    @Query("DELETE FROM games")
    suspend fun deleteAll()

    @Query("UPDATE games SET artPath = :artPath WHERE id = :gameId")
    suspend fun updateArtPath(gameId: Long, artPath: String)

    @Query("DELETE FROM games WHERE id = :gameId")
    suspend fun deleteGameById(gameId: Long)
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE gameId = :gameId ORDER BY trackIndex ASC")
    suspend fun getTracksForGame(gameId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE isFavorite = 1")
    suspend fun getFavoriteTracks(): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE gameId = :gameId")
    suspend fun deleteTracksForGame(gameId: Long)
}
