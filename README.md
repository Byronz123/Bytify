# Bytify

Pemutar musik Android tanpa iklan. Katalog dan audio diambil langsung dari YouTube Music, tanpa menyimpan file MP3.

## Fitur

- **Beranda pintar**: quick picks, lanjutkan mendengarkan, Top 50 Indonesia, "Karena kamu mendengarkan…", favorit yang terlupakan, mood & genre
- **Player**: lirik tersinkron (LrcLib) dan terjemahan Bahasa Indonesia, antrean, radio otomatis, sleep timer, tempo & nada, equalizer + bass boost
- **Koleksi**: lagu disukai, playlist, unduhan offline, impor playlist dari YouTube / Spotify / Apple Music
- **Statistik & Recap** tahunan bergaya story
- **Dengar Bareng**: dengarkan lagu yang sama dengan teman secara realtime (Supabase Realtime)
- **Alarm musik**, **Dynamic Island**, **widget layar utama**, **Android Auto**
- **Bagikan** lagu dan potongan lirik sebagai gambar
- **Pembaruan otomatis** dari GitHub Releases

## Build

Butuh JDK 17+ dan Android SDK 36.

1. Isi `local.properties`:
   ```properties
   sdk.dir=/path/ke/Android/Sdk
   # Opsional, untuk Dengar Bareng (pakai publishable key, BUKAN secret key)
   supabase.url=https://<project-ref>.supabase.co
   supabase.key=sb_publishable_...
   ```
2. Build debug:
   ```bash
   ./gradlew :app:assembleDebug
   ```

Build rilis membaca `keystore.properties` (tidak ikut di repo). Tanpa file itu, build rilis memakai kunci debug.

## Teknologi

Kotlin, Jetpack Compose, Media3 (ExoPlayer + MediaLibraryService), Room, DataStore, NewPipe Extractor, Coil, Glance, OkHttp.

## Lisensi

GPL-3.0. Lihat [LICENSE](LICENSE).
