# ==========================================
# Bytify\backend\scraper\film_indonesia_scraper.py
# ==========================================
"""
Pemanen katalog film Indonesia dari filmindonesia.or.id.

**Kenapa sumber ini, bukan menambah panen dari LK21.** Terukur: kategori
/country/indonesia di LK21 hanya berisi 181 film, dan 180 di antaranya sudah
ada di katalog kita. Database mentah 25.197 entri tidak punya satu pun entri
bernegara Indonesia; negara terbanyaknya China, Amerika, India. Jadi memperdalam
panen di LK21 tidak akan menghasilkan perpustakaan film Indonesia — plafonnya
memang segitu.

filmindonesia.or.id adalah basis data film nasional (Pusat Data Perfilman
H. Misbach Yusa Biran). Terukur 2.337 judul lewat indeks /film?page=1..162,
dengan metadata yang justru lebih kaya daripada situs streaming: sinopsis
berbahasa Indonesia, sutradara, produser, pemeran, genre, durasi, tahun, poster.

**Yang TIDAK diberikan sumber ini: tautan tonton.** Ia katalog, bukan situs
streaming. Entri hasil panen ini karena itu ditandai `playable: false` dan
disimpan terpisah — menyuntikkannya begitu saja ke catalog.json akan membuat
ribuan judul tampil lalu gagal diputar saat ditekan.

Tanpa Selenium: seluruh halaman dirender di server.
"""
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from bs4 import BeautifulSoup

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "https://filmindonesia.or.id"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "id-ID,id;q=0.9,en;q=0.8",
}

#: Halaman indeks terakhir, dibaca dari paginasi situs. Dilewati sedikit lebih
#: jauh supaya judul baru tetap terjaring tanpa perlu mengubah berkas ini.
HALAMAN_MAKS = 180

POLA_FILM = re.compile(r"/film/lf-[a-z0-9\-]+")
JEDA = 0.2

BERKAS_TAUTAN = os.path.join(os.path.dirname(__file__), "film_indonesia_links.txt")
BERKAS_HASIL = os.path.join(
    os.path.dirname(__file__), "..", "data", "film_indonesia_database.json"
)

kunci = threading.Lock()


def _ambil(sesi, url, percobaan=3):
    for ke in range(percobaan):
        try:
            r = sesi.get(url, headers=HEADERS, timeout=40)
            if r.status_code == 404:
                return None
            r.raise_for_status()
            return r.text
        except Exception:
            if ke == percobaan - 1:
                return None
            time.sleep(1.5 * (ke + 1))
    return None


def jelajahi_daftar(pekerja=8):
    """Sapu indeks /film?page=N sampai halaman berhenti membawa judul baru."""
    sesi = requests.Session()

    def satu(n):
        html = _ambil(sesi, f"{BASE}/film?page={n}")
        time.sleep(JEDA)
        return set(POLA_FILM.findall(html)) if html else set()

    terkumpul = set()
    kosong_beruntun = 0
    with ThreadPoolExecutor(max_workers=pekerja) as kolam:
        for blok_awal in range(1, HALAMAN_MAKS + 1, pekerja * 2):
            blok = range(blok_awal, min(blok_awal + pekerja * 2, HALAMAN_MAKS + 1))
            hasil = list(kolam.map(satu, blok))
            baru = set().union(*hasil) - terkumpul if hasil else set()
            terkumpul |= baru
            if not baru:
                kosong_beruntun += 1
                # Dua blok berturut-turut tanpa judul baru = indeksnya habis.
                if kosong_beruntun >= 2:
                    break
            else:
                kosong_beruntun = 0
            print(f"  halaman {blok.start}-{blok.stop - 1}: total {len(terkumpul)}")

    urut = sorted(terkumpul)
    with open(BERKAS_TAUTAN, "w", encoding="utf-8") as f:
        f.write("\n".join(urut))
    print(f"[film-id] {len(urut)} judul -> {BERKAS_TAUTAN}")
    return urut


def _teks(simpul):
    return simpul.get_text(" ", strip=True) if simpul else ""


#: Urutan label kredit pada halaman film, dipakai untuk memotong teksnya.
LABEL_KREDIT = ("Sutradara", "Produser", "Penulis", "Pemeran")


def _daftar_nama(teks, label):
    """
    Ambil nama di belakang satu label kredit.

    Dipotong dari TEKS halaman di antara label ini dan label berikutnya, bukan
    dengan mengambil seluruh <a> di simpul induk. Versi pertama memakai cara
    kedua dan hasilnya tercampur: sutradara "12:00 AM" terbaca
    ["Koya Pagayo", "Ery Sofid", "Chandra Willim"] padahal dua nama terakhir
    itu produsernya. Ini jebakan ruang lingkup selector yang sama seperti di
    pemanen serial.
    """
    m = re.search(rf"\b{label}\s*:\s*(.*)", teks)
    if not m:
        return []
    sisa = m.group(1)
    # Berhenti di label berikutnya, apa pun itu.
    berhenti = len(sisa)
    for lain in LABEL_KREDIT:
        if lain == label:
            continue
        m2 = re.search(rf"\b{lain}\s*:", sisa)
        if m2:
            berhenti = min(berhenti, m2.start())
    for penanda in ("Kredit", "Media", "Sinopsis", "Tinjauan", "Galeri"):
        m2 = re.search(rf"\b{penanda}\b", sisa)
        if m2:
            berhenti = min(berhenti, m2.start())
    potong = sisa[:berhenti]
    nama = [n.strip() for n in potong.split(",")]
    return [n for n in dict.fromkeys(nama) if 2 < len(n) < 60]


def panen_satu(jalur, sesi=None):
    sesi = sesi or requests.Session()
    url = BASE + jalur
    html = _ambil(sesi, url)
    if not html:
        return None
    soup = BeautifulSoup(html, "html.parser")

    judul = (soup.title.string or "").strip() if soup.title else ""
    judul = re.sub(r"\s*\|\s*filmindonesia.*$", "", judul, flags=re.I).strip()
    if not judul:
        return None

    teks = soup.get_text(" ", strip=True)

    tahun = None
    m = re.search(r"\b(19\d{2}|20\d{2})\b", teks)
    if m:
        tahun = int(m.group(1))

    durasi = None
    m = re.search(r"(\d{2,3})\s*mnt", teks)
    if m:
        durasi = int(m.group(1))

    genre = []
    m = re.search(r"\b(Horor|Drama|Komedi|Laga|Romantis|Thriller|Animasi|Dokumenter|"
                  r"Misteri|Fantasi|Petualangan|Musikal|Perang|Kriminal|Keluarga|Anak)\b",
                  teks)
    if m:
        genre = [m.group(1)]

    og = soup.find("meta", attrs={"property": "og:image"})
    poster = (og.get("content") or "").strip() if og else None

    sinopsis = ""
    m = re.search(r"Sinopsis\s+(.{40,2000}?)(?:Kredit|Media|Tinjauan|Galeri|$)", teks)
    if m:
        sinopsis = m.group(1).strip()

    return {
        "id": "fi-" + jalur.rsplit("/", 1)[-1],
        "type": "movie",
        "title": judul,
        "overview": sinopsis,
        "posterUrl": poster or None,
        "releaseYear": tahun,
        "durationMin": durasi,
        "genres": genre,
        "countries": ["Indonesia"],
        "directors": _daftar_nama(teks, "Sutradara"),
        "producers": _daftar_nama(teks, "Produser"),
        "cast": _daftar_nama(teks, "Pemeran"),
        "sourceUrl": url,
        # Katalog, bukan situs streaming: tidak ada tautan tonton di sini.
        "playable": False,
    }


def panen_semua(jalur_list, pekerja=10, berkas=BERKAS_HASIL):
    hasil, sudah = [], set()
    if os.path.exists(berkas):
        try:
            with open(berkas, encoding="utf-8") as f:
                hasil = json.load(f)
            sudah = {x.get("sourceUrl") for x in hasil}
            print(f"[film-id] melanjutkan: {len(hasil)} judul sudah ada")
        except Exception:
            hasil, sudah = [], set()

    sisa = [j for j in jalur_list if BASE + j not in sudah]
    print(f"[film-id] {len(sisa)} judul akan dipanen")

    def simpan():
        sementara = f"{berkas}.{threading.get_ident()}.tmp"
        with open(sementara, "w", encoding="utf-8") as f:
            json.dump(hasil, f, ensure_ascii=False)
        os.replace(sementara, berkas)

    selesai = 0
    with ThreadPoolExecutor(max_workers=pekerja) as kolam:
        tugas = {kolam.submit(panen_satu, j): j for j in sisa}
        for t in as_completed(tugas):
            selesai += 1
            try:
                satu = t.result()
            except Exception:
                satu = None
            if satu:
                with kunci:
                    hasil.append(satu)
                    if len(hasil) % 100 == 0:
                        simpan()
            if selesai % 100 == 0:
                print(f"  {selesai}/{len(sisa)} | terkumpul {len(hasil)}")
    with kunci:
        simpan()
    print(f"[film-id] selesai: {len(hasil)} judul -> {berkas}")
    return hasil


if __name__ == "__main__":
    perintah = sys.argv[1] if len(sys.argv) > 1 else "semua"
    if perintah in ("daftar", "semua"):
        tautan = jelajahi_daftar()
    else:
        with open(BERKAS_TAUTAN, encoding="utf-8") as f:
            tautan = [b.strip() for b in f if b.strip()]
    if perintah in ("panen", "semua"):
        panen_semua(tautan)
