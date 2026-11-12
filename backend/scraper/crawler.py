# ==========================================
# Bytify\backend\scraper\crawler.py
# ==========================================
"""
Penjelajah daftar film LK21.

Tujuannya menyapu katalog sampai habis, bukan sekadar mengambil sebagian.
Tiga hal yang membuat versi ini berbeda dari sebelumnya:

1. **Tanpa Selenium.** Halaman daftar LK21 dirender di server, jadi cukup HTTP
   biasa. Versi lama membuka 10 Chrome sungguhan untuk membaca HTML statis —
   ribuan halaman mustahil ditempuh dengan cara itu.
2. **Kategori ditemukan sendiri** dari halaman depan, bukan daftar tetap.
   Versi lama memakai 8 genre dan 5 tahun; situsnya ternyata punya 20 genre dan
   20 tahun (2007–2026), sehingga 27 kategori tidak pernah disentuh.
3. **Berhenti saat habis, bukan di nomor halaman tertentu.** Versi lama dipatok
   50 halaman per kategori. Situs ini juga TIDAK mengembalikan 404 di luar
   jangkauan — ia menyajikan halaman cadangan yang isinya berulang identik —
   jadi aturan berhentinya adalah "halaman ini tidak lagi membawa tautan baru
   bagi kategorinya".
"""
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "id-ID,id;q=0.9,en;q=0.8",
}

# Ruas yang bukan halaman film. Disaring supaya menu situs tidak ikut terkumpul.
EXCLUDED = [
    "/page/", "/genre/", "/country/", "/year/", "/tag/", "/category/",
    "/artist/", "/director/", "/search/", "/populer", "/rating", "/dmca", "/faq",
]

# Jalur yang persis sama dengan ini bukan halaman film melainkan indeks bagian
# (mis. etalase serial), jadi tidak boleh ikut terkumpul.
BUKAN_FILM = {"/nontondrama", "/anime", "/series"}

# Pagar pengaman supaya satu kategori yang aneh tidak membuat penjelajahan
# berputar tanpa akhir. Jauh di atas kedalaman nyata (~50 halaman).
BATAS_HALAMAN = 400

JEDA_ANTAR_HALAMAN = 0.25  # sopan terhadap server

file_lock = threading.Lock()


#: Dikembalikan [_ambil] saat server menyatakan halaman memang tidak ada.
TIDAK_ADA = object()


def _ambil(sesi, url, percobaan=4):
    """
    Unduh satu halaman.

    Membedakan dua kegagalan yang artinya berlawanan, dan ini penting:
    404 berarti kategori memang sudah habis, sedangkan timeout berarti kita
    belum tahu apa-apa. Menyamakan keduanya membuat satu gangguan jaringan
    sesaat memotong seluruh kategori diam-diam — terbukti membuat dua kali
    penjelajahan memberi jumlah film yang berbeda.

    Mengembalikan HTML, [TIDAK_ADA] bila 404, atau None bila gagal sementara.
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


def _tautan_film(html, base_url, base_domain):
    """Ambil tautan halaman film dari sebuah halaman daftar."""
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
        if jalur.rstrip("/") in BUKAN_FILM:
            continue
        if len(jalur.strip("/")) > 3 and not any(ex in jalur for ex in EXCLUDED):
            hasil.add(penuh.split("?")[0].rstrip("/") + "/")
    return hasil


def temukan_kategori(sesi, base_url, base_domain):
    """
    Baca halaman depan untuk menemukan seluruh genre, tahun, DAN negara.

    Negara sempat terlewat pada versi pertama perbaikan ini, dan uji
    menunjukkan tiga negara saja sudah menyumbang ratusan film yang tidak
    terjangkau lewat genre maupun tahun.
    Dengan begini kategori baru di situs otomatis ikut terjelajahi, tanpa perlu
    daftar tetap yang cepat basi.
    """
    jalur = {"", "/populer", "/rating"}
    html = _ambil(sesi, base_url)
    if html:
        soup = BeautifulSoup(html, "html.parser")
        for a in soup.find_all("a", href=True):
            p = urlparse(urljoin(base_url, a["href"])).path.rstrip("/")
            if p.startswith(("/genre/", "/year/", "/country/")):
                jalur.add(p)
    if len(jalur) <= 3:
        print("[!] Gagal membaca kategori dari halaman depan; memakai daftar cadangan.")
        jalur.update(f"/year/{t}" for t in range(2007, 2027))
        jalur.update(
            f"/country/{n}" for n in [
                "australia", "canada", "china", "france", "germany", "hong-kong",
                "india", "indonesia", "italy", "japan", "south-korea", "malaysia",
                "mexico", "philippines", "spain", "taiwan", "thailand",
                "united-kingdom", "united-states",
            ]
        )
        jalur.update(
            f"/genre/{g}" for g in [
                "action", "adventure", "animation", "biography", "comedy", "crime",
                "documentary", "drama", "family", "fantasy", "history", "horror",
                "musical", "mystery", "romance", "sci-fi", "sport", "thriller",
                "war", "western",
            ]
        )
    return sorted(jalur)


def sapu_kategori(sesi, base_url, base_domain, jalur, terkumpul):
    """
    Telusuri satu kategori halaman demi halaman sampai habis.

    Berhenti bila halaman tidak memuat tautan film sama sekali, atau bila
    isinya sama persis dengan halaman sebelumnya — tanda kita sudah melewati
    halaman terakhir dan server menyajikan halaman cadangan.
    """
    nama = jalur or "/(depan)"
    sebelumnya = None
    baru_total = 0
    gagal_beruntun = 0
    tuntas = False
    for halaman in range(1, BATAS_HALAMAN + 1):
        akar = f"{base_url.rstrip('/')}{jalur}"
        url = f"{akar}/page/{halaman}/" if halaman > 1 else f"{akar}/"
        html = _ambil(sesi, url)

        if html is None:
            # Gangguan sementara: JANGAN anggap kategori habis. Lewati halaman
            # ini dan lanjut, kecuali gangguannya beruntun.
            gagal_beruntun += 1
            if gagal_beruntun >= 3:
                print(f"[!] {nama}: 3 halaman beruntun gagal diambil, dihentikan BELUM TUNTAS.")
                break
            time.sleep(2.0)
            continue
        gagal_beruntun = 0

        if html is TIDAK_ADA:
            tuntas = True
            break
        tautan = _tautan_film(html, base_url, base_domain)
        if not tautan or tautan == sebelumnya:
            tuntas = True
            break
        sebelumnya = tautan
        with file_lock:
            sebelum = len(terkumpul)
            terkumpul.update(tautan)
            baru_total += len(terkumpul) - sebelum
        time.sleep(JEDA_ANTAR_HALAMAN)
    else:
        print(f"[!] {nama} mencapai batas pengaman {BATAS_HALAMAN} halaman.")
    print(f"[Crawler] {nama}: {'tuntas' if tuntas else 'BELUM TUNTAS'}, +{baru_total} film baru")
    return tuntas


def crawl_massive_catalog_parallel(base_url, target_limit=None):
    """
    Sapu seluruh katalog film.

    [target_limit] dibiarkan ada demi pemanggil lama, tetapi kini berarti
    "berhenti lebih awal setelah sekian URL" dan hanya berguna untuk uji cepat.
    Biarkan None agar katalog disapu sampai habis.
    """
    output_file = os.path.join(os.path.dirname(__file__), "movie_links.txt")
    terkumpul = set()
    if os.path.exists(output_file):
        with open(output_file, "r", encoding="utf-8") as f:
            terkumpul = {b.strip() for b in f if b.strip()}
    awal = len(terkumpul)
    print(f"[*] Memuat data lama: {awal} URL sudah tersimpan.")

    base_domain = urlparse(base_url).netloc
    sesi = requests.Session()
    kategori = temukan_kategori(sesi, base_url, base_domain)
    print(f"[*] {len(kategori)} kategori ditemukan; menelusuri tiap kategori sampai habis.")

    tuntas = 0
    try:
        with ThreadPoolExecutor(max_workers=8) as pool:
            hasil = [
                pool.submit(sapu_kategori, sesi, base_url, base_domain, jalur, terkumpul)
                for jalur in kategori
            ]
            for f in hasil:
                if f.result():
                    tuntas += 1
    except KeyboardInterrupt:
        print("\n[!] Dihentikan pengguna. Menyimpan hasil sementara...")

    if target_limit:
        terkumpul = set(sorted(terkumpul)[:target_limit])

    with file_lock:
        with open(output_file, "w", encoding="utf-8") as f:
            for tautan in sorted(terkumpul):
                f.write(tautan + "\n")
    print(f"\n[*] Selesai! {len(terkumpul)} URL unik (+{len(terkumpul) - awal} baru) di {output_file}")
    print(f"[*] Kategori tuntas: {tuntas}/{len(kategori)}")
    if tuntas < len(kategori):
        print("[!] Sebagian kategori terhenti karena gangguan jaringan — jalankan lagi")
        print("    untuk melengkapinya. Hasil menumpuk, jadi tidak ada yang hilang.")
    return len(terkumpul)


if __name__ == "__main__":
    domain = input("Masukkan domain utama (contoh: https://tv12.lk21official.cc/): ").strip()
    if not domain.startswith("http"):
        domain = "https://" + domain
    crawl_massive_catalog_parallel(domain)
