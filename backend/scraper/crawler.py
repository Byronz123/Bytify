 # ==========================================
# 1. Bytify\backend\scraper\crawler.py (10 Workers Paralel)
# ==========================================
import os
import time
import re
import queue
import threading
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup
from ultimate_scraper import init_stealth_driver, wait_for_cloudflare

# Lock untuk menulis ke file teks secara bersamaan (Thread-Safe)
file_lock = threading.Lock()

def worker_crawl_task(worker_id, url_queue, base_domain, base_url, collected_links):
    print(f"[*] Crawler Worker-{worker_id} mulai berjalan...")
    try:
        driver = init_stealth_driver()
    except Exception as e:
        print(f"[!] Crawler Worker-{worker_id} gagal inisialisasi browser: {e}")
        return

    while not url_queue.empty():
        try:
            target_page = url_queue.get_nowait()
        except queue.Empty:
            break

        print(f"[Crawler-Worker-{worker_id}] Memindai: {target_page}")
        try:
            driver.get(target_page)
            wait_for_cloudflare(driver, timeout=8)
            
            try:
                driver.execute_script("window.scrollTo(0, 400);")
                time.sleep(0.5)
            except Exception:
                pass

            soup = BeautifulSoup(driver.page_source, 'html.parser')
            page_links = set()
            
            for a in soup.find_all('a', href=True):
                full_url = urljoin(base_url, a['href'])
                parsed = urlparse(full_url)
                
                if parsed.netloc == base_domain:
                    path = parsed.path.lower()
                    excluded = ['/page/', '/genre/', '/country/', '/year/', '/tag/', '/category/', '/artist/', '/director/', '/search/', '/populer', '/rating', '/dmca', '/faq']
                    
                    if len(path.strip('/')) > 3 and not any(ex in path for ex in excluded):
                        if re.search(r'-\d{4}/?$', path) or a.find_parent(['article', 'div'], class_=re.compile(r'item|movie|post|grid|card|thumbnail', re.I)):
                            page_links.add(full_url.rstrip('/') + '/')

            if page_links:
                with file_lock:
                    added_count = 0
                    for link in page_links:
                        if link not in collected_links:
                            collected_links.add(link)
                            added_count += 1
                    if added_count > 0:
                        print(f"[Crawler-Worker-{worker_id}] +{added_count} link baru dari {target_page} (Total: {len(collected_links)})")
                    
        except Exception as e:
            print(f"[Crawler-Worker-{worker_id}] Error pada halaman {target_page}: {e}")
        finally:
            url_queue.task_done()

    try:
        driver.quit()
    except Exception:
        pass
    print(f"[*] Crawler Worker-{worker_id} selesai.")

def crawl_massive_catalog_parallel(base_url, target_limit=2000):
    output_file = os.path.join(os.path.dirname(__file__), "movie_links.txt")
    
    collected_links = set()
    if os.path.exists(output_file):
        with open(output_file, "r", encoding="utf-8") as f:
            collected_links = {line.strip() for line in f if line.strip()}
            
    print(f"[*] Memuat data lama: {len(collected_links)} URL sudah tersimpan di file.")
    base_domain = urlparse(base_url).netloc
    
    crawl_paths = [
        "", "/populer", "/rating", 
        "/year/2026", "/year/2025", "/year/2024", "/year/2023", "/year/2022",
        "/genre/action", "/genre/horror", "/genre/drama", "/genre/sci-fi", "/genre/anime",
        "/genre/romance", "/genre/thriller", "/genre/comedy", "/genre/adventure"
    ]
    
    url_queue = queue.Queue()
    for sub_path in crawl_paths:
        for page in range(1, 51):  # 50 halaman per kategori
            prefix = f"{base_url.rstrip('/')}{sub_path}"
            target_page = f"{prefix}/page/{page}/" if page > 1 else f"{prefix}/"
            url_queue.put(target_page)

    max_workers = 10
    print(f"[*] Total target halaman katalog: {url_queue.qsize()} halaman dengan {max_workers} Workers Paralel.")

    threads = []
    for i in range(1, max_workers + 1):
        t = threading.Thread(target=worker_crawl_task, args=(i, url_queue, base_domain, base_url, collected_links), daemon=True)
        t.start()
        threads.append(t)

    try:
        for t in threads:
            while t.is_alive():
                t.join(timeout=1.0)
    except KeyboardInterrupt:
        print("\n[!] Dihentikan oleh pengguna. Menyimpan hasil sementara...")
    finally:
        with file_lock:
            with open(output_file, "w", encoding="utf-8") as f:
                for link in sorted(collected_links):
                    f.write(link + "\n")
        print(f"\n[*] Selesai! Total terkumpul {len(collected_links)} URL unik tersimpan di {output_file}")

if __name__ == "__main__":
    domain = input("Masukkan domain utama (contoh: https://d21.team/): ").strip()
    if not domain.startswith("http"):
        domain = "https://" + domain
        
    target_input = input("Masukkan target jumlah URL (default 2000): ").strip()
    target_limit = int(target_input) if target_input.isdigit() else 2000
    
    crawl_massive_catalog_parallel(domain, target_limit)