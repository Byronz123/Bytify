package com.bytify.app.data.movie

import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.WatchAliasEntity
import com.bytify.app.data.db.WatchProviderEntity
import com.bytify.app.data.db.WatchTitleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Ketersediaan resmi berubah jauh lebih lambat daripada katalog film: sebuah
 * judul masuk atau keluar Netflix dalam hitungan minggu, bukan jam. Jadi TTL-nya
 * sehari, bukan 3 jam seperti katalog.
 */
const val USIA_WATCH_DEFAULT_MS = 24L * 60 * 60 * 1000

/**
 * Atribusi yang WAJIB tampil bersama data ini. Disimpan juga sebagai konstanta,
 * bukan hanya diambil dari berkas, supaya tetap muncul walau berkasnya belum
 * pernah terunduh di pemasangan ini.
 */
const val ATRIBUSI_KETERSEDIAAN = "Data ketersediaan dari JustWatch melalui TMDB"

/** Baris yang ditulis sekali jalan ke Room; menahan semuanya sekaligus sia-sia. */
private const val POTONG = 2_000

/**
 * Lapisan ketersediaan resmi: di layanan sah mana sebuah judul bisa ditonton di
 * Indonesia.
 *
 * Repositori ini sengaja TIDAK punya Flow daftar penuh. Tabelnya puluhan ribu
 * baris, dan StateFlow yang menahan seluruh daftar film di memori adalah yang
 * pernah mematikan aplikasi dengan OutOfMemoryError di Galaxy A36. Semua akses
 * di sini bertitik — satu judul lewat [ketersediaan], atau sepotong daftar
 * dengan batas lewat [terbaru].
 */
class WatchRepository(
    private val dao: BytifyDao,
    private val api: MovieCatalogApi,
    private val json: Json,
) {
    val isConfigured: Boolean get() = api.isConfigured

    @Volatile
    private var atribusi: String = ATRIBUSI_KETERSEDIAAN

    suspend fun refreshIfStale(maksimalUsiaMs: Long = USIA_WATCH_DEFAULT_MS): Int {
        val terakhir = runCatching { dao.lastWatchSync() }.getOrNull() ?: 0L
        if (terakhir > 0L && System.currentTimeMillis() - terakhir < maksimalUsiaMs) {
            return KATALOG_MASIH_SEGAR
        }
        return refresh()
    }

    /**
     * Unduh lapisan ketersediaan dan simpan ke Room.
     *
     * Mengembalikan 0 bila berkasnya belum terbit — bukan melempar — supaya
     * sinkron katalog lain tidak ikut gagal karena lapisan ini belum ada.
     */
    suspend fun refresh(): Int {
        val katalog = runCatching { api.fetchWatchCatalog() }.getOrNull() ?: return 0
        val now = System.currentTimeMillis()
        katalog.attribution?.takeIf { it.isNotBlank() }?.let { atribusi = it }

        val penyedia = katalog.providers.mapNotNull { (kunci, nilai) ->
            val id = kunci.toIntOrNull() ?: return@mapNotNull null
            if (nilai.name.isBlank()) return@mapNotNull null
            WatchProviderEntity(
                providerId = id,
                name = nilai.name,
                logoPath = nilai.logoPath,
                priority = nilai.priority,
            )
        }
        if (penyedia.isNotEmpty()) dao.upsertWatchProviders(penyedia)

        val baris = katalog.titles.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.tmdbId > 0 }
            .filter { dto -> dto.watch.any { it.value.isNotEmpty() } }
            .distinctBy(WatchTitleDto::id)
            .map { dto ->
                WatchTitleEntity(
                    id = dto.id,
                    tmdbId = dto.tmdbId,
                    type = if (dto.type == "tv") "tv" else "movie",
                    title = dto.title,
                    posterUrl = dto.posterUrl,
                    year = dto.year,
                    overview = dto.overview,
                    genresJson = json.encodeToString(dto.genres),
                    rating = dto.rating,
                    runtimeMin = dto.runtimeMin,
                    watchJson = json.encodeToString(dto.watch),
                    watchLink = dto.watchLink,
                    updatedAt = now,
                )
            }
            .toList()

        baris.chunked(POTONG).forEach { dao.upsertWatchTitles(it) }

        // Alias id katalog -> TMDB id, ikut di berkas yang sama. Hanya hasil
        // pencocokan ketat yang ada di sini.
        val alias = katalog.aliases.mapNotNull { (idFilm, acuan) ->
            if (idFilm.isBlank() || acuan.tmdbId <= 0) return@mapNotNull null
            WatchAliasEntity(
                movieId = idFilm,
                tmdbId = acuan.tmdbId,
                type = if (acuan.type == "tv") "tv" else "movie",
            )
        }
        alias.chunked(POTONG).forEach { dao.upsertWatchAliases(it) }
        return baris.size
    }

    /**
     * Menurunkan TMDB id dari id film di katalog.
     *
     * Film Indonesia memakai "tmdb-<id>", sementara lapisan ini memakai
     * "tmdb-movie-<id>" / "tmdb-tv-<id>". Keduanya ditangani di sini supaya
     * penjodohannya tidak butuh kolom baru di tabel `movies` — pola yang sama
     * dengan `pemilikEpisode()` di MoviesScreen.
     *
     * Id lk21 dan anilist tidak punya TMDB id, jadi mengembalikan null.
     */
    fun tmdbIdDari(idFilm: String): Int? {
        if (!idFilm.startsWith("tmdb-")) return null
        val sisa = idFilm.removePrefix("tmdb-")
            .removePrefix("movie-")
            .removePrefix("tv-")
        return sisa.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Tipe yang dipakai untuk mencari, diturunkan dari id bila disebut. */
    private fun tipeDari(idFilm: String): String? = when {
        idFilm.startsWith("tmdb-tv-") -> "tv"
        idFilm.startsWith("tmdb-movie-") -> "movie"
        else -> null
    }

    /**
     * Ketersediaan resmi untuk satu film di katalog, atau null bila judul itu
     * tidak punya layanan sah di Indonesia (atau bukan judul berkunci TMDB).
     */
    suspend fun ketersediaan(idFilm: String): KetersediaanResmi? =
        withContext(Dispatchers.Default) {
            val tmdbId = tmdbIdDari(idFilm) ?: return@withContext null
            ketersediaanTmdb(tmdbId, tipeDari(idFilm))
        }

    suspend fun ketersediaanTmdb(tmdbId: Int, tipe: String? = null): KetersediaanResmi? =
        withContext(Dispatchers.Default) {
            val baris = runCatching {
                if (tipe != null) dao.watchTitle(tmdbId, tipe) else dao.watchTitleAnyType(tmdbId)
            }.getOrNull() ?: return@withContext null

            val peta: Map<String, List<Int>> = runCatching {
                json.decodeFromString<Map<String, List<Int>>>(baris.watchJson)
            }.getOrDefault(emptyMap())
            if (peta.isEmpty()) return@withContext null

            val semuaId = peta.values.flatten().distinct()
            if (semuaId.isEmpty()) return@withContext null
            val nama = runCatching { dao.watchProviders(semuaId) }
                .getOrDefault(emptyList())
                .associateBy(WatchProviderEntity::providerId)

            // Urutan jenis sengaja: yang tidak menambah biaya bagi pengguna
            // tampil lebih dulu.
            val urutJenis = listOf("flatrate", "free", "ads", "rent", "buy")
            val chip = urutJenis.flatMap { jenis ->
                (peta[jenis] ?: emptyList()).mapNotNull { id ->
                    val info = nama[id] ?: return@mapNotNull null
                    PenyediaTonton(
                        id = id,
                        nama = info.name,
                        logoUrl = info.logoPath?.let { "https://image.tmdb.org/t/p/w92$it" },
                        jenis = jenis,
                    )
                }
            }.distinctBy { it.id to it.jenis }

            if (chip.isEmpty()) return@withContext null
            KetersediaanResmi(
                tmdbId = baris.tmdbId,
                judul = baris.title,
                penyedia = chip,
                tautan = baris.watchLink,
                atribusi = atribusi,
            )
        }

    /**
     * TMDB id untuk satu film di katalog, dengan tipenya.
     *
     * Dua jalur, urut murah dulu:
     *   1. Diturunkan dari id ("tmdb-<id>", "tmdb-movie-<id>", "tmdb-tv-<id>").
     *   2. Tabel alias, hasil pencocokan KETAT judul+tahun di tmdb_match.py —
     *      inilah yang membuat 25.186 film LK21 berid siput ikut terjangkau.
     *
     * Dipakai oleh lapisan ketersediaan DAN oleh subtitle, karena keduanya
     * dikunci TMDB id.
     */
    suspend fun tmdbUntuk(idFilm: String): Pair<Int, String>? {
        tmdbIdDari(idFilm)?.let { return it to (tipeDari(idFilm) ?: "movie") }
        val alias = runCatching { dao.watchAlias(idFilm) }.getOrNull() ?: return null
        return alias.tmdbId to alias.type
    }

    /**
     * Apakah aman meminta subtitle PER EPISODE untuk judul ini.
     *
     * Masalahnya khas anime: AniList memecah serial per musim sementara TMDB
     * menyatukannya, jadi beberapa judul katalog menunjuk ke satu TMDB id.
     * Episode kita semuanya bernomor musim 1, jadi untuk judul yang berbagi
     * id, meminta "musim 1 episode 5" akan memulangkan subtitle MUSIM YANG
     * SALAH. Subtitle film lain lebih buruk daripada tidak ada subtitle, jadi
     * untuk judul seperti itu permintaannya dilewati.
     *
     * Ketersediaan TIDAK terpengaruh: "ada di Netflix" tetap benar untuk
     * seluruh musim, jadi chipnya tetap tampil.
     */
    suspend fun amanSubtitlePerEpisode(idFilm: String, tmdbId: Int, tipe: String): Boolean =
        withContext(Dispatchers.Default) {
            if (tipe != "tv") return@withContext true

            // Serial lk21/nontondrama: SELURUH 65.296 episodenya bernomor
            // musim 1, karena situs sumbernya meratakan penomoran. Untuk acara
            // yang di TMDB punya beberapa musim, "musim 1 episode 30" akan
            // memulangkan subtitle musim yang salah, dan kita belum punya data
            // jumlah musim TMDB untuk memilahnya. Jadi subtitle per-episode
            // untuk serial DITUNDA sampai itu terjawab — chip ketersediaannya
            // tetap tampil, karena itu per-acara dan tidak bergantung musim.
            if (idFilm.startsWith("series-")) return@withContext false

            // Anime: beberapa entri AniList (musim) menunjuk satu entri TMDB.
            val jumlah = runCatching { dao.jumlahAliasUntukTmdb(tmdbId, tipe) }.getOrDefault(0)
            // 0 berarti judul ini tidak lewat alias sama sekali (TMDB id-nya
            // ada di dalam id-nya sendiri), jadi tidak ada ambiguitas musim.
            jumlah <= 1
        }

    /** Sepotong daftar judul yang tersedia resmi, selalu dengan batas. */
    suspend fun terbaru(batas: Int = 60): List<WatchTitleEntity> =
        runCatching { dao.watchTerbaru(batas) }.getOrDefault(emptyList())

    suspend fun jumlah(): Int = runCatching { dao.jumlahWatchTitle() }.getOrDefault(0)
}
