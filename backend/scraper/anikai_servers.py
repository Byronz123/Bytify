# ==========================================
# Bytify\backend\scraper\anikai_servers.py
# ==========================================
"""
Memanen SERVER anime dari anikai.org (AnimeKai) — hanya bagian servernya.

**Kenapa ini menggantikan scrape_anikai.js, dan ini penting.** Skrip Puppeteer
itu dibangun di atas asumsi situs bergaya Zoro/HiAnime: menunggu gerbang
Cloudflare, menggulir, lalu MENGKLIK tab server satu per satu untuk memancing
iframe keluar. Diperiksa langsung, anikai.org sama sekali bukan situs seperti
itu — ia situs STATIS (Hugo) yang menyajikan:

  * `/watch-sitemap.xml`      -> 8.006 URL anime, lengkap, satu permintaan
  * `/episodes/<id>.json`     -> daftar episode sebagai larik ringkas
  * `/watch/<slug>-<id>/`     -> HTML yang memuat seluruh kunci pemutarnya

Tidak ada `/ajax/v2/episode/servers`, tidak ada gerbang Cloudflare, tidak ada
yang perlu diklik. Jadi browser justru memperlambat dan membuat pemanenan
TIDAK lengkap (skrip lama berhenti di ENRICH_DETAIL_LIMIT=80 judul dan hanya
mengambil server episode pertama). Dengan HTTP biasa seluruh 8.006 judul
terjangkau, dan tidak ada episode yang terlewat.

**Bentuk server anikai, dibaca dari kode situsnya sendiri** (`js/main.js`):

    `${dataset.player}/watch#${dataset.seal}.${episode}.${server}.${audio}`

Jadi URL pemutarnya DITURUNKAN dari empat hal, dan hanya `seal` yang perlu
dipanen — satu per anime, BUKAN per episode. Itu sebabnya berkas hasil panen
ini kecil dan setiap episode tetap terjangkau: nomor episode cukup disisipkan
ke pola di atas. `server == 0` berarti situs itu sendiri menyatakan judulnya
belum punya sumber, dan entri seperti itu ditandai supaya tidak pernah
dipasang sebagai server.

Sesuai keputusan 3f, yang diambil HANYA bagian server (id, seal, nomor server,
daftar episode) beserta judul untuk penjodohan. Metadata lain di keluaran
scraper JS tidak dipakai: `tmdbId`-nya hash buatan (7000000+),
`imdbId`-nya "tt"+padding, rating "8.2" dan tahun "2026" konstanta.

Pemakaian:
    python anikai_servers.py                 # panen penuh, bisa dilanjutkan
    python anikai_servers.py --batas 40      # cicip dulu 40 judul
    python anikai_servers.py --segarkan      # panen ulang semuanya
"""
import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
KELUARAN = os.path.join(DIR_DATA, "anikai_database.json")

ASAL = "https://anikai.org"
PETA_SITUS = f"{ASAL}/watch-sitemap.xml"

#: Etiket yang sama seperti UA_BYTIFY di pemanen AniList: menyebut nama
#: aplikasi dan repositorinya, bukan menyamar sebagai browser.
UA = "Bytify/1.3 (+https://github.com/Byronz123/Bytify) anime-server-harvester"

PEKERJA = 6
JEDA = 0.12
SIMPAN_TIAP = 200

#: Kunci pemutar, dibaca dari atribut data-* pada <div id=watch-page>.
POLA_WATCH = re.compile(
    r'id=watch-page\s+data-id=(\d+)\s+data-seal=([A-Za-z0-9_\-]*)\s+'
    r'data-player=(\S+?)\s+data-server-default=(\d+)'
)
POLA_JUDUL = re.compile(r'<meta\s+property="og:title"\s+content="([^"]+)"', re.I)
POLA_JUDUL2 = re.compile(r"<title>(.*?)</title>", re.I | re.S)
POLA_JEPANG = re.compile(r'"japanese"\s*:\s*"([^"]+)"')
POLA_ID_SLUG = re.compile(r"/watch/(.+)-(\d+)/?$")
POLA_LD = re.compile(r'application/ld\+json[^>]*>(.*?)</script>', re.S)


def dari_ld(html):
    """
    Judul alternatif + tahun dari blok JSON-LD halaman.

    Inilah yang membuat judul non-Jepang terjodohkan. Anikai menamai
    halamannya dalam bahasa Inggris ("Swallowed Star 2nd Season") sementara
    AniList menyimpan romaji ("Tunshi Xingkong 2nd Season"), dan tanpa
    jembatan itu seluruh donghua gagal dicocokkan. JSON-LD membawa keduanya:
    `alternateName` berisi bentuk romaji, `datePublished` berisi tanggal
    tayang — dan tahun itu dipakai sebagai bukti penguat, bukan sebagai
    pelonggar aturan.
    """
    for mentah in POLA_LD.findall(html or ""):
        try:
            data = json.loads(mentah)
        except (json.JSONDecodeError, TypeError):
            continue
        simpul = data.get("@graph") if isinstance(data, dict) else None
        for n in (simpul if isinstance(simpul, list) else [data]):
            if not isinstance(n, dict):
                continue
            # HANYA simpul karyanya. @graph juga memuat simpul WebSite yang
            # alternateName-nya "AniKai" — nama situsnya sendiri, bukan judul
            # anime — dan tanpa saringan ini setiap entri memakainya.
            tipe = n.get("@type")
            tipe = tipe if isinstance(tipe, str) else ""
            if tipe not in ("TVSeries", "Movie", "TVSeason", "TVEpisode", "CreativeWork"):
                continue
            if n.get("alternateName") or n.get("datePublished"):
                tahun = None
                m = re.search(r"\b(19\d{2}|20\d{2})\b", str(n.get("datePublished") or ""))
                if m:
                    tahun = int(m.group(1))
                alt = n.get("alternateName")
                if isinstance(alt, list):
                    alt = next((x for x in alt if isinstance(x, str)), None)
                return (alt.strip() if isinstance(alt, str) else None), tahun
    return None, None

_kunci = threading.Lock()

#: Kunci proses tunggal, sama seperti di build_database.py.
#:
#: Bukan kehati-hatian teoretis: dua pemanen pernah berjalan bersamaan dan
#: saling menimpa berkas sementara yang sama, sehingga os.replace() gagal
#: dengan "cannot find the file specified" dan seluruh hasil panen hilang.
#: Jebakan yang sama sudah tercatat di CLAUDE.md untuk build_database.py.
BERKAS_KUNCI = os.path.join(DIR_DATA, "anikai_servers.lock")


class KunciProses:
    def __enter__(self):
        if os.path.exists(BERKAS_KUNCI):
            try:
                with open(BERKAS_KUNCI, encoding="utf-8") as f:
                    pemilik = f.read().strip()
            except OSError:
                pemilik = "?"
            raise SystemExit(
                f"Pemanen lain sedang berjalan (pid {pemilik}).\n"
                f"Kalau yakin tidak ada, hapus {BERKAS_KUNCI} lalu ulangi."
            )
        os.makedirs(DIR_DATA, exist_ok=True)
        with open(BERKAS_KUNCI, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
        return self

    def __exit__(self, *_):
        try:
            os.remove(BERKAS_KUNCI)
        except OSError:
            pass
        return False


def ambil(url, percobaan=3):
    for n in range(percobaan):
        try:
            r = urllib.request.Request(url, headers={
                "User-Agent": UA,
                "Accept": "text/html,application/json,application/xml,*/*;q=0.8",
            })
            with urllib.request.urlopen(r, timeout=30) as b:
                return b.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            # 404 itu jawaban, bukan gangguan: jangan diulang. Membedakan
            # keduanya penting — menyamakannya membuat satu gangguan jaringan
            # terbaca sebagai "judul ini memang tidak ada".
            if e.code == 404:
                return None
            if n == percobaan - 1:
                raise
        except Exception:
            if n == percobaan - 1:
                raise
        time.sleep(1.5 * (n + 1))
    return None


def daftar_anime():
    """Seluruh URL anime dari peta situs. Satu permintaan, tidak ada yang luput."""
    xml = ambil(PETA_SITUS)
    if not xml:
        return []
    alamat = re.findall(r"<loc>([^<]+)</loc>", xml)
    return [a for a in alamat if "/watch/" in a]


def nomor_episode(id_anime):
    """
    Nomor episode dari /episodes/<id>.json.

    Bentuknya larik ringkas: [nomor, judul, tanggal, bool, bool]. Yang dipakai
    hanya nomornya, karena itulah yang disisipkan ke pola URL pemutar.
    Mengembalikan daftar kosong kalau berkasnya tidak ada — judul seperti itu
    tetap disimpan dengan satu episode, supaya film/OVA tidak ikut terbuang.
    """
    isi = ambil(f"{ASAL}/episodes/{id_anime}.json")
    if not isi:
        return []
    try:
        data = json.loads(isi)
    except json.JSONDecodeError:
        return []
    keluar = []
    for baris in data if isinstance(data, list) else []:
        if isinstance(baris, list) and baris and isinstance(baris[0], int):
            keluar.append(baris[0])
        elif isinstance(baris, dict) and isinstance(baris.get("episode"), int):
            keluar.append(baris["episode"])
    return sorted(set(n for n in keluar if n > 0))


def panen_satu(alamat):
    html = ambil(alamat)
    if not html:
        return None
    cocok = POLA_WATCH.search(html)
    if not cocok:
        return None
    id_anime, seal, pemutar, server = cocok.groups()

    judul = None
    m = POLA_JUDUL.search(html) or POLA_JUDUL2.search(html)
    if m:
        # og:title berpola "<judul> | AnimeKai"; ekornya dibuang.
        judul = re.sub(r"\s*\|\s*AnimeKai\s*$", "", m.group(1)).strip()
    jepang = None
    mj = POLA_JEPANG.search(html)
    if mj:
        jepang = mj.group(1).strip()

    slug = None
    ms = POLA_ID_SLUG.search(alamat)
    if ms:
        slug = ms.group(1)

    alt, tahun = dari_ld(html)
    episode = nomor_episode(id_anime)
    time.sleep(JEDA)
    return {
        "anikaiId": int(id_anime),
        "slug": slug,
        "title": judul,
        "altTitle": alt,
        "year": tahun,
        "japanese": jepang,
        "seal": seal or None,
        "server": int(server),
        "player": pemutar,
        "episodes": episode or [1],
        "url": alamat,
    }


def muat_lama():
    if not os.path.exists(KELUARAN):
        return {}
    try:
        with open(KELUARAN, encoding="utf-8") as f:
            lama = json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}
    return {int(x["anikaiId"]): x for x in lama if x.get("anikaiId")}


def simpan(peta):
    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(sorted(peta.values(), key=lambda x: x["anikaiId"]), f,
                  ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)


def main():
    with KunciProses():
        return jalankan()


def jalankan():
    p = argparse.ArgumentParser()
    p.add_argument("--batas", type=int, default=0, help="hanya N judul (uji cicip)")
    p.add_argument("--segarkan", action="store_true", help="panen ulang semuanya")
    p.add_argument("--pekerja", type=int, default=PEKERJA)
    arg = p.parse_args()

    alamat = daftar_anime()
    if not alamat:
        print("Peta situs kosong; anikai.org tidak terjangkau.")
        return 1
    print(f"{len(alamat)} judul di peta situs")

    hasil = {} if arg.segarkan else muat_lama()
    if hasil:
        print(f"{len(hasil)} judul sudah dipanen sebelumnya; dilanjutkan")

    def sudah(a):
        # Dianggap selesai hanya kalau sudah dipanen dengan skema SEKARANG.
        # Entri lama tidak membawa altTitle, dan justru field itulah yang
        # menjembatani judul non-Jepang.
        m = POLA_ID_SLUG.search(a)
        if not m:
            return False
        lama = hasil.get(int(m.group(2)))
        return bool(lama) and "altTitle" in lama

    sisa = [a for a in alamat if not sudah(a)]
    if arg.batas:
        sisa = sisa[:arg.batas]
    print(f"{len(sisa)} judul akan dipanen\n")

    selesai = [0]
    gagal = [0]

    def kerjakan(a):
        try:
            satu = panen_satu(a)
        except Exception as e:
            with _kunci:
                gagal[0] += 1
                print(f"  [GAGAL] {a} -> {type(e).__name__}")
            return
        with _kunci:
            selesai[0] += 1
            if satu:
                hasil[satu["anikaiId"]] = satu
            else:
                gagal[0] += 1
            if selesai[0] % 50 == 0:
                print(f"  {selesai[0]}/{len(sisa)} | tersimpan {len(hasil)}")
            if selesai[0] % SIMPAN_TIAP == 0:
                simpan(hasil)

    with ThreadPoolExecutor(max_workers=arg.pekerja) as kolam:
        list(kolam.map(kerjakan, sisa))

    with _kunci:
        simpan(hasil)
    tanpa_sumber = sum(1 for x in hasil.values() if x["server"] == 0 or not x["seal"])
    episode = sum(len(x["episodes"]) for x in hasil.values())
    print(f"\n{len(hasil)} judul, {episode} episode -> {KELUARAN}")
    print(f"  tanpa sumber (server=0 / tanpa seal): {tanpa_sumber}")
    if gagal[0]:
        print(f"  {gagal[0]} halaman gagal dipanen")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
