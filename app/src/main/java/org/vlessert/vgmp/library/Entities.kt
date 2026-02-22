package org.vlessert.vgmp.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,          // display name (from tags or folder)
    val system: String,
    val author: String,
    val year: String,
    val folderPath: String,    // absolute path to extracted game folder on disk
    val artPath: String,       // absolute path to .png art, or ""
    val zipSource: String,     // source zip filename for reference
    val isFavorite: Boolean = false
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = GameEntity::class,
        parentColumns = ["id"],
        childColumns = ["gameId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("gameId")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val gameId: Long,
    val title: String,
    val filePath: String,       // absolute path to .vgm/.vgz/.nsf etc file
    val durationSamples: Long,  // -1 if unknown
    val trackIndex: Int,        // order within the game
    val isFavorite: Boolean = false,
    val subTrackIndex: Int = -1 // for multi-track files like NSF (-1 = not a subtrack)
)
