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
DIST_CATALOG = os.path.abspath(os.path.join(BASE_DIR, "..", "api", "dist", "api", "catalog.json"))

MAX_WORKERS = int(os.environ.get("BYTIFY_WORKERS", "4") or 4)
SAVE_EVERY = 5
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")

db_lock = threading.Lock()
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
    no_info = not (item.get("overview") or "").strip() and item.get("rating") in (None, "", "N/A")
    return len(genres) > 8 or no_info or not item.get("videoUrl")


def clean_existing_database(db):
    """Bersihkan junk dari seluruh database (format lama maupun baru)."""
    return sum(to_api_record(item) for item in db)


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
    year = item.get("releaseYear") or item.get("year")
    try:
        year = int(year) if year else None
    except (TypeError, ValueError):
        year = None

    title = item.get("title") or "Unknown"
    return {
        "id": dto_id,
        "type": movie_type,
        "title": title,
        "overview": item.get("overview") or f"Tonton {title} Subtitle Indonesia di Bytify.",
        "posterUrl": item.get("posterUrl"),
        "backdropUrl": item.get("backdropUrl") or item.get("posterUrl"),
        "releaseYear": year,
        "durationMin": item.get("durationMin"),
        "genres": item.get("genres") or [],
        "rating": rating,
        "language": item.get("language") or "id",
        "videoUrl": item.get("videoUrl"),
        "trailerUrl": item.get("trailerUrl"),
        "subtitles": item.get("subtitles") or [],
        "episodes": item.get("episodes") or [],
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

def _atomic_write(path, payload):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as out:
        json.dump(payload, out, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def save_db_safely():
    """Simpan database (array, dibaca API Node), catalog.json, dan salinan dist bila ada."""
    os.makedirs(DATA_DIR, exist_ok=True)
    _atomic_write(DB_FILE, database)
    _atomic_write(CATALOG_FILE, build_catalog_json([normalize_to_movie_dto(i) for i in database]))

    if os.path.isdir(os.path.dirname(DIST_CATALOG)):
        try:
            _atomic_write(DIST_CATALOG, {
                "ok": True,
                "updatedAt": int(time.time() * 1000),
                "total": len(database),
                "items": database,
            })
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

    while True:
        try:
            idx, url = url_queue.get_nowait()
        except queue.Empty:
            break

        try:
            item = scrape_with_driver(driver, url)
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
                if ".m3u8" in video.lower() or ".mp4" in video.lower():
                    stats["m3u8_found"] += 1
                    video_icon = "stream"
                elif item.get("embedUrl"):
                    stats["embed_only"] += 1
                    video_icon = "embed"
                else:
                    stats["no_video"] += 1
                    video_icon = "-"

                print(
                    f"[W-{worker_id}] [{idx}/{total_to_process}] OK {item['title'][:40]} | "
                    f"Poster:{'v' if item.get('posterUrl') else 'x'} Video:{video_icon} "
                    f"Sinopsis:{'v' if item.get('overview') else 'x'} Rating:{item.get('rating') or '-'}"
                )
                if stats["success"] % SAVE_EVERY == 0:
                    save_db_safely()

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


if __name__ == "__main__":
    if "--clean" in sys.argv:
        clean_old_database()
    else:
        build_database()
