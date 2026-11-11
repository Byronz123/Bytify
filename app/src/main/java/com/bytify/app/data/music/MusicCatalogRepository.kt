package com.bytify.app.data.music

import android.util.Log
import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.ArtistStat
import com.bytify.app.data.db.CatalogSongEntity
import com.bytify.app.data.db.SongEntity
import com.bytify.app.data.model.Song
import com.bytify.app.data.prefs.SettingsRepository
import com.bytify.app.data.remote.ChartsRepository
import com.bytify.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

private const val TAG = "BytifyKatalogLagu"

const val SUMBER_APPLE = "apple"
const val SUMBER_YOUTUBE = "youtube"

/**
 * Peringkat awal untuk lagu dari sumber pelengkap. Dimulai di atas 100 supaya
 * peringkat tangga Apple (1..100) tetap memimpin sinyal popularitas.
 */
private const val PERINGKAT_DASAR_YOUTUBE = 300

/**
 * Kueri benih untuk memanen YouTube: genre, suasana, bahasa, dan era. Dipilih
 * agar katalog melebar ke musik yang tidak pernah masuk tangga Apple —
 * terutama musik Indonesia daerah dan genre niche.
 */
val BENIH_YOUTUBE = listOf(
    "pop indonesia terbaru", "dangdut koplo terbaru", "campursari", "keroncong",
    "tembang kenangan indonesia", "lagu daerah indonesia", "sholawat terbaru",
    "nasyid islami", "lagu rohani kristen", "pop sunda", "pop batak", "pop minang",
    "pop jawa", "lagu ambon", "lagu manado", "indie indonesia", "rock indonesia",
    "slow rock indonesia", "akustik indonesia", "lagu galau indonesia",
    "hip hop indonesia", "rap indonesia", "jazz indonesia", "reggae indonesia",
    "lagu anak indonesia", "lagu pernikahan indonesia",
    "kpop hits", "jpop hits", "mandopop", "cantopop", "thai pop", "vietnamese pop",
    "malaysia pop", "bollywood songs", "arabic pop", "turkish pop", "latin pop",
    "afrobeats", "reggaeton", "k indie",
    "pop hits", "rock classics", "hip hop hits", "rnb soul", "country hits",
    "edm festival", "house music", "techno", "trance", "lofi hip hop",
    "jazz standards", "blues classics", "classical music", "piano instrumental",
    "metal classics", "punk rock", "funk disco", "gospel music", "soundtrack film",
    "lagu hits 2020", "lagu hits 2021", "lagu hits 2022", "lagu hits 2023",
    "lagu hits 2024", "lagu hits 2025", "lagu hits 2026",
)

/** Jeda antar kueri YouTube; pencarian NewPipe jauh lebih berat dari RSS. */
private const val JEDA_ANTAR_BENIH_MS = 800L

/** Batas waktu satu pencarian YouTube. */
private const val BATAS_PER_BENIH_MS = 25_000L

/** Satu baris mix personal di Beranda: judul dan isi lagunya. */
data class MixLagu(val judul: String, val lagu: List<Song>)

/** Katalog lagu disegarkan sekali sehari; tangga Apple berubah harian. */
const val USIA_KATALOG_LAGU_MS = 24L * 60 * 60 * 1000

/** Penanda katalog masih segar sehingga sinkronisasi dilewati. */
const val KATALOG_LAGU_MASIH_SEGAR = -1

/**
 * Negara tangga Apple Music yang dipanen. Makin banyak negara makin lengkap
 * katalognya; yang gagal (batas laju) dilewati dan diisi pada sinkron berikutnya
 * karena hasilnya menumpuk di Room, bukan ditulis ulang.
 */
val NEGARA_TANGGA = listOf(
    // Pasar utama & Asia Tenggara lebih dulu: paling relevan bagi pengguna.
    "id", "my", "sg", "th", "ph", "vn", "us", "gb", "jp", "kr",
    "in", "au", "ca", "de", "fr", "it", "es", "br", "mx", "nl",
    "se", "no", "dk", "fi", "pl", "tr", "sa", "ae", "hk", "tw",
    "nz", "za", "ng", "eg", "pt", "ie", "ch", "at", "be", "cl",
    // Pasar tambahan: memperluas ke musik lokal yang tak masuk tangga besar.
    "ar", "co", "pe", "uy", "py", "bo", "ec", "ve", "cr", "pa",
    "do", "gt", "hn", "ni", "sv", "jm", "tt", "bb", "bs", "bz",
    "cz", "sk", "hu", "ro", "bg", "hr", "si", "rs", "gr", "cy",
    "ee", "lv", "lt", "ua", "kz", "uz", "md", "mk", "mt", "is",
    "il", "jo", "lb", "kw", "qa", "bh", "om", "ma", "dz", "tn",
    "ke", "gh", "tz", "ug", "zm", "zw", "mz", "bw", "na", "sn",
    "pk", "lk", "np", "bd", "kh", "la", "mn", "mo", "bn", "fj",
)

/**
 * Jeda antar negara supaya tidak kena pembatasan laju Apple RSS. Dibuat longgar
 * dengan sengaja: panen hanya sekali sehari di latar belakang, dan jeda 350 ms
 * terbukti membuat 25 dari 40 negara ditolak.
 */
private const val JEDA_ANTAR_NEGARA_MS = 1_200L

/** Jeda lebih longgar untuk putaran ulang negara yang tadi ditolak. */
private const val JEDA_ULANG_MS = 3_000L

/**
 * Batas waktu per negara. OkHttp hanya membatasi connect/read, bukan keseluruhan
 * panggilan, sehingga koneksi yang macet pernah menggantung panen tanpa batas.
 * Batas di level coroutine ini menjamin panen selalu maju.
 */
private const val BATAS_PER_NEGARA_MS = 35_000L

/**
 * Memisahkan artis utama dari string gabungan Apple ("A, B & C", "A feat. B")
 * lalu menormalkannya, agar selera pengguna bisa dicocokkan antar lagu.
 */
/** Membuang tanda baca, kurung, dan beda huruf agar judul/artis bisa dibandingkan. */
private fun normalkan(teks: String): String = teks
    .lowercase()
    .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
    .trim()

/**
 * Kunci anti-duplikat sebuah lagu. "Judul (feat. X)" dan "judul" dari sumber
 * berbeda menghasilkan kunci sama, sehingga lagu tidak tersimpan dua kali.
 */
fun kunciLagu(title: String, artist: String): String =
    "${normalkan(title)}|${normalkan(kunciArtis(artist))}"

fun kunciArtis(artist: String): String {
    val utama = artist.split(
        Regex("""\s*(,|&|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE),
    ).firstOrNull().orEmpty()
    return utama.trim().lowercase()
}

/**
 * Katalog lagu lokal dan mesin rekomendasi yang belajar dari kebiasaan dengar.
 *
 * Katalog dipanen dari tangga Apple Music banyak negara lalu disimpan di Room,
 * sehingga jumlah lagu jauh melampaui satu tangga saja dan tetap tersedia offline.
 * [rekomendasi] menilai setiap lagu katalog memakai profil selera yang dihitung
 * ulang otomatis setiap riwayat putar berubah.
 */
class MusicCatalogRepository(
    private val dao: BytifyDao,
    private val charts: ChartsRepository,
    private val youtube: YouTubeRepository,
    private val settings: SettingsRepository,
) {
    /** Benih acak tetap selama sesi: mix berubah tiap buka aplikasi, bukan tiap gambar ulang. */
    private val benihSesi: Long = System.currentTimeMillis()

    /** Seluruh katalog, terurut populer. */
    val catalogSongs: Flow<List<Song>> = dao.catalogSongs()
        .map { rows -> rows.map { it.toSong() } }
        .flowOn(Dispatchers.Default)

    suspend fun jumlahLagu(): Int = dao.catalogSongCount()

    /**
     * Segarkan hanya bila panen terakhir yang **tuntas** sudah lebih tua dari
     * [maksimalUsiaMs].
     *
     * Sengaja memakai penanda "selesai", bukan stempel waktu baris katalog:
     * panen menulis bertahap, jadi panen yang terputus di tengah (aplikasi
     * ditutup, baterai habis) meninggalkan baris-baris baru yang membuat
     * katalog terlihat segar dan sisa pekerjaannya terlewat sampai 24 jam.
     */
    suspend fun syncIfStale(maksimalUsiaMs: Long = USIA_KATALOG_LAGU_MS): Int {
        val selesaiTerakhir = runCatching { settings.settings.first().katalogLaguSelesai }.getOrDefault(0L)
        if (selesaiTerakhir > 0L && System.currentTimeMillis() - selesaiTerakhir < maksimalUsiaMs) {
            return KATALOG_LAGU_MASIH_SEGAR
        }
        return sync()
    }

    /**
     * Panen tangga seluruh [NEGARA_TANGGA] satu per satu. Negara yang gagal
     * dilewati tanpa menggagalkan yang lain; peringkat terbaik dipertahankan
     * bila satu lagu muncul di beberapa negara.
     *
     * Hasil tiap negara langsung ditulis ke Room, bukan ditahan sampai akhir:
     * lagu mulai tampil dalam hitungan detik, dan panen yang terputus di tengah
     * tidak membuang apa yang sudah didapat.
     */
    suspend fun sync(): Int {
        val now = System.currentTimeMillis()
        val terbaik = HashMap<String, CatalogSongEntity>()
        val berhasil = HashSet<String>()

        suspend fun panen(negara: String, jeda: Long) {
            // Dua umpan per negara: tangga lagu dan tangga video musik. Isinya
            // banyak yang berbeda, dan inilah yang melipatgandakan katalog.
            val lagu = runCatching {
                withTimeoutOrNull(BATAS_PER_NEGARA_MS) {
                    val a = charts.topSongs(negara)
                    val b = runCatching { charts.topMusicVideos(negara) }.getOrDefault(emptyList())
                    a + b
                }
            }.getOrNull()
            if (lagu == null) {
                Log.w(TAG, "tangga $negara dilewati (gagal/terlalu lama)")
            } else {
                berhasil += negara
                val batch = ArrayList<CatalogSongEntity>()
                lagu.forEachIndexed { index, song ->
                    val peringkat = index + 1
                    val kunci = kunciLagu(song.title, song.artist)
                    val lama = terbaik[kunci]
                    if (lama == null || peringkat < lama.rank) {
                        val baris = CatalogSongEntity(
                            kunci = kunci,
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            artistKey = kunciArtis(song.artist),
                            thumbnailUrl = song.thumbnailUrl,
                            rank = peringkat,
                            sumber = SUMBER_APPLE,
                            updatedAt = now,
                        )
                        terbaik[kunci] = baris
                        batch += baris
                    }
                }
                if (batch.isNotEmpty()) runCatching { dao.upsertCatalogSongs(batch) }
                Log.i(TAG, "tangga $negara: +${batch.size} baru, total ${terbaik.size}")
            }
            delay(jeda)
        }

        for (negara in NEGARA_TANGGA) panen(negara, JEDA_ANTAR_NEGARA_MS)

        // Putaran ulang: negara yang tadi ditolak dicoba sekali lagi dengan jeda
        // lebih longgar, karena penolakan hampir selalu soal batas laju sesaat.
        val sisa = NEGARA_TANGGA.filterNot { it in berhasil }
        if (sisa.isNotEmpty()) {
            Log.i(TAG, "mengulang ${sisa.size} negara yang ditolak")
            for (negara in sisa) panen(negara, JEDA_ULANG_MS)
        }

        Log.i(TAG, "panen Apple selesai: ${berhasil.size}/${NEGARA_TANGGA.size} negara, ${terbaik.size} lagu")

        val dariYouTube = panenYouTube()
        val total = runCatching { dao.catalogSongCount() }.getOrDefault(terbaik.size)
        // Penanda ini baru ditulis di sini: hanya panen yang sampai ujung yang
        // dianggap tuntas, sehingga panen terputus akan dilanjutkan, bukan dilewati.
        runCatching { settings.tandaiKatalogLaguSelesai() }
        Log.i(TAG, "panen selesai: Apple ${terbaik.size} + YouTube $dariYouTube, katalog $total lagu")
        return total
    }

    /**
     * Melebarkan katalog lewat pencarian YouTube (NewPipe, tanpa kunci API).
     * Hanya menambah lagu yang kuncinya belum ada, jadi peringkat populer dari
     * Apple tidak tertimpa. Kueri yang gagal dilewati tanpa menggagalkan panen.
     */
    private suspend fun panenYouTube(): Int {
        val now = System.currentTimeMillis()
        var ditambah = 0
        BENIH_YOUTUBE.forEachIndexed { urutan, benih ->
            val hasil = runCatching {
                withTimeoutOrNull(BATAS_PER_BENIH_MS) { youtube.searchSongs(benih) }
            }.getOrNull()
            if (hasil.isNullOrEmpty()) {
                Log.w(TAG, "benih \"$benih\" dilewati")
            } else {
                val baris = hasil.asSequence()
                    .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                    .map { song ->
                        CatalogSongEntity(
                            kunci = kunciLagu(song.title, song.artist),
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            artistKey = kunciArtis(song.artist),
                            thumbnailUrl = song.thumbnailUrl,
                            rank = PERINGKAT_DASAR_YOUTUBE + urutan,
                            sumber = SUMBER_YOUTUBE,
                            updatedAt = now,
                        )
                    }
                    .distinctBy { it.kunci }
                    .toList()
                if (baris.isNotEmpty()) {
                    runCatching { dao.insertCatalogSongsIfAbsent(baris) }
                        .onSuccess { ditambah += baris.size }
                }
                Log.i(TAG, "benih \"$benih\": ${baris.size} lagu")
            }
            delay(JEDA_ANTAR_BENIH_MS)
        }
        return ditambah
    }

    /**
     * Rekomendasi yang mengikuti selera pengguna dan terus menyesuaikan.
     *
     * Profil selera dibangun dari bobot waktu dengar per artis (90 hari
     * terakhir) ditambah bonus untuk artis dari lagu yang disukai. Tiap lagu
     * katalog dinilai: kecocokan artis (bobot terbesar), popularitas tangga
     * (agar pengguna baru tetap dapat hasil bagus), dan penalti bila baru saja
     * diputar supaya daftar tidak itu-itu saja. Karena sumbernya Flow Room,
     * nilai dihitung ulang sendiri setiap ada putaran atau suka baru.
     */
    fun rekomendasi(limit: Int = 50, jendelaHari: Int = 90): Flow<List<Song>> {
        val sejak = System.currentTimeMillis() - jendelaHari * 24L * 60 * 60 * 1000
        return combine(
            dao.catalogSongs(),
            dao.topArtists(sejak, 50),
            dao.likedSongs(),
            dao.recentlyPlayedIds(80),
        ) { katalog, artisTeratas, disukai, baruDiputar ->
            if (katalog.isEmpty()) return@combine emptyList()
            val bobot = profilSelera(artisTeratas, disukai)
            val baru = baruDiputar.toHashSet()
            katalog.asSequence()
                .map { it to skorLagu(it, bobot, baru) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first.toSong() }
                .toList()
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Baris-baris mix untuk Beranda, bergaya Spotify: bukan daftar peringkat,
     * melainkan beberapa campuran yang isinya diacak dan dipilih dari selera
     * pengguna — mix utama, mix per artis favorit, lalu penemuan acak.
     *
     * Acaknya memakai benih tetap per sesi, jadi urutan berubah tiap aplikasi
     * dibuka tetapi tidak melompat-lompat saat layar digambar ulang.
     */
    fun mixPersonal(isiPerMix: Int = 20, jendelaHari: Int = 90): Flow<List<MixLagu>> {
        val sejak = System.currentTimeMillis() - jendelaHari * 24L * 60 * 60 * 1000
        return combine(
            dao.catalogSongs(),
            dao.topArtists(sejak, 20),
            dao.likedSongs(),
            dao.recentlyPlayedIds(80),
        ) { katalog, artisTeratas, disukai, baruDiputar ->
            if (katalog.isEmpty()) return@combine emptyList()
            val acak = Random(benihSesi)
            val bobot = profilSelera(artisTeratas, disukai)
            val baru = baruDiputar.toHashSet()
            val hasil = ArrayList<MixLagu>()
            val sudahDipakai = HashSet<String>()

            // 1. Mix utama: ambil kandidat terbaik lalu acak urutannya supaya
            //    tidak terasa seperti tangga lagu bernomor.
            val utama = katalog.asSequence()
                .map { it to skorLagu(it, bobot, baru) }
                .sortedByDescending { it.second }
                .take(isiPerMix * 3)
                .map { it.first }
                .toList()
                .shuffled(acak)
                .take(isiPerMix)
            if (utama.isNotEmpty()) {
                hasil += MixLagu(if (bobot.isEmpty()) "Mix pembuka" else "Mix untukmu", utama.map { it.toSong() })
                sudahDipakai += utama.map { it.kunci }
            }

            // 2. Mix per artis yang paling sering didengar.
            val perArtis = katalog.groupBy { it.artistKey }
            artisTeratas.take(6).forEach { stat ->
                if (hasil.size >= 4) return@forEach
                val isi = perArtis[kunciArtis(stat.artist)].orEmpty()
                    .filter { it.kunci !in sudahDipakai }
                if (isi.size >= 4) {
                    val dipilih = isi.shuffled(acak).take(isiPerMix)
                    hasil += MixLagu("Mix ${stat.artist}", dipilih.map { it.toSong() })
                    sudahDipakai += dipilih.map { it.kunci }
                }
            }

            // 3. Penemuan acak: memperluas selera, bukan mengulang yang itu-itu.
            val penemuan = katalog
                .filter { it.kunci !in sudahDipakai && it.id !in baru }
                .shuffled(acak)
                .take(isiPerMix)
            if (penemuan.isNotEmpty()) {
                hasil += MixLagu("Penemuan acak", penemuan.map { it.toSong() })
            }
            hasil
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Lagu lanjutan untuk antrean pemutar, menyesuaikan selera pengguna dan
     * lagu yang sedang diputar. Dipakai saat YouTube "related" tidak tersedia —
     * misalnya lagu dari tangga Apple yang id-nya masih berupa kueri pencarian.
     *
     * Lagu dari artis yang sama dengan [seed] diberi dorongan agar nuansanya
     * nyambung, sisanya mengikuti profil selera.
     */
    suspend fun lanjutan(seed: Song?, limit: Int = 20, jendelaHari: Int = 90): List<Song> =
        withContext(Dispatchers.Default) {
            val sejak = System.currentTimeMillis() - jendelaHari * 24L * 60 * 60 * 1000
            val katalog = runCatching { dao.catalogSongs().first() }.getOrDefault(emptyList())
            if (katalog.isEmpty()) return@withContext emptyList()
            val artisTeratas = runCatching { dao.topArtists(sejak, 50).first() }.getOrDefault(emptyList())
            val disukai = runCatching { dao.likedSongs().first() }.getOrDefault(emptyList())
            val baru = runCatching { dao.recentlyPlayedIds(80).first() }.getOrDefault(emptyList()).toHashSet()

            val bobot = profilSelera(artisTeratas, disukai)
            val artisBenih = seed?.artist?.let(::kunciArtis)
            val kunciBenih = seed?.let { kunciLagu(it.title, it.artist) }

            katalog.asSequence()
                .filter { it.kunci != kunciBenih }
                .map { lagu ->
                    val dorongan = if (artisBenih != null && lagu.artistKey == artisBenih) 2.5 else 0.0
                    lagu to skorLagu(lagu, bobot, baru) + dorongan
                }
                .sortedByDescending { it.second }
                .take(limit * 3)
                .map { it.first }
                .toList()
                .shuffled(Random(System.currentTimeMillis()))
                .take(limit)
                .map { it.toSong() }
        }

    /**
     * Bobot selera per artis: porsi waktu dengar 90 hari terakhir (0..1),
     * ditambah bonus tetap untuk artis dari lagu yang disukai.
     */
    private fun profilSelera(
        artisTeratas: List<ArtistStat>,
        disukai: List<SongEntity>,
    ): Map<String, Double> {
        val msMaks = artisTeratas.maxOfOrNull { it.totalMs }?.takeIf { it > 0 } ?: 1L
        val bobot = HashMap<String, Double>()
        artisTeratas.forEach { stat ->
            bobot[kunciArtis(stat.artist)] = stat.totalMs.toDouble() / msMaks
        }
        disukai.forEach { lagu ->
            val k = kunciArtis(lagu.artist)
            bobot[k] = (bobot[k] ?: 0.0) + 0.5
        }
        return bobot
    }

    /**
     * Nilai satu lagu: kecocokan artis paling berbobot, popularitas tangga jadi
     * penyeimbang (dan pemimpin saat pengguna belum punya riwayat), lalu penalti
     * bila lagu baru saja diputar supaya tidak berulang.
     */
    private fun skorLagu(
        lagu: CatalogSongEntity,
        bobot: Map<String, Double>,
        baruDiputar: Set<String>,
    ): Double {
        val afinitas = (bobot[lagu.artistKey] ?: 0.0).coerceAtMost(1.5)
        val populer = ((101 - lagu.rank).coerceAtLeast(0)) / 100.0
        val dasar = if (bobot.isEmpty()) populer else 3.0 * afinitas + 0.6 * populer
        return dasar - if (lagu.id in baruDiputar) 2.0 else 0.0
    }
}
