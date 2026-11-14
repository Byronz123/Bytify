# ==========================================
# Bytify\backend\scraper\probe_servers.py
# ==========================================
"""
Mengukur penyedia embed berkunci TMDB id mana yang BENAR-BENAR menjawab.

**Kenapa alat ini ada, dan kenapa bukan scraper.** Keluaran scraper cinevo
memasang keempat penyedia (vidcore, vidsrc, autoembed, vidlink) ke SELURUH
50.072 entrinya — terbukti dari `cinevo_providers_report.json` yang mencatat
50072 untuk tiap penyedia. Artinya daftar itu dibangun dari template, bukan
hasil panen: tidak satu pun diverifikasi. URL-nya sendiri deterministik dari
TMDB id, jadi mengikis situsnya untuk mendapatkan daftar itu tidak menambah
apa pun — aplikasi bisa menurunkannya sendiri (lihat ServerTambahan.kt).

Yang TIDAK diketahui, dan itulah yang diukur di sini: dari keempat penyedia,
berapa yang sungguh punya sumber untuk judul yang ada di katalog kita. Angka
itu menentukan URUTAN penelusuran di pemutar, dan urutan menentukan berapa lama
pengguna menunggu sebelum filmnya berputar.

**Batas pengukuran ini, dan harus dibaca apa adanya.** Pemeriksaan dari sisi
server TIDAK bisa membuktikan videonya berputar: aliran sebenarnya diresolusi
JavaScript di dalam iframe, setelah halaman dimuat. Jadi yang diukur di sini
adalah syarat PERLU, bukan syarat cukup — "penyedia ini menerima id tersebut
dan menyajikan kerangka pemutar", bukan "film ini bisa ditonton". Pembuktian
pemutaran hanya bisa dari perangkat sungguhan.

Pemakaian:
    python probe_servers.py                 # 60 judul contoh
    python probe_servers.py --jumlah 200    # contoh lebih besar
    python probe_servers.py --tipe movie    # hanya film
"""
import argparse
import json
import os
import random
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
WATCH = os.path.join(DIR_DATA, "watch_catalog.json")

#: Harus SAMA dengan RUTE di app/.../data/movie/ServerTambahan.kt. Kalau salah
#: satu berubah, yang lain ikut — kalau tidak, yang diukur bukan yang dipakai.
PENYEDIA = {
    "vidsrc": (
        "https://vidsrc.cc/v2/embed/movie/{id}",
        "https://vidsrc.cc/v2/embed/tv/{id}/{musim}/{episode}",
    ),
    "vidcore": (
        "https://vidcore.io/movie/{id}",
        "https://vidcore.io/tv/{id}/{musim}/{episode}",
    ),
    "autoembed": (
        "https://player.autoembed.cc/embed/movie/{id}",
        "https://player.autoembed.cc/embed/tv/{id}/{musim}/{episode}",
    ),
    "vidlink": (
        "https://vidlink.pro/movie/{id}",
        "https://vidlink.pro/tv/{id}/{musim}/{episode}",
    ),
}

#: User-Agent yang sama dengan EMBED_USER_AGENT di pemutar. Penyedia embed
#: sering menyajikan halaman berbeda untuk klien yang bukan browser.
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

TANPA_SUMBER = re.compile(
    r"no server could play|no sources? found|no stream found|video not found|"
    r"media not found|title not available|not found|404",
    re.I,
)
PENANDA_PEMUTAR = re.compile(
    r"<video|jwplayer|hls\.js|hlsjs|\.m3u8|playerjs|videojs|video-js|"
    r"sources\s*[:=]|data-hash|plyr",
    re.I,
)

BATAS_BAIT = 240_000


def domain(url):
    m = re.match(r"https?://([^/]+)", url or "")
    if not m:
        return None
    return ".".join(m.group(1).lower().split(".")[-2:])


def periksa(url):
    """
    Mengembalikan (status, keterangan).

    status: "pemutar" | "tanpa-sumber" | "dialihkan" | "galat" | "tak-jelas"
    """
    minta = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9,id;q=0.8",
        # Referer alamatnya sendiri: itu yang dipakai pemutar untuk embed
        # pihak ketiga (lihat PilihanServer di MoviePlayerScreen.kt).
        "Referer": url,
    })
    try:
        with urllib.request.urlopen(minta, timeout=20) as balasan:
            akhir = balasan.geturl()
            if domain(akhir) != domain(url):
                return "dialihkan", f"-> {domain(akhir)}"
            badan = balasan.read(BATAS_BAIT).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return "galat", f"HTTP {e.code}"
    except Exception as e:  # timeout, DNS, TLS, reset
        return "galat", type(e).__name__

    if PENANDA_PEMUTAR.search(badan):
        return "pemutar", f"{len(badan)} bait"
    if TANPA_SUMBER.search(badan):
        return "tanpa-sumber", f"{len(badan)} bait"
    return "tak-jelas", f"{len(badan)} bait"


def ambil_contoh(jumlah, tipe_diminta):
    """Pasangan (tmdbId, tipe) nyata dari alias yang sudah dimiliki Bytify."""
    if not os.path.exists(WATCH):
        print(f"Tidak ada {WATCH}. Jalankan build_watch_catalog.py dulu.")
        return []
    with open(WATCH, encoding="utf-8") as f:
        alias = (json.load(f).get("aliases") or {})
    pasangan = sorted({
        (int(v["tmdbId"]), "tv" if v.get("type") == "tv" else "movie")
        for v in alias.values() if v.get("tmdbId")
    })
    if tipe_diminta != "semua":
        pasangan = [p for p in pasangan if p[1] == tipe_diminta]
    if not pasangan:
        return []
    # Benih tetap supaya dua jalan pengukuran bisa dibandingkan.
    random.Random(20261115).shuffle(pasangan)
    return pasangan[:jumlah]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--jumlah", type=int, default=60)
    p.add_argument("--tipe", choices=["semua", "movie", "tv"], default="semua")
    p.add_argument("--pekerja", type=int, default=8)
    arg = p.parse_args()

    contoh = ambil_contoh(arg.jumlah, arg.tipe)
    if not contoh:
        return 1
    print(f"Memeriksa {len(contoh)} judul x {len(PENYEDIA)} penyedia "
          f"= {len(contoh) * len(PENYEDIA)} permintaan\n")

    tugas = []
    for tmdb_id, tipe in contoh:
        for nama, (pola_film, pola_serial) in PENYEDIA.items():
            url = (pola_serial.format(id=tmdb_id, musim=1, episode=1)
                   if tipe == "tv" else pola_film.format(id=tmdb_id))
            tugas.append((nama, tipe, tmdb_id, url))

    hasil = {}
    with ThreadPoolExecutor(max_workers=arg.pekerja) as kolam:
        for (nama, tipe, tmdb_id, url), (status, ket) in zip(
            tugas, kolam.map(lambda t: periksa(t[3]), tugas)
        ):
            hasil.setdefault(nama, []).append((status, tipe, tmdb_id, ket))

    print(f"{'penyedia':<12}{'pemutar':>9}{'tanpa-sumber':>14}"
          f"{'dialihkan':>11}{'galat':>8}{'tak-jelas':>11}")
    print("-" * 65)
    peringkat = []
    for nama in PENYEDIA:
        baris = hasil.get(nama, [])
        hitung = {s: sum(1 for x in baris if x[0] == s) for s in
                  ("pemutar", "tanpa-sumber", "dialihkan", "galat", "tak-jelas")}
        peringkat.append((hitung["pemutar"], nama))
        print(f"{nama:<12}{hitung['pemutar']:>9}{hitung['tanpa-sumber']:>14}"
              f"{hitung['dialihkan']:>11}{hitung['galat']:>8}{hitung['tak-jelas']:>11}")

    peringkat.sort(reverse=True)
    print("\nUrutan yang disarankan untuk RUTE di ServerTambahan.kt:")
    for n, (skor, nama) in enumerate(peringkat, 1):
        print(f"  {n}. {nama:<12} {skor}/{len(contoh)} menyajikan kerangka pemutar")
    print("\nCATATAN: ini syarat PERLU, bukan bukti pemutaran. Aliran sebenarnya "
          "diresolusi JavaScript di dalam iframe; hanya perangkat sungguhan yang "
          "bisa membuktikan filmnya berputar.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
