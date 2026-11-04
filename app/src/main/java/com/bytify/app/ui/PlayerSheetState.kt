package com.bytify.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Posisi player layar penuh: 0 = mini player, 1 = terbuka penuh. Mengikuti
 * jari saat ditarik, lalu memantul ke posisi terdekat saat dilepas.
 */
class PlayerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    /** Tinggi layar dalam px; jarak tarik penuh dari mini player ke atas. */
    var heightPx by mutableFloatStateOf(1f)

    val isOpen: Boolean get() = progress.targetValue > 0.5f

    private val settle = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

    fun expand() {
        scope.launch { progress.animateTo(1f, settle) }
    }

    fun collapse() {
        scope.launch { progress.animateTo(0f, settle) }
    }

    fun snapClosed() {
        dragValue = 0f
        scope.launch { progress.snapTo(0f) }
    }

    /**
     * Posisi tarikan dicatat langsung (bukan menunggu coroutine), agar saat jari
     * dilepas keputusan buka/tutup memakai posisi jari yang sebenarnya.
     */
    private var dragValue = 0f
    private var dragging = false

    /** [deltaY] positif = jari turun (menutup), negatif = naik (membuka). */
    fun dragBy(deltaY: Float) {
        if (!dragging) {
            dragging = true
            dragValue = progress.value
        }
        dragValue = (dragValue - deltaY / heightPx).coerceIn(0f, 1f)
        val value = dragValue
        scope.launch { progress.snapTo(value) }
    }

    /** Lepas jari: lemparan cepat menentukan arah, selain itu pilih yang terdekat. */
    fun release(velocityY: Float) {
        val target = when {
            velocityY < -FLING_PX -> 1f
            velocityY > FLING_PX -> 0f
            dragValue > 0.5f -> 1f
            else -> 0f
        }
        dragging = false
        scope.launch { progress.animateTo(target, settle, initialVelocity = -velocityY / heightPx) }
    }

    private companion object {
        const val FLING_PX = 900f
    }
}
