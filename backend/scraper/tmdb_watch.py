# ==========================================
# Bytify\backend\scraper\tmdb_watch.py
# ==========================================
"""
Pemanen KETERSEDIAAN RESMI dari TMDB (data JustWatch) untuk wilayah Indonesia.

Satu permintaan per judul dengan `append_to_response=watch/providers,external_ids`
memulangkan sekaligus:
  - ketersediaan resmi per jenis langganan (flatrate/free/ads/rent/buy)
  - runtime ASLI, imdb_id ASLI, genre ASLI, status rilis

Itu penting karena berkas himpunan judul yang dipakai sebagai daftar kerja
(cinevo_clean_catalog.json) memuat tiga field yang TERBUKTI dibuat-buat:
  - imdbId   = "tt" + tmdbId yang dipadkan nol (pola ttNNNNNNN untuk 50.072 entri;
               14.355 di antaranya tepat "tt0" + tmdbId)
  - duration = konstanta "1h 55m" untuk 46.046 film, "45m" untuk 3.205 serial
  - quality  = konstanta per tipe
Jadi ketiganya DIBUANG dan diambil ulang dari sumber resminya di sini.

Daftar kerja itu hanya dipakai sebagai himpunan (type, tmdbId); seluruh isi
katalog dibangun dari jawaban TMDB, bukan dari berkas itu.

Keluaran: backend/data/watch_database.json (TIDAK dilacak git).
Bisa dilanjutkan: jalankan ulang dan ia melewati judul yang sudah dipanen.

Atribusi WAJIB: data ketersediaan berasal dari JustWatch lewat TMDB. Teksnya
disimpan di berkas keluaran pada kunci "attribution" supaya ikut terbit dan
bisa ditampilkan di aplikasi.
"""
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import concurrent.futures as cf

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR = os.path.dirname(os.path.abspath(__file__))
DIR_DATA = os.path.join(DIR, "..", "data")
KELUARAN = os.path.join(DIR_DATA, "watch_database.json")

#: Daftar kerja: himpunan (type, tmdbId) yang mau dipanen.
SUMBER_DAFTAR = os.environ.get(
    "BYTIFY_DAFTAR_TMDB",
    os.path.join("E:", os.sep, "Tools", "Scraper Cloudflare", "cinevo-scraper",
                 "scraped_output", "cinevo_clean_catalog.json"))

WILAYAH = "ID"
UTAS = 16
SIMPAN_SETIAP = 500
TAHUN_MIN, TAHUN_MAKS = 1900, 2030

ATRIBUSI = "Data ketersediaan dari JustWatch melalui TMDB"

_kunci_simpan = threading.Lock()


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


def ambil_json(path, **q):
    q["api_key"] = KEY
    url = "https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(q)
    for coba in range(6):
        try:
            r = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(r, timeout=25) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(float(e.headers.get("Retry-After") or 2) + coba)
                continue
            if e.code in (404, 401):
                return None
            time.sleep(1 + coba)
        except Exception:
            time.sleep(1 + coba)
    return None


def daftar_penyedia():
    """
    Kamus penyedia untuk memetakan id -> nama.

    Dibangun DUA lapis, dan itu perlu: daftar ber-`watch_region=ID` hanya memuat
    46 penyedia, tetapi ketersediaan satu judul di Indonesia bisa menyebut
    penyedia yang TIDAK ada di daftar regional itu — terukur pada id 3
    (Google Play Movies, 14 judul) dan 2782 (Filmtap, 1 judul). Kamus yang
    bolong membuat chip-nya tampil tanpa nama, jadi daftar global (912 penyedia)
    dipakai untuk menambal sisanya.

    Daftar regional diambil LEBIH DULU supaya `priority`-nya — urutan tampil
    khusus Indonesia — tidak tertimpa nilai global.
    """
    peta = {}
    for jenis in ("movie", "tv"):
        d = ambil_json("/watch/providers/" + jenis, watch_region=WILAYAH) or {}
        for p in d.get("results", []):
            peta[p["provider_id"]] = {
                "name": p["provider_name"],
                "logoPath": p.get("logo_path"),
                "priority": p.get("display_priority"),
            }
    regional = len(peta)
    for jenis in ("movie", "tv"):
        d = ambil_json("/watch/providers/" + jenis) or {}
        for p in d.get("results", []):
            if p["provider_id"] in peta:
                continue
            peta[p["provider_id"]] = {
                "name": p["provider_name"],
                "logoPath": p.get("logo_path"),
                # Tanpa priority: penyedia ini tidak ada di indeks regional,
                # jadi urutan tampilnya diserahkan ke aplikasi.
                "priority": None,
            }
    print("[watch] penyedia: %d regional %s + %d tambalan global"
          % (regional, WILAYAH, len(peta) - regional))
    return peta


def tmdb_dari_id(nilai):
    """
    Menurunkan (type, tmdbId) dari id katalog Bytify.

    Film Indonesia memakai "tmdb-<id>", lapisan ini memakai "tmdb-movie-<id>"
    dan "tmdb-tv-<id>". Id lk21, anilist, dan siput serial tidak punya TMDB id,
    jadi dilewati.
    """
    teks = str(nilai or "")
    if not teks.startswith("tmdb-"):
        return None
    sisa = teks[len("tmdb-"):]
    jenis = "movie"
    if sisa.startswith("tv-"):
        jenis, sisa = "tv", sisa[3:]
    elif sisa.startswith("movie-"):
        sisa = sisa[6:]
    return (jenis, int(sisa)) if sisa.isdigit() else None


def muat_daftar_kerja():
    """
    Daftar kerja bisa datang dari dua bentuk berkas:

    1. Katalog Bytify sendiri — `{"movies": [{"id": "tmdb-<id>", ...}]}`. INI
       yang seharusnya dipakai, karena lapisan ketersediaan hanya berguna untuk
       judul yang memang ada di aplikasi. Terukur: memanen dari himpunan judul
       luar memberi 6.926 judul tersedia tetapi hanya 68 di antaranya yang
       cocok dengan katalog, jadi chipnya praktis tak pernah tampil.
    2. Daftar judul luar — array objek dengan `type` + `tmdbId`.

    Keduanya diterima; bentuknya dikenali dari isi berkas.
    """
    with open(SUMBER_DAFTAR, encoding="utf-8") as f:
        mentah = json.load(f)

    baris = mentah.get("movies") if isinstance(mentah, dict) else mentah
    if not isinstance(baris, list):
        raise SystemExit("Bentuk daftar kerja tidak dikenali: " + SUMBER_DAFTAR)

    kerja, lihat = [], set()
    for d in baris:
        if not isinstance(d, dict):
            continue
        pasang = None
        t, i = d.get("type"), d.get("tmdbId")
        if t in ("movie", "tv") and isinstance(i, int):
            pasang = (t, i)
        else:
            # Katalog Bytify: tipe diturunkan dari id, bukan dari field `type`
            # (yang di sana berarti "movie"/"series" untuk pemutar, bukan ruang
            # id TMDB).
            pasang = tmdb_dari_id(d.get("id"))
        if pasang and pasang not in lihat:
            lihat.add(pasang)
            kerja.append(pasang)
    return kerja


def muat_hasil():
    if not os.path.exists(KELUARAN):
        return {}, {}
    try:
        with open(KELUARAN, encoding="utf-8") as f:
            d = json.load(f)
        return {x["id"]: x for x in d.get("titles", [])}, d.get("providers", {})
    except Exception:
        return {}, {}


def tahun_wajar(teks):
    m = re.match(r"^(\d{4})", str(teks or ""))
    if not m:
        return None
    th = int(m.group(1))
    return th if TAHUN_MIN <= th <= TAHUN_MAKS else None


def runtime_menit(d, jenis):
    if jenis == "movie":
        r = d.get("runtime")
        return r if isinstance(r, int) and r > 0 else None
    deret = d.get("episode_run_time") or []
    kandidat = [x for x in deret if isinstance(x, int) and x > 0]
    if kandidat:
        return kandidat[0]
    akhir = (d.get("last_episode_to_air") or {}).get("runtime")
    return akhir if isinstance(akhir, int) and akhir > 0 else None


def ringkas_ketersediaan(d):
    """Hanya wilayah Indonesia; simpan id penyedia saja agar berkasnya ramping."""
    hasil = (d.get("watch/providers") or {}).get("results") or {}
    idn = hasil.get(WILAYAH)
    if not idn:
        return None
    w = {}
    for jenis in ("flatrate", "free", "ads", "rent", "buy"):
        ids = [x["provider_id"] for x in (idn.get(jenis) or []) if x.get("provider_id")]
        if ids:
            w[jenis] = sorted(set(ids))
    if not w:
        return None
    if idn.get("link"):
        w["link"] = idn["link"]
    return w


def olah(tugas):
    jenis, tid = tugas
    d = ambil_json("/" + jenis + "/" + str(tid),
                   append_to_response="watch/providers,external_ids")
    if not d:
        return None
    judul = (d.get("title") or d.get("name") or "").strip()
    if not judul:
        return None
    poster = d.get("poster_path")
    nilai = d.get("vote_average")
    entri = {
        "id": "tmdb-" + jenis + "-" + str(tid),
        "tmdbId": tid,
        "type": jenis,
        "title": judul,
        "originalTitle": (d.get("original_title") or d.get("original_name") or "").strip() or None,
        "year": tahun_wajar(d.get("release_date") or d.get("first_air_date")),
        "posterUrl": "https://image.tmdb.org/t/p/w500" + poster if poster else None,
        "overview": (d.get("overview") or "").strip() or None,
        "genres": [g["name"] for g in (d.get("genres") or []) if g.get("name")],
        "rating": round(nilai, 1) if isinstance(nilai, (int, float)) and nilai > 0 else None,
        "runtimeMin": runtime_menit(d, jenis),
        "imdbId": (d.get("external_ids") or {}).get("imdb_id") or None,
        "status": d.get("status") or None,
        "language": d.get("original_language") or None,
        # Jumlah musim TMDB. Datang GRATIS di jawaban yang sama, dan itulah
        # satu-satunya yang kurang untuk membuka subtitle per-episode serial:
        # seluruh 65.296 episode katalog kita bernomor musim 1, jadi acara
        # BERMUSIM TUNGGAL aman diminta subtitlenya, sementara yang bermusim
        # banyak tetap dilewati (3k).
        "seasons": d.get("number_of_seasons") if jenis == "tv" else None,
    }
    w = ringkas_ketersediaan(d)
    if w:
        entri["watch"] = w
    return {k: v for k, v in entri.items() if v not in (None, [], {})}


def simpan(titles, providers):
    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({"attribution": ATRIBUSI, "region": WILAYAH,
                   "providers": providers,
                   "titles": list(titles.values())},
                  f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)


def main():
    hanya_penyedia = "--segarkan-penyedia" in sys.argv
    if hanya_penyedia:
        # Kamus penyedia saja: dipakai kalau ternyata ada id yang dirujuk judul
        # tapi belum ada namanya. Judul yang sudah dipanen TIDAK disentuh.
        hasil, _ = muat_hasil()
        penyedia = dict((str(k), v) for k, v in daftar_penyedia().items())
        simpan(hasil, penyedia)
        print("[watch] kamus penyedia disegarkan: %d entri, %d judul dipertahankan"
              % (len(penyedia), len(hasil)))
        return 0

    hasil, penyedia = muat_hasil()

    if "--segarkan-musim" in sys.argv:
        # Judul TV yang dipanen SEBELUM `seasons` ada dibuang dari hasil supaya
        # loop lanjutan di bawah memanennya ulang. Hanya TV, dan hanya yang
        # belum punya angkanya: memanen ulang 68.916 film hanya untuk sebuah
        # field yang memang tidak berlaku bagi film adalah pemborosan kuota.
        #
        # Daftar kerjanya diturunkan dari HASIL, bukan dari SUMBER_DAFTAR.
        # Yang disegarkan adalah judul yang sudah pernah dipanen, jadi berkas
        # daftar kerja aslinya tidak perlu ada lagi — dan memang sering tidak
        # ada, karena ia berasal dari mesin yang memanennya pertama kali.
        kerja = [(v["type"], v["tmdbId"]) for v in hasil.values()
                 if v.get("type") == "tv" and not v.get("seasons")
                 and isinstance(v.get("tmdbId"), int)]
        for t, i in kerja:
            hasil.pop("tmdb-" + t + "-" + str(i), None)
        print("[watch] %d judul TV dipanen ulang untuk jumlah musim" % len(kerja))
    else:
        kerja = muat_daftar_kerja()
    if not penyedia:
        penyedia = dict((str(k), v) for k, v in daftar_penyedia().items())
        print("[watch] " + str(len(penyedia)) + " penyedia berlaku di " + WILAYAH)
    sisa = [(t, i) for (t, i) in kerja if "tmdb-" + t + "-" + str(i) not in hasil]
    print("[watch] daftar kerja %d, sudah ada %d, sisa %d" % (len(kerja), len(hasil), len(sisa)))
    if not sisa:
        simpan(hasil, penyedia)
        print("[watch] sudah lengkap")
        return 0

    t0 = time.time()
    selesai = 0
    gagal = 0
    with cf.ThreadPoolExecutor(UTAS) as ex:
        for entri in ex.map(olah, sisa):
            selesai += 1
            if entri:
                hasil[entri["id"]] = entri
            else:
                gagal += 1
            if selesai % SIMPAN_SETIAP == 0:
                with _kunci_simpan:
                    simpan(hasil, penyedia)
                laju = selesai / max(time.time() - t0, 1)
                punya = sum(1 for x in hasil.values() if "watch" in x)
                print("  %d/%d  %.1f/s  tersedia=%d  gagal=%d  sisa~%.0fm"
                      % (selesai, len(sisa), laju, punya, gagal,
                         (len(sisa) - selesai) / max(laju, 0.1) / 60), flush=True)

    simpan(hasil, penyedia)
    punya = sum(1 for x in hasil.values() if "watch" in x)
    mib = os.path.getsize(KELUARAN) / 1024 / 1024
    print("[watch] selesai: %d judul, %d punya ketersediaan resmi (%.1f%%), "
          "%d gagal, %.2f MiB -> %s"
          % (len(hasil), punya, punya * 100.0 / max(len(hasil), 1), gagal, mib, KELUARAN))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
