package com.bytify.app.ui

import com.bytify.app.ui.screens.PodcastScreen
import com.bytify.app.ui.screens.ExploreScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LocalContentColor
import com.bytify.app.ui.theme.BytifyTheme
import com.bytify.app.ui.theme.PaperBackground
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bytify.app.AppContainer
import com.bytify.app.ui.components.AddToPlaylistHost
import com.bytify.app.ui.components.FloatingNavBar
import com.bytify.app.ui.components.MiniPlayer
import com.bytify.app.ui.components.SongMenuHost
import com.bytify.app.ui.components.UpdateDialogHost
import com.bytify.app.ui.screens.AlarmScreen
import com.bytify.app.ui.screens.CollectionScreen
import com.bytify.app.ui.screens.EqualizerScreen
import com.bytify.app.ui.screens.ImportScreen
import com.bytify.app.ui.screens.HistoryScreen
import com.bytify.app.ui.screens.HomeScreen
import com.bytify.app.ui.screens.LibraryScreen
import com.bytify.app.ui.screens.LiveTvScreen
import com.bytify.app.ui.screens.LivePlayerScreen
import com.bytify.app.ui.screens.MoviesScreen
import com.bytify.app.ui.screens.MovieDetailScreen
import com.bytify.app.ui.screens.MoviePlayerScreen
import com.bytify.app.ui.screens.PlayerScreen
import com.bytify.app.ui.screens.PlaylistScreen
import com.bytify.app.ui.screens.RecapScreen
import com.bytify.app.ui.screens.SearchScreen
import com.bytify.app.ui.screens.SettingsScreen
import com.bytify.app.ui.screens.StatsScreen
import com.bytify.app.ui.screens.TogetherScreen

/** Akar UI: navigasi, mini player & nav melayang, player layar penuh. */
@Composable
fun BytifyApp(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val app = remember { AppState(container, navController, scope) }
    val sheet = app.playerSheet

    // Tarik mini player ke bawah: geser keluar layar lalu hentikan lagu.
    val dismiss = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 72.dp.toPx() }
    // Arah tarikan diputuskan sekali di awal tarikan dan dicatat langsung
    // (bukan di coroutine), agar tidak salah membaca nilai yang belum diperbarui.
    var miniMode by remember { mutableIntStateOf(MINI_IDLE) }
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    fun dragMini(delta: Float) {
        if (miniMode == MINI_IDLE) miniMode = if (delta > 0f) MINI_DISMISS else MINI_SHEET
        if (miniMode == MINI_DISMISS) {
            dismissOffset = (dismissOffset + delta).coerceAtLeast(0f)
            val value = dismissOffset
            scope.launch { dismiss.snapTo(value) }
        } else {
            sheet.dragBy(delta)
        }
    }
    fun releaseMini(velocity: Float) {
        val mode = miniMode
        val offset = dismissOffset
        miniMode = MINI_IDLE
        dismissOffset = 0f
        if (mode != MINI_DISMISS) {
            sheet.release(velocity)
            return
        }
        scope.launch {
            if (offset > dismissPx || velocity > 1_200f) {
                dismiss.animateTo(dismissPx * 4, tween(180))
                container.player.stopAndClear()
                dismiss.snapTo(0f)
            } else {
                dismiss.animateTo(0f, spring(dampingRatio = 0.7f))
            }
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playerState by container.player.state.collectAsState()
    val hasSong = playerState.song != null
    val isTab = route in Routes.tabs
    val isVideoPlayer = route == Routes.LIVE_PLAYER || route == Routes.MOVIE_PLAYER
    val isRecap = route == Routes.RECAP
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(app.message) {
        app.message?.let {
            snackbar.showSnackbar(it)
            app.message = null
        }
    }

    // Lagu berhenti total: tutup player tanpa animasi.
    LaunchedEffect(hasSong) { if (!hasSong) sheet.snapClosed() }

    // Tinggi area bawah yang tertutup elemen melayang, untuk padding konten.
    val bottomInset = (if (hasSong && !isVideoPlayer) 84.dp else 0.dp) + (if (isTab) 76.dp else 0.dp) + 24.dp

    CompositionLocalProvider(LocalAppState provides app, LocalBottomInset provides bottomInset) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PaperBackground(MaterialTheme.colorScheme.background, Modifier.fillMaxSize().onSizeChanged { sheet.heightPx = it.height.toFloat().coerceAtLeast(1f) }) {
            // Perpindahan rute: antar-tab memudar-naik di tempat (arah tidak
            // bermakna untuk tab), sedangkan masuk ke halaman rinci menggeser
            // dari kanan dan kembali menggeser balik ke kanan. Durasi dijaga
            // pendek supaya terasa sigap, bukan lamban.
            val tabUtama = setOf(Routes.HOME, Routes.SEARCH, Routes.EXPLORE, Routes.LIBRARY)
            fun AnimatedContentTransitionScope<NavBackStackEntry>.keTab() =
                initialState.destination.route in tabUtama && targetState.destination.route in tabUtama
            NavHost(
                navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (keTab()) {
                        fadeIn(tween(170, easing = LinearEasing))
                    } else {
                        fadeIn(tween(160)) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 }
                    }
                },
                exitTransition = {
                    if (keTab()) fadeOut(tween(170, easing = LinearEasing))
                    else fadeOut(tween(140)) + slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 10 }
                },
                popEnterTransition = {
                    if (keTab()) {
                        fadeIn(tween(170, easing = LinearEasing))
                    } else {
                        fadeIn(tween(160)) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 10 }
                    }
                },
                popExitTransition = {
                    if (keTab()) fadeOut(tween(170, easing = LinearEasing))
                    else fadeOut(tween(160)) + slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 }
                },
            ) {
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.SEARCH) { SearchScreen() }
                composable(Routes.MOVIES) { MoviesScreen() }
                composable(Routes.LIVE) { LiveTvScreen() }
                composable(Routes.LIVE_PLAYER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    LivePlayerScreen(android.net.Uri.decode(entry.arguments?.getString("id").orEmpty()))
                }
                composable(Routes.MOVIE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    MovieDetailScreen(android.net.Uri.decode(entry.arguments?.getString("id").orEmpty()))
                }
                composable(
                    Routes.MOVIE_PLAYER,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("ep") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    MoviePlayerScreen(
                        android.net.Uri.decode(entry.arguments?.getString("id").orEmpty()),
                        entry.arguments?.getString("ep")?.let(android.net.Uri::decode),
                    )
                }
                composable(Routes.LIBRARY) { LibraryScreen() }
                composable(Routes.HISTORY) { HistoryScreen() }
                composable(Routes.STATS) { StatsScreen() }
                composable(Routes.RECAP) { RecapScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.EQUALIZER) { EqualizerScreen() }
                composable(Routes.IMPORT) { ImportScreen() }
                composable(Routes.ALARMS) { AlarmScreen() }
                composable(Routes.TOGETHER) { TogetherScreen() }
                composable(Routes.EXPLORE) { ExploreScreen() }
                composable(Routes.PODCAST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    PodcastScreen(it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    PlaylistScreen(it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.COLLECTION) {
                    CollectionScreen(android.net.Uri.decode(it.arguments?.getString("kind").orEmpty()))
                }
            }

            if (!isRecap) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
                ) {
                    SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
                    if (hasSong && !isVideoPlayer) {
                        // Mini player ikut naik & memudar saat player ditarik terbuka.
                        // Tarikan dibaca di wadah yang diam: mini player sendiri ikut
                        // bergerak, sehingga jarak tarik di dalamnya akan "termakan".
                        Box(Modifier.verticalDragGesture(onDrag = ::dragMini, onRelease = ::releaseMini)) {
                        MiniPlayer(
                            Modifier.graphicsLayer {
                                val p = sheet.progress.value.coerceIn(0f, 1f)
                                val d = dismiss.value
                                translationY = -p * size.height * 2.5f + d
                                alpha = ((1f - p * 2.2f) * (1f - d / (dismissPx * 3f))).coerceIn(0f, 1f)
                            },
                        )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (isTab) {
                        FloatingNavBar(
                            modifier = Modifier.graphicsLayer {
                                val p = sheet.progress.value.coerceIn(0f, 1f)
                                translationY = p * size.height * 1.6f
                                alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
                            },
                            currentRoute = route,
                            onSelect = { target ->
                            navController.navigate(target) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        })
                    }
                }
            }

            val sheetShown by remember { derivedStateOf { sheet.progress.value > 0.001f } }
            // Player disiapkan sejak ada lagu (tersembunyi di bawah layar), sehingga
            // tarikan pertama tidak tersendat menunggu layar player disusun.
            if (hasSong) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalDragGesture(
                            enabled = sheetShown,
                            onDrag = { sheet.dragBy(it) },
                            onRelease = { sheet.release(it) },
                        )
                        .graphicsLayer {
                        // Pegas bisa sedikit melampaui 0..1; batasi agar sudut tak negatif.
                        val p = sheet.progress.value.coerceIn(0f, 1f)
                        translationY = (1f - p) * size.height
                        alpha = (p * 1.6f).coerceIn(0f, 1f)
                        val s = 0.94f + 0.06f * p
                        scaleX = s
                        scaleY = s
                        shape = RoundedCornerShape(((1f - p) * 32f).dp)
                        clip = p < 1f
                    },
                ) {
                    // Player selalu bernuansa malam (navy) agar imersif di kedua mode.
                    BytifyTheme(darkTheme = true) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            PlayerScreen(onCollapse = sheet::collapse)
                        }
                    }
                }
            }

            SongMenuHost()
            UpdateDialogHost()
            AddToPlaylistHost()
        }
        }
    }
}

private const val MINI_IDLE = 0
private const val MINI_SHEET = 1
private const val MINI_DISMISS = 2
