package com.bytify.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Tarikan vertikal yang tetap jalan walau jari mulai di atas tombol/area yang
 * bisa diketuk (mis. mini player): sentuhan dipantau di tahap Initial, dan baru
 * "diambil" setelah jari benar-benar bergeser (melewati touch slop), sehingga
 * ketukan biasa tetap sampai ke tombol.
 */
fun Modifier.verticalDragGesture(
    enabled: Boolean = true,
    onDrag: (Float) -> Unit,
    onRelease: (velocityY: Float) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var lastY = down.position.y
        while (true) {
            // Tahap Initial: kita melihat sentuhan sebelum tombol di dalamnya.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) break
            val total = change.position.y - down.position.y
            val sideways = kotlin.math.abs(change.position.x - down.position.x)
            if (!dragging && kotlin.math.abs(total) > slop && kotlin.math.abs(total) > sideways) {
                dragging = true
                onDrag(total)
                lastY = change.position.y
                change.consume()
                continue
            }
            if (dragging) {
                onDrag(change.position.y - lastY)
                lastY = change.position.y
                change.consume()
            }
        }
        if (dragging) onRelease(tracker.calculateVelocity().y)
    }
}
