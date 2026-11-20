# ==========================================
# Bytify\backend\scraper\auto_sync_daemon.py
# ==========================================
import os
import subprocess
import sys
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
    # Lapisan ketersediaan + alias TMDB. Tanpa berkas ini film LK21 yang baru
    # masuk tidak punya TMDB id, dan tanpa TMDB id `ServerTambahan.kt` tidak
    # bisa menurunkan satu pun dari 16 penyedia embed — judul barunya hanya
    # punya server LK21-nya sendiri.
    "backend/data/watch_catalog.json",
]

# Langkah jembatan TMDB, dijalankan berurutan. Masing-masing BISA DILANJUTKAN:
# `tmdb_match.py` hanya mengerjakan judul yang belum pernah diputuskan, dan
# `tmdb_watch.py` hanya id yang belum dipanen. Jadi siklus tiga jam berikutnya
# hanya menyentuh judul yang benar-benar baru, bukan mengulang 25 ribu film.
LANGKAH_TMDB = [
    ("mencocokkan film baru ke TMDB id", ["tmdb_match.py"]),
    ("mencocokkan serial baru ke TMDB id", ["tmdb_match.py", "--serial"]),
    ("memanen ketersediaan resmi", ["tmdb_watch.py"]),
    ("membangun watch_catalog.json", ["build_watch_catalog.py"]),
]

#: Batas waktu per langkah. Daemon tidak boleh tergantung pada satu permintaan
#: jaringan yang menggantung; langkah yang lewat batas dipotong dan siklus
#: berikutnya melanjutkannya dari tempatnya berhenti.
BATAS_LANGKAH_DETIK = 3600


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

def jembatani_tmdb():
    """
    Isi `watch_aliases` untuk judul yang baru masuk katalog.

    Dijalankan sebagai proses TERPISAH, bukan diimpor: tiap skrip punya
    kuncinya sendiri, memakai utas sendiri, dan membaca `.env`-nya sendiri.
    Kegagalan satu langkah tidak boleh menjatuhkan daemon, jadi hasilnya
    dilaporkan lalu siklus lanjut — langkah yang sama akan dicoba lagi tiga
    jam kemudian, melanjutkan dari tempatnya berhenti.
    """
    skrip_dir = Path(__file__).resolve().parent
    for nama, perintah in LANGKAH_TMDB:
        print(f"  - {nama}...")
        try:
            hasil = subprocess.run(
                [sys.executable, "-I", *perintah],
                cwd=skrip_dir,
                capture_output=True,
                text=True,
                timeout=BATAS_LANGKAH_DETIK,
            )
        except subprocess.TimeoutExpired:
            print(f"    [!] lewat batas {BATAS_LANGKAH_DETIK}s; dilanjutkan siklus berikutnya")
            continue
        baris = [b for b in (hasil.stdout or "").strip().splitlines() if b.strip()]
        for b in baris[-3:]:
            print("    " + b)
        if hasil.returncode != 0:
            galat = (hasil.stderr or "").strip().splitlines()
            print(f"    [!] gagal (kode {hasil.returncode}): {galat[-1] if galat else 'tanpa pesan'}")


def run_full_sync_cycle():
    print("\n" + "=" * 65)
    print(f"[*] [{time.strftime('%Y-%m-%d %H:%M:%S')}] MEMULAI SIKLUS AUTO-SYNC BYTIFY")
    print("=" * 65)

    # 1. Auto-Crawl Film Baru
    try:
        print("\n[TAHAP 1/6] Memindai Katalog Film Terbaru...")
        crawl_massive_catalog_parallel(DEFAULT_MOVIE_DOMAIN)
    except Exception as e:
        print(f"[!] Error pada Tahap 1 (Crawler): {e}")
        traceback.print_exc()

    # 2. Auto-Build Database Film Baru (Hanya memproses URL yang belum ada di JSON)
    try:
        print("\n[TAHAP 2/6] Mengekstrak Metadata, Poster, ND_URL & Stream Film Baru...")
        build_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 2 (Build Database): {e}")
        traceback.print_exc()

    # 3. Auto-Scrape Saluran Live TV dari TV Garden
    try:
        print("\n[TAHAP 3/6] Memperbarui Saluran Live TV dari TV Garden...")
        build_live_tv_database()
    except Exception as e:
        print(f"[!] Error pada Tahap 3 (TV Garden Scraper): {e}")
        traceback.print_exc()

    # 4. Serial: hanya judul baru dan yang masih "Ongoing" yang dipanen ulang.
    #    Serial "Complete" tidak pernah bertambah episodenya, jadi memanennya
    #    tiap tiga jam hanya membuang puluhan ribu permintaan.
    try:
        print("\n[TAHAP 4/6] Menyegarkan serial baru & yang masih tayang...")
        segarkan_serial()
        bangun_katalog_serial()
    except Exception as e:
        print(f"[!] Error pada Tahap 4 (Serial): {e}")
        traceback.print_exc()

    # 5. Jembatan TMDB: tanpa tahap ini film baru masuk katalog TANPA TMDB id,
    #    sehingga server turunan dan subtitle tidak bisa dibentuk untuknya.
    try:
        print("\n[TAHAP 5/6] Menjembatani judul baru ke TMDB id...")
        jembatani_tmdb()
    except Exception as e:
        print(f"[!] Error pada Tahap 5 (Jembatan TMDB): {e}")
        traceback.print_exc()

    # 6. Terbitkan: tanpa tahap ini katalog hanya berubah di komputer dan
    #    tidak pernah sampai ke pengguna.
    try:
        print("\n[TAHAP 6/6] Menerbitkan katalog ke GitHub...")
        terbitkan_katalog()
    except Exception as e:
        print(f"[!] Error pada Tahap 6 (Terbit): {e}")
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