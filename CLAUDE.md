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
| Serial | 4.919 | 65.296 episode; berkas terbit TERPISAH |
| Kanal Live TV | 8.681 | 180 negara, semua HTTPS |
| Katalog lagu | ~5.000 | di Room perangkat, bukan berkas |
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
backend/scraper/series_links.txt         5.143 URL serial hasil sapuan
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
  data/db/BytifyDatabase.kt            Room v10, MIGRATION_8_9 & 9_10
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
- [ ] Sambungkan Galaxy A36 untuk pengukuran performa yang sahih
- [ ] `fetch_subtitles.py` belum pernah dijalankan sungguhan (butuh kunci API)
- [ ] Podcast masih Indonesia saja; radio sudah lintas negara
- [ ] Status kanal Live TV belum memverifikasi stream hidup/mati
- [ ] Animasi navigasi belum dibandingkan bingkai-per-bingkai dengan video acuan
