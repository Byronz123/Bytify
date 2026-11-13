package com.bytify.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.delay

/**
 * Hitung mundur "Nonton Bareng" yang tampil di atas segalanya.
 *
 * Ini aba-aba, bukan sinkronisasi pemutar: katalog film diputar lewat iframe
 * pihak ketiga yang garis waktunya tidak bisa disentuh aplikasi. Yang dijamin
 * adalah angka yang sama muncul di semua perangkat pada saat yang sama, lalu
 * semua orang menekan putar bersamaan.
 *
 * [batasElapsedRealtime] memakai jam monotonik perangkat masing-masing, jadi
 * perbedaan jam dinding antar-ponsel tidak merusak hitungannya.
 */
@Composable
fun AbaAbaNonton(
    batasElapsedRealtime: Long,
    catatan: String,
    onSelesai: () -> Unit,
) {
    var sisa by remember(batasElapsedRealtime) {
        mutableIntStateOf(sisaDetik(batasElapsedRealtime))
    }

    LaunchedEffect(batasElapsedRealtime) {
        while (true) {
            val detik = sisaDetik(batasElapsedRealtime)
            sisa = detik
            if (detik <= 0) break
            delay(200)
        }
        // "Mulai!" sempat terbaca sebentar sebelum lapisan ini hilang.
        delay(900)
        onSelesai()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (sisa > 0) "$sisa" else "Mulai!",
                color = BytifyColors.Gold,
                fontWeight = FontWeight.Bold,
                fontSize = if (sisa > 0) 96.sp else 56.sp,
            )
            Text(
                catatan,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

private fun sisaDetik(batas: Long): Int {
    val sisaMs = batas - SystemClock.elapsedRealtime()
    return if (sisaMs <= 0) 0 else ((sisaMs + 999) / 1000).toInt()
}
