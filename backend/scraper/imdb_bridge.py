# ==========================================
# Bytify\backend\scraper\imdb_bridge.py
# ==========================================
"""
Jembatan TERAKHIR untuk judul yang TMDB sendiri tidak bisa cocokkan.

**Kenapa alat ini ada.** `tmdb_match.py` mencocokkan judul katalog ke TMDB
dengan aturan ketat (3h), dan sesudah pemutus durasi dipasang ia menjangkau
22.391 dari 25.258 film. Sisanya 2.299 judul TIDAK punya padanan judul+tahun
yang persis di TMDB — bukan karena filmnya tidak ada di sana, melainkan karena
LK21 menamainya dengan judul lokal atau transliterasi, sementara pencarian
TMDB mencocokkan judul utama dan judul aslinya saja.

Yang hilang itu justru kekuatan IMDb: `title.akas.tsv` memuat judul alternatif
per WILAYAH dan per BAHASA untuk tiap judul. Jadi alurnya:

    judul katalog --(akas/basics, judul persis + tahun ±1)--> tconst
    tconst --(TMDB /find?external_source=imdb_id)--> TMDB id

**Aturannya tetap sama ketatnya dengan 3h**, dan itu disengaja: yang bertambah
hanya SUMBER judul yang dicocokkan, bukan kelonggarannya.

1. Judul ternormalkan harus SAMA PERSIS dengan `primaryTitle`,
   `originalTitle`, atau salah satu judul alternatif IMDb.
2. Tahun cocok dalam ±1.
3. Kalau lebih dari satu tconst lolos, DURASI yang memutuskan (±3 menit,
   sama seperti `tmdb_match.py`; terukur selisih maksimal 2 menit pada judul
   yang sudah pasti).
4. Kalau sesudah itu masih lebih dari satu, judulnya DILEWATI.

**Berkasnya tidak disimpan utuh.** `title.akas.tsv.gz` 500 MB dan
`title.basics.tsv.gz` 220 MB; keduanya dibaca sambil MENGALIR dan hanya baris
yang judulnya memang dicari yang ditahan di memori. Tanpa itu, satu berkas saja
sudah melampaui yang pantas ditahan untuk 2.299 judul.

Pemakaian:
    python imdb_bridge.py --unduh          # ambil datasetnya lebih dulu
    python imdb_bridge.py                  # cocokkan lalu tulis hasilnya
    python imdb_bridge.py --serial         # mode serial
"""
import csv
import gzip
import io
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import concurrent.futures as cf

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR = os.path.dirname(os.path.abspath(__file__))
DIR_DATA = os.path.join(DIR, "..", "data")
DIR_IMDB = os.path.join(DIR_DATA, "imdb")

BASICS = os.path.join(DIR_IMDB, "title.basics.tsv.gz")
AKAS = os.path.join(DIR_IMDB, "title.akas.tsv.gz")
SUMBER_UNDUH = {
    BASICS: "https://datasets.imdbws.com/title.basics.tsv.gz",
    AKAS: "https://datasets.imdbws.com/title.akas.tsv.gz",
}

SERIAL = "--serial" in sys.argv
MODE = {
    "sumber": os.path.join(DIR_DATA, "series_catalog.json" if SERIAL else "catalog.json"),
    "awalan": "series-" if SERIAL else "lk21-",
    "match": os.path.join(DIR_DATA, "series_match.json" if SERIAL else "tmdb_match.json"),
    "type": "tv" if SERIAL else "movie",
    #: Jenis judul IMDb yang boleh dipertimbangkan. Memisahkannya mencegah
    #: sebuah film tertukar dengan episode serial bernama sama.
    "jenis": {"tvSeries", "tvMiniSeries"} if SERIAL else {"movie", "tvMovie", "video"},
}

TOLERANSI_TAHUN = 1
TOLERANSI_DURASI = 3
UTAS = 12

UA = "Bytify/1.3 (+https://github.com/Byronz123/Bytify)"


def kunci_api():
    jalur = os.path.join(DIR, ".env")
    if os.path.exists(jalur):
        teks = io.open(jalur, encoding="utf-8").read()
        pet = dict(re.findall(r"^\s*([A-Z_]+)\s*=\s*(.+?)\s*$", teks, re.M))
        if pet.get("TMDB_API_KEY"):
            return pet["TMDB_API_KEY"]
    if os.environ.get("TMDB_API_KEY"):
        return os.environ["TMDB_API_KEY"]
    raise SystemExit("TMDB_API_KEY tidak ada di backend/scraper/.env")


def normal(teks):
    """Sama persis dengan normalisasi di tmdb_match.py — jangan dibedakan."""
    t = unicodedata.normalize("NFKD", str(teks or ""))
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = t.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def kandidat_judul(judul):
    """Bentuk-bentuk judul yang dicoba; sama alurnya dengan tmdb_match.py."""
    dasar = re.sub(r"\s*\((\d{4})\)\s*$", "", str(judul or "")).strip()
    keluar = [dasar]
    sebelum = dasar.split("(")[0].strip()
    if sebelum and sebelum != dasar:
        keluar.append(sebelum)
    for isi in re.findall(r"\(([^)]*)\)", dasar):
        isi = isi.strip()
        if len(isi) >= 3 and not re.match(r"(?i)^(uncut|extended|remastered|dub|sub)", isi):
            keluar.append(isi)
    hasil, lihat = [], []
    for k in keluar:
        n = normal(k)
        if n and n not in lihat:
            lihat.append(n)
            hasil.append(n)
    return hasil


def unduh():
    os.makedirs(DIR_IMDB, exist_ok=True)
    for jalur, url in SUMBER_UNDUH.items():
        if os.path.exists(jalur) and os.path.getsize(jalur) > 1_000_000:
            print(f"  sudah ada: {os.path.basename(jalur)} "
                  f"({os.path.getsize(jalur)/1048576:.0f} MiB)")
            continue
        print(f"  mengunduh {os.path.basename(jalur)} ...", flush=True)
        permintaan = urllib.request.Request(url, headers={"User-Agent": UA})
        sementara = jalur + ".tmp"
        with urllib.request.urlopen(permintaan, timeout=120) as sumber, \
                open(sementara, "wb") as keluar:
            while True:
                potong = sumber.read(1 << 20)
                if not potong:
                    break
                keluar.write(potong)
        os.replace(sementara, jalur)
        print(f"    {os.path.getsize(jalur)/1048576:.0f} MiB")


def baca_tsv(jalur):
    """Baris TSV IMDb, dibaca sambil mengalir."""
    with gzip.open(jalur, "rt", encoding="utf-8", errors="replace", newline="") as f:
        for baris in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            yield baris


def muat_sasaran():
    """Judul yang BELUM tercocokkan: id -> (judul, tahun, durasi, bentuk judul)."""
    with io.open(MODE["sumber"], encoding="utf-8") as f:
        katalog = {m["id"]: m for m in (json.load(f).get("movies") or [])
                   if str(m.get("id") or "").startswith(MODE["awalan"])}
    with io.open(MODE["match"], encoding="utf-8") as f:
        hasil = json.load(f)
    belum = set(hasil.get("tanpa") or {}) | set(hasil.get("ambigu") or {})
    sasaran = {}
    for idf in belum:
        m = katalog.get(idf)
        if not m or not isinstance(m.get("releaseYear"), int):
            continue
        bentuk = kandidat_judul(m.get("title"))
        if bentuk:
            sasaran[idf] = {
                "judul": m.get("title"),
                "tahun": m["releaseYear"],
                "durasi": m.get("durationMin"),
                "bentuk": bentuk,
            }
    return sasaran, hasil


def main():
    if "--unduh" in sys.argv:
        unduh()
        return 0
    for jalur in (BASICS, AKAS):
        if not os.path.exists(jalur):
            print(f"Dataset belum ada: {jalur}\nJalankan: python imdb_bridge.py --unduh")
            return 1

    sasaran, hasil = muat_sasaran()
    print(f"[imdb] {len(sasaran)} judul belum tercocokkan")
    if not sasaran:
        return 0

    # Peta judul-ternormalkan -> id katalog yang mencarinya.
    cari = {}
    for idf, s in sasaran.items():
        for b in s["bentuk"]:
            cari.setdefault(b, []).append(idf)
    print(f"[imdb] {len(cari)} bentuk judul dicari")

    # --- Lintasan 1: basics. Judul utama dan judul asli sekaligus. ---
    kandidat = {}           # idf -> {tconst: (tahun, durasi)}
    info = {}               # tconst -> (tahun, durasi)
    jenis_ok = MODE["jenis"]
    dibaca = 0
    for baris in baca_tsv(BASICS):
        dibaca += 1
        if dibaca % 2_000_000 == 0:
            print(f"    basics {dibaca:,} baris", flush=True)
        if baris.get("titleType") not in jenis_ok:
            continue
        for nama in (baris.get("primaryTitle"), baris.get("originalTitle")):
            n = normal(nama)
            if not n or n not in cari:
                continue
            tahun = baris.get("startYear")
            tahun = int(tahun) if tahun and tahun.isdigit() else None
            durasi = baris.get("runtimeMinutes")
            durasi = int(durasi) if durasi and durasi.isdigit() else None
            tconst = baris.get("tconst")
            info[tconst] = (tahun, durasi)
            for idf in cari[n]:
                kandidat.setdefault(idf, {})[tconst] = (tahun, durasi)
    print(f"[imdb] basics selesai: {dibaca:,} baris, {len(kandidat)} judul dapat kandidat")

    # --- Lintasan 2: akas. Inilah yang membawa judul lokal. ---
    perlu = {}              # tconst -> set(idf) dari akas, tahunnya belum tahu
    dibaca = 0
    for baris in baca_tsv(AKAS):
        dibaca += 1
        if dibaca % 5_000_000 == 0:
            print(f"    akas {dibaca:,} baris", flush=True)
        n = normal(baris.get("title"))
        if not n or n not in cari:
            continue
        tconst = baris.get("titleId")
        for idf in cari[n]:
            perlu.setdefault(tconst, set()).add(idf)
    print(f"[imdb] akas selesai: {dibaca:,} baris, {len(perlu)} tconst dari judul alternatif")

    # Tahun dan durasi untuk tconst yang hanya muncul lewat akas.
    belum_tahu = [t for t in perlu if t not in info]
    if belum_tahu:
        kurang = set(belum_tahu)
        for baris in baca_tsv(BASICS):
            t = baris.get("tconst")
            if t not in kurang:
                continue
            if baris.get("titleType") not in jenis_ok:
                continue
            tahun = baris.get("startYear")
            durasi = baris.get("runtimeMinutes")
            info[t] = (int(tahun) if tahun and tahun.isdigit() else None,
                       int(durasi) if durasi and durasi.isdigit() else None)
        print(f"[imdb] {len(belum_tahu)} tconst dilengkapi tahun/durasinya")
    for tconst, daftar in perlu.items():
        if tconst not in info:
            continue
        for idf in daftar:
            kandidat.setdefault(idf, {})[tconst] = info[tconst]

    # --- Saring: tahun ±1, lalu durasi ±3 sebagai pemutus ---
    terpilih = {}
    ambigu = tanpa = 0
    for idf, peta in kandidat.items():
        s = sasaran[idf]
        lolos = [t for t, (th, _) in peta.items()
                 if th is not None and abs(th - s["tahun"]) <= TOLERANSI_TAHUN]
        if not lolos:
            tanpa += 1
            continue
        if len(lolos) > 1 and isinstance(s.get("durasi"), int) and s["durasi"] > 0:
            dekat = [t for t in lolos
                     if peta[t][1] is not None
                     and abs(peta[t][1] - s["durasi"]) <= TOLERANSI_DURASI]
            if len(dekat) == 1:
                lolos = dekat
        if len(lolos) == 1:
            terpilih[idf] = lolos[0]
        else:
            ambigu += 1
    print(f"[imdb] {len(terpilih)} judul dapat tconst tunggal "
          f"({ambigu} ambigu, {tanpa} tahunnya tidak cocok)")
    if not terpilih:
        return 0

    # --- tconst -> TMDB id ---
    key = kunci_api()

    def ke_tmdb(pasangan):
        idf, tconst = pasangan
        q = urllib.parse.urlencode({"api_key": key, "external_source": "imdb_id"})
        url = f"https://api.themoviedb.org/3/find/{tconst}?{q}"
        for coba in range(4):
            try:
                r = urllib.request.Request(url, headers={"Accept": "application/json",
                                                         "User-Agent": UA})
                with urllib.request.urlopen(r, timeout=25) as x:
                    d = json.load(x)
                kunci = "tv_results" if MODE["type"] == "tv" else "movie_results"
                baris = d.get(kunci) or []
                if len(baris) == 1 and isinstance(baris[0].get("id"), int):
                    return idf, tconst, baris[0]["id"], baris[0]
                return idf, tconst, None, None
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    time.sleep(float(e.headers.get("Retry-After") or 2) + coba)
                    continue
                return idf, tconst, None, None
            except Exception:
                time.sleep(1 + coba)
        return idf, tconst, None, None

    baru = 0
    with cf.ThreadPoolExecutor(UTAS) as ex:
        for idf, tconst, tmdb, data in ex.map(ke_tmdb, list(terpilih.items())):
            if not tmdb:
                continue
            hasil.setdefault("matches", {})[idf] = {
                "tmdbId": tmdb,
                "type": MODE["type"],
                "via": "imdb",
                "lewat": "imdb-akas",
                "imdbId": tconst,
                "tmdbTitle": (data or {}).get("title") or (data or {}).get("name"),
                "tahunKatalog": sasaran[idf]["tahun"],
            }
            hasil.get("tanpa", {}).pop(idf, None)
            hasil.get("ambigu", {}).pop(idf, None)
            baru += 1

    sementara = MODE["match"] + ".tmp"
    with io.open(sementara, "w", encoding="utf-8") as f:
        json.dump(hasil, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, MODE["match"])
    print(f"[imdb] {baru} judul baru dapat TMDB id lewat IMDb; "
          f"kini {len(hasil.get('matches', {}))} cocok, "
          f"{len(hasil.get('ambigu', {}))} ambigu, {len(hasil.get('tanpa', {}))} tanpa")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
