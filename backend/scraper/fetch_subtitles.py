# ==========================================
# Bytify/backend/scraper/fetch_subtitles.py
# Unduh subtitle multi-bahasa ke backend/data/subtitles/<slug>/<kode>.srt
#
# build_database.py otomatis memasukkan berkas di folder itu ke catalog.json,
# jadi skrip ini hanya perlu dijalankan sesekali setelah scraping.
#
# BUTUH KUNCI API (gratis, daftar sekali):
#   OpenSubtitles  -> https://www.opensubtitles.com/consumers  (OPENSUBTITLES_API_KEY)
#   TMDB           -> https://www.themoviedb.org/settings/api  (TMDB_API_KEY)
#
# Pemakaian:
#   set OPENSUBTITLES_API_KEY=xxxx
#   set TMDB_API_KEY=yyyy
#   python fetch_subtitles.py                 -> semua film tanpa subtitle
#   python fetch_subtitles.py --bahasa id,en,ms
#   python fetch_subtitles.py --batas 50      -> uji coba 50 film dulu
# ==========================================
import os
import re
import sys
import json
import time

try:
    import requests
except ImportError:
    print("[!] Modul 'requests' belum ada. Jalankan: pip install requests")
    sys.exit(1)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
DB_FILE = os.path.join(DATA_DIR, "bytify_database.json")
SUBTITLE_DIR = os.path.join(DATA_DIR, "subtitles")

OS_KEY = os.environ.get("OPENSUBTITLES_API_KEY", "").strip()
TMDB_KEY = os.environ.get("TMDB_API_KEY", "").strip()
NAMA_APLIKASI = "Bytify v1.0"

BAHASA_DEFAULT = ["id", "en"]
JEDA_DETIK = 1.0  # OpenSubtitles membatasi ~5 permintaan/detik; jangan diperkecil


def sesi_opensubtitles():
    s = requests.Session()
    s.headers.update({
        "Api-Key": OS_KEY,
        "User-Agent": NAMA_APLIKASI,
        "Accept": "application/json",
    })
    return s


def cari_imdb_id(judul, tahun):
    """Judul LK21 -> ID IMDb lewat TMDB. Tanpa TMDB_API_KEY, kembalikan None."""
    if not TMDB_KEY:
        return None
    bersih = re.sub(r"\s*\(\d{4}\)\s*$", "", judul).strip()
    try:
        cari = requests.get(
            "https://api.themoviedb.org/3/search/movie",
            params={"api_key": TMDB_KEY, "query": bersih, "year": tahun or ""},
            timeout=20,
        ).json()
        hasil = (cari.get("results") or [])
        if not hasil:
            return None
        tmdb_id = hasil[0]["id"]
        detail = requests.get(
            f"https://api.themoviedb.org/3/movie/{tmdb_id}/external_ids",
            params={"api_key": TMDB_KEY}, timeout=20,
        ).json()
        return detail.get("imdb_id") or None
    except Exception as e:
        print(f"    [!] TMDB gagal untuk '{judul}': {e}")
        return None


def unduh_satu_bahasa(sesi, slug, judul, tahun, imdb_id, kode):
    """Cari dan unduh satu subtitle. True bila berhasil disimpan."""
    tujuan_dir = os.path.join(SUBTITLE_DIR, slug)
    tujuan = os.path.join(tujuan_dir, f"{kode}.srt")
    if os.path.exists(tujuan) and os.path.getsize(tujuan) > 200:
        return True  # sudah ada

    params = {"languages": kode, "order_by": "download_count", "order_direction": "desc"}
    if imdb_id:
        params["imdb_id"] = imdb_id.replace("tt", "")
    else:
        params["query"] = re.sub(r"\s*\(\d{4}\)\s*$", "", judul).strip()
        if tahun:
            params["year"] = tahun

    try:
        cari = sesi.get("https://api.opensubtitles.com/api/v1/subtitles",
                        params=params, timeout=25)
        if cari.status_code != 200:
            print(f"    [!] pencarian {kode}: HTTP {cari.status_code}")
            return False
        data = (cari.json().get("data") or [])
        if not data:
            return False
        berkas = (data[0].get("attributes", {}).get("files") or [])
        if not berkas:
            return False
        file_id = berkas[0].get("file_id")
        if not file_id:
            return False

        time.sleep(JEDA_DETIK)
        minta = sesi.post("https://api.opensubtitles.com/api/v1/download",
                          json={"file_id": file_id}, timeout=25)
        if minta.status_code != 200:
            print(f"    [!] unduh {kode}: HTTP {minta.status_code} {minta.text[:90]}")
            return False
        tautan = minta.json().get("link")
        if not tautan:
            return False

        isi = requests.get(tautan, timeout=40)
        if isi.status_code != 200 or len(isi.content) < 200:
            return False
        os.makedirs(tujuan_dir, exist_ok=True)
        with open(tujuan, "wb") as f:
            f.write(isi.content)
        return True
    except Exception as e:
        print(f"    [!] {kode} gagal: {e}")
        return False


def main():
    if not OS_KEY:
        print("[!] OPENSUBTITLES_API_KEY belum diset.")
        print("    Daftar gratis di https://www.opensubtitles.com/consumers lalu:")
        print("    set OPENSUBTITLES_API_KEY=kunci_anda")
        return
    if not TMDB_KEY:
        print("[*] TMDB_API_KEY kosong: pencocokan memakai judul+tahun saja")
        print("    (lebih sering meleset). Set TMDB_API_KEY agar akurat.")

    bahasa = BAHASA_DEFAULT
    batas = None
    for i, arg in enumerate(sys.argv):
        if arg == "--bahasa" and i + 1 < len(sys.argv):
            bahasa = [b.strip().lower() for b in sys.argv[i + 1].split(",") if b.strip()]
        if arg == "--batas" and i + 1 < len(sys.argv):
            batas = int(sys.argv[i + 1])

    if not os.path.exists(DB_FILE):
        print(f"[!] {DB_FILE} belum ada. Jalankan build_database.py dulu.")
        return
    with open(DB_FILE, "r", encoding="utf-8") as f:
        db = json.load(f)
    if batas:
        db = db[:batas]

    os.makedirs(SUBTITLE_DIR, exist_ok=True)
    sesi = sesi_opensubtitles()
    print("=" * 66)
    print(f"  UNDUH SUBTITLE  |  {len(db)} film  |  bahasa: {', '.join(bahasa)}")
    print("=" * 66)

    total_berkas = 0
    film_terisi = 0
    for idx, item in enumerate(db, 1):
        slug = str(item.get("id") or "").strip()
        judul = item.get("title") or slug
        tahun = item.get("releaseYear")
        if not slug:
            continue

        imdb_id = cari_imdb_id(judul, tahun)
        didapat = []
        for kode in bahasa:
            if unduh_satu_bahasa(sesi, slug, judul, tahun, imdb_id, kode):
                didapat.append(kode)
                total_berkas += 1
            time.sleep(JEDA_DETIK)

        if didapat:
            film_terisi += 1
        status = ", ".join(didapat) if didapat else "tidak ada"
        print(f"[{idx}/{len(db)}] {judul[:42]:42} imdb:{imdb_id or '-':<11} -> {status}")

    print("=" * 66)
    print(f"  Film dapat subtitle : {film_terisi}/{len(db)}")
    print(f"  Total berkas        : {total_berkas}")
    print(f"  Tersimpan di        : {SUBTITLE_DIR}")
    print()
    print("  Langkah berikutnya:")
    print("    1) set BYTIFY_SUBTITLE_BASE=https://<domain-anda>/subtitles")
    print("    2) python build_database.py --clean   (memasukkan subtitle ke catalog.json)")
    print("    3) unggah folder backend/data/subtitles ke hosting yang sama")
    print("=" * 66)


if __name__ == "__main__":
    main()
