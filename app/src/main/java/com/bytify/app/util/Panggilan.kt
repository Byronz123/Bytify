package com.bytify.app.util

import android.content.Context
import android.media.AudioManager

/**
 * Apakah perangkat sedang dipakai menelpon.
 *
 * Dibaca dari [AudioManager.getMode], yang TIDAK memerlukan izin apa pun —
 * berbeda dengan TelephonyManager yang menuntut READ_PHONE_STATE hanya untuk
 * menjawab pertanyaan sesederhana ini.
 *
 * Selama panggilan, aplikasi telepon memegang fokus audio secara eksklusif.
 * Pemutar mana pun yang berkelakuan baik — termasuk ExoPlayer dengan
 * handleAudioFocus — akan menolak mulai, dan itu memang benar: suara media
 * tidak boleh menimpa percakapan. Yang bisa diperbaiki bukan larangannya,
 * melainkan diamnya: tanpa keterangan, tombol putar terlihat seperti rusak.
 */
fun sedangMenelpon(context: Context): Boolean {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return when (audio.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE,
        -> true
        else -> false
    }
}

/** Keterangan tunggal supaya pesannya sama di seluruh aplikasi. */
const val PESAN_SEDANG_MENELPON =
    "Tidak bisa memutar selagi ada panggilan. Putar lagi setelah panggilan selesai."
