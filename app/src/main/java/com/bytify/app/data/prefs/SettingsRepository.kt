package com.bytify.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Pengaturan equalizer; [preset] -1 berarti band kustom dari [bandLevels] (milibel). */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: Int = -1,
    val bandLevels: List<Int> = emptyList(),
    val bassStrength: Int = 0,
)

data class Settings(
    val darkTheme: Boolean = false,
    val skipSilence: Boolean = false,
    val autoRadio: Boolean = true,
    val highQuality: Boolean = true,
    val autoDownloadLiked: Boolean = false,
    val translateLyrics: Boolean = false,
    val dynamicIsland: Boolean = false,
    val togetherName: String = "",
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** Kapan panen katalog lagu terakhir kali benar-benar TUNTAS (bukan terputus). */
    val katalogLaguSelesai: Long = 0,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val searchHistory: List<String> = emptyList(),
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val darkTheme = booleanPreferencesKey("dark_theme")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val autoRadio = booleanPreferencesKey("auto_radio")
        val highQuality = booleanPreferencesKey("high_quality")
        val autoDownloadLiked = booleanPreferencesKey("auto_download_liked")
        val translateLyrics = booleanPreferencesKey("translate_lyrics")
        val dynamicIsland = booleanPreferencesKey("dynamic_island")
        val togetherName = stringPreferencesKey("together_name")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val katalogLaguSelesai = longPreferencesKey("katalog_lagu_selesai")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bassStrength = intPreferencesKey("bass_strength")
        val searchHistory = stringPreferencesKey("search_history")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        darkTheme = this[Keys.darkTheme] ?: false,
        skipSilence = this[Keys.skipSilence] ?: false,
        autoRadio = this[Keys.autoRadio] ?: true,
        highQuality = this[Keys.highQuality] ?: true,
        autoDownloadLiked = this[Keys.autoDownloadLiked] ?: false,
        translateLyrics = this[Keys.translateLyrics] ?: false,
        dynamicIsland = this[Keys.dynamicIsland] ?: false,
        togetherName = this[Keys.togetherName].orEmpty(),
        autoCheckUpdates = this[Keys.autoCheckUpdates] ?: true,
        lastUpdateCheck = this[Keys.lastUpdateCheck] ?: 0,
        katalogLaguSelesai = this[Keys.katalogLaguSelesai] ?: 0,
        equalizer = EqualizerSettings(
            enabled = this[Keys.eqEnabled] ?: false,
            preset = this[Keys.eqPreset] ?: -1,
            bandLevels = this[Keys.eqBands]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            bassStrength = this[Keys.bassStrength] ?: 0,
        ),
        searchHistory = this[Keys.searchHistory]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
    )

    suspend fun setDarkTheme(value: Boolean) = context.dataStore.edit { it[Keys.darkTheme] = value }
    suspend fun setSkipSilence(value: Boolean) = context.dataStore.edit { it[Keys.skipSilence] = value }
    suspend fun setAutoRadio(value: Boolean) = context.dataStore.edit { it[Keys.autoRadio] = value }
    suspend fun setHighQuality(value: Boolean) = context.dataStore.edit { it[Keys.highQuality] = value }
    suspend fun setAutoDownloadLiked(value: Boolean) = context.dataStore.edit { it[Keys.autoDownloadLiked] = value }
    suspend fun setTranslateLyrics(value: Boolean) = context.dataStore.edit { it[Keys.translateLyrics] = value }
    suspend fun setDynamicIsland(value: Boolean) = context.dataStore.edit { it[Keys.dynamicIsland] = value }
    suspend fun setTogetherName(value: String) = context.dataStore.edit { it[Keys.togetherName] = value.trim() }
    suspend fun setAutoCheckUpdates(value: Boolean) = context.dataStore.edit { it[Keys.autoCheckUpdates] = value }
    suspend fun markUpdateChecked() = context.dataStore.edit { it[Keys.lastUpdateCheck] = System.currentTimeMillis() }

    /** Ditandai hanya saat panen katalog selesai seluruhnya. */
    suspend fun tandaiKatalogLaguSelesai() =
        context.dataStore.edit { it[Keys.katalogLaguSelesai] = System.currentTimeMillis() }

    suspend fun setEqualizerEnabled(value: Boolean) = context.dataStore.edit { it[Keys.eqEnabled] = value }

    suspend fun setEqualizerPreset(preset: Int) = context.dataStore.edit { it[Keys.eqPreset] = preset }

    /** Menyimpan band kustom sekaligus beralih dari preset ke mode kustom. */
    suspend fun setEqualizerBands(levels: List<Int>) = context.dataStore.edit {
        it[Keys.eqBands] = levels.joinToString(",")
        it[Keys.eqPreset] = -1
    }

    suspend fun setBassStrength(value: Int) = context.dataStore.edit { it[Keys.bassStrength] = value }

    suspend fun addSearch(query: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.searchHistory]?.split('\n').orEmpty()
        val updated = (listOf(query.trim()) + current.filter { !it.equals(query.trim(), true) })
            .filter { it.isNotBlank() }
            .take(MAX_SEARCH_HISTORY)
        prefs[Keys.searchHistory] = updated.joinToString("\n")
    }

    suspend fun removeSearch(query: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.searchHistory]?.split('\n').orEmpty()
        prefs[Keys.searchHistory] = current.filter { it != query }.joinToString("\n")
    }

    suspend fun clearSearchHistory() = context.dataStore.edit { it.remove(Keys.searchHistory) }

    companion object {
        private const val MAX_SEARCH_HISTORY = 15
    }
}
