# ==========================================
# Bytify\backend\scraper\tv_garden_scraper.py
# ==========================================
"""
Pembangun katalog Live TV.

Dulu berkas ini membuka Chrome lewat Selenium untuk mengikis tv.garden pada
delapan negara, dan menghasilkan 1.815 kanal. Sekarang katalog dibangun dari
API terbuka iptv-org, yang menyediakan daftar kanal, stream, dan logo dalam
tiga berkas JSON — tanpa browser sama sekali.

Alasan penggantian, berdasarkan pengukuran:
  - Cakupan: 8 negara -> 180 negara, 1.815 -> ~13.000 kanal.
  - Kecepatan: tiga permintaan HTTP, bukan puluhan sesi browser.
  - Keandalan: tidak bergantung pada tata letak halaman yang bisa berubah.

Dua penyaring sengaja diterapkan di sini, bukan di hilir:
  - Kanal NSFW dibuang.
  - Hanya stream HTTPS yang disimpan, karena backend (toLiveChannel di
    server.mjs) menolak non-HTTPS dan Android memblokir lalu lintas cleartext.
    Menyimpannya hanya akan menggelembungkan berkas dengan entri yang pasti
    dibuang.
"""
import json
import os
import urllib.request

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
LIVE_DB_FILE = os.path.join(DATA_DIR, "live_tv_database.json")
LIVE_M3U_FILE = os.path.join(DATA_DIR, "tv_garden_streams.m3u")

API = "https://iptv-org.github.io/api"
HEADERS = {"User-Agent": "Bytify-LiveTV/1.0"}
TIMEOUT = 120

channels_db = []


def _ambil_json(nama, percobaan=3):
    """Unduh satu berkas API iptv-org, dengan percobaan ulang."""
    url = f"{API}/{nama}"
    galat = None
    for ke in range(percobaan):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return json.loads(r.read().decode("utf-8", "ignore"))
        except Exception as e:
            galat = e
    raise RuntimeError(f"Gagal mengambil {nama}: {galat}")


def save_live_outputs():
    """Simpan hasil sebagai JSON dan M3U agar terbaca live.mjs dan APK."""
    os.makedirs(DATA_DIR, exist_ok=True)

    tmp_json = LIVE_DB_FILE + ".tmp"
    with open(tmp_json, "w", encoding="utf-8") as f:
        # Tanpa indentasi: dengan belasan ribu kanal, spasi saja memakan
        # megabyte pada berkas yang ikut diterbitkan.
        json.dump(channels_db, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp_json, LIVE_DB_FILE)

    tmp_m3u = LIVE_M3U_FILE + ".tmp"
    with open(tmp_m3u, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for ch in channels_db:
            stream_url = ch.get("stream_url") or ""
            if not stream_url:
                continue
            logo = ch.get("logo") or ""
            group = ch.get("category") or "General"
            country = ch.get("country") or "ID"
            name = ch.get("name") or "Unknown Channel"
            f.write(
                f'#EXTINF:-1 tvg-id="{ch["id"]}" tvg-logo="{logo}" '
                f'tvg-country="{country}" group-title="{group}",{name}\n'
            )
            f.write(f"{stream_url}\n")
    os.replace(tmp_m3u, LIVE_M3U_FILE)


def build_live_tv_database():
    """Bangun ulang katalog Live TV dari API iptv-org."""
    global channels_db
    os.makedirs(DATA_DIR, exist_ok=True)

    print("=" * 60)
    print("[*] Membangun katalog Live TV dari iptv-org...")
    print("=" * 60)

    streams = _ambil_json("streams.json")
    kanal = _ambil_json("channels.json")
    logos = _ambil_json("logos.json")
    print(f"[*] Terunduh: {len(streams)} stream, {len(kanal)} kanal, {len(logos)} logo.")

    peta_kanal = {c["id"]: c for c in kanal if c.get("id")}
    peta_logo = {}
    for l in logos:
        kid = l.get("channel")
        if kid and kid not in peta_logo and str(l.get("url", "")).startswith("https://"):
            peta_logo[kid] = l["url"]

    def mutu(s):
        """Angka kualitas untuk memilih stream terbaik per kanal (1080p > 720p)."""
        q = (s.get("quality") or "").lower().rstrip("p")
        try:
            return int(q)
        except ValueError:
            return 0

    # Satu entri per KANAL, bukan per stream. iptv-org menyediakan beberapa
    # cermin/kualitas untuk kanal yang sama, sedangkan aplikasi menyaringnya
    # dengan distinctBy(id) — mengirim semuanya hanya menggelembungkan berkas
    # dengan ribuan entri yang pasti dibuang (terukur: 13.006 terkirim, 8.681
    # terpakai).
    hasil = {}
    dibuang_nsfw = dibuang_http = dibuang_tanpa_meta = 0
    for s in streams:
        url = (s.get("url") or "").strip()
        kid = s.get("channel")
        if not url or not kid:
            dibuang_tanpa_meta += 1
            continue
        info = peta_kanal.get(kid)
        if not info:
            dibuang_tanpa_meta += 1
            continue
        if info.get("is_nsfw"):
            dibuang_nsfw += 1
            continue
        if not url.lower().startswith("https://"):
            dibuang_http += 1
            continue
        lama = hasil.get(kid)
        if lama is not None and lama["_mutu"] >= mutu(s):
            continue

        kategori = ";".join(info.get("categories") or []) or "General"
        hasil[kid] = {
            "_mutu": mutu(s),
            "id": kid,
            "name": s.get("title") or info.get("name") or kid,
            "country": (info.get("country") or "ID").upper(),
            "category": kategori.title(),
            "logo": peta_logo.get(kid, ""),
            "source": "iptv-org",
            "stream_url": url,
        }

    channels_db = sorted(
        ({k: v for k, v in c.items() if k != "_mutu"} for c in hasil.values()),
        key=lambda c: (c["country"], c["name"]),
    )
    save_live_outputs()

    negara = len({c["country"] for c in channels_db})
    print(f"[+] Katalog Live TV: {len(channels_db)} kanal dari {negara} negara.")
    print(
        f"    dibuang -> NSFW: {dibuang_nsfw}, bukan HTTPS: {dibuang_http}, "
        f"tanpa metadata: {dibuang_tanpa_meta}"
    )
    return len(channels_db)


if __name__ == "__main__":
    build_live_tv_database()
