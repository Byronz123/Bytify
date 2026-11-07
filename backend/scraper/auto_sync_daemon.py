# ==========================================
# Bytify\backend\scraper\auto_sync_daemon.py
# ==========================================
import os
import time
import traceback
from crawler import crawl_massive_catalog_parallel
from build_database import build_database
from tv_garden_scraper import build_live_tv_database

# Domain utama disetel otomatis berdasarkan movie_links.txt Anda
DEFAULT_MOVIE_DOMAIN = os.environ.get("LK21_SCRAPE_DOMAIN", "https://tv12.lk21official.cc/")
SYNC_INTERVAL_HOURS = 3  # Otomatis cek film & TV baru setiap 3 jam

def run_full_sync_cycle():
    print("\n" + "=" * 65)
    print(f"[*] [{time.strftime('%Y-%m-%d %H:%M:%S')}] MEMULAI SIKLUS AUTO-SYNC BYTIFY")
    print("=" * 65)

    # 1. Auto-Crawl Film Baru
    try:
        print("\n[TAHAP 1/3] Memindai Katalog Film Terbaru...")
        crawl_massive_catalog_parallel(DEFAULT_MOVIE_DOMAIN, target_limit=3000)
    except Exception as e:
        print(f"[!] Error pada Tahap 1 (Crawler): {e}")
        traceback.print_exc()

    # 2. Auto-Build Database Film Baru (Hanya memproses URL yang belum ada di JSON)
    try:
        print("\n[TAHAP 2/3] Mengekstrak Metadata, Poster, ND_URL & Stream Film Baru...")
        build_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 2 (Build Database): {e}")
        traceback.print_exc()

    # 3. Auto-Scrape Saluran Live TV dari TV Garden
    try:
        print("\n[TAHAP 3/3] Memperbarui Saluran Live TV dari TV Garden...")
        build_live_tv_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 3 (TV Garden Scraper): {e}")
        traceback.print_exc()

    print("\n" + "=" * 65)
    print(f"[*] [{time.strftime('%Y-%m-%d %H:%M:%S')}] SIKLUS SELESAI! Data siap di APK.")
    print("=" * 65)

if __name__ == "__main__":
    print("[*] Daemon Auto-Sync Bytify Aktif (Tekan Ctrl+C untuk berhenti).")
    while True:
        run_full_sync_cycle()
        print(f"\n[*] Menunggu {SYNC_INTERVAL_HOURS} jam sebelum pemeriksaan film baru berikutnya...")
        time.sleep(SYNC_INTERVAL_HOURS * 3600)