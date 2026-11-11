package com.bytify.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        MovieEntity::class,
        MovieFavoriteEntity::class,
        MovieHistoryEntity::class,
        MovieSectionEntity::class,
        LiveChannelEntity::class,
        LiveFavoriteEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class BytifyDatabase : RoomDatabase() {
    abstract fun dao(): BytifyDao

    companion object {
        /** Migrasi aditif: data musik, playlist, dan favorit lama tidak disentuh. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `movies` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `overview` TEXT NOT NULL,
                        `posterUrl` TEXT,
                        `backdropUrl` TEXT,
                        `releaseYear` INTEGER,
                        `durationMin` INTEGER,
                        `genresJson` TEXT NOT NULL,
                        `rating` REAL,
                        `language` TEXT,
                        `videoUrl` TEXT,
                        `trailerUrl` TEXT,
                        `subtitlesJson` TEXT NOT NULL,
                        `license` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `movie_favorites` (
                        `movieId` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`movieId`)
                    )""".trimIndent(),
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `movie_history` (
                        `movieId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `posterUrl` TEXT,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`movieId`)
                    )""".trimIndent(),
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `movie_sections` (
                        `title` TEXT NOT NULL,
                        `movieIdsJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`title`)
                    )""".trimIndent(),
                )
            }
        }

        /** Migrasi aditif untuk katalog live TV; tabel musik/film tidak disentuh. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `live_channels` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `logoUrl` TEXT,
                        `category` TEXT NOT NULL,
                        `country` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `streamUrl` TEXT NOT NULL,
                        `streamType` TEXT NOT NULL,
                        `isAvailable` INTEGER NOT NULL,
                        `statusMessage` TEXT,
                        `checkedAtEpochMs` INTEGER NOT NULL,
                        `license` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_live_channels_category` ON `live_channels` (`category`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_live_channels_isAvailable` ON `live_channels` (`isAvailable`)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `live_favorites` (
                        `channelId` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`channelId`)
                    )""".trimIndent(),
                )
            }
        }

        /** Migrasi aditif: kolom serial/episode; baris film lama dianggap "movie". */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `movies` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'movie'")
                database.execSQL("ALTER TABLE `movies` ADD COLUMN `episodesJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Migrasi aditif: alamat halaman sumber film (Referer untuk embed player). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `movies` ADD COLUMN `sourceUrl` TEXT DEFAULT ''")
            }
        }

        /** Migrasi aditif: daftar server player alternatif per film. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `movies` ADD COLUMN `serversJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun create(context: Context): BytifyDatabase =
            Room.databaseBuilder(context, BytifyDatabase::class.java, "bytify.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
    }
}
