package com.bytify.app.ui

import android.util.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keadaan mode floating (picture-in-picture) yang dipakai bersama antara
 * MainActivity dan layar pemutar.
 *
 * Activity-lah satu-satunya yang boleh memanggil API picture-in-picture, tetapi
 * yang tahu "sedang ada video di layar atau tidak" adalah layar pemutar. Objek
 * kecil ini menjembatani keduanya tanpa membuat layar pemutar menyentuh
 * Activity secara langsung.
 */
object ModeFloating {

    /** Rasio paling ekstrem yang masih diterima Android untuk jendela floating. */
    private const val RASIO_MIN = 0.42f
    private const val RASIO_MAKS = 2.39f

    private val _aktif = MutableStateFlow(false)

    /** true selama jendela aplikasi benar-benar berada dalam mode floating. */
    val aktif: StateFlow<Boolean> = _aktif.asStateFlow()

    private val _adaVideo = MutableStateFlow(false)

    /** true selama sebuah layar pemutar video sedang ditampilkan. */
    val adaVideo: StateFlow<Boolean> = _adaVideo.asStateFlow()

    private val _sedangMain = MutableStateFlow(true)

    /** Dipakai MainActivity untuk memilih ikon tombol: jeda atau putar. */
    val sedangMain: StateFlow<Boolean> = _sedangMain.asStateFlow()

    /**
     * Kendali pemutar yang sedang aktif.
     *
     * Jendela floating Android TIDAK menerima sentuhan pada isi aplikasi —
     * hanya tombol yang didaftarkan aplikasi ke sistem. Tanpa kendali ini,
     * video yang dijeda di jendela kecil tidak akan pernah bisa dilanjutkan:
     * tombolnya ada tetapi tidak tersambung ke apa pun.
     */
    @Volatile
    var kendali: (() -> Unit)? = null
        private set

    @Volatile
    var rasio: Rational = Rational(16, 9)
        private set

    /**
     * Dipanggil layar pemutar selama videonya ada di layar. Tanpa ini, menekan
     * tombol home dari layar mana pun akan memunculkan jendela floating berisi
     * daftar film — bukan yang diinginkan siapa pun.
     */
    fun daftarkanVideo(lebar: Int = 16, tinggi: Int = 9) {
        rasio = rasioAman(lebar, tinggi)
        _adaVideo.value = true
    }

    fun lepaskanVideo() {
        _adaVideo.value = false
        kendali = null
    }

    /** Layar pemutar mendaftarkan cara menukar jeda/putar miliknya sendiri. */
    fun pasangKendali(sedangMain: Boolean, tukar: () -> Unit) {
        kendali = tukar
        _sedangMain.value = sedangMain
    }

    fun setAktif(nilai: Boolean) {
        _aktif.value = nilai
    }

    /**
     * Android MENOLAK rasio di luar rentangnya dan melempar
     * IllegalArgumentException, yang berarti aplikasi mati hanya karena sebuah
     * video kebetulan sangat panjang atau sangat tinggi. Rasio dijepit di sini.
     */
    private fun rasioAman(lebar: Int, tinggi: Int): Rational {
        if (lebar <= 0 || tinggi <= 0) return Rational(16, 9)
        val nilai = lebar.toFloat() / tinggi.toFloat()
        return when {
            nilai < RASIO_MIN -> Rational(42, 100)
            nilai > RASIO_MAKS -> Rational(239, 100)
            else -> Rational(lebar, tinggi)
        }
    }
}
