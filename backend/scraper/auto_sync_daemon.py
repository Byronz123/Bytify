# ==========================================
# Bytify\backend\scraper\auto_sync_daemon.py
# ==========================================
import os
import subprocess
import time
import traceback
from pathlib import Path
from crawler import crawl_massive_catalog_parallel
from build_database import build_database
from tv_garden_scraper import build_live_tv_database
from series_scraper import segarkan as segarkan_serial
from build_series_catalog import main as bangun_katalog_serial

# Domain utama disetel otomatis berdasarkan movie_links.txt Anda
DEFAULT_MOVIE_DOMAIN = os.environ.get("LK21_SCRAPE_DOMAIN", "https://tv12.lk21official.cc/")
SYNC_INTERVAL_HOURS = 3  # Otomatis cek film & TV baru setiap 3 jam

# Akar repo: backend/scraper -> backend -> akar
REPO_ROOT = Path(__file__).resolve().parents[2]

# HANYA berkas inilah yang boleh ikut ter-commit otomatis. Sengaja disebut satu
# per satu, bukan "git add .", supaya pekerjaan yang sedang Anda edit tidak
# pernah ikut terdorong tanpa sengaja.
BERKAS_TERBIT = [
    "backend/data/catalog.json",
    "backend/data/series_catalog.json",
    "backend/data/live_tv_database.json",
]


def _git(*args, timeout=300):
    return subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, timeout=timeout
    )


def terbitkan_katalog():
    """
    Dorong katalog yang baru dibangun ke GitHub, yang lalu memicu workflow
    deploy ke Cloudflare Pages. Inilah mata rantai yang dulu putus: katalog
    diperbarui di komputer tetapi tidak pernah sampai ke pengguna.

    Tidak melakukan apa-apa bila katalog tidak berubah, jadi tidak ada commit
    kosong yang mengotori riwayat saat tidak ada film baru.
    """
    cek = _git("status", "--porcelain", "--", *BERKAS_TERBIT)
    if cek.returncode != 0:
        print(f"[!] Gagal memeriksa status git: {cek.stderr.strip()}")
        return
    if not cek.stdout.strip():
        print("[=] Katalog tidak berubah — tidak ada yang perlu diterbitkan.")
        return

    tambah = _git("add", "--", *BERKAS_TERBIT)
    if tambah.returncode != 0:
        print(f"[!] Gagal menyiapkan berkas: {tambah.stderr.strip()}")
        return

    pesan = f"data: refresh catalog from auto-sync {time.strftime('%Y-%m-%d %H:%M')}"
    commit = _git("commit", "-m", pesan)
    if commit.returncode != 0:
        print(f"[!] Gagal membuat commit: {commit.stdout.strip() or commit.stderr.strip()}")
        return

    push = _git("push")
    if push.returncode != 0:
        # Paling sering karena remote sudah bergerak. Sengaja TIDAK rebase
        # otomatis supaya pekerjaan Anda tidak diubah diam-diam.
        print(f"[!] Commit tersimpan tetapi push gagal: {push.stderr.strip()}")
        print("    Jalankan 'git pull --rebase' lalu 'git push' saat sempat.")
        return

    print("[+] Katalog diterbitkan ke GitHub — deploy ke Cloudflare akan berjalan sendiri.")

def run_full_sync_cycle():
    print("\n" + "=" * 65)
    print(f"[*] [{time.strftime('%Y-%m-%d %H:%M:%S')}] MEMULAI SIKLUS AUTO-SYNC BYTIFY")
    print("=" * 65)

    # 1. Auto-Crawl Film Baru
    try:
        print("\n[TAHAP 1/5] Memindai Katalog Film Terbaru...")
        crawl_massive_catalog_parallel(DEFAULT_MOVIE_DOMAIN)
    except Exception as e:
        print(f"[!] Error pada Tahap 1 (Crawler): {e}")
        traceback.print_exc()

    # 2. Auto-Build Database Film Baru (Hanya memproses URL yang belum ada di JSON)
    try:
        print("\n[TAHAP 2/5] Mengekstrak Metadata, Poster, ND_URL & Stream Film Baru...")
        build_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 2 (Build Database): {e}")
        traceback.print_exc()

    # 3. Auto-Scrape Saluran Live TV dari TV Garden
    try:
        print("\n[TAHAP 3/5] Memperbarui Saluran Live TV dari TV Garden...")
        build_live_tv_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 3 (TV Garden Scraper): {e}")
        traceback.print_exc()

    # 4. Serial: hanya judul baru dan yang masih "Ongoing" yang dipanen ulang.
    #    Serial "Complete" tidak pernah bertambah episodenya, jadi memanennya
    #    tiap tiga jam hanya membuang puluhan ribu permintaan.
    try:
        print("\n[TAHAP 4/5] Menyegarkan serial baru & yang masih tayang...")
        segarkan_serial()
        bangun_katalog_serial()
    except Exception as e:
        print(f"[!] Error pada Tahap 4 (Serial): {e}")
        traceback.print_exc()

    # 5. Terbitkan: tanpa tahap ini katalog hanya berubah di komputer dan
    #    tidak pernah sampai ke pengguna.
    try:
        print("\n[TAHAP 5/5] Menerbitkan katalog ke GitHub...")
        terbitkan_katalog()
    except Exception as e:
        print(f"[!] Error pada Tahap 5 (Terbit): {e}")
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