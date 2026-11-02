package com.bytify.app.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.bytify.app.data.prefs.EqualizerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kemampuan equalizer perangkat, ditampilkan di layar Equalizer. */
data class EqualizerInfo(
    val centerFrequenciesHz: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
    val bassSupported: Boolean,
)

/**
 * Equalizer & bass boost bawaan Android, ditempel ke sesi audio pemutar.
 * Satu instance untuk seluruh aplikasi (service dan UI ada di proses yang sama).
 */
class AudioEffects {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    private val _info = MutableStateFlow<EqualizerInfo?>(null)
    val info: StateFlow<EqualizerInfo?> = _info.asStateFlow()

    /** Level band yang sedang aktif (berubah saat preset dipilih). */
    private val _currentLevels = MutableStateFlow<List<Int>>(emptyList())
    val currentLevels: StateFlow<List<Int>> = _currentLevels.asStateFlow()

    fun attach(audioSessionId: Int, settings: EqualizerSettings) {
        release()
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        val eq = equalizer
        _info.value = eq?.let {
            val range = it.bandLevelRange
            EqualizerInfo(
                centerFrequenciesHz = (0 until it.numberOfBands).map { band -> it.getCenterFreq(band.toShort()) / 1000 },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = (0 until it.numberOfPresets).map { p -> it.getPresetName(p.toShort()) },
                bassSupported = bassBoost?.strengthSupported == true,
            )
        }
        apply(settings)
    }

    fun apply(settings: EqualizerSettings) {
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = settings.enabled
                if (settings.preset in 0 until eq.numberOfPresets) {
                    eq.usePreset(settings.preset.toShort())
                } else {
                    settings.bandLevels.take(eq.numberOfBands.toInt()).forEachIndexed { band, level ->
                        eq.setBandLevel(band.toShort(), level.toShort())
                    }
                }
                _currentLevels.value = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
            }
        }
        bassBoost?.let { bass ->
            runCatching {
                bass.enabled = settings.enabled && settings.bassStrength > 0
                if (bass.strengthSupported) bass.setStrength(settings.bassStrength.toShort())
            }
        }
    }

    fun release() {
        equalizer?.release()
        bassBoost?.release()
        equalizer = null
        bassBoost = null
    }
}
