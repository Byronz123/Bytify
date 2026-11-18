package com.bytify.app.data.movie

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Respons JSON katalog film yang disediakan backend Bytify. */
@Serializable
data class MovieCatalogDto(
    val movies: List<MovieDto> = emptyList(),
    val sections: List<MovieSectionDto> = emptyList(),
)

@Serializable
data class MovieDto(
    val id: String = "",
    /** "movie" atau "series". */
    val type: String = "movie",
    val title: String = "",
    val overview: String = "",
    val poster: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val year: JsonElement? = null,
    val durationMin: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: JsonElement? = null,
    val language: String? = null,
    val videoUrl: String? = null,
    val streamUrl: String? = null,
    val streamUrls: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val servers: List<MovieServerDto> = emptyList(),
    val trailerUrl: String? = null,
    val subtitles: List<MovieSubtitleDto> = emptyList(),
    val license: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    /**
     * Jumlah episode SEBENARNYA, kalau [episodes] sengaja dipotong di katalog.
     * Dipakai anime: daftarnya dipangkas agar berkasnya muat di batas 25 MiB,
     * dan sisanya dilengkapi di sini.
     */
    val episodeCount: Int? = null,
)

/** Satu episode serial; setiap episode punya URL video dan subtitle sendiri. */
@Serializable
data class EpisodeDto(
    val id: String = "",
    val title: String = "",
    val season: Int = 1,
    val episode: Int = 1,
    val durationMin: Int? = null,
    val videoUrl: String? = null,
    val subtitles: List<MovieSubtitleDto> = emptyList(),
)

@Serializable
data class MovieSubtitleDto(
    val lang: String = "",
    val label: String = "",
    val url: String = "",
)

/** Satu server player alternatif (p2p, cast, hydrax, turbovip, ...). */
@Serializable
data class MovieServerDto(
    val server: String = "",
    val url: String = "",
)

@Serializable
data class MovieSectionDto(
    val title: String = "",
    val movieIds: List<String> = emptyList(),
)

data class Movie(
    val id: String,
    val isSeries: Boolean,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: Int?,
    val durationMin: Int?,
    val genres: List<String>,
    val rating: Double?,
    val language: String?,
    val videoUrl: String?,
    val trailerUrl: String?,
    val subtitles: List<MovieSubtitleDto>,
    val license: String?,
    /** Halaman sumber film. Dipakai sebagai Referer saat memuat embed player. */
    val sourceUrl: String? = null,
    /** Server player alternatif; dipakai untuk berpindah sumber/resolusi. */
    val servers: List<MovieServerDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
) {
    /** Bisa diputar bila film punya URL video atau serial punya minimal satu episode. */
    val isPlayable: Boolean get() = !videoUrl.isNullOrBlank() || episodes.isNotEmpty()

    fun episode(id: String?): EpisodeDto? = episodes.firstOrNull { it.id == id }
}

data class MovieSection(
    val title: String,
    val movieIds: List<String>,
)

fun MovieDto.toMovie(): Movie = Movie(
    id = id.trim(),
    isSeries = type.equals("series", ignoreCase = true),
    title = title.trim(),
    overview = overview,
    posterUrl = (posterUrl ?: poster).cleanUrlOrNull(),
    backdropUrl = backdropUrl.cleanUrlOrNull(),
    releaseYear = (releaseYear ?: year?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        ?.takeIf { it in 1888..2200 },
    durationMin = durationMin?.takeIf { it > 0 },
    genres = genres.map(String::trim).filter(String::isNotEmpty).distinct(),
    rating = rating?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..10.0 },
    language = language?.trim()?.takeIf(String::isNotEmpty),
    videoUrl = (videoUrl ?: streamUrl ?: streamUrls.firstOrNull()).cleanUrlOrNull(),
    trailerUrl = trailerUrl.cleanUrlOrNull(),
    subtitles = subtitles.filter { it.url.cleanUrlOrNull() != null },
    license = license?.trim()?.takeIf(String::isNotEmpty),
    sourceUrl = sourceUrl.cleanUrlOrNull(),
    servers = servers.filter { it.url.cleanUrlOrNull() != null }.distinctBy { it.url },
    episodes = lengkapiEpisode(
        id.trim(),
        episodes
            .filter { it.id.isNotBlank() && it.videoUrl.cleanUrlOrNull() != null }
            .map { it.copy(subtitles = it.subtitles.filter { s -> s.url.cleanUrlOrNull() != null }) }
            .distinctBy { it.id },
        episodeCount,
    ),
)

/** Sebanyak-banyaknya episode yang dilengkapi sendiri untuk satu judul. */
private const val BATAS_LENGKAPI_EPISODE = 500

/**
 * Melengkapi daftar episode yang sengaja dipotong di katalog.
 *
 * Katalog anime memangkas daftar episodenya supaya berkasnya muat di batas
 * keras 25 MiB Cloudflare Pages. Akibatnya Jujutsu Kaisen tampil 8 episode
 * padahal aslinya 24 — dan itu berlaku untuk 6.297 judul, yaitu 124.248
 * episode yang tidak pernah sampai ke pengguna.
 *
 * URL episode berpola `.../embed/anime/<id>/<nomor>`, jadi sisanya bisa
 * DIHITUNG, bukan disimpan. Polanya diturunkan dari episode pertama yang
 * memang ada di katalog — bukan ditanam di sini — supaya kalau pembangun
 * katalog berganti cermin, yang di sini ikut berganti sendiri.
 *
 * Dibatasi [BATAS_LENGKAPI_EPISODE] karena daftar ini dibentuk saat sinkron
 * katalog, dan puncak memori sinkron adalah hal yang sudah pernah menyentuh
 * plafon heap di Galaxy A36 (lihat 3m).
 */
private fun lengkapiEpisode(
    idFilm: String,
    tersimpan: List<EpisodeDto>,
    jumlah: Int?,
): List<EpisodeDto> {
    val target = jumlah?.coerceAtMost(BATAS_LENGKAPI_EPISODE) ?: return tersimpan
    if (target <= tersimpan.size || tersimpan.isEmpty()) return tersimpan
    val contoh = tersimpan.last()
    val alamat = contoh.videoUrl?.takeIf { it.isNotBlank() } ?: return tersimpan
    val bentuk = alamatEpisode(alamat, contoh.episode) ?: return tersimpan

    val akhiran = idFilm.ifBlank { "anime" }
    val tambahan = (tersimpan.size + 1..target).map { nomor ->
        EpisodeDto(
            id = "$akhiran-e$nomor",
            title = "Episode $nomor",
            season = contoh.season,
            episode = nomor,
            videoUrl = bentuk(nomor),
        )
    }
    return tersimpan + tambahan
}

/** `#<seal>.<episode>.<server>.<audio>` — bentuk alamat pemutar anikai. */
private val POLA_ANIKAI = Regex("""(#[^./]+\.)(\d+)(\.\d+\.[A-Za-z]+)$""")

/**
 * Membentuk "pabrik alamat" untuk episode lain dari SATU alamat contoh.
 *
 * Dua bentuk yang dipakai katalog anime, dan keduanya harus dikenali:
 *   - `.../embed/anime/113415/8`              nomornya segmen jalur terakhir
 *   - `.../watch#<seal>.8.2.sub`              nomornya bagian dari fragmen
 *
 * Mengembalikan null bila alamatnya tidak berbentuk salah satu dari itu —
 * lebih baik menampilkan episode seadanya daripada membentuk alamat tebakan
 * yang membawa penonton ke episode yang salah.
 */
private fun alamatEpisode(contoh: String, nomorContoh: Int): ((Int) -> String)? {
    POLA_ANIKAI.find(contoh)?.let { cocok ->
        val (awal, _, akhir) = cocok.destructured
        val pangkal = contoh.substring(0, cocok.range.first)
        return { nomor -> "$pangkal$awal$nomor$akhir" }
    }
    val ekor = contoh.substringAfterLast('/', "")
    if (ekor.toIntOrNull() == nomorContoh && ekor.isNotEmpty()) {
        val dasar = contoh.substringBeforeLast('/')
        if (dasar.isNotBlank() && dasar != contoh) return { nomor -> "$dasar/$nomor" }
    }
    return null
}

/**
 * Penanda URL sampah: video kampanye anti-judi dan iklan yang disisipkan sumber
 * ke dalam halaman film. URL seperti ini valid secara teknis (benar-benar .mp4),
 * sehingga tanpa penyaringan ini pemutar akan memutarnya seolah-olah film.
 * Catatan: hanya "donasi.assetsy" yang disaring; "poster.assetsy" adalah CDN
 * poster yang sah dan harus tetap lolos.
 */
private val JUNK_URL_MARKERS = listOf(
    "stopjudi", "donasi.assetsy", "judol", "slot-gacor", "anti-judi",
)

internal fun isJunkMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    return JUNK_URL_MARKERS.any { lower.contains(it) }
}

private fun String?.cleanUrlOrNull(): String? = this
    ?.trim()
    ?.takeIf { value ->
        (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) &&
            !isJunkMediaUrl(value)
    }


// --- Lapisan SERVER anime (anikai) ----------------------------------------
//
// Dikunci AniList id LANGSUNG, dan itulah nilainya: lapisan server turunan
// berkunci TMDB id terpaksa dilewati untuk separuh anime karena AniList
// memecah musim sementara TMDB menyatukannya (lihat 3j). Anikai memecah musim
// dengan cara yang sama seperti AniList, jadi tidak ada ambiguitas musim sama
// sekali dan penjaga itu tidak diperlukan di sini.
//
// Isinya satu `seal` per JUDUL, bukan per episode. URL tiap episode dibentuk
// dari `pattern` saat tayang, jadi berapa pun episodenya tetap terjangkau
// tanpa menyimpan satu baris per episode.

@Serializable
data class AnimeServerCatalogDto(
    val player: String = "",
    /** mis. "{player}/watch#{seal}.{episode}.{server}.{audio}". */
    val pattern: String = "",
    val audio: String = "sub",
    /** AniList id (sebagai teks, karena kunci JSON) -> kunci pemutarnya. */
    val titles: Map<String, AnimeServerDto> = emptyMap(),
)

@Serializable
data class AnimeServerDto(
    val seal: String = "",
    /** 0 berarti penyedia menyatakan judul ini belum punya sumber. */
    val server: Int = 0,
    /** Jumlah episode menurut anikai. */
    val eps: Int? = null,
    /**
     * TMDB id judul ini, kalau jembatan judul menemukannya.
     *
     * Ikut di lapisan ini karena katalog anime tidak lagi berid "anilist-",
     * sehingga tabel `watch_aliases` tidak bisa menjangkau anime. Tanpa angka
     * ini anime kehilangan subtitle seluruhnya.
     */
    val tmdbId: Int? = null,
    /**
     * Nomor musim TMDB judul ini, bila bisa ditentukan dengan yakin.
     *
     * Dipakai untuk SUBTITLE, bukan untuk pemutaran. Katalog menomori semua
     * episode anime sebagai musim 1, sehingga anime yang berbagi satu entri
     * TMDB dengan musim lain terpaksa melewatkan subtitle (3j). Dengan angka
     * ini permintaannya bisa menyebut musim yang benar, jadi subtitle —
     * termasuk bahasa Indonesia — akhirnya sampai ke judul-judul itu.
     *
     * Null berarti musimnya tidak dapat dipastikan; penjaga lama tetap
     * berlaku untuk judul seperti itu.
     */
    val season: Int? = null,
)


// --- Lapisan ketersediaan resmi (JustWatch lewat TMDB) ---------------------
//
// Bentuknya SENGAJA beda dari MovieCatalogDto: kuncinya `titles` dengan kamus
// `providers` terpisah, bukan `movies`. Alasannya dua. Pertama, mengulang nama
// penyedia ("Amazon Prime Video") di puluhan ribu entri jauh lebih mahal
// daripada satu kamus 46 baris. Kedua, lapisan ini bukan daftar yang bisa
// diputar di dalam aplikasi — tidak ada videoUrl atau servers di sini sama
// sekali, dan uji kontrak di build-pages.test.mjs menjaga agar tetap begitu.

/** Respons JSON lapisan ketersediaan resmi. */
@Serializable
data class WatchCatalogDto(
    /** Atribusi wajib; ditampilkan di halaman detail. */
    val attribution: String? = null,
    val region: String = "ID",
    /** id penyedia (sebagai teks, karena kunci JSON) -> nama & logo. */
    val providers: Map<String, WatchProviderDto> = emptyMap(),
    val titles: List<WatchTitleDto> = emptyList(),
    /**
     * id katalog -> TMDB id, hasil pencocokan ketat judul+tahun. Inilah yang
     * menjembatani film LK21 berid siput ke lapisan ketersediaan & subtitle.
     */
    val aliases: Map<String, WatchAliasDto> = emptyMap(),
)

@Serializable
data class WatchAliasDto(
    val tmdbId: Int = 0,
    val type: String = "movie",
)

@Serializable
data class WatchProviderDto(
    val name: String = "",
    val logoPath: String? = null,
    val priority: Int? = null,
)

@Serializable
data class WatchTitleDto(
    val id: String = "",
    val tmdbId: Int = 0,
    /** "movie" atau "tv" — ruang id TMDB terpisah untuk keduanya. */
    val type: String = "movie",
    val title: String = "",
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtimeMin: Int? = null,
    /** jenis langganan -> daftar id penyedia, mis. {"flatrate":[8,159]}. */
    val watch: Map<String, List<Int>> = emptyMap(),
    /** Tautan resmi halaman tonton; dibuka DI LUAR aplikasi. */
    val watchLink: String? = null,
)

/** Satu penyedia sah yang siap ditampilkan sebagai chip. */
data class PenyediaTonton(
    val id: Int,
    val nama: String,
    val logoUrl: String?,
    /** "flatrate", "free", "ads", "rent", atau "buy". */
    val jenis: String,
)

/** Ketersediaan resmi satu judul, sudah dipetakan untuk UI. */
data class KetersediaanResmi(
    val tmdbId: Int,
    val judul: String,
    val penyedia: List<PenyediaTonton>,
    val tautan: String?,
    val atribusi: String?,
) {
    val kosong: Boolean get() = penyedia.isEmpty()
}


// --- Subtitle dari OpenSubtitles lewat proxy backend ---------------------
//
// Kunci API TIDAK ada di APK: ia hanya di sisi server, dan APK memanggil
// /api/subtitles. Kunci yang ditanam di APK bisa diambil siapa pun yang
// membongkar berkasnya.

@Serializable
data class SubtitleResponseDto(
    val ok: Boolean = false,
    val tmdbId: Int = 0,
    val subtitles: List<SubtitlePilihanDto> = emptyList(),
    val totalFound: Int = 0,
    val attribution: String? = null,
)

@Serializable
data class SubtitlePilihanDto(
    val fileId: Long = 0,
    val fileName: String? = null,
    val lang: String? = null,
    val release: String? = null,
    val downloads: Int = 0,
    /** Subtitle untuk penonton dengan gangguan pendengaran. */
    val hearingImpaired: Boolean = false,
)

/**
 * Satu deret genre siap tampil. Dulu dibentuk di MoviesScreen dengan
 * mengelompokkan seluruh daftar film di memori; sekarang dibangun dari SQL.
 */
data class DeretGenre(
    val genre: String,
    val film: List<Movie>,
    val jumlah: Int,
)

