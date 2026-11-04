package com.bytify.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayEventEntity::class,
        AlarmEntity::class,
        RadioFavoriteEntity::class,
        PodcastSubscriptionEntity::class,
        EpisodeProgressEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class BytifyDatabase : RoomDatabase() {
    abstract fun dao(): BytifyDao

    companion object {
        fun create(context: Context): BytifyDatabase =
            Room.databaseBuilder(context, BytifyDatabase::class.java, "bytify.db").build()
    }
}
