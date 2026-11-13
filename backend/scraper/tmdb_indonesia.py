# ==========================================
# Bytify\backend\scraper\tmdb_indonesia.py
# ==========================================
"""
Pemanen katalog film Indonesia dari TMDB.

**Kenapa TMDB, setelah lima sumber lain diukur.** Jumlah film Indonesia yang
terukur di tiap sumber:

    TMDB                  7.839   <- id resmi, poster, sinopsis Indonesia
    filmindonesia.or.id   2.324   katalog resmi, tanpa tautan tonton
    Wikidata              3.700   metadata tipis
    LK21                    181
    IndoXXI                  30   sebagian salah tag
    framextv                  0   katalognya memang TMDB

TMDB bukan sekadar yang terbanyak. Ia memberi **TMDB id**, dan itulah kunci
yang dipakai pemutar embed pihak ketiga (framextv memublikasikan rute
/embed/movie/:tmdbId). Jadi satu panggilan ini sekaligus menyelesaikan dua
masalah: katalognya dan cara memutarnya.

Kunci API dibaca dari variabel lingkungan TMDB_API_KEY, atau dari
backend/scraper/.env yang TIDAK dilacak git.

Catatan sinopsis: diminta dalam bahasa Indonesia (language=id-ID). TMDB
mengembalikan sinopsis kosong bila terjemahan Indonesianya belum ada, jadi
entri seperti itu dilengkapi dari bahasa Inggris sebagai cadangan.
"""
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASIS = "https://api.themoviedb.org/3"
GAMBAR = "https://image.tmdb.org/t/p/w500"

BERKAS_HASIL = os.path.join(
    os.path.dirname(__file__), "..", "data", "tmdb_indonesia_database.json"
)


def kunci_api():
    k = os.environ.get("TMDB_API_KEY", "").strip()
    if k:
        return k
    env = os.path.join(os.path.dirname(__file__), ".env")
    if os.path.exists(env):
        for baris in open(env, encoding="utf-8"):
            if baris.startswith("TMDB_API_KEY="):
                return baris.split("=", 1)[1].strip()
    raise SystemExit(
        "TMDB_API_KEY belum diatur. Isi backend/scraper/.env "
        "(berkas itu tidak dilacak git)."
    )


KUNCI = kunci_api()
sesi = requests.Session()


def _ambil(jalur, **params):
    params["api_key"] = KUNCI
    for ke in range(4):
        try:
            r = sesi.get(BASIS + jalur, params=params, timeout=30)
            if r.status_code == 429:
                # TMDB membatasi laju; tunggu lalu coba lagi.
                time.sleep(float(r.headers.get("Retry-After", 2)) + 0.5)
                continue
            r.raise_for_status()
            return r.json()
        except Exception:
            if ke == 3:
                return None
            time.sleep(1.5 * (ke + 1))
    return None


def peta_genre(bahasa="id-ID"):
    d = _ambil("/genre/movie/list", language=bahasa) or {}
    return {g["id"]: g["name"] for g in d.get("genres", [])}


def halaman(n, bahasa="id-ID"):
    return _ambil(
        "/discover/movie",
        with_origin_country="ID",
        sort_by="primary_release_date.desc",
        include_adult="false",
        page=n,
        language=bahasa,
    )


def rapikan(f, genre_nama, sinopsis_en):
    judul = (f.get("title") or f.get("original_title") or "").strip()
    if not judul:
        return None
    tanggal = (f.get("release_date") or "").strip()
    tahun = int(tanggal[:4]) if len(tanggal) >= 4 and tanggal[:4].isdigit() else None
    sinopsis = (f.get("overview") or "").strip() or sinopsis_en.get(f["id"], "")
    nilai = f.get("vote_average")
    hasil = {
        # Diberi awalan supaya tidak bertabrakan dengan id katalog LK21.
        "id": f"tmdb-{f['id']}",
        "tmdbId": f["id"],
        "type": "movie",
        "title": judul,
        "originalTitle": (f.get("original_title") or "").strip() or None,
        "overview": sinopsis,
        "posterUrl": (GAMBAR + f["poster_path"]) if f.get("poster_path") else None,
        "releaseDate": tanggal or None,
        "releaseYear": tahun,
        "genres": [genre_nama[g] for g in (f.get("genre_ids") or []) if g in genre_nama],
        "countries": ["Indonesia"],
        "language": f.get("original_language"),
        "sourceUrl": f"https://www.themoviedb.org/movie/{f['id']}",
    }
    if isinstance(nilai, (int, float)) and nilai > 0:
        hasil["rating"] = round(float(nilai), 1)
    return hasil


def panen(berkas=BERKAS_HASIL, pekerja=8):
    genre_nama = peta_genre()
    awal = halaman(1)
    if not awal:
        raise SystemExit("Gagal menghubungi TMDB.")
    total_hal = min(int(awal.get("total_pages") or 1), 500)  # TMDB berhenti di 500
    print(f"[tmdb-id] {awal.get('total_results')} film, {total_hal} halaman")

    mentah = {}

    def kumpul(d):
        for f in (d or {}).get("results", []):
            mentah[f["id"]] = f

    kumpul(awal)
    with ThreadPoolExecutor(max_workers=pekerja) as kolam:
        tugas = {kolam.submit(halaman, n): n for n in range(2, total_hal + 1)}
        selesai = 0
        for t in as_completed(tugas):
            kumpul(t.result())
            selesai += 1
            if selesai % 50 == 0:
                print(f"  {selesai}/{total_hal - 1} halaman | {len(mentah)} film")

    # Sinopsis cadangan dalam bahasa Inggris untuk yang belum diterjemahkan.
    kosong = [i for i, f in mentah.items() if not (f.get("overview") or "").strip()]
    print(f"[tmdb-id] {len(kosong)} film tanpa sinopsis Indonesia; ambil cadangan Inggris")
    sinopsis_en = {}
    if kosong:
        with ThreadPoolExecutor(max_workers=pekerja) as kolam:
            tugas = {kolam.submit(_ambil, f"/movie/{i}", language="en-US"): i
                     for i in kosong}
            for t in as_completed(tugas):
                d = t.result()
                if d and (d.get("overview") or "").strip():
                    sinopsis_en[d["id"]] = d["overview"].strip()

    keluar = [x for x in (rapikan(f, genre_nama, sinopsis_en) for f in mentah.values()) if x]
    keluar.sort(key=lambda x: (x.get("releaseDate") or ""), reverse=True)

    sementara = berkas + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(keluar, f, ensure_ascii=False)
    os.replace(sementara, berkas)

    mib = os.path.getsize(berkas) / 1024 / 1024
    print(f"[tmdb-id] selesai: {len(keluar)} film, {mib:.2f} MiB -> {berkas}")
    return keluar


if __name__ == "__main__":
    panen()
