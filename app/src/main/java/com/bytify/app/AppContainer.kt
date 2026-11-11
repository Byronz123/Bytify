package com.bytify.app

import android.util.Log
import kotlinx.coroutines.delay
import com.bytify.app.data.movie.KATALOG_MASIH_SEGAR
import com.bytify.app.data.movie.USIA_KATALOG_DEFAULT_MS
import com.bytify.app.data.remote.PodcastRepository
import com.bytify.app.data.movie.MovieCatalogApi
import com.bytify.app.data.movie.MovieRepository
import com.bytify.app.data.live.LiveCatalogApi
import com.bytify.app.data.live.LiveRepository
import com.bytify.app.data.remote.RadioRepository
import com.bytify.app.data.youtube.RoutingDataSource
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.bytify.app.alarm.AlarmRepository
import com.bytify.app.data.DownloadRepository
import com.bytify.app.data.DownloadState
import com.bytify.app.data.LibraryRepository
import com.bytify.app.data.db.BytifyDatabase
import com.bytify.app.data.model.Song
import com.bytify.app.data.prefs.SettingsRepository
import com.bytify.app.data.together.ListenTogether
import com.bytify.app.data.update.UpdateRepository
import com.bytify.app.data.remote.ChartsRepository
import com.bytify.app.data.remote.ImportRepository
import com.bytify.app.data.remote.LyricsRepository
import com.bytify.app.data.remote.TranslateRepository
import com.bytify.app.data.youtube.ChunkedDataSource
import com.bytify.app.data.youtube.NewPipeDownloader
import com.bytify.app.data.youtube.YouTubeRepository
import com.bytify.app.playback.AudioEffects
import com.bytify.app.playback.NowPlaying
import com.bytify.app.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Dependency injection manual: satu instance untuk seluruh aplikasi. */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val json = Json { ignoreUnknownKeys = true }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val database = BytifyDatabase.create(appContext)
    private val mediaDatabase = StandaloneDatabaseProvider(appContext)

    val library = LibraryRepository(database.dao())
    val settings = SettingsRepository(appContext)
    val youtube = YouTubeRepository()
    val charts = ChartsRepository(okHttp, json)
    val lyrics = LyricsRepository(okHttp, json)
    val translator = TranslateRepository(okHttp, json)
    val importer = ImportRepository(okHttp, json, youtube, library)
    val radio = RadioRepository(okHttp, json, database.dao())
    val podcasts = PodcastRepository(okHttp, json, database.dao())
    val movieCatalogApi = MovieCatalogApi(okHttp, json, BuildConfig.MOVIE_CATALOG_URL)
    val movies = MovieRepository(database.dao(), movieCatalogApi, json)
    val liveCatalogApi = LiveCatalogApi(okHttp, json, BuildConfig.LIVE_CATALOG_URL)
    val live = LiveRepository(database.dao(), liveCatalogApi)
    val updates = UpdateRepository(appContext, okHttp, json)
    val audioEffects = AudioEffects()
    val alarms = AlarmRepository(appContext, database.dao())

    /** Lagu yang sedang diputar, dibagikan ke widget & Dynamic Island. */
    val nowPlaying = MutableStateFlow(NowPlaying())

    /** Cache audio: lagu yang pernah diputar tidak perlu diunduh ulang. */
    val songCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "songs"),
            LeastRecentlyUsedCacheEvictor(SONG_CACHE_BYTES),
            mediaDatabase,
        )
    }

    /**
     * Sumber audio online: id lagu diubah jadi URL stream saat dibuka, lalu
     * dibaca per potongan 1 MB agar tidak diperlambat server.
     */
    val resolvingDataSourceFactory: DataSource.Factory = run {
        val resolving = ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(okHttp).setUserAgent(NewPipeDownloader.USER_AGENT),
        ) { dataSpec ->
            val url = youtube.resolveStreamUrlBlocking(Song.idFromUri(dataSpec.uri))
            dataSpec.withUri(Uri.parse(url))
        }
        DataSource.Factory { ChunkedDataSource(resolving.createDataSource(), ChunkedDataSource.DEFAULT_CHUNK) }
    }

    /** Alamat audio langsung (radio, episode podcast): HTTP biasa, ikuti redirect. */
    private val directDataSourceFactory: DataSource.Factory =
        OkHttpDataSource.Factory(okHttp).setUserAgent("Bytify/${BuildConfig.VERSION_NAME} (Android)")

    /** Untuk unduhan: musik lewat YouTube, episode lewat alamat langsung. */
    private val downloadUpstreamFactory: DataSource.Factory =
        RoutingDataSource.Factory(resolvingDataSourceFactory, directDataSourceFactory)

    /** Dibuat saat pertama dipakai agar pembukaan aplikasi tetap ringan. */
    val downloads by lazy {
        DownloadRepository(appContext, mediaDatabase, downloadUpstreamFactory, library, appScope)
    }

    /**
     * Rantai sumber untuk pemutar: unduhan offline (baca saja) → cache lagu →
     * internet.
     */
    val playbackDataSourceFactory: DataSource.Factory by lazy {
        val online = CacheDataSource.Factory()
            .setCache(songCache)
            .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val music = CacheDataSource.Factory()
            .setCache(downloads.cache)
            .setUpstreamDataSourceFactory(online)
            .setCacheWriteDataSinkFactory(null)
        // Radio & episode: episode yang diunduh dibaca dari penyimpanan, sisanya
        // langsung dari internet tanpa cache (siaran radio tidak pernah berakhir).
        val direct = CacheDataSource.Factory()
            .setCache(downloads.cache)
            .setUpstreamDataSourceFactory(directDataSourceFactory)
            .setCacheWriteDataSinkFactory(null)
        RoutingDataSource.Factory(music, direct)
    }

    val player = PlayerConnection(appContext, appScope, youtube, settings)

    /** Dengar Bareng lewat Supabase Realtime (URL & kunci dari local.properties). */
    val together by lazy {
        ListenTogether(okHttp, json, appScope, player, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
    }

    init {
        autoDownloadLikedSongs()
        autoSyncKatalogFilm()
    }

    /**
     * Katalog film diperbarui sendiri: sekali saat aplikasi dijalankan, lalu
     * diperiksa berkala selama aplikasi hidup. Unduhan hanya terjadi bila
     * katalog sudah usang, jadi membuka layar film tetap ringan.
     */
    private fun autoSyncKatalogFilm() {
        if (!movies.isCatalogConfigured) return
        appScope.launch {
            while (true) {
                runCatching { movies.refreshIfStale() }
                    .onSuccess { jumlah ->
                        if (jumlah != KATALOG_MASIH_SEGAR) {
                            Log.i("BytifyKatalog", "katalog film diperbarui otomatis: $jumlah judul")
                        }
                    }
                    .onFailure { Log.w("BytifyKatalog", "sinkronisasi otomatis gagal: ${it.message}") }
                delay(USIA_KATALOG_DEFAULT_MS)
            }
        }
    }

    /** Jika diaktifkan, lagu yang baru disukai langsung diunduh. */
    private fun autoDownloadLikedSongs() {
        appScope.launch {
            var previous: Set<String>? = null
            combine(library.likedSongs, settings.settings) { liked, s -> liked to s.autoDownloadLiked }
                .collect { (liked, enabled) ->
                    val ids = liked.map { it.id }.toSet()
                    val before = previous
                    previous = ids
                    // Jangan sentuh DownloadManager kecuali fitur ini aktif.
                    if (!enabled || before == null) return@collect
                    liked.filter { it.id !in before && downloads.states.value[it.id] != DownloadState.Completed }
                        .forEach(downloads::download)
                }
        }
    }

    /** Daftar lagu untuk sumber `liked`, `downloaded`, `recent`, atau `playlist:<id>`. */
    suspend fun songsForSource(source: String): List<Song> = when {
        source == "liked" -> library.likedSongs.first()
        source == "downloaded" -> downloads.downloadedSongs.first()
        source == "recent" -> library.recentSongs(100).first()
        source.startsWith("playlist:") ->
            source.removePrefix("playlist:").toLongOrNull()?.let { library.playlistSongs(it).first() }.orEmpty()
        else -> emptyList()
    }

    companion object {
        const val SONG_CACHE_BYTES = 512L * 1024 * 1024
    }
}
