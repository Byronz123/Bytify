# ==========================================
# Bytify\backend\scraper\series_scraper.py
# ==========================================
"""
Pemanen serial LK21.

Serial TIDAK ada di domain film. `crawler.py` memang melewati /series,
/nontondrama, dan /anime, dan itu benar: di tv12.lk21official.cc ketiganya cuma
halaman antara yang mengalihkan ke situs lain. Katalog serial yang sesungguhnya
ada di **tv.nontondrama.vip**, dengan struktur kategori yang sama persis dengan
situs film (genre / country / year), jadi aturan penjelajahan yang sudah terbukti
di `crawler.py` dipakai ulang di sini.

Tiga hal yang membuat pemanenan ini murah:

1. **Tanpa Selenium.** Halaman daftar, halaman serial, dan halaman episode
   semuanya dirender di server. Iframe pemutar dan daftar server alternatif
   sudah ada di HTML mentah.
2. **Episode ditelusuri sampai 404.** Berbeda dari halaman daftar film — yang
   menyajikan halaman cadangan berulang alih-alih 404 — URL episode di luar
   jangkauan di situs ini MENGEMBALIKAN 404 betulan (terbukti: episode 11 dari
   serial 10 episode -> 404, panjang badan 26 byte). Jadi jumlah episode tidak
   perlu ditebak dari kartu daftar.
3. **Satu permintaan per episode, bukan per server.** Ketiga server (p2p, cast,
   hydrax) muncul bersama di halaman episode yang sama.
"""
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DOMAIN_SERIAL = os.environ.get("LK21_SERIES_DOMAIN", "https://tv.nontondrama.vip/")

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "id-ID,id;q=0.9,en;q=0.8",
}

# Ruas yang bukan halaman serial.
EXCLUDED = (
    "/page/", "/genre/", "/country/", "/year/", "/tag/", "/category/",
    "/artist/", "/director/", "/search/", "/populer", "/rating", "/dmca",
    "/faq", "/most-commented", "/request", "/cara-install-vpn",
    "/privacy-policy", "/series/", "/latest-series", "/top-movie-today",
)

# Halaman episode punya pola slug sendiri; saat menjelajah daftar, halaman
# episode TIDAK boleh ikut dicatat sebagai serial.
POLA_EPISODE = re.compile(r"-season-\d+-episode-\d+-", re.I)

BATAS_HALAMAN = 400
JEDA_ANTAR_HALAMAN = 0.25

TIDAK_ADA = object()

BERKAS_TAUTAN = os.path.join(os.path.dirname(__file__), "series_links.txt")
BERKAS_HASIL = os.path.join(
    os.path.dirname(__file__), "..", "data", "series_database.json"
)

kunci_simpan = threading.Lock()


def _ambil(sesi, url, percobaan=4):
    """
    Unduh satu halaman.

    Membedakan 404 (memang tidak ada) dari gangguan sementara, sama seperti
    `crawler.py`: menyamakan keduanya membuat satu kedip jaringan memotong
    seluruh kategori diam-diam.
    """
    for ke in range(percobaan):
        try:
            r = sesi.get(url, headers=HEADERS, timeout=30)
            if r.status_code == 404:
                return TIDAK_ADA
            r.raise_for_status()
            return r.text
        except Exception:
            if ke == percobaan - 1:
                return None
            time.sleep(1.5 * (ke + 1))
    return None


def _tautan_serial(html, base_url, base_domain):
    """Ambil tautan halaman serial dari sebuah halaman daftar."""
    soup = BeautifulSoup(html, "html.parser")
    hasil = set()
    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        if not href or href.startswith(("#", "javascript:", "mailto:")):
            continue
        penuh = urljoin(base_url, href)
        pecah = urlparse(penuh)
        if pecah.netloc != base_domain or pecah.query:
            continue
        jalur = pecah.path
        if POLA_EPISODE.search(jalur):
            continue
        if any(ex in jalur for ex in EXCLUDED):
            continue
        if len(jalur.strip("/")) <= 3 or "/" in jalur.strip("/"):
            continue
        hasil.add(penuh.split("?")[0].rstrip("/"))
    return hasil


def temukan_kategori(sesi, base_url, base_domain):
    """Baca halaman depan untuk menemukan seluruh genre, negara, dan tahun."""
    jalur = {"", "/populer", "/series/ongoing", "/series/complete",
             "/series/asian", "/series/west"}
    html = _ambil(sesi, base_url)
    if html and html is not TIDAK_ADA:
        soup = BeautifulSoup(html, "html.parser")
        for a in soup.find_all("a", href=True):
            p = urlparse(urljoin(base_url, a["href"])).path
            if p.startswith(("/genre/", "/country/", "/year/")):
                jalur.add(p.rstrip("/"))
    return sorted(jalur)


def _jelajahi_kategori(base_url, base_domain, jalur, terkumpul):
    """
    Telusuri satu kategori sampai halaman berhenti membawa tautan baru.

    Aturan berhentinya sengaja "tidak ada tautan baru", bukan 404: halaman
    daftar di luar jangkauan menyajikan isi berulang, bukan galat.
    """
    sesi = requests.Session()
    milik_kategori = set()
    halaman = 1
    kosong_beruntun = 0
    while halaman <= BATAS_HALAMAN:
        url = f"{base_url.rstrip('/')}{jalur}" if halaman == 1 else \
            f"{base_url.rstrip('/')}{jalur}/page/{halaman}"
        html = _ambil(sesi, url)
        if html is TIDAK_ADA:
            break
        if html is None:
            kosong_beruntun += 1
            if kosong_beruntun >= 3:
                break
            halaman += 1
            continue
        kosong_beruntun = 0
        baru = _tautan_serial(html, url, base_domain) - milik_kategori
        if not baru:
            break
        milik_kategori |= baru
        with kunci_simpan:
            terkumpul |= baru
        halaman += 1
        time.sleep(JEDA_ANTAR_HALAMAN)
    return jalur, len(milik_kategori), halaman - 1


def jelajahi_daftar_serial(base_url=DOMAIN_SERIAL, pekerja=8):
    """Sapu seluruh kategori, kembalikan daftar URL serial yang unik."""
    base_domain = urlparse(base_url).netloc
    sesi = requests.Session()
    kategori = temukan_kategori(sesi, base_url, base_domain)
    print(f"[serial] {len(kategori)} kategori ditemukan")
    terkumpul = set()
    with ThreadPoolExecutor(max_workers=pekerja) as kolam:
        tugas = [
            kolam.submit(_jelajahi_kategori, base_url, base_domain, j, terkumpul)
            for j in kategori
        ]
        for selesai in as_completed(tugas):
            jalur, jumlah, halaman = selesai.result()
            print(f"  {jalur or '/'}: {jumlah} serial dari {halaman} halaman "
                  f"(total {len(terkumpul)})")
    urut = sorted(terkumpul)
    with open(BERKAS_TAUTAN, "w", encoding="utf-8") as f:
        f.write("\n".join(urut))
    print(f"[serial] {len(urut)} URL serial -> {BERKAS_TAUTAN}")
    return urut


# --------------------------------------------------------------------------
# Pemanenan satu serial
# --------------------------------------------------------------------------

def _teks(simpul):
    return simpul.get_text(" ", strip=True) if simpul else ""


def _angka(teks):
    m = re.search(r"\d+(?:\.\d+)?", teks or "")
    return float(m.group()) if m else None


def _server_dari_halaman(html):
    """Kumpulkan iframe pemutar dan server alternatif dari HTML episode."""
    soup = BeautifulSoup(html, "html.parser")
    utama = None
    for ifr in soup.find_all("iframe"):
        src = (ifr.get("src") or ifr.get("data-src") or "").strip()
        if src.startswith("http"):
            utama = src
            break
    server = []
    for a in soup.find_all(attrs={"data-server": True}):
        nama = (a.get("data-server") or "").strip()
        url = (a.get("data-src") or a.get("data-url") or a.get("href") or "").strip()
        if url.startswith("http"):
            server.append({"server": nama or "server", "url": url})
    if not server:
        for url in dict.fromkeys(re.findall(r'https://videonode\.de/iframe3/[^"\'\s<>]+', html)):
            nama = url.rsplit("/", 2)[-2] if "/" in url else "server"
            server.append({"server": nama, "url": url})
    if utama is None and server:
        utama = server[0]["url"]
    return utama, server


def panen_satu_serial(url, sesi=None, batas_episode=200):
    """
    Ambil metadata satu serial beserta seluruh episodenya.

    Episode ditelusuri 1..N sampai server menjawab 404; itu batas yang jujur,
    bukan tebakan dari kartu daftar yang bisa basi untuk serial ongoing.
    """
    sesi = sesi or requests.Session()
    html = _ambil(sesi, url)
    if not html or html is TIDAK_ADA:
        return None
    soup = BeautifulSoup(html, "html.parser")

    judul_mentah = _teks(soup.find("h1")) or (soup.title.string if soup.title else "")
    judul = re.sub(r"^(Lk21\s+)?Nonton\s+", "", judul_mentah or "", flags=re.I)
    judul = re.sub(r"\s*(Series\s+)?Sub\s+Indo.*$", "", judul, flags=re.I).strip()
    if not judul:
        return None

    siput = urlparse(url).path.strip("/")
    tahun = None
    m = re.search(r"-(\d{4})$", siput)
    if m:
        tahun = int(m.group(1))

    # Poster diambil dari og:image, bukan <img> pertama: halaman ini juga
    # memuat belasan poster rekomendasi, dan versi pertama pemanen ini sempat
    # mengambil salah satunya.
    og = soup.find("meta", attrs={"property": "og:image"})
    poster = (og.get("content") or "").strip() if og else None
    if not poster:
        gambar = soup.select_one(".movie-info img, .poster img")
        poster = (gambar.get("src") or gambar.get("data-src") or "").strip() if gambar else None

    sinopsis = _teks(soup.select_one(".synopsis"))

    # RUANG LINGKUP SELECTOR DIBATASI. `a[href^="/genre/"]` global mengambil
    # menu navigasi situs, bukan genre serialnya: versi pertama pemanen ini
    # memberi "Action, Adventure, Animation" untuk SETIAP serial. Genre dan
    # negara yang benar hanya ada di dalam .tag-list milik blok info.
    daftar_tag = soup.select_one(".tag-list")
    genre, negara = [], []
    if daftar_tag:
        genre = [_teks(a) for a in daftar_tag.select('a[href^="/genre/"]') if _teks(a)]
        negara = [_teks(a) for a in daftar_tag.select('a[href^="/country/"]') if _teks(a)]

    # .rating-score adalah angkanya sendiri; .rating-box ikut membawa jumlah
    # pemilih dan teks ajakan, sehingga angka pertama yang terbaca salah.
    nilai = _angka(_teks(soup.select_one(".rating-score, .rating-number")))

    # "Ongoing" / "Complete" ada di .info-tag bersama tanggal tayang.
    info_tag = _teks(soup.select_one(".info-tag"))
    status = None
    for kata in ("Ongoing", "Complete"):
        if kata.lower() in info_tag.lower():
            status = kata
            break

    # "Play Terbaru" menunjuk episode terakhir yang sudah terbit; dari situ
    # pola URL episode dan nomor musimnya terbaca tanpa menebak.
    pola = None
    musim = 1
    for a in soup.find_all("a", href=True):
        m = POLA_EPISODE.search(a["href"])
        if m:
            pola = a["href"]
            ms = re.search(r"-season-(\d+)-episode-(\d+)-", a["href"], re.I)
            if ms:
                musim = int(ms.group(1))
            break
    if not pola:
        return None
    awalan, akhiran = re.split(r"episode-\d+-", pola, maxsplit=1)

    episodes = []
    nomor = 1
    while nomor <= batas_episode:
        ep_url = urljoin(url, f"{awalan}episode-{nomor}-{akhiran}")
        ep_html = _ambil(sesi, ep_url, percobaan=3)
        if ep_html is TIDAK_ADA:
            break
        if ep_html is None:
            # Gangguan sementara bukan tanda habis: lewati, jangan berhenti.
            nomor += 1
            continue
        video, server = _server_dari_halaman(ep_html)
        if not video:
            nomor += 1
            continue
        episodes.append({
            "id": f"{siput}-s{musim}e{nomor}",
            "title": f"Episode {nomor}",
            "season": musim,
            "episode": nomor,
            "videoUrl": video,
            "servers": server,
        })
        nomor += 1

    if not episodes:
        return None

    return {
        "id": siput,
        "type": "series",
        "title": judul,
        "overview": sinopsis,
        "posterUrl": poster,
        "releaseYear": tahun,
        "genres": [g for g in dict.fromkeys(genre)],
        "country": negara[0] if negara else None,
        "status": status,
        "rating": nilai,
        "sourceUrl": url,
        "episodes": episodes,
    }


def panen_semua(urls, pekerja=20, berkas=BERKAS_HASIL):
    """Panen daftar serial secara paralel, simpan berkala supaya tahan putus."""
    hasil = []
    sudah = set()
    if os.path.exists(berkas):
        try:
            with open(berkas, encoding="utf-8") as f:
                hasil = json.load(f)
            sudah = {s.get("sourceUrl") for s in hasil}
            print(f"[serial] melanjutkan: {len(hasil)} serial sudah ada")
        except Exception:
            hasil, sudah = [], set()

    sisa = [u for u in urls if u not in sudah]
    print(f"[serial] {len(sisa)} serial akan dipanen")

    def simpan():
        sementara = berkas + f".{threading.get_ident()}.tmp"
        with open(sementara, "w", encoding="utf-8") as f:
            json.dump(hasil, f, ensure_ascii=False)
        os.replace(sementara, berkas)

    selesai = 0
    with ThreadPoolExecutor(max_workers=pekerja) as kolam:
        tugas = {kolam.submit(panen_satu_serial, u): u for u in sisa}
        for t in as_completed(tugas):
            selesai += 1
            try:
                satu = t.result()
            except Exception as e:
                print(f"  gagal {tugas[t]}: {e}")
                satu = None
            if satu:
                with kunci_simpan:
                    hasil.append(satu)
                    if len(hasil) % 25 == 0:
                        simpan()
            if selesai % 25 == 0:
                print(f"  {selesai}/{len(sisa)} | terkumpul {len(hasil)} serial")
    with kunci_simpan:
        simpan()
    jumlah_ep = sum(len(s["episodes"]) for s in hasil)
    print(f"[serial] selesai: {len(hasil)} serial, {jumlah_ep} episode -> {berkas}")
    return hasil


if __name__ == "__main__":
    perintah = sys.argv[1] if len(sys.argv) > 1 else "semua"
    if perintah in ("daftar", "semua"):
        tautan = jelajahi_daftar_serial()
    else:
        with open(BERKAS_TAUTAN, encoding="utf-8") as f:
            tautan = [b.strip() for b in f if b.strip()]
    if perintah in ("panen", "semua"):
        panen_semua(tautan)
