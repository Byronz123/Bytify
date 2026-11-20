# Bytify — panduan kerja

Bytify adalah pemutar media Android open-source (Kotlin + Jetpack Compose) yang
mengagregasi situs streaming Indonesia LK21, ditambah podcast, radio, dan Live TV.

- Repo: `Byronz123/Bytify`
- Folder kerja: `E:\Portofolio\Karya\Bytify`
- Branch aktif: `feature/complete-1.3.0`
- **Balas dalam Bahasa Indonesia.**

Kamu punya shell. Jalankan sendiri build, scraper, dan adb; jangan menyerahkan
perintah ke saya untuk dijalankan manual kecuali memang butuh GUI.

---

## Isi katalog saat ini

| Bagian | Jumlah | Catatan |
|---|---|---|
| Film | 25.135 | 7 sections, 100% punya server |
| Serial | 4.919 | 65.296 episode; berkas terbit TERPISAH; 2.486 dapat chip |
| Anime | 8.007 | 140.002 episode (72.547 tersimpan, cap 14); id & pemutar ANIKAI |
| Kanal Live TV | 8.681 | 180 negara, semua HTTPS |
| Katalog lagu | ~5.000 | di Room perangkat, bukan berkas |
| Ketersediaan resmi | lihat 3e | judul + layanan sah di Indonesia; TANPA URL stream |
| Podcast teratas | 100 | batas Apple RSS |
| Stasiun radio | 500 | per kueri, per negara; lintas dunia |

---

## Peta berkas

```
backend/scraper/crawler.py             penjelajah daftar film. TANPA Selenium —
                                       halaman daftar LK21 dirender server, jadi
                                       cukup HTTP. Kategori ditemukan sendiri dari
                                       halaman depan (62: genre+tahun+negara).
backend/scraper/ultimate_scraper.py    Selenium + undetected-chromedriver;
                                       metadata & daftar server dari halaman film
backend/scraper/build_database.py      bytify_database.json -> catalog.json (ramping)
backend/scraper/tv_garden_scraper.py   katalog Live TV dari API iptv-org (bukan
                                       lagi mengikis tv.garden dengan Selenium)
backend/scraper/series_scraper.py        penjelajah + pemanen SERIAL. Serial TIDAK ada
                                       di domain film: /series & /nontondrama di
                                       LK21 cuma halaman pengalih. Katalog serial
                                       yang asli ada di tv.nontondrama.vip, dengan
                                       struktur kategori yang sama. Tanpa Selenium.
                                       Mode "segarkan" dipakai daemon.
backend/scraper/build_series_catalog.py  series_database.json -> series_catalog.json
backend/scraper/anilist_anime.py       pemanen metadata+AniList id dari API
                                       GraphQL AniList; dipecah per tahun
backend/scraper/build_anime_catalog.py   anime_database.json -> anime_catalog.json;
                                       embed framextv per AniList id
backend/scraper/series_links.txt         5.143 URL serial hasil sapuan
backend/scraper/tmdb_watch.py          pemanen KETERSEDIAAN RESMI per judul dari
                                       TMDB (data JustWatch), wilayah ID; satu
                                       permintaan sekalian membawa runtime, imdb_id,
                                       dan genre ASLI. Bisa dilanjutkan.
backend/scraper/build_watch_catalog.py   watch_database.json -> watch_catalog.json
backend/scraper/anilist_tmdb_map.py    AniList id -> TMDB id lewat dataset
                                       pemetaan publik, diverifikasi ulang.
                                       Fase: --buat-daftar, --judul, --verifikasi
backend/scraper/tmdb_match.py          judul katalog LK21 -> TMDB id, KETAT:
                                       judul ternormalkan sama persis + tahun ±1,
                                       yang ambigu dilewati. Bisa dilanjutkan.
backend/api/src/subtitles.mjs          proxy OpenSubtitles; kunci hanya di server
backend/scraper/anikai_servers.py      panen SERVER anime dari anikai.org lewat
                                       HTTP biasa (situsnya statis); satu seal
                                       per judul. Ada kunci proses tunggal.
backend/scraper/build_anime_servers.py anikai_database.json -> anime_servers.json;
                                       jembatan judul -> AniList id, KETAT
backend/scraper/probe_servers.py       mengukur penyedia embed berkunci TMDB id
                                       mana yang betul menjawab; menentukan
                                       URUTAN RUTE di ServerTambahan.kt
backend/scraper/fetch_subtitles.py     OpenSubtitles + TMDB -> backend/data/subtitles/
backend/scraper/auto_sync_daemon.py    crawl -> build -> Live TV -> commit & push
backend/scraper/update_bytify.bat      pembungkus daemon; menyetel PYTHONIOENCODING
backend/scraper/movie_links.txt        25.204 URL film hasil sapuan

backend/data/bytify_database.json      hasil scraping, 25.135 entri (38 MB);
                                       TIDAK dilacak git. CATATAN: berkas ini
                                       direkonstruksi dari catalog.json setelah
                                       sempat terhapus, jadi sinopsis entri lama
                                       sudah terpotong. Sinopsis utuh tetap sampai
                                       ke pengguna lewat enrichIfNeeded.
                                       Cadangan lama di
                                       E:\Portofolio\Karya\bytify-cadangan\
backend/data/catalog.json              yang diunduh APK: 23,26 MiB
backend/data/series_database.json      hasil panen serial, 4.919 entri (17,8 MB);
                                       TIDAK dilacak git
backend/data/series_catalog.json       yang diunduh APK: 12,71 MiB
backend/data/watch_database.json       hasil panen ketersediaan; TIDAK dilacak git
backend/data/watch_catalog.json        yang diunduh APK: lapisan ketersediaan resmi
backend/data/live_tv_database.json     katalog kanal: 1,99 MiB

backend/api/                           API Node (server.mjs, src/movies.mjs, src/live.mjs)
backend/api/scripts/build-pages.mjs    salin catalog.json APA ADANYA + bangun Live TV
backend/api/test/build-pages.test.mjs  uji kontrak: sections, jumlah film, UKURAN
functions/                             Cloudflare Pages Functions
.github/workflows/deploy-pages.yml     terbit otomatis saat catalog berubah

app/src/main/java/com/bytify/app/
  AppContainer.kt                      autoSyncKatalogFilm/TanggaLagu/KatalogLagu
  data/movie/MovieCatalogApi.kt        pengunduh katalog (decodeFromStream)
  data/movie/MovieRepository.kt        refreshIfStale(), TTL 3 jam
  data/music/MusicCatalogRepository.kt katalog lagu + mesin rekomendasi selera
  data/live/LiveRepository.kt          kanal Live TV (flowOn Default)
  data/live/LiveCatalogApi.kt          pengunduh kanal (decodeFromStream)
  data/remote/ChartsRepository.kt      tangga lagu & album Apple RSS, TTL 3 jam
  data/movie/WatchRepository.kt         ketersediaan resmi; akses BERTITIK, tanpa Flow penuh
  data/movie/ServerTambahan.kt          server embed DITURUNKAN dari TMDB id;
                                        tanpa berkas terbitan, tanpa tabel baru
  data/db/BytifyDatabase.kt            Room v11, MIGRATION_8_9, 9_10 & 10_11
  ui/screens/MoviePlayerScreen.kt      WebView, bypass gerbang iklan, subtitle
  ui/screens/HomeScreen.kt             mix personal acak, album, grid 2 baris
  ui/components/BottomBars.kt          navigasi indeks pecahan mengikuti jari
  ui/BytifyApp.kt                      LAPISAN TAB: keempat layar tab ditahan hidup
  ui/screens/ExploreScreen.kt          SearchField bersama (Film/Live TV/Podcast/Radio)
```

Katalog di-host di Cloudflare Pages:
- film `https://bytify-api.pages.dev/api/catalog.json`
- serial `https://bytify-api.pages.dev/api/series.json`
- live TV `https://bytify-api.pages.dev/api/live/channels.json`
- ketersediaan resmi `https://bytify-api.pages.dev/api/watch.json`

---

## Keputusan final — jangan diubah tanpa saya minta

### 1. Tidak perlu terbit APK ulang untuk menambah film

APK menarik `catalog.json` sendiri (TTL 3 jam) lewat `MovieRepository.refreshIfStale()`
ditambah loop `AppContainer.autoSyncKatalogFilm()`.

Alur: scraper -> `build_database.py` -> commit `catalog.json` -> push.
GitHub Actions menerbitkan sendiri. Tidak ada build APK.

### 2. Gzip tidak perlu dikonfigurasi

Cloudflare Pages sudah mengompres dengan brotli otomatis. Jangan tambah lapisan
kompresi manual.

### 3. Katalog harus muat di bawah 25 MiB — ini batas keras, bukan preferensi

**Cloudflare Pages MENOLAK berkas di atas 25 MiB.** Katalog 31,6 MB pernah lolos
semua uji lalu gagal di langkah terbit. Penjagaannya sekarang ada di
`build-pages.test.mjs` (ambang aman 24 MiB) supaya ketahuan di CI, bukan saat deploy.

Yang membuatnya muat, dan jangan dibatalkan tanpa alasan:
- `license` dibuang — hanya 1 nilai unik untuk seluruh katalog; aplikasi memakai
  konstanta `LISENSI_BAWAAN` di MovieDetailScreen.
- `backdropUrl` dibuang — terukur identik `posterUrl` pada 25.135 dari 25.135 film;
  UI sudah jatuh ke poster.
- Sinopsis dipotong `PANJANG_SINOPSIS_KATALOG` (150) — halaman detail
  melengkapinya lewat `enrichIfNeeded`, yang dipicu oleh akhiran "...".

### 3b. Serial terbit sebagai berkas SENDIRI, bukan digabung ke catalog.json

65.296 episode tidak akan muat di sisa ruang catalog.json yang sudah 23,26 MiB.
`series_catalog.json` 12,71 MiB terbit ke `/api/series.json`; `MovieCatalogApi`
menurunkan URL-nya dari URL katalog film, jadi tidak ada setelan baru di
local.properties. `fetchSeriesCatalog()` mengembalikan null (bukan melempar)
kalau berkasnya belum ada, supaya APK lama tetap jalan.

Sinopsis serial TIDAK dipotong. Pemotongan pada film aman karena dilengkapi
`enrichIfNeeded`, tapi jalur itu hanya untuk id berawalan "lk21-"; serial
memakai siput situsnya sendiri. Server alternatif per episode dibuang — pemutar
memakai `episode.videoUrl` dengan `movie.sourceUrl` sebagai Referer.

### 3d. Film Indonesia & anime: dua berkas tambahan lagi, embed framextv

Selain serial, ada dua katalog tambahan yang terbit sebagai berkas sendiri dan
masuk ke tabel `movies` yang sama lewat `MovieRepository.refreshIfStale()`:

- **Film Indonesia** — `tmdb_indonesia.py` memanen dari TMDB
  (`discover?with_origin_country=ID`), `build_indonesia_catalog.py` membangun
  `indonesia_catalog.json` -> `/api/indonesia.json`. Server pemutarnya embed
  framextv berkunci **TMDB id**. 24 film yang tumpang tindih dengan LK21
  digabung di bawah id lk21- dengan kedua set server.
- **Anime** — `anilist_anime.py` memanen metadata + **AniList id** dari API
  GraphQL AniList (publik, gratis, tanpa kunci; dipecah per tahun karena satu
  kueri hanya memulangkan 5.000). `build_anime_catalog.py` membangun
  `anime_catalog.json` -> `/api/anime.json`. Server pemutarnya rute embed yang
  DIPUBLIKASIKAN framextv: `/embed/anime/<anilistId>` dan
  `/embed/anime/<anilistId>/<episode>`.

`MovieCatalogApi` memakai satu helper `ambilKatalogTambahan(berkas)` untuk
ketiganya (serial, indonesia, anime): URL diturunkan dari URL katalog film,
diurai dari ALIRAN DATA, mengembalikan null bila berkasnya belum ada.

**Anime WAJIB dijaga ukurannya dua arah.** 13.094 judul x semua episode =
32 MiB, lewat batas keras 25 MiB. Dua pemotongan di `build_anime_catalog.py`
membuatnya muat (22,68 MiB):
- `BATAS_EPISODE = 12` — episode disimpan per judul dibatasi. URL deterministik,
  jadi ini hanya memotong daftar yang tampil, bukan merusak pemutaran.
- `PANJANG_SINOPSIS = 400` — sinopsis dipotong. BERBEDA dari serial: anime
  memakai id "anilist-", yang TIDAK punya jalur `enrichIfNeeded` (itu hanya
  untuk id "lk21-"), jadi pemotongan ini PERMANEN. Batas 25 MiB tidak bisa
  ditawar, jadi ini konsekuensi yang diterima.

Penjagaan ukuran + "setiap judul harus punya episode" + "tidak ada id ganda"
ada di `build-pages.test.mjs` supaya ketahuan di CI, bukan saat deploy.

### 3c. Keempat layar tab ditahan hidup; NavHost tidak lagi memuatnya

NavHost membongkar lalu membangun ulang tujuannya setiap kali tab ditekan.
Terukur di Galaxy A36: 85-101 ms persentil-90 PER PINDAH, sama besar bahkan
untuk tab yang tidak menyentuh katalog film — jadi penyebabnya struktur
navigasi, bukan data. Sekarang keempat tab ada di lapisan sendiri di
`BytifyApp.kt`; tujuan tab di NavHost kosong, dan NavHost hanya mengurus
halaman rinci dengan latar buram yang memudar.

Lapisan tab yang tidak aktif memakai `Modifier.disimpan()`: DIUKUR dengan
batasan penuh (daftar di dalamnya menyimpan komposisi dan posisi gulir) tetapi
TIDAK ditempatkan. Yang tidak ditempatkan tidak digambar dan tidak ikut uji
sentuh. Percobaan pertama memakai pointerInput yang menelan sentuhan, dan itu
MEMATIKAN seluruh tombol navigasi bawah.

Hasil: pindah tab 85-101 ms -> 32-42 ms, jank 15-30% -> 6,9-11,2%.

### 3e. Lapisan KETERSEDIAAN RESMI berdiri sendiri, dan tidak boleh bisa diputar

`/api/watch.json` menjawab satu pertanyaan: judul ini bisa ditonton **resmi** di
layanan apa saja di Indonesia. Sumbernya TMDB `watch/providers` (data JustWatch),
jadi isinya nama layanan + tautan resmi — **tidak ada URL stream sama sekali**.

Tiga hal yang mengikat bentuknya, jangan dibatalkan tanpa alasan:

- **Bentuknya `titles` + kamus `providers`, bukan `movies`.** Mengulang nama
  seperti "Amazon Prime Video" di puluhan ribu entri jauh lebih mahal daripada
  satu kamus 46 baris. Kamus juga dipangkas ke penyedia yang betul-betul dirujuk.
- **Tabel Room-nya SENDIRI (`watch_titles` + `watch_providers`), bukan kolom baru
  di `movies`.** `movies` dibaca utuh ke memori oleh StateFlow daftar besar;
  menambah puluhan ribu baris lagi ke sana adalah jalan yang sudah pernah
  mematikan aplikasi dengan OutOfMemoryError di Galaxy A36. `WatchRepository`
  karena itu TIDAK punya Flow daftar penuh — hanya akses bertitik (satu judul)
  dan kueri berbatas `LIMIT`.
- **Penjodohannya menurunkan TMDB id dari id film** ("tmdb-<id>" untuk film
  Indonesia, "tmdb-movie-<id>"/"tmdb-tv-<id>" untuk lapisan ini), pola yang sama
  dengan `pemilikEpisode()`. Jadi tidak ada kolom baru di `movies`.

`build-pages.test.mjs` menjaga: ukuran < 24 MiB, setiap judul punya minimal satu
penyedia, setiap id penyedia ada di kamus (integritas rujukan), id tidak ganda,
atribusi ikut terbit, dan — yang khas lapisan ini — **tidak ada videoUrl,
servers, atau streamUrl**. Kalau suatu saat ada yang menyelipkan URL pemutaran
ke sini, uji itu gagal di CI.

Atribusi "Data ketersediaan dari JustWatch melalui TMDB" WAJIB tampil; teksnya
ikut di berkas dan juga ada sebagai konstanta `ATRIBUSI_KETERSEDIAAN` supaya
tetap muncul walau berkasnya belum pernah terunduh.

### 3f. Himpunan judul luar: pakai tmdbId-nya, buang field buatannya

Daftar judul pihak ketiga (mis. hasil panen sendiri) dipakai **hanya sebagai
himpunan (type, tmdbId)**; seluruh isi katalog dibangun dari jawaban TMDB.
Alasannya terukur pada berkas 50.072 entri yang dipakai sebagai daftar kerja:

| Field | Temuan |
|---|---|
| `imdbId` | pola `ttNNNNNNN` untuk 50.072 entri; 14.355 tepat `tt0`+tmdbId. Dibuat-buat. |
| `duration` | konstanta `1h 55m` untuk 46.046 film, `45m` untuk 3.205 serial |
| `quality` | konstanta per tipe (`1080p FHD` / `FHD SERIES`) |

Yang asli dan boleh dipakai: `tmdbId`, judul, poster, sinopsis, genre, rating,
tahun. Verifikasinya sederhana: The Empress imdb aslinya `tt13720112`, bukan
`tt0131488`; Inception runtime aslinya 148 menit, bukan "1h 55m".

### 3g. Subtitle diminta SAAT TAYANG, bukan dipanen lalu diterbitkan

Pola lapisan-lapisan lain (panen -> bangun -> terbitkan berkas) TIDAK dipakai
untuk subtitle, dan itu disengaja:

- 25 ribu film x 2 bahasa x ~50 KB kira-kira **2,5 GB**, sementara batas keras
  Cloudflare Pages 25 MiB per berkas. Tidak akan pernah muat.
- Memanen lalu menerbitkannya berarti Bytify menyalurkan ulang ribuan berkas
  subtitle buatan orang lain dari CDN sendiri. Dengan permintaan saat tayang,
  tiap pengguna mengambilnya dari OpenSubtitles di bawah ketentuan mereka
  sendiri — pola yang dipakai VLC, Kodi, Plex, dan Jellyfin.

**Kunci API ada di SERVER, tidak pernah di APK.** APK bisa dibongkar (apktool,
strings) dan kuncinya dipakai orang lain sampai kuotanya habis. Karena itu ada
proxy tipis: `backend/api/src/subtitles.mjs`, dipakai bersama oleh server Node
dan fungsi Cloudflare.

Tiga endpoint, dipisah berdasarkan **kuota**:

| Endpoint | Kuota | Dipakai saat |
|---|---|---|
| `/api/subtitles?tmdbId=` | tidak | daftar pilihan subtitle |
| `/api/subtitles/link?fileId=` | ya | kalau butuh tautannya sebagai JSON |
| `/api/subtitles/file?fileId=` | ya | URL STABIL yang dipakai APK; 302 ke tautan sementara |

APK menyimpan URL `/file` sebagai `MovieSubtitleDto.url`, jadi kuota baru
terpakai ketika pemutar benar-benar mengunduh berkasnya — bukan ketika daftar
subtitle tampil. Tautan sementara OpenSubtitles berumur pendek, jadi menyimpannya
langsung akan basi.

`OPENSUBTITLES_TERMS_ACCEPTED` harus `true` atau endpoint menjawab 503 tanpa
menembak API — gerbang sadar yang sejajar dengan `LIVE_SOURCE_RIGHTS_CONFIRMED`.
Semua variabel ada di `backend/api/.env.example`.

**Penyambungan ke pemutar bersifat ADITIF**, sesuai keputusan 4: hasilnya
ditambahkan ke daftar `subtitles` yang SAMA, sehingga konfigurasi ExoPlayer,
pipa cue embed lewat `postMessage`, dan menu pemilih tidak berubah sama sekali.
Subtitle dari katalog diutamakan; yang dari jaringan hanya menambah bahasa yang
belum ada. Gagal = daftar kosong; pemutaran tidak boleh ikut gagal.

Subtitle untuk penonton dengan gangguan pendengaran DITAWARKAN (ditandai "CC"),
bukan disaring — itu justru salah satu alasan utama fitur ini ada.

### 3h. Jembatan id katalog -> TMDB id lewat pencocokan KETAT

Lapisan ketersediaan DAN subtitle keduanya dikunci TMDB id, sementara 25.186
film LK21 memakai id siput tanpa TMDB id. `tmdb_match.py` menjembatani, dan
aturannya sengaja kaku karena **salah pasang lebih buruk daripada tidak pasang**
— menampilkan "ada di Netflix" atau subtitle film lain merusak kepercayaan pada
seluruh fitur:

1. Judul ternormalkan harus SAMA PERSIS dengan `title` atau `original_title`.
   Tidak ada skor kemiripan.
2. Tahun cocok dalam ±1 (beda tanggal rilis antar negara).
3. Kalau lebih dari satu TMDB id lolos, judul itu DILEWATI sebagai ambigu.

Normalisasi hanya mengizinkan satu padanan, `&` -> `and`. TIDAK membuang kata
sandang dan TIDAK menyamakan angka Romawi, supaya "Rocky II" tidak tertukar
dengan "Rocky III".

Judul LK21 berpola "Judul (Tahun)" dan sebagian membawa judul asli di kurung
dalam, mis. "10 Days of a Good Man (Iyi Adamin 10 Gunu) (2023)" — jadi beberapa
kandidat kueri dicoba, termasuk isi kurungnya, karena judul asli sering yang
terindeks TMDB sebagai `original_title`.

Hasilnya masuk tabel `watch_aliases` dan dipakai `WatchRepository.tmdbUntuk()`.
Yang ambigu sengaja TIDAK dipetakan.

### 3i. Jembatan anime: dataset pemetaan, DIVERIFIKASI ulang

Anime tidak bisa dijembatani dengan pencocokan judul seperti film LK21: satu
judul punya bentuk romaji, Inggris, dan Jepang, dan TMDB memecah anime antara
`movie` dan `tv`. Yang dipakai dataset pemetaan publik yang sudah dikurasi
(Fribb/anime-lists), `anilist_id -> themoviedb_id` beserta tipenya.

**Dataset itu tidak dipercaya mentah.** Tiap pemetaan diverifikasi terhadap
judul yang dipulangkan TMDB sendiri, dan verifikasinya gratis karena panen
ketersediaan memang sudah mengambil judul. Hasilnya 98,1% dari 7.877 kandidat
(4.858 judul sama persis, 1.443 tahun cocok, 1.423 warisan musim saudara);
101 ditolak dan dibuang.

Tiga jebakan yang ditemukan, semuanya sudah tertangani di kodenya:

- **Bentuk nilai BERBEDA antar jenis**: `{"tv": 26209}` int, tetapi
  `{"movie": [128]}` LIST. Memperlakukan keduanya sebagai int membuang 1.365
  pemetaan film diam-diam, tanpa galat apa pun.
- **anime_database.json hanya menyimpan ROMAJI.** Membandingkannya dengan judul
  Inggris TMDB menolak pemetaan yang benar — "Youchien WARS" vs "Kindergarten
  Wars", "Shingeki no Kyojin" vs "Attack on Titan". Judul Inggris diambil
  terpisah lewat `--judul`.
- **AniList memecah musim, TMDB menyatukan.** Penanda musim dibuang sebelum
  membandingkan, dan musim yang belum tayang (tanpa judul Inggris, tahunnya
  tentu tidak cocok) mewarisi pembuktian dari musim saudaranya yang sudah lolos.

**AniList menolak klien yang tidak memperkenalkan diri** dengan Cloudflare
error 1010, dan kegagalannya SENYAP: tiap permintaan habis di percobaan ulang
sehingga panen tampak berjalan selama 20 menit padahal tidak memulangkan apa
pun. `UA_BYTIFY` menyebut nama aplikasi dan repositorinya — etiket API biasa,
sama seperti yang diwajibkan OpenSubtitles, BUKAN menyamar sebagai browser.
`anilist_anime.py` ikut diperbaiki karena punya celah yang sama.

### 3j. Subtitle anime dilewati kalau musimnya ambigu

AniList memecah serial per musim sementara TMDB menyatukannya, jadi 3.747 dari
7.724 anime berbagi satu entri TMDB dengan musim lain. Episode di katalog kita
SEMUANYA bernomor musim 1, jadi untuk judul yang berbagi id, meminta "musim 1
episode 5" memulangkan subtitle MUSIM YANG SALAH.

Karena itu `WatchRepository.amanSubtitlePerEpisode()` menghitung berapa baris
`watch_aliases` menunjuk ke satu TMDB id; lebih dari satu berarti ambigu dan
permintaan subtitlenya dilewati. Datanya sendiri yang menjawab — tidak perlu
kolom penanda maupun migrasi baru.

**Ketersediaan TIDAK terpengaruh**: "ada di Netflix" benar untuk seluruh musim,
jadi chipnya tetap tampil untuk semuanya.

### 3k. Jembatan serial: mode kedua pada pencocok yang sama

`tmdb_match.py --serial` memakai aturan KETAT yang sama persis dengan mode
film, hanya endpoint dan nama fieldnya yang berbeda (`/search/tv`,
`first_air_date`, `name`/`original_name`). Satu pencocok, dua mode — bukan dua
skrip yang harus dijaga sejalan.

Hasilnya 4.155 dari 4.920 (84,5%), laju tertinggi dari ketiga jembatan, karena
judul serial jauh lebih jarang kembar daripada judul film. Dan ketersediaannya
50,5% — juga tertinggi, karena serial TV memang jauh lebih banyak ada di
layanan resmi daripada film.

Serial TIDAK dipecah per musim di katalog (hanya 4 dari 4.920 judulnya
berpenanda musim), jadi tidak ada beberapa entri yang menunjuk satu id TMDB
seperti pada anime. 48 "judul kembar" yang ada sebetulnya remake beda tahun
(GTO 1998/2012/2026) dan justru dipilah benar oleh aturan tahun.

**Subtitle per-episode untuk serial DITUNDA, dan ini penting.** Seluruh 65.296
episode bernomor **musim 1** — situs sumbernya meratakan penomoran. Untuk acara
yang di TMDB punya beberapa musim, "musim 1 episode 30" akan memulangkan
subtitle musim yang SALAH, dan kita belum menyimpan jumlah musim TMDB untuk
memilahnya. Jadi `amanSubtitlePerEpisode()` memulangkan false untuk id
berawalan "series-". Ketersediaannya tidak terpengaruh: itu per-acara.

Kalau nanti mau dibuka, yang dibutuhkan: simpan `number_of_seasons` saat panen,
lalu izinkan subtitle hanya untuk acara bermusim tunggal.

### 3l. Daftar film TIDAK BOLEH ditahan utuh di memori

`MovieRepository.movies` dulu StateFlow yang menahan SELURUH katalog. TERUKUR
di Galaxy A36 (batas heap 256 MB), kondisi identik, sudah mengendap:

| Metrik | Sebelum | Sesudah |
|---|---|---|
| Dalvik alloc mengendap | 54 MB | **10 MB** |
| Dalvik alloc puncak (DB siap) | 101 MB | **22 MB** |
| TOTAL PSS | 218 MB | 195 MB |
| Native heap | 18 MB | 27 MB (NAIK) |

StateFlow menahan nilai terakhirnya walau tidak ada lagi yang berlangganan,
jadi sekali terisi, 51.015 objek Movie hidup selamanya. Itu jalan yang pernah
mematikan aplikasi dengan OutOfMemoryError.

**Sifat yang wajib dipahami: ini tidak bisa dikerjakan separuh.** Selama ada
SATU pemakai yang butuh daftar utuh, memorinya tetap terpakai penuh. Di
MoviesScreen ada SEMBILAN pemakaian, dan kesembilannya harus pindah sekaligus
atau hasilnya nol.

Penggantinya:
- `halaman(filter, kueri, tahun)` — Paging 3, tiga kueri BERPARAMETER (umum /
  genre / rating), bukan belasan varian, supaya urutan dan semantiknya tidak
  melenceng antar varian.
- `deretGenre()` dari tabel `movie_genres` (112.029 baris di perangkat).
- `ratingTertinggi()`, `filmTahun()`, `tahunTersedia()`, `adaSerial`,
  `jumlahFilm` — semuanya SQL.
- `jumlahCocok()` untuk label "N film": `itemCount` Paging hanya menghitung
  yang sudah dimuat, jadi labelnya akan berubah sambil pengguna menggulir.
- Baris "Lanjutkan menonton" memakai peta kecil (maks ~48 entri) hasil
  pencarian per-id, bukan `associateBy` atas 51 ribu film.

`movie_genres` berkunci utama GABUNGAN (movieId, genre): genre kembar jadi
mustahil secara struktur. Dulu katalog bisa memulangkan genre yang sama dua
kali, film itu masuk dua kali ke satu deret, dan Compose melempar
IllegalArgumentException — terbukti mematikan aplikasi saat katalog Indonesia
ditambahkan. Sekarang skema yang menjaganya.

MIGRATION_11_12 membiarkan tabelnya KOSONG: mengurai genresJson untuk 51 ribu
baris di dalam migrasi akan menahan pembukaan database tepat di layar pertama.
Isinya menyusul pada sinkron berikutnya; sampai itu terjadi deret genre tampil
kosong dan tidak ada yang rusak.

### 3m. Sinkron katalog diproses BERPOTONG

Angka ~95 MB yang sempat tercatat di sini SALAH: itu satu cuplikan, dan
cuplikan jarang meremehkan puncak. Dengan cuplikan rapat, puncak sebenarnya
**152 MB dari batas heap 256 MB — 58% plafon.**

Penyebabnya tiap katalog dipetakan seluruhnya lebih dulu: DTO -> Movie ->
MovieEntity -> baris genre, jadi EMPAT salinan 25.186 objek hidup bersamaan.
`simpanKatalogBerpotong()` memprosesnya [POTONG_SINKRON] sekali jalan, dan id
kembar dilewati SEBELUM pemetaan supaya Movie-nya tidak dibentuk hanya untuk
dibuang.

Hasil terukur, katalog lengkap, data keluaran IDENTIK (51.015 film,
112.029 baris genre, 7 sections):

| | Puncak Dalvik |
|---|---|
| Sebelum | 152 MB |
| Sesudah | **~112 MB** (113,2 / 112,4 / 111,8 pada tiga jalan) |

**Sebaran pengukurannya hanya 1,4 MB**, jadi puncak Dalvik di sini TERPERCAYA —
berbeda dari angka jank di LDPlayer. Tapi jalan PERTAMA setelah memasang APK
membaca ~6-13 MB lebih tinggi (verifikasi dex dan pemanasan JIT); abaikan jalan
pertama, pakai jalan kedua dan seterusnya.

**Yang sudah dicoba dan TIDAK berhasil:** tidak mengikat katalog ke `val`,
dengan dugaan keempat daftar DTO hidup bersamaan sampai akhir fungsi. Dugaan
itu salah — puncaknya tetap ~112 MB. ART mengumpulkan sampah berdasarkan
liveness, bukan lingkup leksikal. Jangan diulang.

**Ceiling berikutnya:** sisa ~112 MB didominasi `decodeFromStream` yang memang
membentuk seluruh daftar DTO. Menghapusnya butuh pengurai ALIRAN sungguhan —
memecah stream per elemen lalu mengurai satu-satu — atau mengubah format
terbitan jadi NDJSON. Keduanya jauh lebih berisiko daripada yang di atas, dan
puncak ini hanya terjadi saat pemasangan pertama atau setelah TTL lewat, bukan
pada pemakaian sehari-hari (10 MB mengendap). Ukur dulu apakah itu benar-benar
mengganggu sebelum mengerjakannya.

### 3n. Server cadangan DITURUNKAN dari TMDB id, dan pemutar menelusurinya sendiri

Dua hal yang dulu terpisah, sekarang satu: dari mana server cadangan datang,
dan apa yang terjadi kalau sebuah server gagal.

**Tidak ada berkas katalog baru untuk ini, dan itu disengaja.** Rute embed
penyedia pihak ketiga hanya berisi TMDB id, jadi URL-nya deterministik.
Menerbitkannya berarti menuliskan beberapa URL untuk tiap judul — sekitar
9 MiB berkas baru plus tabel Room baru — hanya untuk menyimpan sesuatu yang
bisa dihitung ulang dari satu bilangan yang SUDAH ada di `watch_aliases`
(32.024 baris: 20.145 LK21, 7.724 anime, 4.155 serial). `ServerTambahan.kt`
menurunkannya di aplikasi: nol bait terbitan, nol tabel baru, nol baris baru
di `movies`. Terbukti di perangkat — 32.024 alias terisi setelah pemasangan
bersih, dan film `tmdb-1812324` mendapat 7 server padahal katalognya memberi 5.

**Scraper cinevo/123movies TIDAK dipakai untuk ini.** Keluaran cinevo memasang
keempat penyedia ke SELURUH 50.072 entrinya (terbaca di
`cinevo_providers_report.json`: 50072 untuk tiap penyedia) — daftar itu dibangun
dari template, nol hasil panen, jadi mengikis situsnya tidak menambah apa pun
yang tidak bisa diturunkan sendiri. Metadatanya juga tidak terpakai sesuai 3f:
97,6% entri bersinopsis `"Watch ..."` dan bercast `["Featured Cast"]`.
`scrape_123movies.js` lebih jauh lagi: `tmdbId`-nya diambil dari
`href.match(/-(\d{2,8})/)` alias **post id situsnya sendiri**, bukan TMDB id,
lalu dipakai membangun URL `vidsrc/autoembed/vidlink`. Menjalankannya akan
menghasilkan server yang menunjuk FILM YANG SALAH. Jangan dipakai sebagai
sumber id.

**Urutan penyedia DIUKUR, bukan ditebak** — `probe_servers.py`, 60 TMDB id
nyata dari katalog sendiri:

| penyedia | kerangka pemutar | catatan |
|---|---|---|
| vidlink.pro | 60/60 | HTTP 200 |
| vidcore.io | 60/60 | HTTP 200 |
| vidsrc.cc | 0/60 | HTTP 522, origin tumbang |
| player.autoembed.cc | 0/60 | DNS tidak resolve sama sekali |

`autoembed` TIDAK dipasang: nama yang tidak resolve di jaringan juga tidak
resolve di WebView. `vidsrc` ditaruh paling akhir, bukan dibuang — 522 bisa
pulih. Jalankan ulang probe-nya untuk menyegarkan urutan. Angka itu syarat
PERLU, bukan bukti pemutaran: aliran sebenarnya diresolusi JavaScript di dalam
iframe.

**Failover-nya tiga sinyal gagal, bukan satu timeout.** Sinyal BERHASIL sudah
lama ada (`BYTIFY_MAIN`). Yang ditambahkan:
1. pengalihan frame utama keluar domain player,
2. halaman melapor tak punya sumber (`BYTIFY_GAGAL`),
3. galat jaringan/HTTP **pada alamat server itu** — dicocokkan lewat URL, BUKAN
   lewat `isForMainFrame`, karena server hidup di dalam iframe sehingga
   domain mati muncul sebagai galat SUB-frame,
4. dan baru terakhir ambang `BATAS_TUNGGU_MS` (18 detik).

Ambangnya sengaja longgar: `ketukOtomatis()` melewati gerbang iklan sampai
~23 detik, jadi ambang rapat justru meninggalkan server yang sedang berhasil.

Dua penjaga yang jangan dilepas: laporan gagal dari WebView yang sudah
ditinggalkan diabaikan dengan membandingkan URL-nya (tanpa itu satu kegagalan
basi melompati server yang baru dipasang), dan setelah `BYTIFY_MAIN` semua
laporan gagal diabaikan (iklan yang gagal tidak boleh memindahkan server dan
memulai film dari nol).

Untuk serial, server turunan memakai ULANG penjaga
`amanSubtitlePerEpisode()` — masalahnya identik dengan 3j/3k: semua episode
bernomor musim 1, jadi untuk judul yang berbagi entri TMDB, "musim 1 episode 5"
menunjuk episode yang SALAH.

**Aliran tidak bisa dipanen jadi URL, dan itu sudah diselidiki sampai tuntas.**
Pertanyaannya wajar: kenapa tidak ambil saja m3u8-nya dari komponen pemutar,
supaya hasilnya nyata? Diamati langsung pada vidlink:

- jalur sumbernya `GET /api/b/movie/<token>` — **token terenkripsi** yang dibuat
  JavaScript mereka, bukan TMDB id,
- medianya dirakit `fu.wasm` (WebAssembly) lalu diserahkan ke pemutar sebagai
  **`blob:`** lewat Media Source Extensions.

`blob:` hanya hidup di dalam dokumen yang membuatnya — tidak bisa diserahkan ke
ExoPlayer, tidak bisa diterbitkan ke katalog, tidak bisa diambil backend. Jadi
tidak ada URL untuk dipanen, dan satu-satunya cara memutarnya adalah menjalankan
halaman penyedianya. Jangan ulangi penyelidikan ini.

**Server dimuat LANGSUNG, bukan di dalam iframe, kecuali kalau memang perlu.**
Inilah sebab judul yang berputar di cinevo/123movies dulu gagal di APK:
pembungkus iframe dipasang untuk SEMUA server, padahal ia hanya dibutuhkan embed
LK21 (yang menolak permintaan tanpa halaman film sebagai Referer). Server pihak
ketiga Referer-nya alamatnya sendiri, sehingga membungkusnya menciptakan keadaan
yang tidak pernah terjadi di situs aslinya — pemutar berjalan di dalam frame.
Terbukti di browser: vidlink memutar Inception dengan lancar sebagai halaman
biasa. `langsung` di `EmbedVideoStage` memutuskannya, dan pada jalur itu
`EMBED_CLEANUP_JS` TIDAK dijalankan — pembersih itu membuang elemen ber-class
`overlay`/`popup`/`banner`, justru nama yang dipakai pemutar modern untuk
kontrolnya sendiri.

**Watchdog-nya dua ambang, karena dua kegagalan itu berlawanan.** Skrip
melaporkan `BYTIFY_SIAP` begitu sumber menempel di elemen video, dan
`BYTIFY_DIAM` kalau elemen videonya tidak pernah ada. Server tanpa sumber
dibuang di ~9 detik; server yang sudah menempelkan sumber ditunggu sampai
`BATAS_SABAR_MS` (45 detik) karena justru itu satu-satunya yang akan memutar.
Terukur di perangkat: menjangkau server ke-4 turun dari 54+ detik menjadi
**22 detik**.

Satu jebakan yang sudah memakan waktu: `GATE_BYPASS_JS` disuntikkan ke SETIAP
dokumen termasuk frame bersarang, jadi tiap frame punya penandanya sendiri.
Frame dalam melaporkan SIAP, lalu frame LUAR (yang memang tak pernah memuat
video) melaporkan DIAM, dan server yang sudah dapat sumber ikut dibuang.
Karena itu DIAM diabaikan setelah SIAP — bukti positif mengalahkan ketiadaan
bukti. Penandanya ada di Kotlin (`sudahSiap`), bukan di dalam skrip.

### 3n-bis. Pemutar pihak ketiga MENUNGGU GESTUR, dan dua situs baru ternyata kulit

**Ketukan otomatis dulu dimatikan di jalur langsung, dan itu salah.** Alasannya
masuk akal: `mediaPlaybackRequiresUserGesture` sudah dimatikan, jadi pemutar
pihak ketiga "berjalan sendiri". Diamati di peramban, dugaan itu runtuh --
`vidlink.pro/movie/27205` diam di `readyState 0` TANPA `currentSrc` sama
sekali; satu ketukan sungguhan, dan ia membuat `blob:` lalu mencapai
`readyState 4`. Sumbernya memang baru dibuat setelah ada gestur.

Yang berbahaya bukan ketukannya, melainkan mengetuk pemutar yang SUDAH
berjalan -- itulah yang dulu membuka menu subtitle anikai menutupi video. Jadi
ketukan dipasang di kedua jalur dengan rem `sudahSiap`, dan jeda awal jalur
langsung dibuat 6 detik supaya server yang tidak butuh ketukan (anikai mencapai
SIAP di detik ke-3) sudah menarik remnya lebih dulu.

**watchvanta.xyz dan stcine.com adalah KULIT, bukan server.** Ditelusuri
langsung:

| situs | yang sebenarnya diputar |
|---|---|
| `watchvanta.xyz/movie/<tmdbId>` | iframe `cinesrc.st/embed/movie/<tmdbId>` |
| `stcine.com/player?id=<tmdbId>&type=movie` | iframe `apiplayer.ru/embed/movie/<tmdbId>` |

Keduanya aplikasi satu halaman, dan itu yang memutuskan: `stcine.com/player?...`
dibuka DINGIN hanya memulangkan beranda kosong (ia butuh navigasi di dalam
aplikasinya lebih dulu), sementara `watchvanta.xyz/movie/<id>` butuh dua
ketukan sebelum pemutarnya muncul. Jadi yang dipasang pemutarnya langsung.

`cinesrc.st` DIPASANG dan terbukti berputar: film `/embed/movie/27205` dan
episode `/embed/tv/1396?s=1&e=1` sama-sama mencapai `readyState 4` dengan
sumber `blob:` setelah satu ketukan. `apiplayer.ru` TIDAK dipasang: dibuka
langsung ia menjawab "Verification Failed", jadi ia memeriksa siapa yang
menyematnya, dan melewati pemeriksaan itu berarti berpura-pura menjadi stcine.

`watchvanta.xyz/movie/27205` mengalihkan diri ke `/movie/tt1375666`, dan
`stcine.com/movie/<slug>-<tahun>-<tmdbId>` mengabaikan slug dan tahunnya
(`/movie/x-0-27205` memulangkan Inception). Keduanya menerima TMDB id, jadi
tidak ada berkas katalog baru yang dibutuhkan -- konsisten dengan 3n.

**`vidcore.io` turun urutan: 200-nya menipu.** Halaman berhenti di
"FETCHING YOUR CONTENT..." lebih dari 35 detik, `readyState` tetap 0, tanpa
satu pun permintaan ke API sumbernya, dan ketukan tidak mengubah apa pun.
`vidsrc.cc` masih 522. Inilah contoh persis dari batas yang sudah tercatat:
kerangka pemutar yang tersaji BUKAN bukti ada yang bisa diputar.

**`probe_servers.py` punya salah-negatif yang harus diketahui.** Polanya
mencari penanda pemutar di HTML, sementara `cinesrc.st` merakit sumbernya
sepenuhnya di JavaScript -- dan string `not found` di dalam bundel router
Next.js membuatnya tergolong "tanpa-sumber" untuk 40 dari 40 judul, padahal
ia berputar. Untuk pemutar seperti itu, urutan DITENTUKAN oleh pengamatan di
peramban atau di perangkat, bukan oleh alat ini.

### 3r. Subtitle: celah yang ditutup, dan di mana angkanya disimpan

Tiga lubang berbeda, semuanya bermuara pada "tidak punya TMDB id yang benar".

**Anime tanpa padanan AniList.** Jembatan AniList hanya menjangkau 5.723 dari
8.007 judul anikai. `tmdb_match.py` karena itu dapat mode ketiga, `--anime`,
dengan aturan KETAT yang sama (3h) -- judul ternormalkan sama persis, tahun +-1,
yang menunjuk lebih dari satu DILEWATI. Sumbernya `anikai_database.json`, bukan
katalog terbit, karena hanya di sana ada `altTitle` (romaji dari JSON-LD).

**Anime berepisode tunggal adalah FILM.** 815 judul seperti itu tidak akan
pernah tercocokkan di `/search/tv`. Mode `--anime-film` mencocokkannya ke
`/search/movie`. Ruang id TMDB film dan serial TERPISAH, jadi tipenya ikut
diterbitkan sebagai `tmdbType`; tanpa itu aplikasi akan meminta subtitle untuk
serial yang id-nya kebetulan sama.

Hasilnya: TMDB id 5.723 -> **6.733**, nomor musim 3.314 -> **4.133**, ditambah
326 yang dikenali sebagai film.

**Nomor musim dihitung ulang atas himpunan yang BENAR-BENAR terbit.**
`peta_musim` mengelompokkan lewat alias AniList, sehingga satu saudara DI LUAR
katalog yang nomornya bertabrakan membuang musim untuk saudara yang ada di
dalamnya. Aturannya tetap konservatif (3j): nomor dipakai hanya bila seluruh
anggota grup berbeda-beda; satu tabrakan, seluruh grup kehilangan musimnya.

**Serial bermusim tunggal kini DAPAT subtitle per-episode.** Yang kurang dari
3k hanyalah jumlah musim TMDB, dan angka itu datang GRATIS di jawaban yang
sudah diambil `tmdb_watch.py` (`number_of_seasons`). Acara bermusim tunggal
tidak punya musim lain untuk tertukar, jadi "musim 1 episode N" pasti benar.
Hasilnya 3.202 dari 4.155 serial (77%) terbuka; sisanya tetap dilewati.

**Angkanya disimpan di ALIAS, bukan hanya di daftar judul.** `watch_titles`
hanya memuat judul yang punya layanan sah di Indonesia (15.357 dari 79.707),
jadi menaruh `seasons` hanya di sana berarti 953 serial tanpa layanan tidak
akan pernah dapat subtitle -- padahal subtitle tidak ada hubungannya dengan
ketersediaan. `MIGRATION_12_13` menambah kolom `seasons` ke `watch_titles` DAN
`watch_aliases`. Null diperlakukan TIDAK AMAN: belum tersinkron bukan berarti
bermusim tunggal.

### 3s. Status kanal Live TV kini hasil pengukuran

`isAvailable` dulu konstanta `true`, jadi status yang tampil hanya menandakan
kesegaran data. `probe_live.py` memeriksa tiap alamat dan menulis `alive`,
`probeStatus`, serta `checkedAtEpochMs` kembali ke `live_tv_database.json`;
`toLiveChannel` membacanya saat terbit. Tidak ada berkas baru dan tidak ada
tabel Room baru.

Hasil sapuan pertama: **6.363 dari 8.755 kanal hidup (72,7%)**. Sebab terbanyak
yang mati: `http 403` 628, `http 404` 522, timeout 279, DNS gagal 270,
sertifikat ditolak 264.

Tiga hal yang mengikat bentuknya:

- **GET, bukan HEAD.** Banyak origin HLS menjawab 405/403 untuk HEAD padahal
  GET-nya normal, jadi HEAD akan menandai kanal sehat sebagai mati.
- **Sepotong badan ikut dibaca** (`Range: bytes=0-2047`), karena banyak CDN
  memulangkan 200 untuk halaman galat atau "domain for sale". `#EXTM3U` di awal
  badan memisahkan playlist sungguhan dari 200 yang kosong isinya.
- **Kanal yang BELUM diperiksa tetap dianggap tersedia.** Ketiadaan pengukuran
  bukan bukti mati, dan memadamkan seluruh katalog setiap kali prober belum
  jalan jauh lebih buruk daripada status yang belum diperbarui. Dijaga uji
  kontrak di `build-pages.test.mjs`.

Kanal mati TIDAK dibuang dari katalog, hanya ditandai: 403 sering berarti
pembatasan wilayah, dan itu bisa berbeda di jaringan penonton.

### 3t. Podcast lintas negara

Podcast dulu terkunci di storefront `id`, padahal endpoint Apple-nya sama
persis dan hanya kode negaranya yang berganti. Layar Jelajah kini punya deret
chip 31 storefront, sejajar dengan Radio.

Daftarnya TETAP di `PodcastRepository.negaraPodcast`, bukan hasil panen: Apple
tidak menerbitkan daftar storefront lewat RSS -- yang ada hanya endpoint per
negara yang menjawab 404 kalau kodenya tidak dikenal. Radio memakai daftar
negara dari radio-browser karena sumbernya memang menyediakannya; podcast tidak
punya padanannya, jadi bentuknya berbeda dan itu disengaja.

Jebakan saat memeriksanya: memanggil 30 storefront beruntun membuat Apple
menjatuhkan koneksi, dan hasilnya terbaca seolah storefrontnya tidak ada.
Dengan jeda dan percobaan ulang, semuanya menjawab. Jangan menyimpulkan sebuah
storefront mati dari satu kegagalan.

### 3u. Empat sebab server sehat dibuang, semuanya terukur di A36

Server pihak ketiga bukan gagal memutar — ia dibuang oleh penjaga kita sendiri.
Keempatnya ditemukan pada satu judul (Inception, `lk21-movie-inception-2010`)
dan masing-masing menyamar sebagai kegagalan yang berbeda.

**1. Watchdog berkunci `kandidat.size`.** Server turunan diturunkan dari
`watch_aliases` lewat pembacaan database, jadi daftarnya BERTAMBAH beberapa
detik setelah pemutar terbuka. Kedatangan itu memulai ulang `LaunchedEffect`
dan menghapus `videoBerjalan` untuk server yang saat itu sedang memutar film.
Terukur: Inception berjalan di server 1 pada detik ke-5, dibuang pada detik
ke-18, lalu pemutar menelusuri sampai server ke-6 yang layarnya hitam. Kuncinya
sekarang `activeEmbedUrl` — alamat aktif hanya berubah kalau servernya memang
berganti.

**2. Ambang 18 detik tidak sebanding dengan jalur yang menunggu ketukan.**
Ketukan pertama di jalur langsung sengaja ditunda 6 detik, jadi cinesrc hanya
punya 12 detik untuk menempelkan sumbernya dan dibuang dengan
`siap=false, kemajuan=false`. `BATAS_KETUK_MS` (30 detik) berlaku khusus untuk
server yang dimuat langsung.

**3. Laporan gagal datang BERPASANGAN.** `onReceivedError` lalu
`onReceivedHttpError` keduanya tiba sebelum recomposition, sehingga penjaga
`dariUrl == activeEmbedUrl` saja masih meloloskan keduanya — DUA server
terlompati dalam 11 ms. `sudahDilewati` memastikan satu alamat hanya memajukan
server sekali.

**4. Popunder iklan dihitung sebagai gerbang Referer yang gagal.** Terukur:
`navigasi frame utama ditahan: invl.me` enam detik setelah dokumen cinesrc
(19.711 bait) terkirim utuh. Pembedanya sederhana dan pasti: gerbang Referer
gagal dengan mengalihkan permintaan DOKUMENNYA, sebelum ada halaman pemutar
sama sekali. Kalau halamannya sudah kita sisipi (`dokumenUtamaDisisipi`),
navigasi keluar yang ditahan pasti iklan. Penanda yang sama juga menutup jalur
galat jaringan, karena galat pada alamat yang dokumennya sudah terkirim adalah
permintaan kembar atau yang dibatalkan.

Di jalur LANGSUNG, galat yang dihitung hanya galat FRAME UTAMA — di sana
servernya memang frame utama. Pencocokan lewat URL tetap dipakai di jalur
pembungkus, karena di sana server hidup di dalam iframe sehingga domain mati
muncul sebagai galat sub-frame.

Sesudah keempatnya: cinesrc melapor
`BYTIFY_SIAP rs=4 vid=2592x1080 kotak=384x216@0,0` lalu `film berjalan`, tanpa
satu pun "pindah server".

**Layar hitam pada pemutar yang SEDANG berjalan: wadahnya, bukan videonya.**
Terukur sebelum perbaikan: `kotak=384x216@0,-90` — videonya diletakkan 90
piksel DI ATAS kotak, jadi keluar layar meskipun `readyState 4`. Pemutar modern
memusatkan video di dalam wadahnya; kalau wadah itu lebih tinggi daripada kotak
yang kita beri, pemusatan mendorong video DAN kontrolnya ke luar. Inilah juga
sebab tombol jeda tergambar di atas kotak video pada vidcore.

`pastikanTinggi()` karena itu menjepit tinggi `documentElement`, `body`, dan
setiap wadah leluhur video yang lebih tinggi daripada viewport. Memaksa posisi
videonya hanya cadangan terakhir, kalau sesudah dijepit ia masih keluar kotak —
menjepit wadah menjaga tata letak pemutar tetap utuh, sehingga kontrolnya tetap
pada tempatnya relatif terhadap video.

**Subtitle berlaku untuk SEMUA server, bukan hanya LK21.** Terbukti di A36 pada
cinesrc: `kirim subtitle (119856 bait json)` lalu
`subtitle diterima: 1592 baris; video di sini=1`. Jalur pengirimannya memang
tidak bergantung server — cue dikirim lewat `postMessage` ke dokumen yang
memuat `<video>`, dan `GATE_BYPASS_JS` disuntikkan ke SETIAP dokumen HTML.
Yang dulu menghalanginya bukan subtitlenya, melainkan server yang dibuang
sebelum sempat memutar.

### 3v. Penyedia gagal karena KATALOGNYA, bukan karena rusak

Koreksi atas kesimpulan yang terlalu luas di 3u: `vidcore.io` sempat dibuang
dengan alasan "tidak pernah memutar apa pun". Itu salah. Ia gagal untuk
`tmdb 27205` (Inception) TETAPI MEMUTAR `tmdb 1469930` (Tumbal Proyek) di
Galaxy A36 — `BYTIFY_SIAP rs=4 vid=1920x1080 kotak=384x216@0,0`, gambar
bergerak. Satu judul yang gagal tidak membuktikan penyedianya mati; yang
menentukan apakah ia punya judul YANG SEDANG DIBUKA. Jadi yang diubah
urutannya, bukan keberadaannya.

Dua jawaban "tidak punya" yang kini dikenali dan membuat failover berpindah
dalam hitungan detik, bukan menunggu ambang penuh:

- **Halaman menyatakannya.** `vidlink.pro/movie/1469930` menjawab
  "We Couldn't Find This Content ." Polanya ditambahkan ke
  `cekTakAdaSumber()`, dan pemeriksaannya dimulai sejak detik ke-2,5.
- **Sumber menempel tetapi tidak pernah membawa data.** Diamati pada cinesrc
  untuk judul yang sama: `currentSrc` berisi `blob:`, `networkState` 2, tetapi
  setelah 36 detik `readyState` tetap 0 dan `buffered.length` tetap 0.
  `cekSumberMandek()` melaporkannya di detik ke-20 — keadaan ketiga, di antara
  SIAP dan DIAM, dan yang paling membuang waktu.

**Subtitle Indonesia untuk film Indonesia baru memang TIDAK ADA, dan itu bukan
bug.** Diukur lewat API produksi:

| TMDB id | judul | totalFound | bahasa |
|---|---|---|---|
| 27205 | Inception | 50 | id, en |
| 1061474 | — | 26 | id, en |
| 1469930 | Tumbal Proyek (2026) | 1 | en saja |
| 1525059 | Tiba Tiba Setan (2026) | 0 | — |
| 1589663 | Yang Lain Boleh Hilang… (2026) | 0 | — |

Daftar subtitle dibangun PER JUDUL, bukan per server, jadi server mana pun
yang dipilih mendapat daftar yang sama — terbukti di A36: Inception di cinesrc
menampilkan chip Indonesia dan menerima 1.592 cue. Yang membedakan film LK21
bukan servernya, melainkan judulnya: film lama dan internasional sudah punya
subtitle di OpenSubtitles, sementara rilis Indonesia 2026 belum diunggah siapa
pun. Tidak ada yang bisa diperbaiki di kode untuk judul yang sumbernya memang
kosong.

### 3w. Sumber subtitle KETIGA: trek milik aliran server

Subtitle Bytify punya dua sumber: katalog dan OpenSubtitles. Keduanya kosong
untuk rilis Indonesia baru, dan itu diukur, bukan diduga:

| TMDB id | judul | OpenSubtitles |
|---|---|---|
| 1469930 | Tumbal Proyek (2026) | 1 berkas, Inggris |
| 1525059 | Tiba Tiba Setan (2026) | 0 |
| 1589663 | Yang Lain Boleh Hilang… (2026) | 0 |

Fallback pencarian judul SUDAH DICOBA dan ditolak: pada contoh 14 film
Indonesia acak, pencocokan KETAT (judul ternormalkan sama persis + tahun sama)
memberi **nol** tambahan, sementara pencarian longgar memulangkan film lain —
"Arwah Tumbal Nyai (2020)" untuk kueri "Tumbal Proyek". Memasangnya berarti
menampilkan subtitle film yang salah, yang dilarang 3h.

Yang tersisa, dan kini dipakai: **trek yang dibawa aliran penyedia sendiri**.
`GATE_BYPASS_JS` membaca `video.textTracks` dari frame yang memegang videonya,
melaporkannya sebagai `BYTIFY_TREK:` + JSON, dan aplikasi menampilkannya
sebagai chip bertanda "(server)". Memilihnya mengirim `{bytifyTrek: <i>}` lewat
`postMessage` — jalur yang sama dengan cue, jadi frame bersarang ikut
terjangkau. Memilih subtitle kita sendiri mematikan trek server, karena dua
lapisan teks sekaligus tidak terbaca.

Terukur di Galaxy A36 pada Tumbal Proyek:
`trek server: [{"i":0,"lang":"","label":"Spanish"},{"i":1,"lang":"","label":"English"}]`.
Jadi untuk judul itu penyedianya pun tidak punya Indonesia — tetapi jalurnya
kini ada, dan setiap judul yang treknya memang berbahasa Indonesia akan
langsung tampil tanpa perubahan kode lagi.

Nomor trek adalah indeks `textTracks`, jadi hanya berlaku selama dokumen
pemutar itu hidup. Ia TIDAK disimpan dan TIDAK diterbitkan, dan dikosongkan
setiap kali server berganti.

**vidlink: berjalan tetapi TIDAK TERLIHAT.** Terukur sebelum perbaikan:
`BYTIFY_SIAP rs=4 vid=1920x1080 kotak=384x216@0,18` dan `film berjalan`,
sementara layarnya hitam. Menjepit wadah saja tidak cukup — `position:absolute`
masih mengikuti leluhur berposisi, dan di vidlink leluhur itulah yang menggeser
videonya, sementara poster dan lapisan kontrol menutupinya. Yang bekerja
`position:fixed` di sudut kiri-atas dengan `zIndex` tertinggi. Sesudahnya
`kotak=384x216@0,0` dan gambar "LEGENDARY PICTURES" tampil.

### 3x. SubDL sebagai sumber subtitle KEDUA

OpenSubtitles tidak punya subtitle Indonesia untuk rilis Indonesia baru —
diukur lewat API produksi: `tmdb 1525059` dan `tmdb 1589663` nol berkas,
`tmdb 1469930` satu berkas berbahasa Inggris. SubDL punya: halaman
"Project Sacrifice (2026)" (judul Inggris Tumbal Proyek) mencatat
"8 subtitles across 6 languages — English, Indonesian, Malay, Albanian,
Bangla". Jadi lubangnya bukan di kode kita, melainkan di satu sumber yang
dipakai sendirian.

Sumber kedua itu kini terpasang, dan berlaku untuk SELURUH film — jalurnya
berkunci `tmdbId`, bukan judul tertentu.

Empat sifat yang mengikat bentuknya:

- **SubDL hanya MENAMBAL bahasa yang belum ada.** Yang sudah dipulangkan
  OpenSubtitles tidak pernah tergeser, karena hanya OpenSubtitles yang punya
  `download_count` sebagai sinyal kualitas. SubDL tidak punya padanannya, jadi
  memakainya untuk menggantikan yang terukur adalah kemunduran.
- **Tanpa `SUBDL_API_KEY`, seluruh jalurnya DIAM.** Hasilnya persis seperti
  sebelum SubDL ada, dan itu dijaga uji sendiri — termasuk bahwa api.subdl.com
  tidak ditembak sama sekali.
- **ZIP dibongkar di SERVER, bukan di APK.** SubDL menyajikan `.zip` sementara
  APK mengharapkan teks SRT. `bukaZipSrt()` membaca header lokal ZIP dan
  memakai `DecompressionStream('deflate-raw')` — ada di Cloudflare Workers
  maupun Node 18+, jadi nol ketergantungan baru. Entri tersimpan tanpa
  kompresi (metode 0) ikut ditangani; entri yang ukurannya ada di data
  descriptor DILEWATI, bukan ditebak.
- **Kontrak `/api/subtitles/file` tidak berubah bagi APK.** OpenSubtitles tetap
  dijawab 302 supaya kuotanya baru terpakai saat berkasnya benar-benar
  diunduh; SubDL dijawab dengan ISI berkasnya. `fileId` berawalan `subdl:`
  itulah yang memilih jalur — jadi id SubDL sengaja BUKAN angka.

Kuncinya harus didaftarkan MANUSIA: halaman redeem memakai captcha dan
verifikasi email. Sesudah diisi, ingat jebakan yang sudah tercatat —
mengubah environment variable di Cloudflare TIDAK berlaku untuk deployment
yang sudah ada, jadi harus ada deploy baru.

### 3y. Enam penyedia baru, dan cara mengujinya yang akhirnya sahih

**Enam situs yang diminta ternyata sebagian KULIT.** Ditelusuri satu per satu:

| situs | temuan |
|---|---|
| reelix.ac | siput berkode (`/movie/ghost-soldier-YxXyJhm5861P`); pemutarnya `vidcore.io` yang sudah kita punya. Mode Embed-nya membuka `vsembed.su` dan `multiembed.mov` |
| holaflix.cc | `/play/movie/<tmdbId>` — TMDB id LANGSUNG, dan membawa 14 penyedia |
| flixfix.fun | `/movie/<tmdbId>` ada, tetapi halaman tontonnya tidak menyajikan pemutar |
| novashow.live | `/player?id=<tmdbId>`; halaman pemutarnya menyebut tujuh penyedia lengkap dengan bentuk serialnya |
| dympire.pages.dev | SPA beriklan berat; popunder-nya menutup tab peramban berulang kali, bundel JS-nya tidak memuat satu pun rute pemutar |
| plutocine.pages.dev | sama; `js/app.js` hanya berisi pemanggil TMDB |

Jadi yang dipasang pemutarnya LANGSUNG, bukan kulitnya — keputusan yang sama
dengan cinesrc pada watchvanta (3n-bis). Enam kamar baru di `PROFIL`, masing
masing terpisah: `player.cinezo.live`, `player.vidzee.wtf`, `vaplayer.ru`,
`vsembed.su`, `holaflix.cc`, `novashow.live`. Dua yang berupa SITUS dapat jeda
ketukan 9 detik dan ambang 45 detik, karena mengetuk situs induk menekan
tombolnya sendiri dan ada satu lapisan jaringan tambahan.

**Hasil uji 3 film per server di Galaxy A36:**

| server | hasil | catatan |
|---|---|---|
| cinezo | **3/3** | satu-satunya penyedia baru yang juga memutar film Indonesia |
| holaflix | **3/3** | memutar LEWAT `player.cinezo.live`, jadi bukan cadangan yang berdiri sendiri |
| vidzee | 2/3 | Tumbal Proyek tidak ada di katalognya |
| vsembed | 2/3 | frame dalamnya `stellarconductornexus.com` |
| vaplayer | **0/3** | terpilih di ketiganya, tidak satu pun punya sumber |
| novashow | **0/3** | `/player?id=` menjawab HTTP 200 dengan NOL bait |

`vaplayer.ru` dan `novashow.live` karena itu masuk `HOST_MATI` — diturunkan ke
belakang, bukan dibuang (3v).

**Menguji lewat ANTARMUKA tidak bisa dipercaya, dan ini yang paling mahal
dipelajari.** Tiga cara dicoba, ketiganya gagal dengan bentuk yang sama:
server yang TIDAK PERNAH dimuat terlaporkan sebagai "tidak bekerja".

- `uiautomator dump` **gagal diam-diam** selama video berjalan: ia menunggu
  antarmuka diam, dan video yang berputar tidak pernah membuatnya diam.
  Perintahnya menulis "ERROR: could not get idle state." lalu berkas XML LAMA
  tetap ada, sehingga pembacaan berikutnya memulangkan layar basi. Terukur:
  daftar chip terbaca `VIDCORE…CINEZO` sementara layarnya jelas menampilkan
  `FRAMEXTV-2/3/4`.
- **Ketukan pada koordinat tetap** melewatkan chip yang lebarnya berbeda-beda.
  Terukur: sepanjang seluruh percobaan, `vaplayer` tidak pernah sekali pun
  dimuat meski rutenya ada.
- **Navigasi fokus (DPAD)** tidak selalu mendarat di deret yang benar: 13 dari
  15 langkah memulangkan `None`.

Yang akhirnya dipakai: **siaran `com.bytify.app.UJI_SERVER`** dengan extra
`nama`, dipagari `BuildConfig.DEBUG`. Ia memilih server MENURUT NAMANYA dan
mencatat `uji: pilih '<nama>' -> indeks N dari M`, sehingga pengujian tahu
pasti apakah server itu ditawarkan untuk judul tersebut (`indeks -1` berarti
memang tidak ada) dan tata letak tidak lagi ikut menentukan hasil.

**Putusan uji diambil dari satu angka: `currentTime` yang maju.** Ukuran kotak
dan opacity ikut dibaca untuk memisahkan "berjalan tetapi tak terlihat" dari
"tidak berjalan". Dan tiap laporan membawa `host=` dokumennya: tanpa itu,
laporan dari WebView server SEBELUMNYA terbaca sebagai bukti bahwa server yang
baru dipilih berjalan — sapuan pertama memberi 8 dari 8 "JALAN", dan itu bohong.

**Pilihan MANUAL mematikan perpindahan otomatis.** Penelusuran otomatis ada
untuk saat pengguna belum menyatakan apa pun; begitu ia menekan sebuah chip, ia
sudah menyatakannya. Sebelum ini, chip yang ditekan melaporkan host server LAIN
karena watchdog sudah memindahkannya sebelum server itu sempat menempelkan
sumbernya.

### 3o. Trailer diputar DI DALAM aplikasi, dan butuh origin yang sah

Tombol trailer dulu memanggil `Intent.ACTION_VIEW` sehingga pengguna terlempar
keluar. Sekarang diputar di dialog ber-WebView. Dari 25.186 film, 20.400 punya
trailer dan SEMUANYA YouTube (20.095 `watch?v=`, 200 `youtu.be/`, 105 bentuk
youtube.com lain), jadi satu pengurai `idYouTube()` cukup; bentuk yang tidak
dikenali tetap jatuh ke aplikasi luar.

**Tiga cara yang GAGAL, semuanya terukur di Galaxy A36.** Dicatat lengkap
karena ketiganya tampak masuk akal dan masing-masing memakan satu putaran
pasang-uji:

| Cara | Hasil |
|---|---|
| `loadUrl("youtube.com/embed/<id>")` langsung | **Error 153** — konfigurasi pemutar |
| `loadDataWithBaseURL(base youtube.com, ...)` | layar hitam, **nol** pesan konsol dan nol galat; Chromium memberi dokumen data origin BURAM, jadi penolakannya senyap |
| pembungkus di-host pada alamat `youtube.com/embed/...` | **Error 152 - 4** untuk SETIAP judul — Inception dan Interstellar sama-sama ditolak, jadi ini bukan batasan per-video melainkan YouTube menolak dirinya menyemat dirinya |

**Yang bekerja:** halaman pembungkus beralamat di domain Bytify sendiri
(`ASAL_TRAILER` = https://bytify-api.pages.dev), dijawab sendiri oleh
`shouldInterceptRequest` sehingga tidak ada permintaan jaringan untuknya, dengan
iframe ke `youtube.com/embed/<id>?...&origin=<ASAL_TRAILER>`. Dengan begitu
penyematnya tampak sebagai pihak ketiga yang wajar, persis seperti situs mana
pun yang menanam pemutar YouTube. TERBUKTI berputar untuk Inception dan
Interstellar.

**Jebakan tinggi iframe berlaku di sini juga, dan sempat menipu.** Pembungkus
trailer mula-mula memakai `height:100%`, dan hasilnya layar hitam — persis
jebakan yang sudah tercatat di keputusan 4 untuk pemutar film. Yang benar
`height:100vh` DITAMBAH penyesuai `bytifyFit()` yang menyetel tinggi dalam
piksel. Hitam karena iframe 0 piksel tidak bisa dibedakan dari hitam karena
ditolak YouTube, jadi perbaiki tingginya DULU sebelum mencurigai hal lain.

Pemutarnya sengaja TERPISAH dari `EmbedVideoStage`: pelewat gerbang iklan,
penyuntik subtitle, dan ketukan otomatis tidak diperlukan untuk YouTube dan
hanya menambah risiko pada jalur yang sudah terbukti. `onRelease` memuat
`about:blank` SEBELUM `destroy()`, tanpa itu suara trailer terus berbunyi
setelah dialognya ditutup.

### 3p. Lapisan SERVER anime dari anikai — satu seal per judul, bukan per episode

**Kenapa anime butuh lapisan sendiri.** Terukur di Galaxy A36: anime adalah
satu-satunya bagian katalog yang benar-benar kehabisan pilihan server.
Keempat "cermin" framextv ternyata SATU server (semuanya dialihkan ke
framextv.cc, backend `api.framextv.tech` yang membalas **HTTP 429**), dan
server turunan berkunci TMDB id terpaksa dilewati untuk **51%** anime karena
penjaga ambiguitas musim 3j — hanya 3.805 dari 7.724 anime beralias yang TMDB
id-nya tidak dibagi dengan musim lain.

Anikai dikunci **AniList id langsung**, jadi musimnya sejalan dengan katalog
kita dan penjaga itu tidak diperlukan sama sekali.

**scrape_anikai.js TIDAK dipakai, dan alasannya terukur.** Skrip itu dibangun
untuk situs bergaya Zoro/HiAnime: menunggu Cloudflare, menggulir, lalu
MENGKLIK tab server untuk memancing iframe. anikai.org bukan situs seperti itu
— ia situs STATIS (Hugo):

| Sumber | Isi |
|---|---|
| `/watch-sitemap.xml` | 8.006 URL anime, lengkap, satu permintaan |
| `/episodes/<id>.json` | daftar episode, larik ringkas |
| `/watch/<slug>-<id>/` | HTML yang memuat seluruh kunci pemutar |

Tidak ada `/ajax/v2/episode/servers`, tidak ada gerbang Cloudflare, tidak ada
yang perlu diklik. Jadi browser justru membuat panen lebih lambat DAN tidak
lengkap: skrip lama berhenti di `ENRICH_DETAIL_LIMIT=80` judul dan hanya
mengambil server episode pertama. `anikai_servers.py` memakai HTTP biasa dan
menjangkau seluruh 8.006 judul. Metadatanya tetap dibuang sesuai 3f —
`tmdbId`-nya hash buatan (7000000+), `imdbId` "tt"+padding, rating "8.2",
tahun "2026".

**Bentuk URL-nya dibaca dari kode situsnya sendiri** (`js/main.js`):

```
`${dataset.player}/watch#${dataset.seal}.${episode}.${server}.${audio}`
```

Karena nomor episode hanya DISISIPKAN ke pola itu, yang perlu disimpan cuma
satu `seal` per JUDUL — bukan satu baris per episode. Itu sebabnya lapisan ini
kecil dan tidak ada episode yang bisa terlewat: berapa pun episodenya, URL-nya
terbentuk. Polanya ikut diterbitkan di berkasnya, jadi kalau penyedia mengubah
bentuk alamat, yang perlu diterbitkan ulang cukup berkasnya — tanpa APK baru.

`server == 0` berarti situs itu sendiri menyatakan judulnya belum punya
sumber; entri seperti itu tidak pernah diterbitkan.

**Penjodohannya KETAT, sama alasannya dengan 3h.** Anikai tidak membawa
AniList id, jadi jembatannya judul: judul ternormalkan harus SAMA PERSIS
dengan romaji atau judul Inggris AniList, dan yang cocok ke lebih dari satu id
DILEWATI sebagai ambigu. Berbeda dari 3i, penanda musim TIDAK dibuang — di
sana AniList memecah musim sementara TMDB menyatukan, sedangkan di sini kedua
sisi sama-sama anime dan sama-sama memecah musim, jadi "Season 2" justru
pembeda yang sah.

**Lapisan ini TIDAK masuk Room.** Isinya hanya seal per judul, umurnya seumur
sesi, dan hanya dibaca saat pemutar anime dibuka — jadi ditahan di memori
`MovieRepository` di belakang Mutex. Menyimpannya ke Room berarti satu tabel
dan satu migrasi baru untuk data yang tidak perlu bertahan.

**Anikai didahulukan sebelum framextv untuk anime, dan itu terukur.** Dua anime
yang diuji di A36 (Jujutsu Kaisen dan Renegade Immortal) sama-sama gagal di
framextv sampai ambang waktu habis, sementara anikai memutar keduanya —
Jujutsu Kaisen mulai berjalan **4,5 detik** setelah tombol ditekan, dan
framextv tidak disentuh sama sekali. framextv TIDAK dibuang, hanya turun
urutan: 2.320 judul anikai yang tidak terjodohkan masih mengandalkannya.

**`BYTIFY_SIAP` mensyaratkan DATA, bukan sekadar src yang menempel.** Terukur:
framextv memasang `src` lalu diam dengan `readyState 0` karena backend-nya
membalas 429 — dan dengan syarat longgar, server mati seperti itu justru
DIBERI kesabaran 45 detik, dua kali berturut-turut. "Punya src" bukan
kemajuan; "punya data" baru kemajuan.

**Jumlah episode anime: disimpan sebagian, dilengkapi aplikasi.** Katalog
memangkas daftar episode (`BATAS_EPISODE`) supaya muat di 25 MiB, dan
akibatnya Jujutsu Kaisen tampil **8 episode padahal aslinya 24** — berlaku
untuk 6.297 judul, yaitu **124.248 episode** yang tidak pernah sampai ke
pengguna. Sekarang katalog membawa `episodeCount` (jumlah sebenarnya) dan
`lengkapiEpisode()` di MovieModels.kt membentuk sisanya: URL episode
berpola `.../embed/anime/<id>/<nomor>`, jadi bisa DIHITUNG. Polanya diturunkan
dari episode terakhir yang memang ada di katalog, bukan ditanam di kode, supaya
ikut berganti kalau pembangun katalog berganti cermin. Dibatasi
`BATAS_LENGKAPI_EPISODE` karena daftar ini dibentuk saat sinkron, dan puncak
memori sinkron pernah menyentuh plafon heap (3m); terukur di A36 sesudahnya:
Dalvik mengendap 26,5 MB, nol FATAL EXCEPTION.

**Musim TMDB anime ditentukan dari judul, lalu DIPERIKSA SILANG.** Penjaga 3j
memblokir subtitle per-episode untuk anime yang berbagi entri TMDB, dan
akibatnya Jujutsu Kaisen tidak pernah punya pilihan subtitle Indonesia sama
sekali — padahal subtitlenya ADA (`Jujutsu.Kaisen.S01E01.WEBRip.NF.id`,
terbukti dari API produksi). Sekarang nomor musim dibaca dari judul
("Season N", "Nth Season"; judul tanpa penanda = musim 1), lalu seluruh entri
yang berbagi satu TMDB id dibandingkan: kalau nomornya berbeda-beda semua,
pemetaannya dipakai; kalau ada DUA yang bertabrakan, seluruh grup itu tetap
dilewati. Satu tabrakan berarti kita tidak tahu mana yang mana, dan subtitle
musim yang salah lebih buruk daripada tidak ada subtitle (3h). Hasilnya 3.039
judul dapat musim yang pasti, disimpan di `anime_servers.json` — bukan kolom
Room baru, jadi tidak ada migrasi.

**Jangan menyamakan "Part N" dengan "Season N".** Dr. Stone Part 3 dan
Season 3 adalah dua hal berbeda; menyamakannya memasang server musim yang
salah.

**Penghambat pencocokan anikai bukan aturan, melainkan BAHASA JUDUL.** Yang
pertama dicurigai adalah notasi musim yang berbeda ("… 2" vs "… Season 2"),
dan penyeragamannya memang benar — tetapi hanya menambah 45 judul. Yang
sebenarnya: **7.337 dari 13.094 anime tidak punya judul Inggris sama sekali**,
sementara anikai menamai judulnya dalam bahasa Inggris. `--judul-semua`
memanen judul untuk SELURUH katalog (bukan hanya kandidat TMDB seperti
`--judul`), berikut `synonyms` — karena AniList pun hanya punya judul Inggris
resmi untuk 7.500 dari 14.577 entri, dan nama yang dipakai situs pihak ketiga
biasanya ada di daftar sinonim.

**Katalog anime HANYA memuat judul yang bisa ditonton.** Sejak framextv
terbukti tidak bisa dipakai, `build_anime_catalog.py` membaca
`anime_servers.json` dan MELEWATI anime yang tidak punya seal anikai — 7.018
judul dibuang. Menerbitkan judul yang bisa dibuka tetapi tidak bisa ditonton
lebih buruk daripada tidak menampilkannya. AniList tetap dipakai sebagai
sumber METADATA (judul, poster, sinopsis, genre, jumlah episode); yang dibuang
perannya sebagai server. Metadata anikai sendiri tidak bisa dipakai (3f).

Berkasnya menyusut 19,96 -> 17,60 MiB meski cap episodenya dinaikkan 8 -> 26,
sehingga anime dua-cour kini tersimpan UTUH tanpa perlu dilengkapi aplikasi.

**Pelengkap episode harus mengenali DUA bentuk alamat.** Setelah pindah ke
anikai, nomor episode bukan lagi segmen jalur (`.../anime/<id>/<n>`) melainkan
bagian fragmen (`...#<seal>.<n>.<server>.<audio>`). `alamatEpisode()` di
MovieModels.kt mengenali keduanya dan mengembalikan null untuk bentuk lain —
lebih baik episode seadanya daripada alamat tebakan yang membawa penonton ke
episode yang salah.

**JSON-LD halaman anikai membawa judul romaji-nya — itu jembatan terpenting.**
Berhenti di 6.076 adalah keputusan yang terlalu cepat. Sisa judul yang gagal
didominasi donghua Tiongkok, dan sebabnya bukan aturan yang terlalu ketat
melainkan BAHASA judulnya: anikai menamai halamannya dalam bahasa Inggris
("Swallowed Star 2nd Season") sementara AniList menyimpan romaji
("Tunshi Xingkong 2nd Season"). Blok `application/ld+json` di tiap halaman
membawa keduanya:

```json
"@type":"TVSeries", "alternateName":"Tunshi Xingkong 2nd Season",
"datePublished":"2021-12-29"
```

Jadi jembatannya tetap pencocokan PERSIS — yang bertambah bentuk judul yang
dicoba, bukan kelonggarannya. `datePublished` dipakai sebagai PEMBEDA saat
satu judul menunjuk lebih dari satu entri, bukan sebagai pelonggar.

Jebakan saat memanennya: `@graph` juga memuat simpul `WebSite` yang
`alternateName`-nya "AniKai" — nama situsnya sendiri. Tanpa menyaring
`@type`, setiap entri memakai nama itu. Saring ke TVSeries/Movie/TVSeason.

**Alamat server yang kuncinya ada di FRAGMEN memutus tiga penjaga sekaligus.**
anikai memakai `.../watch#<seal>.<episode>.<server>.<audio>`, sementara peramban
TIDAK PERNAH mengirim fragmen dalam permintaan jaringan. Akibatnya
`reqUrl == videoUrl.lowercase()` tidak pernah cocok, dan dokumen UTAMA anikai
dikira frame bersarang: pengalihan keluar domain tidak dilaporkan gagal, galat
jaringan pada halamannya tidak memicu failover, dan Referer-nya salah.
Perbandingannya sekarang memakai `urlUtama` = `videoUrl.substringBefore('#')`.

**Frame pemutar bersarang adalah BUKTI KEMAJUAN, dan tanpa itu anikai dibuang
di 18 detik.** Elemen `<video>` anikai hidup di dalam iframe megaplay, jadi
dokumen LUAR selalu melapor `BYTIFY_DIAM` ("tak ada video") dan ambang
`BATAS_TUNGGU_MS` menghabisinya. Terukur di Galaxy A36: satu jalan anikai baru
melekatkan sumbernya pada detik ke-33 — dilewati pada detik ke-18, padahal ia
satu-satunya kandidat (server turunan TMDB dilewati oleh penjaga musim 3j),
sehingga pemutar menyatakan server habis atas server yang sedang berhasil.
Sekarang `adaFrameDalam` ditandai saat dokumen frame bersarang disisipi: DIAM
diabaikan, dan ambangnya naik ke `BATAS_SABAR_MS`. Sesudahnya: 4 detik dari
tombol ke `film berjalan`.

**Subtitle Indonesia anime TERBUKTI di A36 dari produksi.** Jujutsu Kaisen E1:
`subtitle anime: tmdb=95479 musim=1 ep=1 hasil=2 bahasa=[id, en]`, 389 baris
cue terkirim ke frame anak, teks "Aku menulisnya ulang." tampil di layar.
tmdbId dan season-nya datang dari `anime_servers.json`, bukan `watch_aliases`.

### 3q. Anime sepenuhnya milik anikai — id, katalog, server

AniList DIBUANG dari anime: bukan lagi sumber metadata, bukan lagi ruang id.
Katalog dibangun langsung dari `anikai_database.json`, id `anikai-<id>`.

Dua alasan terukur:

1. Pemutar berkunci AniList id (framextv) tidak bisa dipakai — empat "cermin"
   satu server, backend membalas HTTP 429.
2. Selama katalognya berid "anilist-", judul yang anikai punya tetapi AniList
   tidak kenal tidak bisa diterbitkan sama sekali. Itu membuang 1.466 judul
   yang sebenarnya bisa diputar.

Hasilnya 6.541 -> **8.007 judul**, 140.002 episode.

**Metadata anikai yang dipakai adalah JSON-LD halamannya, bukan keluaran
`scrape_anikai.js`.** Bedanya penting: skrip JS itu mengarang rating "8.2" dan
tahun "2026" (dilarang 3f), sedangkan `application/ld+json` membawa data asli
situsnya — `name`, `alternateName`, `description`, `genre`, `image`,
`datePublished`. Cakupannya hampir penuh: poster 8.007/8.007, sinopsis 7.978,
genre 7.990, durasi 8.005.

**Yang TIDAK tersedia: skor rating.** Jangan mengisinya dengan angka tebakan.

**Jembatan AniList turun pangkat jadi PENGAYA, bukan penyaring.**
`build_anime_servers.py` masih memakainya untuk mendapat `tmdbId` (5.723 dari
8.007) dan `season` (3.314) — keduanya hanya untuk SUBTITLE. Judul tanpa
padanan tetap terbit dan tetap bisa ditonton, hanya tanpa subtitle.
`watch_aliases` tidak bisa lagi menjangkau anime karena id-nya berubah, jadi
TMDB id ikut di lapisan server.

**Baris `anilist-%` lama WAJIB dibuang saat sinkron.** `pruneMovies` hanya
menyapu setelah katalog FILM, sementara anime datang dari berkas terpisah —
tanpa `hapusAnimeAniList()` yang tersisa adalah judul ganda berserver framextv
yang tidak bisa diputar. Pembersihnya dipanggil HANYA setelah katalog anime
baru benar-benar tersimpan; kalau unduhannya gagal, barisnya dibiarkan.

**Cap episode 14, bukan 26.** Dengan 8.007 judul, cap 26 memberi 22,75 MiB —
terlalu dekat ambang 24. Cap 14 memberi 19,24 MiB. Sisanya dilengkapi aplikasi
dari `episodeCount`.

**Plafonnya 8.007.** anikai hanya memuat 8.007 judul; itu seluruh katalog
anime sekarang.

**Kunci proses tunggal di `anikai_servers.py` jangan dilepas.** Dua pemanen
pernah berjalan bersamaan dan saling menimpa berkas sementara yang sama,
sehingga `os.replace()` gagal dan hasil panennya hilang — jebakan yang sama
yang sudah tercatat untuk `build_database.py`.

### 4. Pemutar sudah bekerja — jangan refactor `MoviePlayerScreen.kt` tanpa alasan kuat

- `sourceUrl` dipakai sebagai **Referer**; tanpa ini embed mengalihkan ke beranda.
- `shouldInterceptRequest` menyajikan `embedWrapperHtml()` dan menyuntik
  `GATE_BYPASS_JS` ke **setiap** dokumen HTML, termasuk iframe bersarang.
- `ketukOtomatis()` mengirim `MotionEvent` asli agar `isTrusted === true`.
- Pemuatan ditunda sampai `height > 0`, jika tidak pemutar hitam.
- Subtitle dirender sendiri dan diteruskan ke frame anak lewat `postMessage`.

### 5. Katalog besar WAJIB diurai dari aliran data

`response.body.string()` pada katalog 31,6 MB membuat String ~66 MB (UTF-16) dan
aplikasi **mati dengan OutOfMemoryError**. `MovieCatalogApi` dan `LiveCatalogApi`
memakai `json.decodeFromStream`. Jangan dikembalikan ke `decodeFromString`.

---

## Jebakan yang sudah memakan waktu — jangan diulang

### Scraper

- `ultimate_scraper.py` **wajib** mendeteksi versi Chrome saat runtime lewat
  `detect_chrome_major_version()`. Versi yang di-hardcode mematikan semua worker.
- Scraper **tidak boleh** menavigasi ke URL embed — embed mengalihkan ke beranda.
- Batasi ruang lingkup selector. `a[href*="/genre/"]` global pernah mengambil menu
  situs; `[class*=info] img` pernah mengambil poster dari sidebar.
- **LK21 tidak mengembalikan 404 di luar jangkauan halaman** — ia menyajikan
  halaman cadangan yang isinya berulang identik. Aturan berhenti yang benar:
  "halaman ini tidak membawa tautan baru", bukan menunggu 404.
- **Bedakan 404 dari timeout.** Menyamakannya membuat satu gangguan jaringan
  memotong seluruh kategori diam-diam, dan dua sapuan memberi angka berbeda.
- `load_existing_database()` **mengembalikan** daftar, TIDAK mengisi global
  `database`. Memanggilnya lalu membuang hasilnya, lalu `save_db_safely()`,
  akan **menghapus seluruh database**. Ini pernah terjadi.
- `save_db_safely()` harus dipanggil di dalam `save_lock`; tanpa itu worker
  berebut berkas sementara dan film yang digarap gagal (WinError 32/2).
- `SAVE_EVERY` jangan dikecilkan lagi — menulis ulang database 65 MB tiap 5 film
  memangkas laju dari 123 ke 58 film/menit.
- `RECYCLE_EVERY` menyegarkan browser tiap 150 film; Chrome menggelembung setelah
  ratusan halaman.

### Scraper serial

- Serial ada di **domain lain**. `/series`, `/nontondrama`, `/anime` di LK21
  hanyalah halaman pengalih berhitung mundur. `BUKAN_FILM` di `crawler.py`
  benar melewatinya; katalog serial ada di tv.nontondrama.vip.
- **Batasi ruang lingkup selector.** `a[href^="/genre/"]` global mengambil menu
  navigasi situs: versi pertama pemanen memberi "Action, Adventure, Animation"
  untuk SETIAP serial. Yang benar ada di dalam `.tag-list`.
  Nilai rating ada di `.rating-score`, bukan `.rating-box` (yang ikut membawa
  jumlah pemilih). Poster dari `og:image`, bukan `<img>` pertama — halaman
  serial memuat belasan poster rekomendasi.
- Berbeda dari halaman daftar film, URL episode di luar jangkauan di situs ini
  MENGEMBALIKAN 404 betulan. Jadi jumlah episode ditelusuri sampai 404, bukan
  ditebak dari kartu daftar yang bisa basi untuk serial ongoing.
- Daemon memanen ulang HANYA serial baru dan yang berstatus "Ongoing". Serial
  "Complete" tidak pernah bertambah episodenya.

### Room, Workers, dan nilai environment

- **Room MEMBANDINGKAN indices saat memvalidasi migrasi.** Index yang dibuat
  migrasi tetapi tidak dideklarasikan di entitas (atau sebaliknya) mematikan
  aplikasi saat pertama dibuka: "Migration didn't properly handle: <tabel>",
  padahal seluruh kolomnya cocok. Sudah terjadi pada `watch_aliases` di A36.
  Sumber kebenarannya `app/schemas/.../<versi>.json` yang dihasilkan Room —
  cocokkan migrasi dengan berkas itu, bukan dengan ingatan.
  Migrasi gagal di-rollback Room dalam transaksi, jadi datanya selamat; tapi
  aplikasinya tetap mati berulang sampai diperbaiki.
- **Jangan menyalin `CREATE INDEX` antar tabel tanpa memeriksa kueri-nya.**
  Index `tmdbId` perlu di `watch_titles` (dikueri `watchTitle(tmdbId, type)`),
  tetapi mubazir di `watch_aliases` yang hanya dicari lewat kunci utamanya.
- **`globalThis.fetch` yang diambil LEPAS lalu dipanggil telanjang** membuat
  `this` undefined. Node 18+ memaafkan, Cloudflare Workers melempar
  "Illegal invocation" yang keluar sebagai **error 1101 / HTTP 500**. Semua uji
  lokal bisa lolos dan tetap tidak menangkapnya. Ikat: `(...a) => globalThis.fetch(...a)`.
- **Bungkus panggilan luar dengan pemeta galat.** Exception yang lolos di
  Workers jadi 1101 mentah yang tidak memberi tahu apa pun — kepada APK maupun
  kepada yang men-debug.
- **Nilai environment yang ditempel sering membawa baris-baru.** Runtime hanya
  bilang "Invalid header value" tanpa menyebut header mana. `subtitles.mjs`
  karena itu membersihkan CR/LF/TAB lalu memeriksa ASCII cetak, dan menyebut
  NAMA variabel beserta kode karakternya kalau masih ada yang liar.
- **Mengubah environment variable di Cloudflare TIDAK berlaku untuk deployment
  yang sudah ada.** Harus ada deploy baru. Kalau sebuah variabel tampak tidak
  terbaca, periksa itu dulu sebelum mencurigai kodenya.
- **`backend/api/.env` tidak terbaca sendiri.** `loadEnvFile()` dipanggil dari
  `startServer()`, bukan dari `createApp()`, supaya uji tidak menyerap `.env`
  mesin pengembang.

### Proses di Windows

- **`pkill` dari Git Bash TIDAK mematikan proses Windows.** Pakai PowerShell
  `Stop-Process`. Tiga `build_database` pernah berjalan bersamaan menulis database
  yang sama karena ini.
- Menghentikan paksa Python **meninggalkan Chrome-nya hidup**. Pernah menumpuk
  286 Chrome yatim memakan 12 GB sampai mesin swap. Matikan keduanya, dan saring
  hanya Chrome otomasi (`--no-sandbox`/`--remote-debugging-port`) supaya browser
  pribadi tidak ikut mati.
- Ada kunci proses tunggal di `build_database.py`; jangan dilepas.

### Aplikasi

- **Jangan menyaring/mengurutkan daftar besar di badan composable.** Bungkus
  dengan `remember(...)`. Berlaku untuk `MoviesScreen` (25 ribu film) dan
  `LiveTvScreen` (8.681 kanal).
- Pemetaan baris Room yang besar harus `flowOn(Dispatchers.Default)` —
  `MovieRepository`, `MusicCatalogRepository`, dan `LiveRepository` sudah.
- `pointerInput` membuat lambdanya **sekali**; nilai yang ditangkap di dalamnya
  membeku. Pakai `rememberUpdatedState` (lihat `ruteKini` di `BottomBars.kt`),
  jika tidak kapsul navigasi berpindah tetapi halaman tidak ikut berganti.
- Kotlin: `"\s"` bukan escape yang sah. Pakai raw string: `Regex("""\n\s*\n""")`.
- Tidak adanya file m3u8 di database film itu **normal**. LK21 memakai HLS di
  dalam iframe lintas-origin; yang disimpan adalah daftar `servers`.
- URL sampah `donasi.assetsy.de/stopjudi/*.mp4` disaring `isJunkMediaUrl()` di
  `cleanUrlOrNull()`. Jangan dilonggarkan.
- **Satu LazyColumn untuk tampilan bawaan DAN hasil pencarian.** Dulu keduanya
  pohon komposisi berbeda, jadi huruf pertama yang diketik menukar seluruh
  pohon: kolom cari kehilangan identitasnya, fokus lepas, papan ketik menutup
  sendiri.
- **Teks petunjuk kolom cari harus `maxLines = 1`.** Teks panjang membungkus
  jadi dua baris dan membuat kolom TERLIHAT dua kali lebih tinggi daripada
  kolom yang sama di Radio, padahal tingginya identik (68px vs 136px terukur).
  Film, Live TV, Podcast, dan Radio kini memakai `SearchField` yang sama.
- Deret "Film Terbaru <tahun>" dibentuk aplikasi dari tahun perangkat, bukan
  dari judul bagian di katalog, supaya ikut berganti sendiri saat tahun baru.
  Bagian katalog berjudul sama dilewati agar tidak muncul dua kali.
- Status kanal Live TV menandakan **kesegaran data**, bukan hasil pengecekan
  stream. `isAvailable` selalu true dari server; `LIVE_STATUS_MAX_AGE_MS` disetel
  sehari agar cocok dengan irama katalog yang statis.

---

## Sumber data dan batasnya

| Sumber | Batas terukur |
|---|---|
| Apple RSS lagu/album | 100 per negara (101+ -> HTTP 500) |
| Apple RSS podcast | 100 (200 -> HTTP 500) |
| radio-browser | tak terbatas; Indonesia punya 505 stasiun |
| iptv-org | 18.605 stream -> 8.681 kanal unik HTTPS non-NSFW |
| LK21 | 62 kategori, 25.204 URL film |
| tv.nontondrama.vip | 40 kategori, 5.143 URL serial -> 4.919 serial |
| radio-browser (negara) | daftar `countrycodes`; US 7.082, DE 5.649 stasiun |

Katalog lagu dipanen dari **110 negara** Apple RSS (dua umpan: `songs` dan
`music-videos`) ditambah **67 kueri benih YouTube** lewat NewPipe. Apple membatasi
laju: jeda antar negara 1,2 detik, dengan satu putaran ulang untuk yang ditolak.

---

## Prosedur pengujian di LDPlayer

```bash
# 1. Sajikan keluaran build (bukan backend/data, supaya yang diuji = yang terbit)
node backend/api/scripts/build-pages.mjs backend/api/dist
python -m http.server 8100 --directory backend/api/dist

# 2. Arahkan APK ke sana (local.properties TIDAK dilacak git)
#    movie.catalog.url=http://127.0.0.1:8100/api/catalog.json
#    live.catalog.url=http://127.0.0.1:8100/api/live/channels.json

# 3. LDPlayer bisa dinyalakan sendiri
"D:\LDPlayer\LDPlayer14\ldconsole.exe" launch --index 0
adb connect 127.0.0.1:5555

# 4. Pasang, jembatani, mulai dari nol
adb -s <DEV> install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s <DEV> reverse tcp:8100 tcp:8100
adb -s <DEV> shell pm clear com.bytify.app
adb -s <DEV> shell am start -n com.bytify.app/.MainActivity

# 5. Verifikasi dari DATABASE, bukan dari tampilan
adb -s <DEV> shell "su -c 'sqlite3 /data/data/com.bytify.app/databases/bytify.db \
  \"SELECT (SELECT COUNT(*) FROM movies), (SELECT COUNT(*) FROM movie_sections), \
    (SELECT COUNT(*) FROM live_channels);\"'"
adb -s <DEV> logcat -d | grep -icE "FATAL EXCEPTION|OutOfMemoryError"
```

Catatan penting:
- **Kanal Live TV baru tersinkron saat layarnya dibuka**, bukan saat aplikasi mulai.
- Video film **tidak berputar di LDPlayer** — CA store lawas menolak sertifikat CDN
  (`CertPathValidatorException`, `net_error -202`). Itu batasan emulator, bukan bug.
  Pemutaran terbukti bekerja di Galaxy A36.
- `screenrecord` **memblokir sentuhan** di LDPlayer; untuk bukti visual pakai
  `screencap` beruntun.

### Mengukur kelancaran

```bash
adb -s <DEV> shell dumpsys gfxinfo com.bytify.app reset
# ...interaksi...
adb -s <DEV> shell dumpsys gfxinfo com.bytify.app | grep -E "Janky|percentile"
```

**Angka di LDPlayer tidak bisa dipercaya.** Build identik pernah memberi 11% dan
30% jank. Batas bawah emulator saja sudah ~17% jank dengan persentil-90 19 ms,
jadi target di bawah 5%/16 ms mustahil tercapai di sini. Ukur di **Galaxy A36**
kalau ingin angka yang sahih.

---

## Cara kerja yang saya harapkan

- Ubah **isi** file, pertahankan nama file. Hapus file yang tidak terpakai.
- Sebelum menyatakan sesuatu selesai, **buktikan**: jalankan, baca log, lihat
  tangkapan layar. Hapus log dulu, lalu periksa stempel waktu.
- Kalau sebuah pengukuran tidak konklusif, **katakan begitu** — jangan mengklaim
  perbaikan yang tidak terbukti.
- Edit file di tempat (`sed -i`, awk, atau baca-ubah-tulis dengan Python).
  Jangan menulis ulang isi file dari keluaran tool yang mungkin terpotong.
- Pesan commit dalam Bahasa Inggris, satu baris, imperatif.
- Kalau ada keputusan besar yang bisa jatuh ke dua arah dan sulit dibatalkan,
  tanya saya dulu. Selain itu jalan terus, lalu laporkan hasilnya di akhir.

---

## Pekerjaan yang masih terbuka

- [x] Daemon dijadwalkan saat login (`schtasks /query /tn "Bytify Auto-Sync"`).
      Perintahnya WAJIB dijalankan dari terminal Administrator; pemicu onlogon
      selalu menolak tanpa elevasi ("ERROR: Access is denied").
      **Format `/delay` adalah `mmmm:ss`, bukan `jam:menit`.** Perintah lama di
      berkas ini memakai `/delay 0000:02` yang berarti 2 DETIK, bukan 2 menit —
      terbaca sebagai `PT2S` saat tugasnya diperiksa. Yang benar:
      `schtasks /create /tn "Bytify Auto-Sync" /tr "E:\Portofolio\Karya\Bytify\backend\scraper\update_bytify.bat" /sc onlogon /delay 0002:00 /rl limited /f`
- [ ] Tugas terjadwal bawaannya TIDAK berjalan saat laptop memakai baterai
      (`DisallowStartIfOnBatteries=True`). Kalau daemon tampak tidak pernah
      jalan, periksa itu dulu sebelum mencurigai skripnya.
      Mengubahnya WAJIB dari PowerShell Administrator -- tanpa elevasi
      `Set-ScheduledTask` menjawab "Access is denied", sama seperti pembuatan
      tugasnya.
- [x] TERBUKTI di Galaxy A36 (SM-A366B, Android 17): film Inception BERPUTAR —
      `BYTIFY_MAIN` muncul 5 detik setelah tombol ditekan, status "Server 1 dari
      6 berjalan", chip server katalog + turunan tampil berdampingan, subtitle
      Indonesia/English ikut terisi. Trailer Inception dan Interstellar juga
      berputar di dalam aplikasi. Nol FATAL EXCEPTION, Dalvik 16,9 MB mengendap,
      TOTAL PSS 196 MB — sejalan dengan angka 3l.
      CATATAN: LDPlayer memalsukan profil perangkat (model PJH110/OPPO) tetapi
      ABI-nya `x86_64`; perangkat fisik ber-ABI `arm64-v8a`. Periksa ABI, bukan
      model, untuk tahu sedang menguji di mana.
- [x] Subtitle TERBUKTI bekerja dari produksi: /api/subtitles memulangkan 50
      kecocokan untuk tmdb 27205, dan /api/subtitles/file mengalihkan ke SRT
      121.932 byte berisi 1.592 cue. `fetch_subtitles.py` yang lama memakai
      pendekatan panen-lalu-terbit yang tidak bisa dipakai (lihat 3g), dan
      `cari_imdb_id()`-nya mengambil hasil pencarian TMDB PERTAMA tanpa
      verifikasi — itu bug salah pasang. Jangan dipakai.
- [x] Migrasi Room v11 TERUJI di Galaxy A36 dengan memasang di atas data v10:
      user_version 11, tiga tabel baru terisi (11.937 / 47 / 20.145), movies
      51.015 dan sections 7 utuh, nol FATAL EXCEPTION. Chip terjangkau oleh
      7.172 film LK21 dan 1.018 film Indonesia — diukur dengan JOIN di
      database perangkat, bukan di PC.
- [x] Podcast lintas negara (31 storefront Apple); lihat 3t
- [x] Status kanal Live TV diukur `probe_live.py`: 6.363/8.755 hidup; lihat 3s
- [ ] Animasi navigasi belum dibandingkan bingkai-per-bingkai dengan video acuan
