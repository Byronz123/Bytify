# Bytify — Musik, Podcast, Radio, Film & Live TV

Bytify adalah aplikasi hiburan Android Native berbasis Kotlin dan Jetpack Compose. Satu proyek ini memuat pemutar musik, podcast, radio internet, katalog dan pemutar film, serta katalog dan pemutar Live TV.

## Modul aplikasi

### Musik

- Pencarian lagu dari YouTube Music melalui repository yang sudah ada.
- Pemutar audio berbasis AndroidX Media3/ExoPlayer dan MediaLibraryService.
- Antrean, lirik tersinkron dan terjemahan, radio otomatis, equalizer, bass boost, tempo/nada, sleep timer.
- Koleksi lagu disukai, playlist, unduhan, impor playlist, riwayat, statistik, dan recap.
- Dengar Bareng melalui Supabase Realtime (opsional).
- Widget, Android Auto, alarm musik, dan pembaruan aplikasi.

### Podcast

- Jelajah dan pencarian podcast melalui katalog Apple Podcasts/iTunes.
- Episode diambil dari RSS feed resmi masing-masing podcast.
- Langganan podcast, daftar episode, pemutaran audio, dan penyimpanan progres episode.

### Radio

- Penemuan stasiun dari Radio Browser API.
- Pencarian stasiun, kategori/genre, negara, favorit stasiun, dan pemutaran stream internet.
- Radio dan podcast menggunakan jalur audio langsung; keduanya tidak mengganggu model data favorit film/live.

### Film & serial

- Katalog JSON dengan poster, backdrop, sinopsis, tahun, durasi, genre, rating, bahasa, trailer, subtitle, dan metadata lisensi.
- Pencarian, section/kategori, detail film, favorit film, dan cache lokal Room.
- Pemutar video internal berbasis Media3 PlayerView dengan dukungan format yang dapat dibaca Media3, subtitle track (WebVTT/SubRip/TTML/SSA), dan resume posisi tontonan.
- URL video harus berasal dari penyedia yang mengizinkan penggunaan dan distribusi.

### Live TV

- Katalog kanal dinamis dari backend Bytify.
- Kategori, negara, pencarian, logo, status ketersediaan, dan favorit kanal.
- Pemutar video Media3 untuk HLS (`.m3u8`) dan MP4.
- Backend dapat membaca playlist M3U milik provider yang memberi izin distribusi, memeriksa status stream, dan memperbarui snapshot katalog.
- Cache Room tetap ditampilkan saat server katalog sementara tidak dapat dijangkau.

## Navigasi aplikasi

Navigasi bawah: Beranda, Cari, Jelajah, Koleksi. Tab **Jelajah** berisi Film, Podcast, Radio, dan Live TV.

<!-- rincian lama -->

- **Beranda**: musik dan rekomendasi.
- **Film**: katalog, pencarian, section, dan detail film.
- **Live**: daftar kanal TV dan pemutar siaran.
- **Jelajah**: memiliki tab **Podcast** dan **Radio**.
- **Cari**: pencarian musik.
- **Koleksi**: koleksi musik yang sudah ada.

Favorites musik, film, dan kanal live disimpan di tabel terpisah.

## Teknologi

- Kotlin, Jetpack Compose, Navigation Compose.
- AndroidX Media3: ExoPlayer, HLS, MediaSession, PlayerView.
- Room SQLite (schema database v5) dan DataStore.
- OkHttp, Kotlin Serialization, Coil, NewPipe Extractor.
- Backend film, serial & Live TV: Node.js (ES modules), tanpa dependency npm runtime eksternal.

## Persyaratan build

- JDK 17 atau lebih baru.
- Android SDK Platform 36 dan Android Build Tools yang cocok dengan Android Gradle Plugin 8.13.0.
- Gradle Wrapper 8.14.3 (diunduh otomatis oleh wrapper jika belum ada di mesin build).

## Konfigurasi Android

Salin `local.properties.example` menjadi `local.properties`, lalu sesuaikan `sdk.dir` dan URL layanan yang kamu miliki:

```properties
sdk.dir=/path/ke/Android/Sdk
# Opsional untuk Dengar Bareng; gunakan publishable key, jangan secret key.
supabase.url=https://PROJECT_REF.supabase.co
supabase.key=sb_publishable_...
# Opsional: endpoint JSON katalog film Bytify.
movie.catalog.url=https://YOUR_DOMAIN/api/catalog.json
# Opsional: endpoint JSON Live TV Bytify.
live.catalog.url=https://YOUR_DOMAIN/api/live/channels
```

`local.properties`, `keystore.properties`, dan file signing tidak disertakan dalam source archive.

## Kontrak katalog film

Dokumentasi lengkap: [`docs/movie-catalog-api.md`](docs/movie-catalog-api.md).

Contoh endpoint:

```http
GET https://YOUR_DOMAIN/api/catalog.json
Accept: application/json
```

Respons memiliki objek tingkat atas `movies` dan `sections`. Contoh JSON: [`examples/movie-catalog.example.json`](examples/movie-catalog.example.json).

## Backend (Film, Serial & Live TV)

Backend berada di [`backend/api`](backend/api). Panduan lengkap: [`backend/api/README.md`](backend/api/README.md).

Mulai backend:

```bash
cd backend/api
cp .env.example .env
# Film langsung jalan (Internet Archive, domain publik). Isi LIVE_PLAYLIST_URLS untuk Live TV.
npm test
npm start
```

Backend harus dipasang di server kamu sendiri, idealnya di belakang HTTPS reverse proxy. `LIVE_SOURCE_RIGHTS_CONFIRMED=true` adalah deklarasi konfigurasi bahwa kamu sudah memiliki hak distribusi; jangan mengaktifkannya untuk playlist yang tidak memberikan hak tersebut. Jangan menaruh kredensial atau URL rahasia ke repository publik.

Backend melakukan pemeriksaan status stream berdasarkan respons jaringan dari sisi server. Status tersebut bukan jaminan stream dapat diputar dari semua negara, ISP, perangkat, atau jaringan pengguna.

## Build APK

Dari root proyek:

```bash
./gradlew :app:assembleDebug
```

Di Windows:

```bat
gradlew.bat :app:assembleDebug
```

APK debug umumnya dihasilkan di `app/build/outputs/apk/debug/app-debug.apk`.

Untuk build rilis, siapkan `keystore.properties` pribadi. Jangan mengganti signing key bila kamu ingin mempertahankan jalur pembaruan otomatis dari rilis sebelumnya.

## Data lokal dan migrasi

Database Room saat ini versi 6. Migrasi 3→4 menambahkan tabel film, 4→5 tabel kanal live dan favorit live, 5→6 kolom `type` dan `episodesJson` untuk serial. Semua migrasi aditif, tidak destruktif. Tabel lagu, playlist, podcast, radio, dan riwayat musik yang sudah ada tidak dihapus oleh migrasi tersebut. Buat backup data sebelum memasang build pengembangan di atas instalasi pribadi.

## Batas integrasi yang perlu dikonfigurasi

Source code ini sudah memuat implementasi klien dan UI kelima modul. Agar katalog film dan Live TV menampilkan data aktual, kamu tetap perlu menyediakan endpoint katalog dan sumber media yang sah. Source archive tidak berisi daftar kanal privat, URL siaran berbayar, kredensial provider, ataupun konten film.

- Film: jalankan backend lalu isi `movie.catalog.url=https://HOST/api/catalog.json` di `local.properties`. Untuk emulator dengan `adb reverse tcp:8787 tcp:8787`, pakai `http://127.0.0.1:8787/api/catalog.json`.
- Live TV: isi `live.catalog.url=https://HOST/api/live/channels`, deploy backend, dan daftarkan playlist provider yang mengizinkan distribusi.
- Musik/podcast/radio: menggunakan integrasi jaringan yang sudah terdapat di source code; ketersediaannya mengikuti layanan dan feed sumbernya.

## Lisensi

Lihat file [`LICENSE`](LICENSE). Hormati ketentuan layanan, hak cipta, dan lisensi setiap penyedia konten/media yang diintegrasikan.
