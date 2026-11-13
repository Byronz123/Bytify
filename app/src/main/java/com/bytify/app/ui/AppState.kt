package com.bytify.app.ui

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytify.app.util.PESAN_SEDANG_MENELPON
import com.bytify.app.util.sedangMenelpon
import androidx.navigation.NavHostController
import com.bytify.app.AppContainer
import com.bytify.app.data.model.Song

/** Konteks tambahan untuk menu lagu (aksi khusus per layar). */
data class SongMenuRequest(
    val song: Song,
    val playlistId: Long? = null,
    val historyEventId: Long? = null,
)

/** State global UI: navigasi, player layar penuh, menu lagu, snackbar. */
class AppState(
    val container: AppContainer,
    val navController: NavHostController,
    scope: CoroutineScope,
) {
    /** Player layar penuh yang bisa ditarik dari mini player. */
    val playerSheet = PlayerSheetState(scope)
    var songMenu by mutableStateOf<SongMenuRequest?>(null)
    var addToPlaylistSong by mutableStateOf<Song?>(null)
    var message by mutableStateOf<String?>(null)

    val player get() = container.player

    fun navigate(route: String) = navController.navigate(route) { launchSingleTop = true }

    fun play(songs: List<Song>, index: Int = 0) {
        // Selama panggilan, aplikasi telepon memegang fokus audio secara
        // eksklusif dan pemutar menolak mulai — tanpa sepatah kata pun, jadi
        // tombol putar terlihat rusak. Larangannya tidak bisa (dan tidak
        // boleh) dilanggar; yang diperbaiki adalah diamnya.
        if (sedangMenelpon(container.appContext)) {
            toast(PESAN_SEDANG_MENELPON)
            return
        }
        container.player.playSongs(songs, index)
    }

    fun showMenu(request: SongMenuRequest) {
        songMenu = request
    }

    fun toast(text: String) {
        message = text
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState belum disediakan") }

/** Ruang bawah yang tertutup mini player + navigasi melayang. */
val LocalBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val MOVIES = "movies"
    const val LIVE = "live"
    const val LIVE_PLAYER = "live/player/{id}"
    const val MOVIE_DETAIL = "movie/{id}"
    const val MOVIE_PLAYER = "movie/player/{id}?ep={ep}"
    const val LIBRARY = "library"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val RECAP = "recap"
    const val SETTINGS = "settings"
    const val EQUALIZER = "equalizer"
    const val IMPORT = "import"
    const val ALARMS = "alarms"
    const val TOGETHER = "together"
    const val EXPLORE = "explore"
    const val PODCAST = "podcast/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val COLLECTION = "collection/{kind}"

    val tabs = listOf(HOME, SEARCH, EXPLORE, LIBRARY)

    fun livePlayer(id: String) = "live/player/${android.net.Uri.encode(id)}"
    fun movieDetail(id: String) = "movie/${android.net.Uri.encode(id)}"
    fun moviePlayer(id: String, episodeId: String? = null) =
        "movie/player/${android.net.Uri.encode(id)}" + (episodeId?.let { "?ep=${android.net.Uri.encode(it)}" } ?: "")
    fun playlist(id: Long) = "playlist/$id"
    fun podcast(id: Long) = "podcast/$id"
    fun collection(kind: String) = "collection/${android.net.Uri.encode(kind)}"
}

@Composable
fun appState(): AppState = LocalAppState.current
