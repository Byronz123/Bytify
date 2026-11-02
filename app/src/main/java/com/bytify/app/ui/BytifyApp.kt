package com.bytify.app.ui

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
    val app = remember { AppState(container, navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playerState by container.player.state.collectAsState()
    val hasSong = playerState.song != null
    val isTab = route in Routes.tabs
    val isRecap = route == Routes.RECAP
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(app.message) {
        app.message?.let {
            snackbar.showSnackbar(it)
            app.message = null
        }
    }

    // Tinggi area bawah yang tertutup elemen melayang, untuk padding konten.
    val bottomInset = (if (hasSong) 84.dp else 0.dp) + (if (isTab) 76.dp else 0.dp) + 24.dp

    CompositionLocalProvider(LocalAppState provides app, LocalBottomInset provides bottomInset) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.SEARCH) { SearchScreen() }
                composable(Routes.LIBRARY) { LibraryScreen() }
                composable(Routes.HISTORY) { HistoryScreen() }
                composable(Routes.STATS) { StatsScreen() }
                composable(Routes.RECAP) { RecapScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.EQUALIZER) { EqualizerScreen() }
                composable(Routes.IMPORT) { ImportScreen() }
                composable(Routes.ALARMS) { AlarmScreen() }
                composable(Routes.TOGETHER) { TogetherScreen() }
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
                    if (hasSong) {
                        MiniPlayer()
                        Spacer(Modifier.height(10.dp))
                    }
                    if (isTab) {
                        FloatingNavBar(currentRoute = route, onSelect = { target ->
                            navController.navigate(target) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        })
                    }
                }
            }

            AnimatedVisibility(
                visible = app.playerExpanded && hasSong,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                PlayerScreen(onCollapse = { app.playerExpanded = false })
            }

            SongMenuHost()
            UpdateDialogHost()
            AddToPlaylistHost()
        }
        }
    }
}
