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

## Peta berkas

```
backend/scraper/ultimate_scraper.py    Selenium + undetected-chromedriver;
                                       metadata & daftar server dari halaman LK21
backend/scraper/crawler.py             penjelajah paralel daftar film
backend/scraper/build_database.py      bytify_database.json -> catalog.json (ramping)
backend/scraper/tv_garden_scraper.py   kanal Live TV
backend/scraper/fetch_subtitles.py     OpenSubtitles + TMDB -> backend/data/subtitles/
backend/scraper/auto_sync_daemon.py    crawl -> build -> scrape TV, berulang
backend/scraper/update_bytify.bat      pembungkus daemon di atas
backend/scraper/movie_links.txt        sumber daftar URL film

backend/data/bytify_database.json      hasil scraping mentah (~27 MB, 10.019 film)
backend/data/catalog.json              yang diunduh APK (~12,4 MB; ~2,8 MB terkompresi)

backend/api/                           API Node (server.mjs, src/movies.mjs, src/live.mjs)
functions/                             Cloudflare Pages Functions (api/, health.js)

app/src/main/java/com/bytify/app/
  AppContainer.kt                      autoSyncKatalogFilm()
  data/movie/MovieModels.kt            DTO + domain, cleanUrlOrNull/isJunkMediaUrl
  data/movie/MovieRepository.kt        refreshIfStale(), konstanta TTL
  data/movie/MovieCatalogApi.kt        pengunduh katalog (OkHttp)
  data/db/Entities.kt                  kolom sourceUrl, serversJson
  data/db/BytifyDatabase.kt            Room v8, MIGRATION_6_7, MIGRATION_7_8
  data/db/BytifyDao.kt                 lastMovieSync()
  ui/screens/MoviesScreen.kt           refreshCatalog(paksa)
  ui/screens/MoviePlayerScreen.kt      WebView, bypass gerbang iklan, subtitle
  ui/screens/MovieDetailScreen.kt
```

Katalog di-host di Cloudflare Pages: `https://bytify-api.pages.dev/api/catalog.json`

---

## Keputusan final — jangan diubah tanpa saya minta

### 1. Tidak perlu terbit APK ulang untuk menambah film

APK menarik `catalog.json` sendiri lewat `MovieRepository.refreshIfStale()`
(TTL 3 jam, `USIA_KATALOG_DEFAULT_MS = 3L * 60 * 60 * 1000`; mengembalikan
`KATALOG_MASIH_SEGAR = -1` bila unduhan dilewati), ditambah loop
`AppContainer.autoSyncKatalogFilm()`. `MoviesScreen` membuka dengan
`refreshCatalog(paksa = false)`.

Alur rilis film baru: jalankan scraper -> unggah `catalog.json` ke pages.dev.
Selesai. Tidak ada build APK.

### 2. Gzip tidak perlu dikonfigurasi

Cloudflare Pages sudah mengompres dengan brotli otomatis. Terukur lewat
`performance.getEntriesByType('navigation')[0]`: 595 KB -> 78 KB (rasio 0,131).
Jangan tambah lapisan kompresi manual.

### 3. Katalog harus tetap ramping

`build_database.py` memakai allowlist `FIELD_KATALOG` (18 field) dan
`rampingkan_dto()` yang membuang nilai kosong (`None`, `""`, `[]`, `{}`),
ditulis lewat `_atomic_write(padat=True)` dengan `separators=(",", ":")`.
Pernah 19,2 MB -> sekarang 12,4 MB. Menambah field ke katalog harus disengaja
dan diukur ulang.

### 4. Pemutar sudah bekerja — jangan refactor `MoviePlayerScreen.kt` tanpa alasan kuat

Tiap bagian di bawah ini menyelesaikan satu bug nyata:

- `sourceUrl` dibawa dari katalog ke Room dan dipakai sebagai **Referer**.
  Tanpa ini embed mengalihkan ke beranda LK21.
- `shouldInterceptRequest` menyajikan `embedWrapperHtml()` untuk frame utama,
  karena `loadDataWithBaseURL` tidak mengirim Referer sama sekali. Ia juga
  menyuntik `GATE_BYPASS_JS` ke **setiap** dokumen HTML (dideteksi dari header
  `Accept: text/html`), termasuk iframe `playcdn.de` yang bersarang — elemen
  `<video>` ada di frame anak itu, bukan di frame utama.
- `GATE_BYPASS_JS` memalsukan objek `window.open` lalu memanggil
  `buangLapisanGerbang()` untuk melewati gerbang iklan "Klik Di Mana Saja".
- `ketukOtomatis()` mengirim `MotionEvent` ACTION_DOWN/UP asli lewat
  `dispatchTouchEvent` agar `isTrusted === true`. Ketukan JavaScript sintetis
  ditolak situsnya.
- Pemuatan ditunda sampai `height > 0`. Factory `AndroidView` berjalan sebelum
  layout, jadi viewport nol dan pemutar tampil hitam.
- Subtitle dirender sendiri: `gambarSub()` pada interval 150 ms, cue diteruskan
  ke frame anak lewat `postMessage`.
- Chip "Kualitas / Server" mengganti `activeEmbedUrl`; `key(activeEmbedUrl)`
  memaksa WebView dibuat ulang sehingga resolusi berganti seketika tanpa
  menyuruh pengguna ganti channel.

---

## Jebakan yang sudah memakan waktu — jangan diulang

- `ultimate_scraper.py` **wajib** mendeteksi versi Chrome saat runtime lewat
  `detect_chrome_major_version()`. Mesin ini memakai Chrome 156; versi yang
  di-hardcode mematikan seluruh worker sekaligus.
- Scraper **tidak boleh** menavigasi ke URL embed — embed mengalihkan ke beranda.
  Metadata hanya diambil dari HTML halaman film.
- Batasi ruang lingkup selector. Pakai `div.movie-info`, bukan selector global.
  `a[href*="/genre/"]` global pernah mengambil menu situs (27 genre identik untuk
  semua film); `[class*=info] img` pernah mengambil poster dari sidebar film
  terkait karena cocok dengan `div.video-info`.
- Kotlin: `"\s"` bukan escape yang sah. Pakai raw string: `Regex("""\n\s*\n""")`.
- Tidak adanya file m3u8 di database itu **normal**. LK21 memakai HLS di dalam
  iframe lintas-origin, bukan URL media langsung; yang disimpan adalah daftar
  `servers`, bukan stream.
- Subtitle LK21 umumnya hardsub (dibakar ke gambar). Trek teks multi-bahasa
  datang dari `fetch_subtitles.py` (butuh `OPENSUBTITLES_API_KEY` dan
  `TMDB_API_KEY`), bukan dari scraping.
- `build_database.py` punya penjaga tahun `time.gmtime().tm_year + 1`. Tanpa itu
  muncul bagian "Film Terbaru 2038" dari data tahun yang rusak.
- URL sampah dulu berasal dari `donasi.assetsy.de/stopjudi/*.mp4` yang memuat
  dirinya sebagai overlay halaman. Disaring oleh `isJunkMediaUrl()` di dalam
  `cleanUrlOrNull()` (MovieModels.kt). Jangan longgarkan filter itu.

---

## Prosedur pengujian di LDPlayer

Urutan yang terbukti bekerja — jalankan sendiri:

```bash
# 1. Sajikan katalog lokal
python -m http.server 8099 --directory backend/data

# 2. Port ADB LDPlayer berpindah setelah restart; sapu lalu ambil yang 'device'
for p in 5555 5554 5556 5557 5558 62001 62025 62026; do
  adb connect 127.0.0.1:$p
done
adb devices

# 3. Jembatani emulator ke host
adb -s <DEV> reverse tcp:8099 tcp:8099

# 4. Paksa unduh ulang katalog
adb -s <DEV> logcat -c
adb -s <DEV> shell pm clear com.bytify.app
adb -s <DEV> shell am start -n com.bytify.app/.MainActivity

# 5. Buka tab Jelajah (koordinat ~591 1522), tunggu sinkronisasi ~100 detik

# 6. Verifikasi
adb -s <DEV> shell uiautomator dump /sdcard/j1.xml
adb -s <DEV> exec-out screencap -p > jelajah.png
adb -s <DEV> logcat -d | grep -iE "FATAL EXCEPTION|AndroidRuntime|Serialization|MissingField|JsonDecoding"
```

Catatan: emulator kadang memunculkan dialog "UI Sistem tidak menanggapi" yang
menelan ketukan pertama. Tutup dengan `input keyevent 4`, lalu ulangi ketukan.

Hasil uji terakhir (08/11/2026): 10.019 film termuat, poster dan tahun tampil,
bagian "Film Terbaru 2026" dan "Rating Tertinggi" terisi, tanpa crash maupun
galat penguraian meski 17 field dibuang dari katalog.

---

## Mutu data saat ini

10.019 film — poster 100%, server 100%, durasi 99%, sinopsis 98%, rating 96%,
URL sampah 0%.

---

## Keadaan repo saat panduan ini ditulis (11/11/2026)

Ada **22 berkas yang belum di-commit** di `feature/complete-1.3.0`, mencakup
seluruh pekerjaan pemutar, Room v8, perampingan katalog, dan API backend:

```
M  AppContainer.kt, BytifyDao.kt, BytifyDatabase.kt, Entities.kt
M  MovieModels.kt, MovieRepository.kt
M  LiveTvScreen.kt, MoviePlayerScreen.kt, MoviesScreen.kt
M  backend/api/server.mjs, src/live.mjs, src/movies.mjs, test/server-contract.test.mjs
M  backend/scraper/build_database.py, ultimate_scraper.py, movie_links.txt
?? app/schemas/.../7.json, 8.json      (skema Room — harus ikut di-commit)
?? backend/data/catalog.json
?? backend/scraper/fetch_subtitles.py
```

Periksa `git status` dulu sebelum mulai; jangan asumsikan sudah bersih.

---

## Pekerjaan yang masih terbuka

- [ ] Commit perubahan di atas, pecah jadi beberapa commit yang masuk akal
- [ ] Unggah `backend/data/catalog.json` terbaru ke pages.dev
- [ ] `fetch_subtitles.py` belum pernah dijalankan sungguhan (butuh kunci API)
- [ ] Jadwalkan `auto_sync_daemon.py` agar katalog tetap segar tanpa dijalankan manual
- [ ] Hapus sisa berkas uji: `build-run-out.txt`, `build-run-err.txt`,
      `watchdog-log.txt`, `jalankan-penjaga.bat` (dua yang pertama terkunci
      proses watchdog; tutup jendela "BytifyPenjaga" dulu)
- [ ] `backend/scraper/__pycache__/` ikut terlacak git — masukkan ke `.gitignore`

---

## Cara kerja yang saya harapkan

- Ubah **isi** file, pertahankan nama file. Hapus file yang tidak terpakai.
- Sebelum menyatakan sesuatu selesai, **buktikan**: jalankan, baca log, lihat
  tangkapan layar. Jangan menyimpulkan dari log lama — hapus log dulu, lalu
  periksa stempel waktu `Mulai:` dan mtime berkas.
- Edit file di tempat (`sed -i`, awk, atau baca-ubah-tulis dengan Python).
  Jangan pernah menulis ulang isi file dari keluaran tool yang mungkin terpotong.
- Pesan commit dalam Bahasa Inggris, satu baris, imperatif.
- Kalau ada keputusan besar yang bisa jatuh ke dua arah dan sulit dibatalkan,
  tanya saya dulu. Selain itu jalan terus, lalu laporkan hasilnya di akhir.
