# ==========================================
# 2. Bytify\backend\scraper\build_database.py (10 Workers Paralel)
# ==========================================
import os
import json
import queue
import threading
from ultimate_scraper import init_stealth_driver, scrape_with_driver

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LINKS_FILE = os.path.join(BASE_DIR, "movie_links.txt")
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
DB_FILE = os.path.join(DATA_DIR, "bytify_database.json")

# Menggunakan 10 Worker secara bersamaan (Sangat stabil, sangat aman untuk RAM, hasil maksimal)
MAX_WORKERS = 10

db_lock = threading.Lock()
database = []
existing_urls = set()
processed_count = 0
total_to_process = 0

def save_db_safely():
    """Menyimpan database secara aman menggunakan file sementara agar tidak korup."""
    temp_file = DB_FILE + ".tmp"
    with open(temp_file, "w", encoding="utf-8") as out:
        json.dump(database, out, indent=2, ensure_ascii=False)
    os.replace(temp_file, DB_FILE)

def worker_task(worker_id, url_queue):
    global processed_count
    print(f"[*] Menyalakan mesin Build Worker-{worker_id}...")
    
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
            with db_lock:
                processed_count += 1
                if item_data:
                    database.append(item_data)
                    existing_urls.add(url)
                    
                    poster_status = "Ada" if item_data.get('poster_url') else "Null"
                    nd_status = "Ada" if item_data.get('nd_url') else "Null"
                    print(f"[Worker-{worker_id}] [{idx}/{total_to_process}] BERHASIL: {item_data['title']} | Poster: {poster_status} | ND_URL: {nd_status} | Stream: {len(item_data['stream_urls'])} | DL: {len(item_data['dl_urls'])}")
                    
                    # Simpan berkala setiap kelipatan 5 film sukses
                    if len(database) % 5 == 0:
                        save_db_safely()
        except Exception as e:
            print(f"[Worker-{worker_id}] [{idx}/{total_to_process}] Gagal pada {url}: {e}")
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

    # Muat database yang sudah ada (Mendukung fitur auto-resume & incremental update otomatis untuk film baru)
    if os.path.exists(DB_FILE):
        try:
            with open(DB_FILE, "r", encoding="utf-8") as f:
                database = json.load(f)
                existing_urls = {item["lk21_url"] for item in database}
        except Exception:
            database = []

    url_queue = queue.Queue()
    skipped = 0
    for idx, url in enumerate(urls, 1):
        if url in existing_urls:
            skipped += 1
        else:
            url_queue.put((idx, url))

    print("="*60)
    print(f"[*] Total URL di daftar       : {total_to_process}")
    print(f"[*] Sudah diekstrak (Skip)    : {skipped}")
    print(f"[*] Sisa antrean diekstrak    : {url_queue.qsize()}")
    print(f"[*] Mode Kecepatan Stabil     : {MAX_WORKERS} Workers Paralel")
    print("="*60)

    if url_queue.empty():
        print("[*] Semua URL sudah ada di dalam database!")
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
        print("\n[!] Dihentikan pengguna (Ctrl+C). Menyimpan seluruh data terakhir...")
    finally:
        with db_lock:
            save_db_safely()
        print(f"\n[*] Selesai! Total {len(database)} film tersimpan aman di: {DB_FILE}")

if __name__ == "__main__":
    build_database()