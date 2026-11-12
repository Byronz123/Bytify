# ==========================================
# Bytify/backend/scraper/build_database.py
# Database builder paralel: Anti-Junk, Auto-clean, output kompatibel API & MovieDto
#
# Pemakaian:
#   python build_database.py            -> scraping URL di movie_links.txt (auto-resume)
#   python build_database.py --clean    -> hanya membersihkan junk dari database yang ada
#
# Variabel lingkungan opsional:
#   BYTIFY_WORKERS  jumlah browser paralel (default 4)
#   TMDB_API_KEY    aktifkan pengayaan metadata dari TMDB
#   CHROME_VERSION  paksa versi mayor Chrome jika deteksi otomatis gagal
# ==========================================
import os
import re
import sys
import json
import time
import queue
import threading
from ultimate_scraper import (
    init_stealth_driver,
    scrape_with_driver,
    enrich_with_tmdb,
    is_junk_url,
)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LINKS_FILE = os.path.join(BASE_DIR, "movie_links.txt")
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
DB_FILE = os.path.join(DATA_DIR, "bytify_database.json")
CATALOG_FILE = os.path.join(DATA_DIR, "catalog.json")  # Format MovieCatalogDto
REDIRECT_FILE = os.path.join(DATA_DIR, "redirect-nontondrama.txt")  # id halaman-antara
SUBTITLE_DIR = os.path.join(DATA_DIR, "subtitles")     # backend/data/subtitles/<slug>/<kode>.srt
DIST_CATALOG = os.path.abspath(os.path.join(BASE_DIR, "..", "api", "dist", "api", "catalog.json"))

MAX_WORKERS = int(os.environ.get("BYTIFY_WORKERS", "4") or 4)
# Alamat publik tempat folder subtitles di-hosting. Tanpa ini, subtitle lokal
# tetap didaftar memakai path relatif "subtitles/<slug>/<berkas>".
SUBTITLE_BASE_URL = os.environ.get("BYTIFY_SUBTITLE_BASE", "").rstrip("/")
# Setiap penyimpanan menulis ULANG seluruh database. Dengan 5, berkas 27 MB
# (nanti ~66 MB) ditulis belasan kali per menit — makin lambat seiring katalog
# membesar. 100 memangkas tulisan disk 20x, dan yang dipertaruhkan saat proses
# berhenti mendadak hanya ~2 menit kerja, karena film yang sudah ada dilewati
# saat dijalankan ulang.
SAVE_EVERY = int(os.environ.get("BYTIFY_SAVE_EVERY", "100") or 100)

# Browser disegarkan tiap sekian film per worker. Chrome menggelembung setelah
# menjelajahi ribuan halaman — terukur: RAM bebas turun 5,1 GB ke 1,7 GB dalam
# 25 menit dan laju anjlok dari 97 ke 33 film/menit. Menutup lalu membuka
# browser melepaskan memori itu, dengan ongkos beberapa detik sekali daur.
RECYCLE_EVERY = int(os.environ.get("BYTIFY_RECYCLE_EVERY", "150") or 150)
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")

# Nama tampil untuk kode bahasa ISO-639-1 yang lazim dipakai subtitle.
NAMA_BAHASA = {
    "id": "Indonesia", "en": "English", "ms": "Melayu", "ar": "العربية",
    "es": "Español", "pt": "Português", "fr": "Français", "de": "Deutsch",
    "it": "Italiano", "nl": "Nederlands", "tr": "Türkçe", "ru": "Русский",
    "ja": "日本語", "ko": "한국어", "zh": "中文", "th": "ไทย", "vi": "Tiếng Việt",
    "hi": "हिन्दी", "tl": "Filipino", "pl": "Polski", "sv": "Svenska",
}


def subtitle_lokal(slug):
    """
    Baca subtitle dari backend/data/subtitles/<slug>/<kode-bahasa>.srt
    Contoh: backend/data/subtitles/0187-ufo-2026/id.srt -> label "Indonesia".
    Berkas .srt maupun .vtt sama-sama diterima; aplikasi menguraikan keduanya.
    """
    folder = os.path.join(SUBTITLE_DIR, slug)
    if not os.path.isdir(folder):
        return []
    hasil = []
    for berkas in sorted(os.listdir(folder)):
        nama, ekstensi = os.path.splitext(berkas)
        if ekstensi.lower() not in (".srt", ".vtt"):
            continue
        kode = nama.strip().lower()
        # BYTIFY_SUBTITLE_BASE sudah menunjuk ke folder subtitles itu sendiri,
        # jadi di belakangnya cukup <slug>/<berkas> agar tidak terduplikasi.
        hasil.append({
            "lang": kode,
            "label": NAMA_BAHASA.get(kode, kode.upper()),
            "url": (
                f"{SUBTITLE_BASE_URL}/{slug}/{berkas}" if SUBTITLE_BASE_URL
                else f"subtitles/{slug}/{berkas}"
            ),
        })
    return hasil


# Field yang benar-benar dibaca MovieDto di aplikasi Android. Sisanya
# (cast, countries, directors, quality, resolution) tetap tersimpan utuh di
# bytify_database.json, hanya tidak ikut dikirim ke perangkat agar katalog
# yang diunduh ponsel tidak membengkak.
# Cloudflare Pages menolak berkas di atas 25 MiB, dan katalog 25.135 film
# sempat mencapai 31,6 MB. Dua field dibuang karena murni mubazir:
#   license      - hanya 1 nilai unik untuk SELURUH katalog; aplikasi memakai
#                  nilai tetap, jadi mengirimnya 25.135 kali sia-sia.
#   backdropUrl  - terukur identik dengan posterUrl pada 25.135 dari 25.135
#                  film; aplikasi jatuh ke posterUrl bila kosong.
FIELD_KATALOG = {
    "id", "type", "title", "overview", "posterUrl",
    "releaseYear", "durationMin", "genres", "rating", "language",
    "videoUrl", "sourceUrl", "servers", "trailerUrl", "subtitles",
    "episodes",
}

# Sinopsis adalah penyumbang terbesar (8,66 MB, rata-rata 359 karakter).
# Dipotong agar katalog muat; halaman detail melengkapinya saat dibuka.
PANJANG_SINOPSIS_KATALOG = int(os.environ.get("BYTIFY_SINOPSIS_MAKS", "150") or 150)


def rampingkan_dto(dto):
    """Sisakan field yang dipakai aplikasi dan buang nilai kosong.

    Setiap field MovieDto punya nilai bawaan, jadi field yang hilang
    diperlakukan sama dengan field bernilai kosong oleh aplikasi.
    """
    ramping = {k: v for k, v in dto.items()
               if k in FIELD_KATALOG and v not in (None, "", [], {})}
    sinopsis = ramping.get("overview")
    if sinopsis and len(sinopsis) > PANJANG_SINOPSIS_KATALOG:
        # Dipotong di batas kata terakhir supaya tidak terputus di tengah kata.
        potong = sinopsis[:PANJANG_SINOPSIS_KATALOG]
        spasi = potong.rfind(" ")
        ramping["overview"] = (potong[:spasi] if spasi > 80 else potong).rstrip(" ,.;:") + "..."
    return ramping


db_lock = threading.Lock()
# Penyimpanan HARUS berurutan. Enam worker bisa mencapainya bersamaan dan
# saling berebut berkas sementara yang sama, sehingga film yang sedang digarap
# gagal dengan WinError 32/2 — film hilang bukan karena situsnya, melainkan
# karena balapan antar-thread.
save_lock = threading.Lock()
database = []
existing_urls = set()
total_to_process = 0
stats = {"success": 0, "failed": 0, "m3u8_found": 0, "embed_only": 0, "no_video": 0}


# ============================================================
# NORMALISASI & PEMBERSIHAN
# ============================================================

def source_url_of(item):
    return item.get("lk21_url") or item.get("sourceUrl") or ""


def _clean_list(values):
    seen, out = set(), []
    for u in values or []:
        if isinstance(u, str) and u.strip() and not is_junk_url(u) and u not in seen:
            seen.add(u)
            out.append(u.strip())
    return out


def _best_stream(streams):
    for s in streams:
        if ".m3u8" in s.lower():
            return s
    for s in streams:
        if ".mp4" in s.lower():
            return s
    return streams[0] if streams else None


def to_api_record(item):
    """
    Lengkapi item agar terbaca oleh API Node (server.mjs & movies.mjs)
    sekaligus tetap menyimpan field MovieDto. Dipakai untuk item baru & lama.
    Mengembalikan jumlah URL junk yang dibuang.
    """
    before = len(item.get("stream_urls") or []) + len(item.get("streamUrls") or [])

    streams = _clean_list((item.get("stream_urls") or []) + (item.get("streamUrls") or []))
    embed = item.get("embedUrl") or item.get("nd_url")
    if embed and is_junk_url(embed):
        embed = None

    video = item.get("videoUrl")
    if not video or is_junk_url(video):
        video = _best_stream(streams) or embed

    if item.get("streamUrl") and is_junk_url(item["streamUrl"]):
        item["streamUrl"] = None

    poster = item.get("posterUrl") or item.get("poster_url") or item.get("poster") or None
    year = item.get("releaseYear") or item.get("year") or None

    item.update({
        "servers": item.get("servers") or [],
        "embedUrl": embed,
        "videoUrl": video,
        "stream_urls": streams,
        "streamUrls": streams or ([video] if video else []),
        "streamUrl": item.get("streamUrl") or (streams[0] if streams else video),
        "posterUrl": poster,
        "poster": poster,
        "releaseYear": year,
        "year": str(year) if year else "",
        "sourceUrl": source_url_of(item),
        "lk21_url": source_url_of(item),
    })
    after = len(streams)
    return max(0, before - after) if before else 0


def needs_rescrape(item):
    """Entri dari scraper lama yang metadatanya rusak: genre berisi menu situs, atau tanpa sinopsis & rating."""
    genres = item.get("genres") or []
    no_info = not (item.get("overview") or "").strip() or item.get("rating") in (None, "", "N/A")
    return (len(genres) > 8 or no_info or not item.get("videoUrl")
            or not item.get("servers") or item.get("durationMin") in (None, 0))


def clean_existing_database(db):
    """Bersihkan junk dari seluruh database (format lama maupun baru)."""
    return sum(to_api_record(item) for item in db)


TAHUN_MIN = 1900
_YEAR_FIX_COUNT = [0]  # dihitung ulang tiap save_db_safely()


def tahun_maksimum_wajar():
    # Situs sumber sesekali menulis tahun mustahil (mis. 2038). Batas atas =
    # tahun berjalan + 1 agar rilis awal tahun depan tetap lolos.
    return time.gmtime().tm_year + 1


def koreksi_tahun(raw_year, title, raw_id):
    """Perbaiki akar, bukan menjepit: kalau releaseYear di luar
    1900..(tahun_sekarang+1), ambil tahun dari judul '(YYYY)' atau ekor slug.
    Mengembalikan int atau None; menaikkan _YEAR_FIX_COUNT bila berubah."""
    maks = tahun_maksimum_wajar()
    try:
        y = int(raw_year) if raw_year not in (None, "", "N/A") else None
    except (TypeError, ValueError):
        y = None
    if y is not None and TAHUN_MIN <= y <= maks:
        return y
    for sumber in (title, raw_id):
        for m in re.findall(r"(?:19|20)\d{2}", str(sumber or "")):
            yy = int(m)
            if TAHUN_MIN <= yy <= maks:
                if yy != y:
                    _YEAR_FIX_COUNT[0] += 1
                return yy
    if y is not None:  # tahun tak wajar & tak ada pengganti -> buang
        _YEAR_FIX_COUNT[0] += 1
    return None


def entri_halaman_redirect(item):
    """Halaman antara LK21 ('Anda akan dialihkan ...') yang tertangkap sebagai film."""
    return (str(item.get("title") or "").strip().lower()
            .startswith("anda akan dialihkan"))


def normalize_to_movie_dto(item):
    """Konversi item database ke MovieDto untuk catalog.json."""
    movie_type = item.get("type", "movie")
    raw_id = str(item.get("id") or "unknown")
    dto_id = raw_id if raw_id.startswith("lk21-") else f"lk21-{movie_type}-{raw_id}"
    rating = item.get("rating")
    try:
        rating = float(rating) if rating not in (None, "", "N/A") else None
    except (TypeError, ValueError):
        rating = None
    title = item.get("title") or "Unknown"
    year = koreksi_tahun(item.get("releaseYear") or item.get("year"), title, raw_id)

    overview = (item.get("overview") or "").strip()
    return {
        "id": dto_id,
        "type": movie_type,
        "title": title,
        "overview": overview or f"Tonton {title} Subtitle Indonesia di Bytify.",
        "posterUrl": item.get("posterUrl"),
        "backdropUrl": item.get("backdropUrl") or item.get("posterUrl"),
        "releaseYear": year,
        "durationMin": item.get("durationMin"),
        "genres": item.get("genres") or [],
        "rating": rating,
        "language": item.get("language") or "id",
        "videoUrl": item.get("videoUrl"),
        # Halaman sumber: dipakai aplikasi sebagai Referer saat memuat embed.
        # Tanpa ini player videonode mengalihkan ke beranda situs sumber.
        "sourceUrl": item.get("sourceUrl") or item.get("lk21_url"),
        "trailerUrl": item.get("trailerUrl"),
        "subtitles": item.get("subtitles") or subtitle_lokal(raw_id),
        "episodes": item.get("episodes") or [],
        "countries": item.get("countries") or [],
        "cast": item.get("cast") or [],
        "directors": item.get("directors") or [],
        "quality": item.get("quality"),
        "resolution": item.get("resolution"),
        "servers": item.get("servers") or [],
        "license": "LK21 Stream Adapter",
    }


def build_catalog_json(movies):
    """MovieCatalogDto dengan section otomatis (terbaru, rating, genre)."""
    sections, genre_buckets, year_buckets = [], {}, {}
    for m in movies:
        for g in m.get("genres", []):
            genre_buckets.setdefault(g, []).append(m["id"])
        if m.get("releaseYear"):
            year_buckets.setdefault(m["releaseYear"], []).append(m["id"])

    # Situs sumber sesekali menulis tahun yang mustahil (mis. 2038); kalau
    # dipakai, seksi "Film Terbaru" jadi salah. Batasi ke tahun berjalan.
    tahun_wajar = time.gmtime().tm_year + 1
    year_buckets = {t: v for t, v in year_buckets.items() if t <= tahun_wajar}
    if year_buckets:
        latest = max(year_buckets)
        sections.append({"title": f"Film Terbaru {latest}", "movieIds": year_buckets[latest][:20]})

    rated = sorted((m for m in movies if m.get("rating")), key=lambda m: m["rating"], reverse=True)
    if rated:
        sections.append({"title": "Rating Tertinggi", "movieIds": [m["id"] for m in rated[:20]]})

    for genre, ids in sorted(genre_buckets.items(), key=lambda x: len(x[1]), reverse=True)[:5]:
        sections.append({"title": genre, "movieIds": ids[:15]})

    return {"movies": movies, "sections": sections, "generatedAtEpochMs": int(time.time() * 1000)}


# ============================================================
# PENYIMPANAN
# ============================================================

def _atomic_write(path, payload, padat=False):
    """padat=True menulis tanpa indentasi; dipakai untuk berkas yang diunduh
    perangkat, karena spasi indentasi saja memakan sekitar 5 MB."""
    # Nama sementara dibuat unik per-thread supaya dua penulis tidak pernah
    # memakai berkas perantara yang sama.
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
    with open(tmp, "w", encoding="utf-8") as out:
        if padat:
            json.dump(payload, out, ensure_ascii=False, separators=(",", ":"))
        else:
            json.dump(payload, out, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def save_db_safely():
    """Simpan database (array, dibaca API Node), catalog.json, dan salinan dist bila ada."""
    with save_lock:
        _save_db_locked()


def _save_db_locked():
    os.makedirs(DATA_DIR, exist_ok=True)
    _atomic_write(DB_FILE, database)

    # Simpan id halaman-antara "Anda akan dialihkan" sebelum dibuang dari katalog
    # (banyak di antaranya serial nyata — bahan dukungan Nontondrama kelak).
    redirect_ids = [str(i.get("id")) for i in database if entri_halaman_redirect(i)]
    if redirect_ids:
        with open(REDIRECT_FILE, "w", encoding="utf-8") as fh:
            fh.write("\n".join(redirect_ids) + "\n")

    _YEAR_FIX_COUNT[0] = 0
    dto_semua = [rampingkan_dto(normalize_to_movie_dto(i)) for i in database]
    # Buang entri yang tak dapat diputar (tanpa server atau tanpa videoUrl).
    dto = [d for d in dto_semua if d.get("servers") and d.get("videoUrl")]
    dibuang = len(dto_semua) - len(dto)
    print(f"[koreksi] tahun diperbaiki dari judul/slug : {_YEAR_FIX_COUNT[0]} entri")
    print(f"[koreksi] entri tak-dapat-diputar dibuang  : {dibuang}"
          f" ({len(redirect_ids)} halaman redirect -> {REDIRECT_FILE})")
    _atomic_write(CATALOG_FILE, build_catalog_json(dto), padat=True)

    if os.path.isdir(os.path.dirname(DIST_CATALOG)):
        try:
            _atomic_write(DIST_CATALOG, {
                "ok": True,
                "updatedAt": int(time.time() * 1000),
                "total": len(database),
                "items": database,
            }, padat=True)
        except Exception as e:
            print(f"[WARN] Gagal menyinkronkan ke dist/api/catalog.json: {e}")


def load_existing_database():
    if not os.path.exists(DB_FILE):
        return []
    try:
        with open(DB_FILE, "r", encoding="utf-8") as f:
            content = f.read().strip()
        if not content:
            return []
        data = json.loads(content)
        if isinstance(data, dict):
            data = data.get("items", [])
        return data if isinstance(data, list) else []
    except Exception as e:
        # Jangan pernah menimpa database yang gagal dibaca
        print(f"[!] Database tidak bisa dibaca ({e}). Perbaiki/backup {DB_FILE} dulu.")
        sys.exit(1)


# ============================================================
# WORKER
# ============================================================

def worker_task(worker_id, url_queue):
    print(f"[*] Build Worker-{worker_id} menyala...")
    try:
        driver = init_stealth_driver()
    except Exception as e:
        print(f"[!] Build Worker-{worker_id} gagal memulai browser: {e}")
        return

    diproses = 0
    while True:
        try:
            idx, url = url_queue.get_nowait()
        except queue.Empty:
            break

        try:
            item = scrape_with_driver(driver, url)
            if item is None:  # halaman antara "Anda akan dialihkan" — lewati
                with db_lock:
                    stats["skipped"] = stats.get("skipped", 0) + 1
                print(f"[W-{worker_id}] [{idx}/{total_to_process}] SKIP redirect {url}")
                continue
            if TMDB_API_KEY:
                item = enrich_with_tmdb(item, TMDB_API_KEY)
            to_api_record(item)

            with db_lock:
                # Ganti entri lama (rusak) untuk URL yang sama, bukan menambah duplikat
                database[:] = [d for d in database if source_url_of(d) != url]
                database.append(item)
                existing_urls.add(url)
                stats["success"] += 1

                video = item.get("videoUrl") or ""
                nserver = len(item.get("servers") or [])
                if ".m3u8" in video.lower() or ".mp4" in video.lower():
                    stats["m3u8_found"] += 1
                    video_icon = "stream"
                elif item.get("embedUrl"):
                    stats["embed_only"] += 1
                    video_icon = f"embed x{nserver}"
                else:
                    stats["no_video"] += 1
                    video_icon = "-"

                print(
                    f"[W-{worker_id}] [{idx}/{total_to_process}] OK {item['title'][:40]} | "
                    f"Poster:{'v' if item.get('posterUrl') else 'x'} Video:{video_icon} "
                    f"Sinopsis:{'v' if item.get('overview') else 'x'} "
                    f"Rating:{item.get('rating') or '-'} Durasi:{item.get('durationMin') or '-'}m"
                )
                if stats["success"] % SAVE_EVERY == 0:
                    save_db_safely()

                diproses += 1
                if diproses % RECYCLE_EVERY == 0:
                    print(f"[W-{worker_id}] menyegarkan browser setelah {diproses} film")
                    try:
                        driver.quit()
                    except Exception:
                        pass
                    driver = init_stealth_driver()

        except Exception as e:
            with db_lock:
                stats["failed"] += 1
            print(f"[W-{worker_id}] [{idx}/{total_to_process}] GAGAL {url} - {e}")
            try:
                driver.quit()
            except Exception:
                pass
            try:
                driver = init_stealth_driver()
            except Exception:
                break
        finally:
            url_queue.task_done()

    try:
        driver.quit()
    except Exception:
        pass
    print(f"[*] Build Worker-{worker_id} selesai.")


# ============================================================
# MAIN
# ============================================================

def build_database():
    global database, existing_urls, total_to_process
    os.makedirs(DATA_DIR, exist_ok=True)

    if not os.path.exists(LINKS_FILE):
        print(f"[!] File {LINKS_FILE} belum ada. Jalankan crawler.py terlebih dahulu.")
        return

    with open(LINKS_FILE, "r", encoding="utf-8") as f:
        urls = list(dict.fromkeys(line.strip() for line in f if line.strip()))
    total_to_process = len(urls)

    database = load_existing_database()
    rescrape = {source_url_of(i) for i in database if source_url_of(i) and needs_rescrape(i)}
    existing_urls = {source_url_of(i) for i in database if source_url_of(i)} - rescrape
    if rescrape:
        print(f"[*] {len(rescrape)} entri lama metadatanya tidak lengkap -> akan di-scrape ulang.")
    junk = clean_existing_database(database)
    if junk:
        print(f"[*] Auto-clean: {junk} URL junk dibuang dari database lama.")

    url_queue = queue.Queue()
    skipped = 0
    for idx, url in enumerate(urls, 1):
        if url in existing_urls:
            skipped += 1
        else:
            url_queue.put((idx, url))

    print("=" * 70)
    print("  BYTIFY DATABASE BUILDER (Anti-Junk)")
    print("=" * 70)
    print(f"  Total URL di daftar       : {total_to_process}")
    print(f"  Sudah diekstrak (skip)    : {skipped}")
    print(f"  Sisa antrean              : {url_queue.qsize()}")
    print(f"  Workers paralel           : {MAX_WORKERS}")
    print(f"  TMDB Enrichment           : {'Aktif' if TMDB_API_KEY else 'Nonaktif (set TMDB_API_KEY)'}")
    print(f"  Database                  : {DB_FILE}")
    print(f"  Catalog                   : {CATALOG_FILE}")
    print("=" * 70)

    if url_queue.empty():
        print("[*] Semua URL sudah ada di database. Menyegarkan catalog.json...")
        save_db_safely()
        return

    threads = []
    for i in range(1, min(MAX_WORKERS, url_queue.qsize()) + 1):
        t = threading.Thread(target=worker_task, args=(i, url_queue), daemon=True)
        t.start()
        threads.append(t)
        time.sleep(1.0)  # Hindari semua Chrome menyala bersamaan

    try:
        for t in threads:
            while t.is_alive():
                t.join(timeout=1.0)
    except KeyboardInterrupt:
        print("\n[!] Dihentikan pengguna (Ctrl+C). Menyimpan data terakhir...")
    finally:
        with db_lock:
            save_db_safely()
        print()
        print("=" * 70)
        print("  HASIL AKHIR")
        print("=" * 70)
        print(f"  Total film tersimpan      : {len(database)}")
        print(f"  Sukses sesi ini           : {stats['success']}")
        print(f"  Gagal                     : {stats['failed']}")
        print(f"  Stream langsung (m3u8/mp4): {stats['m3u8_found']}")
        print(f"  Embed saja                : {stats['embed_only']}")
        print(f"  Tanpa video               : {stats['no_video']}")
        print("=" * 70)


def clean_old_database():
    global database
    database = load_existing_database()
    if not database:
        print("[!] Database kosong atau belum ada.")
        return
    print(f"[*] Membersihkan {len(database)} entri dari URL junk...")
    junk = clean_existing_database(database)
    save_db_safely()
    print(f"[*] Selesai. {junk} URL junk dibuang. Database & catalog.json diperbarui.")


def _kunci_proses_tunggal():
    """
    Cegah dua build_database berjalan bersamaan.

    Pernah terjadi tiga proses menulis database yang sama sekaligus (daemon dan
    jalan manual), saling berebut berkas sementara sehingga film yang sedang
    digarap gagal. Kunci ini membuat kejadian itu mustahil terulang.
    """
    kunci = os.path.join(DATA_DIR, ".build_database.lock")
    os.makedirs(DATA_DIR, exist_ok=True)
    if os.path.exists(kunci):
        try:
            pid_lama = int(open(kunci, encoding="utf-8").read().strip() or 0)
        except Exception:
            pid_lama = 0
        if pid_lama and pid_lama != os.getpid() and _pid_hidup(pid_lama):
            print(f"[!] build_database lain masih berjalan (PID {pid_lama}). Dibatalkan.")
            return None
        print("[*] Kunci lama ditemukan tetapi prosesnya sudah mati — dilanjutkan.")
    with open(kunci, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()))
    return kunci


def _pid_hidup(pid):
    try:
        import subprocess
        keluar = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            capture_output=True, text=True, timeout=20,
        ).stdout
        return str(pid) in keluar
    except Exception:
        return False


if __name__ == "__main__":
    _kunci = _kunci_proses_tunggal()
    if _kunci is None:
        sys.exit(1)
    import atexit
    atexit.register(lambda: os.path.exists(_kunci) and os.remove(_kunci))
    if "--clean" in sys.argv:
        clean_old_database()
    else:
        build_database()
