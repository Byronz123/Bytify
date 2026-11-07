# Bytify API (Film, Serial & Live TV)

Backend Node.js ≥ 20 tanpa dependency npm. Menyediakan katalog film/serial dan kanal Live TV untuk APK Bytify.

## Menjalankan

```bash
cd backend/api
cp .env.example .env      # lalu isi
npm test                  # 12 tes otomatis
npm start                 # default port 8787
```

Produksi: jalankan di belakang reverse proxy HTTPS (Caddy/Nginx), atau dengan `Dockerfile`.

## Sumber data (adapter)

`MOVIE_PROVIDERS` berisi daftar adapter yang dipisah titik koma. UI Android tidak perlu diubah saat adapter diganti.

| Adapter | Isi |
|---|---|
| `archive` (default) | Internet Archive, hanya item berlisensi domain publik (`licenseurl` = publicdomain). Video MP4 dan subtitle `.srt/.vtt` diambil langsung dari arsip. |
| `lk21` | Scraper resmi LK21/NontonDrama, atau custom API yang di-set melalui `LK21_API_BASE_URL`. |
| `file` | File JSON `{ "movies": [], "sections": [] }` yang dikurasi operator (`MOVIE_CATALOG_FILE`). Setiap item wajib punya `license`. |

Saat Anda sudah menyiapkan server `lk21-api-master`, isi `LK21_API_BASE_URL` agar backend Bytify memakai endpoint custom itu sebagai sumber film. Format yang didukung adalah endpoint `GET /movies`, `/series`, `/popular/movies`, `/popular/series`, `/search/{query}`, dan detail per item. 

Filter adapter `archive`:
- Koleksi `feature_films_unsorted` dilewati.
- Film tanpa tahun rilis, atau yang dirilis setelah `ARCHIVE_MAX_YEAR` (default 1977), dilewati. Unggahan baru sering salah label lisensi.
- Judul atau subjek yang mengandung kata di `MOVIE_BLOCKED_TERMS` dilewati.
- ID di `MOVIE_BLOCKLIST` dilewati.

Live TV membaca playlist M3U dari `LIVE_PLAYLIST_URLS`, baik URL HTTPS maupun path berkas lokal. Alternatifnya, Anda bisa memakai Xtream API dengan `LIVE_XTREAM_URL`, `LIVE_XTREAM_USERNAME`, dan `LIVE_XTREAM_PASSWORD`; backend akan membangun URL stream dari `player_api.php`. Fitur ini hanya aktif bila `LIVE_SOURCE_RIGHTS_CONFIRMED=true` dan `LIVE_SOURCE_LICENSE_LABEL` terisi. Stream dicek berkala dengan permintaan GET+Range. Kanal HLS dianggap online hanya bila respons diawali `#EXTM3U`.

`test/fixtures/test-streams.m3u` **hanya untuk pengujian**. Isinya stream contoh publik dari Apple dan Mux, bukan kanal TV.

## Endpoint

| Endpoint | Respons |
|---|---|
| `GET /api/catalog.json` | Kontrak APK `{ movies, sections, generatedAtEpochMs }`, dengan ETag/304 dan cache 5 menit |
| `GET /api/movies?page&pageSize&genre&sort=popular\|latest\|rating\|title` | `{ ok, data:[ringkasan], meta:{page,pageSize,total,totalPages,hasNext} }` |
| `GET /api/movies/{id}` | `{ ok, data }` detail lengkap (episode untuk serial) |
| `GET /api/series` | Sama seperti `/api/movies`, khusus serial |
| `GET /api/genres` | Daftar genre |
| `GET /api/search?q=&type=movie\|series\|live` | Film, serial, dan kanal; `q` 2–100 karakter |
| `GET /api/live/channels?country&category&available=true[&page&pageSize]` | Kontrak APK `{ schemaVersion, channels, ... }` |
| `GET /api/live/categories` | `{ categories, counts, countries }` |
| `GET /api/live/channel/{id}` | `{ ok, data }` |
| `GET /api/sync/status` | Status sinkronisasi film dan live |
| `GET /health` | `{ ok, uptimeSeconds }` |

Error memakai format `{ "ok": false, "error": { "code": "NOT_FOUND", "message": "..." } }`. Status 503 dikirim bila katalog belum siap atau Live TV belum dikonfigurasi.

## Sinkronisasi

- Film: setiap `MOVIE_REFRESH_SECONDS` (default 6 jam). Live: playlist setiap `LIVE_SOURCE_REFRESH_SECONDS`, status stream setiap `LIVE_HEALTH_CHECK_SECONDS`.
- Bila sinkronisasi gagal, dicoba ulang setelah 1, 2, 4 menit, dan seterusnya, maksimal 15 menit. Data terakhir tetap disajikan selama sumber sedang gagal.
- Log berformat JSON per baris, termasuk satu baris untuk setiap request.
