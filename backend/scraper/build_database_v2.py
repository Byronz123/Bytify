# ==========================================
# Bytify/backend/scraper/build_database_v2.py
# Versi perbaikan: Output kompatibel MovieCatalogDto, Auto-clean junk
# ==========================================
import os
import sys
import json
import queue
import threading
import re
from ultimate_scraper_v2 import (
    init_stealth_driver,
    scrape_with_driver,
    enrich_with_tmdb,
    is_junk_url,
)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LINKS_FILE = os.path.join(BASE_DIR, "movie_links.txt")
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
DB_FILE = os.path.join(DATA_DIR, "bytify_database.json")
CATALOG_FILE = os.path.join(DATA_DIR, "catalog.json")  # Format MovieCatalogDto untuk API

# Konfigurasi
MAX_WORKERS = 10
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")  # Opsional: set env var untuk enrichment

db_lock = threading.Lock()
database = []
existing_urls = set()
processed_count = 0
total_to_process = 0
stats = {"success": 0, "failed": 0, "junk_filtered": 0, "m3u8_found": 0, "embed_only": 0}


def clean_existing_database(db):
    """
    Bersihkan database yang sudah ada dari junk URLs.
    Jalankan ini untuk memperbaiki database lama.
    """
    cleaned = 0
    for item in db:
        # Bersihkan stream_urls dari junk
        if "stream_urls" in item:
            original_count = len(item["stream_urls"])
            item["stream_urls"] = [u for u in item["stream_urls"] if not is_junk_url(u)]
            cleaned += original_count - len(item["stream_urls"])

        # Bersihkan videoUrl jika junk
        if item.get("videoUrl") and is_junk_url(item["videoUrl"]):
            # Fallback ke embedUrl atau nd_url
            item["videoUrl"] = item.get("embedUrl") or item.get("nd_url")

        # Migrasi dari format lama
        if "nd_url" in item and not item.get("embedUrl"):
            nd = item["nd_url"]
            if nd and not is_junk_url(nd):
                item["embedUrl"] = nd
                if not item.get("videoUrl"):
                    item["videoUrl"] = nd

    return cleaned


def normalize_to_movie_dto(raw_item):
    """
    Konversi output scraper ke format MovieDto yang kompatibel dengan Android app.
    """
    slug = raw_item.get("id", "unknown")
    movie_type = raw_item.get("type", "movie")

    # Tentukan videoUrl terbaik
    video_url = raw_item.get("videoUrl")
    embed_url = raw_item.get("embedUrl")
    stream_urls = raw_item.get("stream_urls", [])

    # Prioritas: m3u8 > mp4 bersih > embed URL
    best_video = None
    for stream in stream_urls:
        if '.m3u8' in stream.lower():
            best_video = stream
            break
    if not best_video:
        for stream in stream_urls:
            if '.mp4' in stream.lower():
                best_video = stream
                break
    if not best_video:
        best_video = video_url or embed_url

    # Build MovieDto
    dto = {
        "id": f"lk21-{movie_type}-{slug}",
        "type": movie_type,
        "title": raw_item.get("title", "Unknown"),
        "overview": raw_item.get("overview", ""),
        "posterUrl": raw_item.get("posterUrl"),
        "backdropUrl": raw_item.get("backdropUrl") or raw_item.get("posterUrl"),
        "releaseYear": raw_item.get("releaseYear"),
        "durationMin": raw_item.get("durationMin"),
        "genres": raw_item.get("genres", []),
        "rating": raw_item.get("rating"),
        "language": "id",
        "videoUrl": best_video,
        "trailerUrl": raw_item.get("trailerUrl"),
        "subtitles": raw_item.get("subtitles", []),
        "episodes": [],
        "license": "LK21 Stream Adapter",
    }

    # Tambahkan overview default jika kosong
    if not dto["overview"]:
        dto["overview"] = f"Tonton {dto['title']} Subtitle Indonesia di Bytify."

    return dto


def build_catalog_json(movies):
    """
    Build file catalog.json dalam format MovieCatalogDto
    yang dapat langsung dikonsumsi oleh Android app.
    """
    # Build sections berdasarkan genre & tahun
    sections = []
    genre_buckets = {}
    year_buckets = {}

    for movie in movies:
        mid = movie["id"]
        for genre in movie.get("genres", []):
            genre_buckets.setdefault(genre, []).append(mid)
        year = movie.get("releaseYear")
        if year:
            year_buckets.setdefault(str(year), []).append(mid)

    # Section: Film Terbaru (berdasarkan tahun terbaru)
    sorted_years = sorted(year_buckets.keys(), reverse=True)
    if sorted_years:
        latest_year = sorted_years[0]
        sections.append({
            "title": f"Film Terbaru {latest_year}",
            "movieIds": year_buckets[latest_year][:20],
        })

    # Section: Rating Tertinggi
    rated = [m for m in movies if m.get("rating") and m["rating"] > 0]
    rated.sort(key=lambda m: m["rating"], reverse=True)
    if rated:
        sections.append({
            "title": "Rating Tertinggi",
            "movieIds": [m["id"] for m in rated[:20]],
        })

    # Section: Per Genre (top 5 genre terpopuler)
    popular_genres = sorted(genre_buckets.items(), key=lambda x: len(x[1]), reverse=True)[:5]
    for genre, ids in popular_genres:
        sections.append({
            "title": genre,
            "movieIds": ids[:15],
        })

    return {
        "movies": movies,
        "sections": sections,
    }


def save_db_safely():
    """Menyimpan database secara aman menggunakan file sementara."""
    # Simpan format raw (untuk resume & debug)
    temp_file = DB_FILE + ".tmp"
    with open(temp_file, "w", encoding="utf-8") as out:
        json.dump(database, out, indent=2, ensure_ascii=False)
    os.replace(temp_file, DB_FILE)

    # Simpan format catalog.json (untuk API)
    movie_dtos = [normalize_to_movie_dto(item) for item in database]
    catalog = build_catalog_json(movie_dtos)
    temp_catalog = CATALOG_FILE + ".tmp"
    with open(temp_catalog, "w", encoding="utf-8") as out:
        json.dump(catalog, out, indent=2, ensure_ascii=False)
    os.replace(temp_catalog, CATALOG_FILE)


def worker_task(worker_id, url_queue):
    global processed_count
    print(f"[*] Build Worker-{worker_id} menyala...")

    try:
        driver = init_stealth_driver()
    except Exception as e:
        print(f"[!] Build Worker-{worker_id} gagal memulai browser: {e}")
        return

    while not url_queue.empty():
        try:
            idx, url = url_queue.get_nowait()
        except queue.Empty:
            break

        try:
            item_data = scrape_with_driver(driver, url)

            # TMDB enrichment (opsional)
            if TMDB_API_KEY:
                item_data = enrich_with_tmdb(item_data, TMDB_API_KEY)

            with db_lock:
                processed_count += 1
                if item_data:
                    database.append(item_data)
                    existing_urls.add(url)
                    stats["success"] += 1

                    # Statistik stream
                    clean_streams = [s for s in item_data.get("stream_urls", []) if not is_junk_url(s)]
                    has_m3u8 = any('.m3u8' in s.lower() for s in clean_streams)
                    has_embed = bool(item_data.get("embedUrl"))

                    if has_m3u8:
                        stats["m3u8_found"] += 1
                    elif has_embed:
                        stats["embed_only"] += 1

                    poster_icon = "✓" if item_data.get('posterUrl') else "✗"
                    video_icon = "m3u8" if has_m3u8 else ("embed" if has_embed else "✗")
                    synopsis_icon = "✓" if item_data.get('overview') else "✗"

                    print(f"[W-{worker_id}] [{idx}/{total_to_process}] ✓ {item_data['title'][:40]} | Poster:{poster_icon} Video:{video_icon} Synopsis:{synopsis_icon} Rating:{item_data.get('rating', '-')}")

                    # Simpan berkala setiap 5 film
                    if len(database) % 5 == 0:
                        save_db_safely()

        except Exception as e:
            stats["failed"] += 1
            print(f"[W-{worker_id}] [{idx}/{total_to_process}] ✗ Gagal: {url} — {e}")
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


def build_database():
    global database, existing_urls, total_to_process
    os.makedirs(DATA_DIR, exist_ok=True)

    if not os.path.exists(LINKS_FILE):
        print(f"[!] File {LINKS_FILE} belum ada. Jalankan crawler.py terlebih dahulu.")
        return

    with open(LINKS_FILE, "r", encoding="utf-8") as f:
        urls = [line.strip() for line in f if line.strip()]
    total_to_process = len(urls)

    # Muat database yang sudah ada
    if os.path.exists(DB_FILE):
        try:
            with open(DB_FILE, "r", encoding="utf-8") as f:
                database = json.load(f)
                existing_urls = {item.get("lk21_url", "") for item in database}

                # Auto-clean junk dari database lama
                junk_cleaned = clean_existing_database(database)
                if junk_cleaned > 0:
                    print(f"[*] Auto-clean: {junk_cleaned} junk URL dihapus dari database lama.")
                    save_db_safely()
        except Exception:
            database = []

    url_queue = queue.Queue()
    skipped = 0
    for idx, url in enumerate(urls, 1):
        if url in existing_urls:
            skipped += 1
        else:
            url_queue.put((idx, url))

    print("=" * 70)
    print(f"  BYTIFY DATABASE BUILDER v2 (Anti-Junk Edition)")
    print("=" * 70)
    print(f"  Total URL di daftar       : {total_to_process}")
    print(f"  Sudah diekstrak (Skip)    : {skipped}")
    print(f"  Sisa antrean              : {url_queue.qsize()}")
    print(f"  Workers paralel           : {MAX_WORKERS}")
    print(f"  TMDB Enrichment           : {'Aktif' if TMDB_API_KEY else 'Nonaktif (set TMDB_API_KEY)'}")
    print(f"  Output                    : {DB_FILE}")
    print(f"  Catalog API               : {CATALOG_FILE}")
    print("=" * 70)

    if url_queue.empty():
        print("[*] Semua URL sudah ada di dalam database!")
        # Tetap regenerasi catalog.json
        save_db_safely()
        return

    threads = []
    for i in range(1, MAX_WORKERS + 1):
        t = threading.Thread(target=worker_task, args=(i, url_queue), daemon=True)
        t.start()
        threads.append(t)

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
        print(f"  HASIL AKHIR")
        print("=" * 70)
        print(f"  Total film tersimpan      : {len(database)}")
        print(f"  Sukses sesi ini           : {stats['success']}")
        print(f"  Gagal                     : {stats['failed']}")
        print(f"  Dengan M3U8 langsung      : {stats['m3u8_found']}")
        print(f"  Embed-only                : {stats['embed_only']}")
        print(f"  Database                  : {DB_FILE}")
        print(f"  Catalog API               : {CATALOG_FILE}")
        print("=" * 70)


# ============================================================
# BONUS: Tool untuk membersihkan database lama secara standalone
# ============================================================

def clean_old_database():
    """Jalankan: python build_database_v2.py --clean"""
    if not os.path.exists(DB_FILE):
        print("[!] Database belum ada.")
        return

    with open(DB_FILE, "r", encoding="utf-8") as f:
        db = json.load(f)

    print(f"[*] Membersihkan {len(db)} entri dari junk URLs...")

    total_junk = 0
    for item in db:
        # Hitung junk sebelum dibersihkan
        if "stream_urls" in item:
            total_junk += sum(1 for u in item["stream_urls"] if is_junk_url(u))

    cleaned = clean_existing_database(db)

    # Simpan
    database.clear()
    database.extend(db)
    save_db_safely()

    print(f"[*] Selesai! {cleaned} junk URL dihapus.")
    print(f"[*] Database dan catalog.json telah diperbarui.")


if __name__ == "__main__":
    if "--clean" in sys.argv:
        clean_old_database()
    else:
        build_database()
