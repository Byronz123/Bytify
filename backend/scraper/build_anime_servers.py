# ==========================================
# Bytify\backend\scraper\build_anime_servers.py
# ==========================================
"""
anikai_database.json -> anime_servers.json (lapisan SERVER anime, di-git).

**Kenapa lapisan ini ada.** Terukur di Galaxy A36: anime di Bytify hanya punya
framextv, keempat "cermin"-nya ternyata satu server yang sama (semuanya
dialihkan ke framextv.cc dengan backend api.framextv.tech), dan backend itu
membalas HTTP 429. Separuh anime juga tidak kebagian server turunan berkunci
TMDB id karena penjaga ambiguitas musim (3j). Jadi anime adalah satu-satunya
bagian katalog yang benar-benar kehabisan pilihan, dan lapisan ini mengisinya.

**Bentuknya sengaja sangat ringkas: satu seal per judul, bukan per episode.**
URL pemutar anikai diturunkan dari pola yang dibaca dari kode situsnya sendiri:

    <player>/watch#<seal>.<episode>.<server>.<audio>

Nomor episodenya disisipkan saat tayang, dan nomor itu SUDAH ada di katalog
anime kita (id "anilist-<id>-e<n>"). Jadi menyimpan URL per episode hanya akan
menyalin ulang sesuatu yang bisa dihitung — pola yang sama dengan 3n. Hasilnya
berkas kecil, dan TIDAK ADA episode yang terlewat: berapa pun episodenya,
URL-nya terbentuk.

**Penjodohannya KETAT, sama alasannya dengan 3h.** Anikai tidak membawa id
AniList, jadi jembatannya judul. Judul ternormalkan harus SAMA PERSIS dengan
romaji atau judul Inggris AniList; kalau lebih dari satu judul AniList cocok,
entri itu DILEWATI sebagai ambigu. Memasang server anime yang salah lebih
buruk daripada tidak memasang server sama sekali — penonton akan menyangka
katalognya rusak, bukan sekadar kosong.

Penanda musim TIDAK dibuang, berbeda dari 3i. Di sana masalahnya AniList
memecah musim sementara TMDB menyatukan; di sini kedua sisi sama-sama anime
dan sama-sama memecah musim, jadi "Season 2" justru pembeda yang sah.
"""
import json
import os
import re
import sys
from collections import defaultdict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
SUMBER = os.path.join(DIR_DATA, "anikai_database.json")
JUDUL_ANILIST = os.path.join(DIR_DATA, "anime_titles.json")
BASIS_ANIME = os.path.join(DIR_DATA, "anime_database.json")
KELUARAN = os.path.join(DIR_DATA, "anime_servers.json")

BATAS_AMAN_MIB = 24
WATCH = os.path.join(DIR_DATA, "watch_catalog.json")

#: Penanda musim pada judul anime. Hanya bentuk yang EKSPLISIT; "Part 2" dan
#: "Final Season" sengaja tidak diterima karena keduanya tidak memetakan ke
#: nomor musim TMDB secara andal.
_POLA_MUSIM = (
    re.compile(r"\bseason\s+(\d{1,2})\b", re.I),
    re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)\s+season\b", re.I),
)


def musim_dari_judul(judul):
    """Nomor musim yang disebut judul, atau 1 kalau tidak menyebut apa pun."""
    for pola in _POLA_MUSIM:
        m = pola.search(judul or "")
        if m:
            n = int(m.group(1))
            if 1 <= n <= 50:
                return n
    return 1


def peta_musim(judul_peta, basis):
    """
    AniList id -> nomor musim TMDB, hanya bila bisa ditentukan dengan YAKIN.

    Masalahnya yang dicatat di 3j: AniList memecah musim sementara TMDB
    menyatukannya, jadi beberapa entri katalog menunjuk satu TMDB id dan
    episode kita semuanya bernomor musim 1. Akibatnya subtitle per-episode
    dilewati — termasuk untuk Jujutsu Kaisen, yang berbagi TMDB 95479 dengan
    musim 2 dan 3.

    Yang dilakukan di sini: nomor musimnya DIBACA dari judul, lalu diperiksa
    silang terhadap saudara-saudaranya. Kalau seluruh entri yang berbagi satu
    TMDB id menghasilkan nomor musim yang BERBEDA-BEDA, pemetaannya dipakai;
    kalau ada dua yang bertabrakan, SELURUH grup itu dilewati. Satu tabrakan
    berarti kita tidak tahu mana yang mana, dan subtitle musim yang salah
    lebih buruk daripada tidak ada subtitle (3h).
    """
    if not os.path.exists(WATCH):
        return {}
    with open(WATCH, encoding="utf-8") as f:
        alias = (json.load(f).get("aliases") or {})

    def judul_untuk(aid):
        n = judul_peta.get(str(aid)) or {}
        return n.get("english") or n.get("romaji") or basis.get(aid) or ""

    grup = defaultdict(list)
    for kunci, nilai in alias.items():
        if not kunci.startswith("anilist-"):
            continue
        aid = kunci.removeprefix("anilist-").split("-")[0]
        if not aid.isdigit() or not nilai.get("tmdbId"):
            continue
        grup[(int(nilai["tmdbId"]), nilai.get("type"))].append(int(aid))

    hasil = {}
    for anggota in grup.values():
        musim = {aid: musim_dari_judul(judul_untuk(aid)) for aid in anggota}
        if len(set(musim.values())) != len(anggota):
            continue  # ada tabrakan: seluruh grup dilewati
        hasil.update(musim)
    return hasil


def normal(teks):
    """
    Normalisasi judul untuk pencocokan KETAT.

    Hanya satu padanan yang diizinkan, "&" -> "and", sama seperti tmdb_match.py.
    TIDAK membuang kata sandang dan TIDAK menyamakan angka Romawi, supaya
    "Season II" tidak tertukar dengan "Season III".
    """
    if not teks:
        return ""
    t = teks.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    t = " ".join(t.split())
    return _seragamkan_musim(t)


#: Angka Romawi yang masuk akal sebagai penanda musim anime.
_ROMAWI = {"ii": 2, "iii": 3, "iv": 4, "v": 5, "vi": 6, "vii": 7, "viii": 8,
           "ix": 9, "x": 10}


def _seragamkan_musim(t):
    """
    Menyeragamkan cara judul menyebut musim, DI KEDUA SISI.

    Terukur pada 2.187 judul anikai yang tidak terjodohkan: nyaris semuanya
    gagal hanya karena notasi musimnya berbeda, bukan karena judulnya berbeda.

    | anikai                                   | AniList          |
    |------------------------------------------|------------------|
    | The Angel Next Door Spoils Me Rotten 2   | ... Season 2     |
    | Skeleton Knight in Another World II      | ... Season 2     |
    | Swallowed Star 2nd Season                | ... Season 2     |

    Aman karena diterapkan SIMETRIS: kedua sisi diubah dengan aturan yang
    sama, jadi ini bukan pencocokan longgar melainkan satu ejaan baku. Judul
    yang benar-benar berbeda tetap berbeda, dan yang berakhir cocok ke lebih
    dari satu id tetap DILEWATI sebagai ambigu.

    "Part N" sengaja TIDAK disamakan dengan "Season N" — Dr. Stone Part 3 dan
    Season 3 adalah dua hal yang berbeda, dan menyamakannya akan memasang
    server musim yang salah.
    """
    # "2nd season" / "3rd season" -> "season 2"
    t = re.sub(r"\b(\d{1,2})(?:st|nd|rd|th) season\b", r"season \1", t)
    # "season ii" -> "season 2"
    t = re.sub(r"\bseason ([ivx]{1,4})\b",
               lambda m: f"season {_ROMAWI[m.group(1)]}" if m.group(1) in _ROMAWI else m.group(0), t)
    kata = t.split()
    if len(kata) >= 2:
        akhir = kata[-1]
        # Angka Romawi di ujung judul -> musim. Hanya ii ke atas: "v" sendiri
        # terlalu sering bagian dari nama, dan "i" tidak pernah berarti musim.
        if akhir in _ROMAWI and akhir != "v" and kata[-2] != "season":
            return " ".join(kata[:-1] + ["season", str(_ROMAWI[akhir])])
        # Angka telanjang di ujung -> musim, tetapi hanya 2..9 dan hanya bila
        # masih ada minimal dua kata di depannya. Itu menjaga judul yang
        # angkanya memang bagian dari nama, seperti "86" atau "Gundam 00".
        if akhir.isdigit() and len(akhir) == 1 and 2 <= int(akhir) <= 9 \
                and len(kata) >= 3 and kata[-2] not in ("season", "part"):
            return " ".join(kata[:-1] + ["season", akhir])
    return t


def muat(p):
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def main():
    anikai = muat(SUMBER)
    if not anikai:
        print(f"Sumber belum ada: {SUMBER}. Jalankan anikai_servers.py dulu.")
        return 1

    judul_peta = muat(JUDUL_ANILIST) or {}
    basis = muat(BASIS_ANIME) or []

    # Judul ternormalkan -> himpunan AniList id. Himpunan, bukan satu nilai,
    # supaya yang kembar bisa dikenali dan dilewati alih-alih dipasang asal.
    indeks = defaultdict(set)
    for kunci, nilai in judul_peta.items():
        try:
            aid = int(kunci)
        except (TypeError, ValueError):
            continue
        for bentuk in ("romaji", "english"):
            n = normal(nilai.get(bentuk))
            if n:
                indeks[n].add(aid)
    # anime_database.json memuat 13.094 judul romaji — lebih luas daripada
    # anime_titles.json (7.577), jadi dipakai untuk menambah jangkauan.
    for satu in basis:
        aid = satu.get("anilistId")
        n = normal(satu.get("title"))
        if aid and n:
            indeks[n].add(int(aid))

    # Hanya judul yang BENAR-BENAR ada di katalog terbit yang berguna.
    id_katalog = {int(x["anilistId"]) for x in basis if x.get("anilistId")}
    judul_basis = {int(x["anilistId"]): x.get("title") or ""
                   for x in basis if x.get("anilistId")}
    musim = peta_musim(judul_peta, judul_basis)

    titles = {}
    cocok = ambigu = tanpa_sumber = tak_ketemu = luar_katalog = 0
    for satu in anikai:
        if satu.get("server") == 0 or not satu.get("seal"):
            tanpa_sumber += 1
            continue
        n = normal(satu.get("title"))
        if not n:
            tak_ketemu += 1
            continue
        kandidat = indeks.get(n)
        if not kandidat:
            tak_ketemu += 1
            continue
        if len(kandidat) > 1:
            ambigu += 1
            continue
        aid = next(iter(kandidat))
        if aid not in id_katalog:
            luar_katalog += 1
            continue
        # Satu judul anikai per AniList id; yang pertama menang supaya
        # hasilnya tidak berubah-ubah antar jalan.
        kunci = str(aid)
        if kunci in titles:
            continue
        # Objek bernama, bukan larik [seal, server]: selisih ukurannya kecil
        # (sekitar 80 KB untuk seluruh berkas) sementara kontraknya jadi jelas
        # dan bisa diurai bertipe di aplikasi tanpa JsonElement.
        entri = {"seal": satu["seal"], "server": satu["server"]}
        # Nomor musim TMDB ikut, kalau bisa ditentukan dengan yakin. Inilah
        # yang membuka subtitle per-episode untuk anime yang selama ini
        # dilewati penjaga 3j.
        m = musim.get(aid)
        if m:
            entri["season"] = m
        titles[kunci] = entri
        cocok += 1

    keluar = {
        "player": "https://play.anikai.org",
        # Polanya ikut diterbitkan supaya aplikasi tidak perlu menanam
        # pengetahuan tentang bentuk URL penyedia di dalam kodenya.
        "pattern": "{player}/watch#{seal}.{episode}.{server}.{audio}",
        "audio": "sub",
        "titles": titles,
    }

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(keluar, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    mib = os.path.getsize(KELUARAN) / 1024 / 1024
    print(f"{cocok} anime dapat server anikai, {mib:.2f} MiB -> {KELUARAN}")
    print(f"  dilewati: {ambigu} ambigu, {tak_ketemu} judul tak ditemukan, "
          f"{luar_katalog} di luar katalog, {tanpa_sumber} tanpa sumber")
    if mib > BATAS_AMAN_MIB:
        print(f"  PERINGATAN: {mib:.2f} MiB melewati ambang aman {BATAS_AMAN_MIB} MiB")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
