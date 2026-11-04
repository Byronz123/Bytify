package com.bytify.app.util

import java.util.Calendar

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long): String = formatDuration((ms / 1000).coerceAtLeast(0))

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 4..10 -> "Selamat pagi"
    in 11..14 -> "Selamat siang"
    in 15..18 -> "Selamat sore"
    else -> "Selamat malam"
}

fun daysAgo(days: Int): Long = System.currentTimeMillis() - days * 86_400_000L

fun startOfYear(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

/** Link YouTube Music untuk dibagikan. */
fun shareUrl(songId: String): String? =
    if (songId.contains(':')) null else "https://music.youtube.com/watch?v=$songId"
