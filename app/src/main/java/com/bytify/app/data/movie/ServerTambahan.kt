package com.bytify.app.data.movie

/**
 * Server embed tambahan yang DITURUNKAN dari TMDB id, bukan diterbitkan.
 *
 * **Kenapa tidak ada berkas katalog untuk ini.** Kelima penyedia di bawah
 * dijangkau lewat rute yang hanya berisi TMDB id, jadi URL-nya deterministik.
 * Menerbitkannya sebagai berkas berarti menuliskan lima URL untuk tiap judul —
 * sekitar 300 bait x 32.024 judul yang punya alias = ~9 MiB berkas baru, plus
 * tabel Room baru, plus kolom baru — semuanya hanya untuk menyimpan sesuatu
 * yang bisa dihitung ulang dari satu bilangan. Bytify SUDAH punya TMDB id itu:
 * `WatchRepository.tmdbUntuk()` memulangkannya untuk 32.024 judul (20.145 LK21,
 * 7.724 anime, 4.155 serial) lewat tabel `watch_aliases`. Jadi lapisan ini
 * nol bait terbitan, nol tabel baru, nol baris baru di `movies`.
 *
 * **Penyedia ini TIDAK dijamin punya sumber untuk tiap judul.** Itu bukan
 * kekurangan yang harus ditutupi, melainkan alasan failover otomatis ada:
 * terukur pada keluaran scraper cinevo, keempat penyedia itu dipasang ke
 * SEMUA 50.072 entri tanpa satu pun diverifikasi — daftarnya dibangun dari
 * template, bukan hasil panen. Pemutar karena itu menelusuri daftar ini dan
 * memakai yang pertama benar-benar berputar.
 *
 * **Urutannya DIUKUR, bukan ditebak.** `backend/scraper/probe_servers.py`
 * memeriksa tiap penyedia terhadap 60 TMDB id nyata dari katalog kita sendiri:
 *
 * | penyedia            | kerangka pemutar | catatan                       |
 * |---------------------|------------------|-------------------------------|
 * | cinesrc.st          | 40/40            | HTTP 200; TERBUKTI berputar   |
 * | vidlink.pro         | 60/60            | HTTP 200; TERBUKTI berputar   |
 * | vidcore.io          | 60/60            | HTTP 200, tetapi TIDAK pernah |
 * |                     |                  | selesai memuat sumbernya      |
 * | vidsrc.cc           | 0/60             | HTTP 522 — origin-nya mati    |
 * | player.autoembed.cc | 0/60             | DNS tidak resolve sama sekali |
 *
 * `player.autoembed.cc` karena itu TIDAK dipasang: domainnya tidak ada, dan
 * nama yang tidak resolve di jaringan juga tidak akan resolve di WebView —
 * menyertakannya hanya menambah satu siklus gagal untuk setiap judul.
 * `vidcore.io` DIBUANG, bukan diturunkan. Alasannya terukur di Galaxy A36:
 * ia menyajikan kerangka pemutar (200), jadi pemutar menunggunya sampai ambang
 * habis, lalu yang tampil layar HITAM dengan kontrolnya sendiri tergambar di
 * LUAR kotak video. Di peramban, halaman yang sama berhenti di
 * "FETCHING YOUR CONTENT..." lebih dari 35 detik dengan `readyState` 0 dan nol
 * permintaan ke API sumbernya, bahkan setelah diketuk. Server yang tidak pernah
 * memutar apa pun bukan cadangan — ia hanya menunda server berikutnya.
 *
 * `vidsrc.cc` ditaruh PALING AKHIR, bukan dibuang: 522 adalah origin yang
 * sedang tumbang di belakang Cloudflare, dan itu bisa pulih. Jalankan ulang
 * probe-nya untuk menyegarkan urutan ini.
 *
 * **`vidcore.io` turun ke bawah `vidlink` dan `cinesrc` karena 200-nya
 * menipu.** Diamati langsung di peramban pada `vidcore.io/movie/27205`:
 * halaman berhenti di "FETCHING YOUR CONTENT..." selama lebih dari 35 detik,
 * `readyState` tetap 0, tanpa satu pun permintaan ke API sumbernya, dan satu
 * ketukan tidak mengubah apa pun. Itulah persis batas yang disebut di bawah —
 * kerangka pemutar yang tersaji bukan bukti ada yang bisa diputar.
 *
 * Catatan penting tentang angka di atas: probe dari sisi server hanya
 * membuktikan penyedia MENERIMA id itu dan menyajikan kerangka pemutar. Aliran
 * sebenarnya diresolusi JavaScript di dalam iframe, jadi 60/60 BUKAN jaminan
 * 100% judul berputar — itu syarat perlu. Pembuktian pemutaran hanya bisa dari
 * perangkat sungguhan.
 */

/** Satu rute embed: pola untuk film, dan pola untuk episode serial. */
private class RuteEmbed(
    val nama: String,
    val film: (Int) -> String,
    val serial: (Int, Int, Int) -> String,
)

private val RUTE = listOf(
    RuteEmbed(
        // DIUKUR di peramban, bukan diukur dari kode status saja: film
        // `/embed/movie/27205` dan episode `/embed/tv/1396?s=1&e=1` sama-sama
        // mencapai `readyState 4` dengan sumber `blob:` setelah satu ketukan.
        //
        // Ditemukan lewat watchvanta.xyz dan stcine.com, yang keduanya ternyata
        // hanya KULIT: watchvanta menyemat `cinesrc.st/embed/movie/<tmdbId>`,
        // dan stcine menyemat `apiplayer.ru/embed/movie/<tmdbId>`. Yang dipakai
        // di sini pemutarnya langsung, bukan kulitnya, karena kulit itu aplikasi
        // satu halaman: `stcine.com/player?id=27205&type=movie` dibuka dingin
        // hanya memulangkan beranda kosong, dan `watchvanta.xyz/movie/<id>`
        // butuh dua ketukan sebelum pemutarnya muncul.
        //
        // `apiplayer.ru` TIDAK dipasang: dibuka langsung ia menjawab
        // "Verification Failed", jadi ia memeriksa siapa yang menyematnya.
        // Memalsukan pemeriksaan itu berarti berpura-pura menjadi stcine.
        "cinesrc",
        { id -> "https://cinesrc.st/embed/movie/$id" },
        { id, musim, episode -> "https://cinesrc.st/embed/tv/$id?s=$musim&e=$episode" },
    ),
    RuteEmbed(
        "vidlink",
        { id -> "https://vidlink.pro/movie/$id" },
        { id, musim, episode -> "https://vidlink.pro/tv/$id/$musim/$episode" },
    ),
    RuteEmbed(
        "vidsrc",
        { id -> "https://vidsrc.cc/v2/embed/movie/$id" },
        { id, musim, episode -> "https://vidsrc.cc/v2/embed/tv/$id/$musim/$episode" },
    ),
)

/**
 * Server tambahan untuk satu judul berkunci TMDB id.
 *
 * [musim] dan [episode] hanya dipakai bila tipenya "tv". Pemanggil WAJIB sudah
 * memastikan penomoran musimnya tidak ambigu — lihat
 * `WatchRepository.amanSubtitlePerEpisode()`. Alasannya sama persis dengan
 * subtitle: katalog kita menomori SEMUA episode sebagai musim 1, jadi untuk
 * judul yang berbagi satu entri TMDB dengan musim lain, "musim 1 episode 5"
 * menunjuk episode yang SALAH. Film yang salah lebih buruk daripada film yang
 * tidak diputar, jadi untuk judul seperti itu daftar ini dikosongkan.
 */
fun serverTambahan(
    tmdbId: Int,
    tipe: String,
    musim: Int? = null,
    episode: Int? = null,
): List<MovieServerDto> {
    if (tmdbId <= 0) return emptyList()
    if (tipe == "tv") {
        val m = musim?.takeIf { it > 0 } ?: return emptyList()
        val e = episode?.takeIf { it > 0 } ?: return emptyList()
        return RUTE.map { MovieServerDto(server = it.nama, url = it.serial(tmdbId, m, e)) }
    }
    return RUTE.map { MovieServerDto(server = it.nama, url = it.film(tmdbId)) }
}

/**
 * Id anikai dari id katalog anime ("anikai-7696" -> 7696).
 *
 * Katalog anime berpindah dari ruang id AniList ke ruang id anikai, karena
 * anikai yang memutarnya. Id "anilist-" lama tidak dikenali lagi — barisnya
 * dibersihkan `MovieRepository` saat sinkron.
 */
fun anikaiIdDari(idFilm: String): Int? {
    if (!idFilm.startsWith("anikai-")) return null
    return idFilm.removePrefix("anikai-").substringBefore('-').toIntOrNull()
        ?.takeIf { it > 0 }
}

/**
 * Server anikai untuk satu episode anime.
 *
 * URL-nya dibentuk dari `pattern` yang ikut diterbitkan bersama lapisannya,
 * bukan dari pola yang ditanam di sini — kalau penyedia mengubah bentuk
 * alamatnya, yang perlu diterbitkan ulang cukup berkasnya, tanpa APK baru.
 *
 * Mengembalikan null bila judulnya tidak ada di lapisan itu, atau bila
 * penyedia menyatakan judulnya belum punya sumber (`server` 0).
 */
fun serverAnikai(
    katalog: AnimeServerCatalogDto,
    anikaiId: Int,
    episode: Int,
): MovieServerDto? {
    val entri = katalog.titles[anikaiId.toString()] ?: return null
    if (entri.seal.isBlank() || entri.server <= 0) return null
    if (katalog.player.isBlank() || katalog.pattern.isBlank()) return null
    val url = katalog.pattern
        .replace("{player}", katalog.player)
        .replace("{seal}", entri.seal)
        .replace("{episode}", episode.coerceAtLeast(1).toString())
        .replace("{server}", entri.server.toString())
        .replace("{audio}", katalog.audio.ifBlank { "sub" })
    return MovieServerDto(server = "anikai", url = url)
}
