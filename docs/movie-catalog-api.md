# Kontrak API katalog film Bytify

Bytify mengambil metadata film dari satu endpoint JSON HTTPS yang dikendalikan backend aplikasi. Endpoint tidak perlu memiliki rute film terpisah untuk versi pertama.

## Konfigurasi Android

Tambahkan ke `local.properties` (file ini jangan di-commit jika memuat data sensitif):

```properties
movie.catalog.url=https://YOUR_DOMAIN/api/catalog.json
```

Build ulang aplikasi setelah mengubah URL. Untuk emulator Android, server development lokal bisa diakses melalui `http://10.0.2.2:PORT/api/catalog.json`.

## Respons `GET /api/catalog.json`

```json
{
  "movies": [
    {
      "id": "movie-001",
      "title": "Judul Film",
      "overview": "Sinopsis film.",
      "posterUrl": "https://cdn.example.org/poster.jpg",
      "backdropUrl": "https://cdn.example.org/backdrop.jpg",
      "releaseYear": 2024,
      "durationMin": 110,
      "genres": ["Drama", "Petualangan"],
      "rating": 7.8,
      "language": "id",
      "videoUrl": null,
      "trailerUrl": "https://www.youtube.com/watch?v=VIDEO_ID",
      "subtitles": [
        {
          "lang": "id",
          "label": "Indonesia",
          "url": "https://cdn.example.org/subtitle-id.vtt"
        }
      ],
      "license": "Licensed"
    }
  ],
  "sections": [
    {
      "title": "Pilihan Bytify",
      "movieIds": ["movie-001"]
    }
  ]
}
```

Semua field tingkat atas `movies` dan `sections` boleh berupa array kosong. Field opsional film dapat dihilangkan atau bernilai `null`; `id` dan `title` harus berisi teks. Rating menggunakan angka 0–10. URL yang bukan `http://` atau `https://` diabaikan oleh normalizer.

## Perilaku sinkronisasi

- Aplikasi memuat data terakhir dari Room terlebih dahulu.
- Ketika layar Film dibuka, katalog disinkronkan jika URL telah dikonfigurasi dan cache film masih kosong.
- Tombol refresh meminta katalog terbaru secara manual.
- Data film di-upsert berdasarkan `id`, sehingga favorit tidak ditimpa saat metadata diperbarui.
- Section diganti sesuai respons katalog terbaru.
- Kegagalan jaringan tidak menghapus cache lokal.

Backend harus mengirim `Content-Type: application/json`, status HTTP 2xx, dan TLS HTTPS untuk deployment produksi. Metadata dan URL media hanya boleh dimasukkan jika sumbernya memberi izin penggunaan dan distribusi yang sesuai.
