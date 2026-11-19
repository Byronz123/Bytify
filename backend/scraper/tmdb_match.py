# ==========================================
# Bytify\backend\scraper\tmdb_match.py
# ==========================================
"""
Mencocokkan judul katalog LK21 ke TMDB id, KETAT.

Kenapa perlu: lapisan ketersediaan resmi (`tmdb_watch.py`) dikunci TMDB id,
sementara 25.186 film LK21 memakai id siput (`lk21-movie-<slug>`) tanpa TMDB id
sama sekali. Tanpa jembatan ini, chip "Tonton resmi di" tidak pernah tampil di
katalog utama — terukur: 0 dari 25.186.

PRINSIP: salah pasang lebih buruk daripada tidak pasang. Menampilkan "ada di
Netflix" untuk film yang salah merusak kepercayaan pada seluruh fitur, sementara
judul yang tidak tercocokkan cuma tidak menampilkan apa-apa. Jadi aturannya
sengaja kaku dan cakupannya diterima sebagian:

  1. Judul ternormalkan harus SAMA PERSIS dengan `title` atau `original_title`
     hasil TMDB. Tidak ada pencocokan kabur, tidak ada skor kemiripan.
  2. Tahun harus cocok dalam ±1 (beda tanggal rilis antar negara).
  3. Kalau lebih dari satu TMDB id berbeda lolos kedua syarat di atas, judul itu
     DILEWATI sebagai ambigu — bukan diambil yang pertama.

Judul LK21 berpola "Judul (Tahun)" dan sebagian membawa judul asli di kurung
dalam, mis. "10 Days of a Good Man (Iyi Adamin 10 Gunu) (2023)". Karena itu tiap
film dicoba dengan beberapa kandidat kueri, dan yang pertama memberi kecocokan
tunggal dipakai.

Keluaran: backend/data/tmdb_match.json (TIDAK dilacak git). Bisa dilanjutkan.

Pakai:
  python -I tmdb_match.py                 # cocokkan seluruh katalog
  python -I tmdb_match.py --sampel 40     # cetak 40 hasil untuk diperiksa mata
"""
import json
import os
import re
import sys
import threading
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
#: Tiga mode, satu aturan ketat yang sama.
#:   film   : catalog.json, id "lk21-", /search/movie
#:   serial : series_catalog.json, id "series-", /search/tv
#:   anime  : anikai_database.json, id "anikai-", /search/tv
#: Serial dipisah karena endpoint pencariannya beda dan field tanggalnya
#: `first_air_date`, bukan `release_date`.
#:
#: Mode anime ADA karena jembatan AniList hanya menjangkau 5.723 dari 8.007
#: judul anikai; 2.284 sisanya tidak punya TMDB id sama sekali, dan tanpa TMDB
#: id tidak ada subtitle. Sumbernya `anikai_database.json`, bukan katalog
#: terbit, karena hanya di sana ada `altTitle` — judul romaji dari JSON-LD
#: halaman anikai, yang sering justru itu yang terindeks TMDB.
#:
#: Mode ini hanya mencari di `/search/tv`. Film anime karena itu tidak akan
#: tercocokkan, dan itu disengaja: subtitle per-episode memang hanya untuk
#: yang punya episode.
MODE = {
    "film": {
        "sumber": os.path.join(DIR_DATA, "catalog.json"),
        "awalan": "lk21-",
        "endpoint": "/search/movie",
        "tanggal": "release_date",
        "judulAsli": "original_title",
        "type": "movie",
        "keluaran": os.path.join(DIR_DATA, "tmdb_match.json"),
        "daftar": os.path.join(DIR_DATA, "tmdb_match_worklist.json"),
    },
    "serial": {
        "sumber": os.path.join(DIR_DATA, "series_catalog.json"),
        "awalan": "series-",
        "endpoint": "/search/tv",
        "tanggal": "first_air_date",
        "judulAsli": "original_name",
        "type": "tv",
        "keluaran": os.path.join(DIR_DATA, "series_match.json"),
        "daftar": os.path.join(DIR_DATA, "series_match_worklist.json"),
    },
    "anime-film": {
        "sumber": os.path.join(DIR_DATA, "anikai_database.json"),
        "awalan": "anikai-",
        "endpoint": "/search/movie",
        "tanggal": "release_date",
        "judulAsli": "original_title",
        "type": "movie",
        "keluaran": os.path.join(DIR_DATA, "anime_movie_match.json"),
        "daftar": os.path.join(DIR_DATA, "anime_movie_worklist.json"),
    },
    "anime": {
        "sumber": os.path.join(DIR_DATA, "anikai_database.json"),
        "awalan": "anikai-",
        "endpoint": "/search/tv",
        "tanggal": "first_air_date",
        "judulAsli": "original_name",
        "type": "tv",
        "keluaran": os.path.join(DIR_DATA, "anime_match.json"),
        "daftar": os.path.join(DIR_DATA, "anime_match_worklist.json"),
    },
}
if "--anime-film" in sys.argv:
    # Anime BEREPISODE TUNGGAL adalah film, dan film tidak ada di /search/tv.
    # 815 judul seperti itu karena itu tidak pernah dapat TMDB id — dan tanpa
    # TMDB id tidak ada subtitle sama sekali.
    AKTIF = MODE["anime-film"]
elif "--anime" in sys.argv:
    AKTIF = MODE["anime"]
elif "--serial" in sys.argv:
    AKTIF = MODE["serial"]
else:
    AKTIF = MODE["film"]

SUMBER = os.path.join(DIR_DATA, "catalog.json")
KELUARAN = AKTIF["keluaran"]

UTAS = 14
SIMPAN_SETIAP = 500
TOLERANSI_TAHUN = 1

_kunci = threading.Lock()


def kunci_api():
    jalur = os.path.join(DIR, ".env")
    if os.path.exists(jalur):
        teks = open(jalur, encoding="utf-8").read()
        pet = dict(re.findall(r"^\s*([A-Z_]+)\s*=\s*(.+?)\s*$", teks, re.M))
        if pet.get("TMDB_API_KEY"):
            return pet["TMDB_API_KEY"]
    if os.environ.get("TMDB_API_KEY"):
        return os.environ["TMDB_API_KEY"]
    raise SystemExit("TMDB_API_KEY tidak ada di backend/scraper/.env")


KEY = kunci_api()


def normal(teks):
    """
    Bentuk pembanding: huruf kecil, tanpa aksen, tanpa tanda baca, spasi rapat.

    Hanya satu padanan yang diizinkan, "&" -> "and", karena itu beda penulisan
    yang sama artinya dan sering muncul. Selain itu TIDAK ada pelonggaran —
    tidak membuang kata sandang, tidak menyamakan angka Romawi — supaya
    "Rocky II" tidak tertukar dengan "Rocky III".
    """
    t = unicodedata.normalize("NFKD", str(teks or ""))
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = t.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def kandidat_judul(judul):
    """
    Beberapa bentuk kueri untuk satu judul LK21, urut dari yang paling spesifik.

    "10 Days of a Good Man (Iyi Adamin 10 Gunu) (2023)" menghasilkan:
      - "10 Days of a Good Man (Iyi Adamin 10 Gunu)"  (tanpa tahun)
      - "10 Days of a Good Man"                        (sebelum kurung)
      - "Iyi Adamin 10 Gunu"                           (isi kurung: judul asli)
    """
    dasar = re.sub(r"\s*\((\d{4})\)\s*$", "", str(judul or "")).strip()
    keluar = [dasar]
    sebelum = dasar.split("(")[0].strip()
    if sebelum and sebelum != dasar:
        keluar.append(sebelum)
    for isi in re.findall(r"\(([^)]*)\)", dasar):
        isi = isi.strip()
        # Lewati kurung yang isinya cuma keterangan, bukan judul.
        if len(isi) >= 3 and not re.match(r"(?i)^(uncut|extended|remastered|dub|sub)", isi):
            keluar.append(isi)
    hasil, lihat = [], set()
    for k in keluar:
        n = normal(k)
        if n and n not in lihat:
            lihat.add(n)
            hasil.append(k)
    return hasil


def cari_tmdb(kueri):
    q = urllib.parse.urlencode({"api_key": KEY, "query": kueri, "include_adult": "false"})
    url = "https://api.themoviedb.org/3" + AKTIF["endpoint"] + "?" + q
    for coba in range(5):
        try:
            r = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(r, timeout=25) as resp:
                return (json.loads(resp.read()) or {}).get("results") or []
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(float(e.headers.get("Retry-After") or 2) + coba)
                continue
            if e.code in (404, 401, 422):
                return []
            time.sleep(1 + coba)
        except Exception:
            time.sleep(1 + coba)
    return []


def tahun_dari(hasil):
    m = re.match(r"^(\d{4})", str(hasil.get(AKTIF["tanggal"]) or ""))
    return int(m.group(1)) if m else None


def judul_hasil(h):
    """TMDB memakai title/original_title untuk film, name/original_name untuk tv."""
    utama = h.get("title") if AKTIF["type"] == "movie" else h.get("name")
    asli = h.get(AKTIF["judulAsli"])
    return utama, asli


def cocokkan(film):
    """
    Mengembalikan (status, data). Status: "cocok", "ambigu", atau "tanpa".
    """
    judul, tahun = film.get("title") or "", film.get("releaseYear")
    if not isinstance(tahun, int):
        return "tanpa", {"alasan": "tahun katalog tidak ada"}

    # Judul alternatif (romaji dari JSON-LD anikai) dicoba sebagai bentuk
    # TAMBAHAN, bukan sebagai pelonggaran: tiap bentuk tetap harus sama persis
    # dengan judul TMDB, dan yang menunjuk lebih dari satu tetap dilewati.
    bentuk = kandidat_judul(judul)
    alt = film.get("judulAlt")
    if alt:
        for b in kandidat_judul(alt):
            if normal(b) not in {normal(x) for x in bentuk}:
                bentuk.append(b)

    for kueri in bentuk:
        nk = normal(kueri)
        # Dipisah berdasarkan CARA cocoknya. Kecocokan pada judul utama lebih
        # kuat daripada pada judul asli saja, dan itu dipakai sebagai pemutus.
        lewat_judul, lewat_asli = {}, {}
        for h in cari_tmdb(kueri):
            th = tahun_dari(h)
            if th is None or abs(th - tahun) > TOLERANSI_TAHUN:
                continue
            tid = h.get("id")
            if not isinstance(tid, int):
                continue
            utama, asli = judul_hasil(h)
            if nk == normal(utama):
                lewat_judul[tid] = h
            elif nk == normal(asli):
                lewat_asli[tid] = h

        # Pemutus BERPRINSIP, bukan skor kemiripan: kalau tepat satu kandidat
        # judul UTAMA-nya sama persis, itu yang dipakai meski kandidat lain
        # cocok lewat judul aslinya. Terukur pada "1917 (2019)", yang bertemu
        # film Sam Mendes (title "1917") dan satu film lain yang judul aslinya
        # "1917" tetapi judul Inggrisnya "Unknown 1917" — yang kedua bukan
        # film yang dimaksud katalog.
        #
        # Kalau judul utama tidak memutuskan, baru judul asli dipertimbangkan,
        # dan tetap harus tunggal. Dua film dengan judul utama sama persis di
        # tahun sama tetap ambigu, dan tetap dilewati.
        terpilih = None
        if len(lewat_judul) == 1:
            terpilih = next(iter(lewat_judul.items()))
        elif not lewat_judul and len(lewat_asli) == 1:
            terpilih = next(iter(lewat_asli.items()))

        if terpilih:
            tid, h = terpilih
            return "cocok", {
                "tmdbId": tid,
                "type": AKTIF["type"],
                "via": kueri,
                "lewat": "judul-utama" if tid in lewat_judul else "judul-asli",
                "tmdbTitle": judul_hasil(h)[0],
                "tmdbYear": tahun_dari(h),
                "tahunKatalog": tahun,
            }

        gabung = {**lewat_asli, **lewat_judul}
        if len(gabung) > 1:
            return "ambigu", {
                "kueri": kueri,
                "kandidat": [
                    {"tmdbId": t, "title": judul_hasil(x)[0], "year": tahun_dari(x)}
                    for t, x in list(gabung.items())[:5]
                ],
            }
    return "tanpa", {"alasan": "tidak ada kecocokan persis judul+tahun"}


def muat_katalog():
    with open(AKTIF["sumber"], encoding="utf-8") as f:
        d = json.load(f)
    if AKTIF in (MODE["anime"], MODE["anime-film"]):
        hanya_film = AKTIF is MODE["anime-film"]
        # anikai_database.json adalah LARIK hasil panen, bukan katalog terbit.
        # Hanya judul yang punya seal yang berguna: yang lain tidak terbit, jadi
        # mencocokkannya hanya menghabiskan kuota TMDB.
        keluar = []
        for a in d:
            aid, judul = a.get("anikaiId"), (a.get("title") or "").strip()
            if not aid or not judul or not a.get("seal") or not a.get("server"):
                continue
            # Mode film hanya untuk judul berepisode tunggal; mode serial untuk
            # sisanya. Memisahkannya menghindari dua ruang id TMDB bertabrakan
            # pada judul yang sama.
            satu_episode = len(a.get("episodes") or []) <= 1
            if hanya_film != satu_episode:
                continue
            keluar.append({
                "id": f"anikai-{aid}",
                "title": judul,
                "judulAlt": (a.get("altTitle") or "").strip(),
                "releaseYear": a.get("year"),
            })
        return keluar
    return [m for m in (d.get("movies") or [])
            if str(m.get("id") or "").startswith(AKTIF["awalan"])]


def muat_hasil():
    if not os.path.exists(KELUARAN):
        return {"matches": {}, "ambigu": {}, "tanpa": {}}
    try:
        with open(KELUARAN, encoding="utf-8") as f:
            d = json.load(f)
        for k in ("matches", "ambigu", "tanpa"):
            d.setdefault(k, {})
        return d
    except Exception:
        return {"matches": {}, "ambigu": {}, "tanpa": {}}


def simpan(h):
    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(h, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)


def main():
    katalog = muat_katalog()
    h = muat_hasil()

    if "--sampel" in sys.argv:
        n = 20
        i = sys.argv.index("--sampel")
        if i + 1 < len(sys.argv) and sys.argv[i + 1].isdigit():
            n = int(sys.argv[i + 1])
        import random
        random.seed(7)
        contoh = random.sample(katalog, min(n, len(katalog)))
        print("Periksa dengan mata: judul katalog -> judul TMDB (tahun)\n")
        cocok = 0
        for film in contoh:
            status, data = cocokkan(film)
            if status == "cocok":
                cocok += 1
                print("  COCOK   %-46s -> %-40s (%s vs %s)"
                      % (film["title"][:46], str(data["tmdbTitle"])[:40],
                         data["tmdbYear"], data["tahunKatalog"]))
            elif status == "ambigu":
                print("  AMBIGU  %-46s -> %s"
                      % (film["title"][:46],
                         "; ".join("%s (%s)" % (c["title"], c["year"])
                                   for c in data["kandidat"][:3])))
            else:
                print("  TANPA   %-46s -> %s" % (film["title"][:46], data["alasan"]))
        print("\n%d/%d tercocokkan pada sampel ini" % (cocok, len(contoh)))
        return 0

    if "--ulangi-ambigu" in sys.argv:
        # Setelah pemutus dipertajam, hanya yang ambigu yang perlu dicoba lagi.
        # Yang sudah cocok TIDAK disentuh: aturannya hanya menambah keputusan,
        # tidak pernah mengubah kecocokan yang sudah tunggal.
        ulang = [m for m in katalog if m["id"] in h["ambigu"]]
        print("[match] mengulang %d judul yang ambigu" % len(ulang))
        pulih = 0
        with cf.ThreadPoolExecutor(UTAS) as ex:
            for film, (status, data) in zip(ulang, ex.map(cocokkan, ulang)):
                if status == "cocok":
                    h["matches"][film["id"]] = data
                    h["ambigu"].pop(film["id"], None)
                    pulih += 1
                elif status == "tanpa":
                    h["tanpa"][film["id"]] = data
                    h["ambigu"].pop(film["id"], None)
        simpan(h)
        print("[match] %d pulih dari ambigu; kini %d cocok, %d ambigu, %d tanpa"
              % (pulih, len(h["matches"]), len(h["ambigu"]), len(h["tanpa"])))
        kerja = AKTIF["daftar"]
        unik = sorted({(v["type"], v["tmdbId"]) for v in h["matches"].values()})
        with open(kerja, "w", encoding="utf-8") as f:
            json.dump([{"type": a_, "tmdbId": b_} for a_, b_ in unik], f, separators=(",", ":"))
        print("  %d TMDB id unik -> %s" % (len(unik), kerja))
        return 0

    sudah = set(h["matches"]) | set(h["ambigu"]) | set(h["tanpa"])
    sisa = [m for m in katalog if m["id"] not in sudah]
    print("[match] katalog %d, sudah %d, sisa %d" % (len(katalog), len(sudah), len(sisa)))
    if not sisa:
        print("[match] sudah lengkap")
    else:
        t0, selesai = time.time(), 0
        with cf.ThreadPoolExecutor(UTAS) as ex:
            for film, (status, data) in zip(sisa, ex.map(cocokkan, sisa)):
                selesai += 1
                if status == "cocok":
                    h["matches"][film["id"]] = data
                elif status == "ambigu":
                    h["ambigu"][film["id"]] = data
                else:
                    h["tanpa"][film["id"]] = data
                if selesai % SIMPAN_SETIAP == 0:
                    with _kunci:
                        simpan(h)
                    laju = selesai / max(time.time() - t0, 1)
                    print("  %d/%d  %.1f/s  cocok=%d ambigu=%d tanpa=%d  sisa~%.0fm"
                          % (selesai, len(sisa), laju, len(h["matches"]),
                             len(h["ambigu"]), len(h["tanpa"]),
                             (len(sisa) - selesai) / max(laju, 0.1) / 60), flush=True)
        simpan(h)

    total = len(katalog)
    c, a, t = len(h["matches"]), len(h["ambigu"]), len(h["tanpa"])
    print("[match] selesai: %d cocok (%.1f%%), %d ambigu, %d tanpa, dari %d film"
          % (c, c * 100.0 / max(total, 1), a, t, total))

    # Daftar kerja siap pakai untuk tmdb_watch.py
    kerja = AKTIF["daftar"]
    unik = sorted({(v["type"], v["tmdbId"]) for v in h["matches"].values()})
    with open(kerja, "w", encoding="utf-8") as f:
        json.dump([{"type": a_, "tmdbId": b_} for a_, b_ in unik], f, separators=(",", ":"))
    print("  %d TMDB id unik -> %s" % (len(unik), kerja))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
