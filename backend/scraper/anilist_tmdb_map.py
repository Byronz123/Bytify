# ==========================================
# Bytify\backend\scraper\anilist_tmdb_map.py
# ==========================================
"""
Menjembatani AniList id -> TMDB id untuk katalog anime.

Kenapa perlu: lapisan ketersediaan resmi dan lapisan subtitle keduanya dikunci
TMDB id, sementara 13.094 anime memakai id "anilist-<id>". Tanpa jembatan ini
keduanya tidak pernah menjangkau anime sama sekali — terukur 0 dari 13.094.

KENAPA TIDAK MENCOCOKKAN JUDUL seperti tmdb_match.py: judul anime punya bentuk
romaji, Inggris, dan Jepang sekaligus, dan TMDB memecah anime antara `movie`
dan `tv`. Mencocokkan judul di sini jauh lebih rawan daripada untuk film LK21.
Yang dipakai justru dataset pemetaan publik yang sudah dikurasi
(Fribb/anime-lists), yang memetakan anilist_id -> themoviedb_id BESERTA
tipenya.

TAPI DATASET ITU TIDAK DIPERCAYA BEGITU SAJA. Prinsipnya sama dengan
tmdb_match.py: salah pasang lebih buruk daripada tidak pasang — subtitle atau
ketersediaan milik judul lain merusak kepercayaan pada seluruh fitur. Karena
itu tiap pemetaan DIVERIFIKASI terhadap judul yang dipulangkan TMDB sendiri,
dan yang tidak terverifikasi dibuang.

Verifikasinya gratis: panen ketersediaan (`tmdb_watch.py`) memang sudah
mengambil judul dari TMDB, jadi fase verifikasi hanya membaca
watch_database.json yang sudah ada — tanpa satu pun permintaan tambahan.

Pakai, berurutan:
  python -I anilist_tmdb_map.py --buat-daftar
      -> backend/data/anime_tmdb_worklist.json  (umpan untuk tmdb_watch.py)

  BYTIFY_DAFTAR_TMDB=...anime_tmdb_worklist.json python -I tmdb_watch.py

  python -I anilist_tmdb_map.py --verifikasi
      -> backend/data/anime_tmdb_map.json  (alias terverifikasi saja)
"""
import json
import os
import re
import sys
import unicodedata
import time
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR = os.path.dirname(os.path.abspath(__file__))
DIR_DATA = os.path.join(DIR, "..", "data")

SUMBER_PETA = os.path.join(DIR_DATA, "anime_tmdb_source.json")
SUMBER_ANIME = os.path.join(DIR_DATA, "anime_database.json")
SUMBER_WATCH = os.path.join(DIR_DATA, "watch_database.json")
DAFTAR_KERJA = os.path.join(DIR_DATA, "anime_tmdb_worklist.json")
SUMBER_JUDUL = os.path.join(DIR_DATA, "anime_titles.json")
KELUARAN = os.path.join(DIR_DATA, "anime_tmdb_map.json")

#: AniList menolak klien yang tidak memperkenalkan diri dengan Cloudflare
#: error 1010 ("browser_signature_banned"), dan kegagalannya SENYAP: tiap
#: permintaan habis di percobaan ulang, jadi panen tampak berjalan padahal
#: tidak memulangkan apa pun. Menyebut jati diri adalah etiket API biasa —
#: bukan menyamar sebagai browser.
UA_BYTIFY = "Bytify/1.3.0 (+https://github.com/Byronz123/Bytify)"

URL_PETA = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"

#: Toleransi tahun. Anime sering tayang lintas tahun (musim gugur -> musim
#: dingin), dan TMDB memakai tanggal tayang perdana, jadi 1 tahun wajar.
TOLERANSI_TAHUN = 1


def normal(teks):
    """Bentuk pembanding: huruf kecil, tanpa aksen, tanpa tanda baca."""
    t = unicodedata.normalize("NFKD", str(teks or ""))
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = t.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def unduh_peta():
    if os.path.exists(SUMBER_PETA):
        return
    print("[peta] mengunduh dataset pemetaan anime...")
    sementara = SUMBER_PETA + ".tmp"
    req = urllib.request.Request(URL_PETA, headers={"Accept": "application/json",
             "User-Agent": UA_BYTIFY})
    with urllib.request.urlopen(req, timeout=120) as resp, open(sementara, "wb") as f:
        f.write(resp.read())
    os.replace(sementara, SUMBER_PETA)
    print("[peta] %.1f MB -> %s" % (os.path.getsize(SUMBER_PETA) / 1048576, SUMBER_PETA))


def muat_peta():
    """anilist_id -> (jenis, tmdbId). Entri tanpa keduanya dilewati."""
    unduh_peta()
    with open(SUMBER_PETA, encoding="utf-8") as f:
        mentah = json.load(f)
    peta = {}
    for x in mentah:
        a, t = x.get("anilist_id"), x.get("themoviedb_id")
        if not isinstance(a, int) or not isinstance(t, dict):
            continue
        # Bentuk nilainya BERBEDA antar jenis, dan ini mudah terlewat:
        #   {"tv": 26209}      -> int
        #   {"movie": [128]}   -> LIST
        # Memperlakukan keduanya sebagai int membuang seluruh 1.365 pemetaan
        # film diam-diam, tanpa galat apa pun.
        #
        # List dengan lebih dari satu id (30 entri, biasanya seri film yang
        # dibundel) ambigu — dilewati, bukan ditebak.
        pasangan = []
        for k in ("tv", "movie"):
            v = t.get(k)
            if isinstance(v, int):
                pasangan.append((k, v))
            elif isinstance(v, list) and len(v) == 1 and isinstance(v[0], int):
                pasangan.append((k, v[0]))
        if len(pasangan) != 1:
            continue
        peta[a] = pasangan[0]
    return peta


def muat_anime():
    with open(SUMBER_ANIME, encoding="utf-8") as f:
        return json.load(f)


def anilist_dari(idFilm):
    m = re.match(r"^anilist-(\d+)$", str(idFilm or ""))
    return int(m.group(1)) if m else None


def buat_daftar():
    peta = muat_peta()
    anime = muat_anime()
    cocok, tanpa = {}, 0
    for a in anime:
        aid = a.get("anilistId") or anilist_dari(a.get("id"))
        if not isinstance(aid, int):
            continue
        pas = peta.get(aid)
        if not pas:
            tanpa += 1
            continue
        cocok[a["id"]] = {"anilistId": aid, "type": pas[0], "tmdbId": pas[1]}

    unik = sorted({(v["type"], v["tmdbId"]) for v in cocok.values()})
    with open(DAFTAR_KERJA, "w", encoding="utf-8") as f:
        json.dump([{"type": t, "tmdbId": i} for t, i in unik], f, separators=(",", ":"))

    sementara = KELUARAN + ".kandidat.tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({"kandidat": cocok}, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN + ".kandidat")

    print("[peta] %d anime punya kandidat TMDB (%.1f%%), %d tanpa pemetaan"
          % (len(cocok), len(cocok) * 100.0 / max(len(anime), 1), tanpa))
    print("  %d TMDB id unik -> %s" % (len(unik), DAFTAR_KERJA))
    print("  kandidat -> %s.kandidat" % KELUARAN)
    print()
    print("  Berikutnya: panen judulnya, lalu --verifikasi")
    return 0


def ambil_judul_anilist(ids):
    """
    Judul romaji + english + native dari AniList untuk sekumpulan id.

    Perlu karena anime_database.json hanya menyimpan ROMAJI, sementara TMDB
    kerap memakai judul Inggris resmi. Membandingkan romaji dengan judul
    Inggris menolak pemetaan yang sebetulnya benar — terukur: "Youchien WARS"
    vs "Kindergarten Wars", "Sousou no Frieren 3rd Season" vs "Frieren:
    Beyond Journey's End". Keduanya judul yang sama.

    AniList membatasi 90 permintaan/menit, jadi 50 id per halaman dengan jeda.
    """
    KUERI = """
    query ($ids: [Int]) {
      Page(page: 1, perPage: 50) {
        media(id_in: $ids, type: ANIME) {
          id
          title { romaji english native }
          synonyms
          startDate { year }
        }
      }
    }"""
    hasil = {}
    unik = sorted(set(ids))
    for i in range(0, len(unik), 50):
        potong = unik[i:i + 50]
        badan = json.dumps({"query": KUERI, "variables": {"ids": potong}}).encode("utf-8")
        req = urllib.request.Request(
            "https://graphql.anilist.co", data=badan,
            headers={"Content-Type": "application/json", "Accept": "application/json",
             "User-Agent": UA_BYTIFY})
        for coba in range(5):
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    d = json.loads(resp.read())
                for m in ((d.get("data") or {}).get("Page") or {}).get("media") or []:
                    t = m.get("title") or {}
                    hasil[m["id"]] = {
                        "romaji": t.get("romaji"),
                        "english": t.get("english"),
                        "native": t.get("native"),
                        # Sinonim ikut diambil karena banyak anime TIDAK punya
                        # judul Inggris resmi di AniList (terukur: hanya 7.500
                        # dari 14.577), sementara situs pihak ketiga menamai
                        # judulnya dalam bahasa Inggris. Sinonim sering memuat
                        # justru nama yang dipakai situs-situs itu.
                        "synonyms": [s for s in (m.get("synonyms") or []) if s],
                        "year": (m.get("startDate") or {}).get("year"),
                    }
                break
            except Exception:
                time.sleep(2 + coba * 2)
        time.sleep(0.8)
        if (i // 50) % 20 == 0:
            print("  judul %d/%d" % (min(i + 50, len(unik)), len(unik)), flush=True)
    return hasil


def panen_judul():
    jalur_kandidat = KELUARAN + ".kandidat"
    if not os.path.exists(jalur_kandidat):
        print("Kandidat belum ada. Jalankan --buat-daftar dulu.")
        return 1
    with open(jalur_kandidat, encoding="utf-8") as f:
        kandidat = json.load(f)["kandidat"]
    ids = [v["anilistId"] for v in kandidat.values()]
    print("[judul] mengambil judul AniList untuk %d anime..." % len(set(ids)))
    hasil = ambil_judul_anilist(ids)
    sementara = SUMBER_JUDUL + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(hasil, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, SUMBER_JUDUL)
    print("[judul] %d judul tersimpan (%d punya judul Inggris) -> %s"
          % (len(hasil), sum(1 for v in hasil.values() if v.get("english")), SUMBER_JUDUL))
    return 0


#: Penanda musim yang dipakai AniList tetapi TIDAK dipakai TMDB: AniList
#: memecah serial per musim, TMDB menyatukannya dalam satu entri.
#: Membuangnya BUKAN pelonggaran jadi pencocokan kabur — ini menghapus satu
#: perbedaan struktural yang sudah terukur, mis. "Sousou no Frieren 3rd
#: Season" vs "Frieren: Beyond Journey's End".
_POLA_MUSIM = re.compile(
    r"\s*(?:"
    r"(?:the\s+)?(?:final\s+)?season\s*\d*"
    r"|\d+(?:st|nd|rd|th)\s+season"
    r"|part\s*\d+"
    r"|cour\s*\d+"
    r"|final\s+season"
    r")\s*$", re.I)


def tanpa_musim(teks):
    t = str(teks or "").strip()
    for _ in range(3):
        b = _POLA_MUSIM.sub("", t).strip(" :-")
        if b == t:
            break
        t = b
    return t


def judul_anime(a, dariAniList=None):
    """
    Semua bentuk judul untuk satu anime.

    anime_database.json hanya menyimpan ROMAJI, sementara TMDB kerap memakai
    judul Inggris resmi. Membandingkan romaji dengan judul Inggris menolak
    pemetaan yang sebetulnya benar — terukur: "Youchien WARS" vs
    "Kindergarten Wars". Judul Inggris diambil terpisah lewat --judul.
    """
    keluar = []
    for k in ("title", "originalTitle"):
        v = (a.get(k) or "").strip()
        if v:
            keluar.append(v)
    if dariAniList:
        for k in ("romaji", "english", "native"):
            v = (dariAniList.get(k) or "").strip()
            if v:
                keluar.append(v)
    return keluar


def verifikasi():
    jalur_kandidat = KELUARAN + ".kandidat"
    if not os.path.exists(jalur_kandidat):
        print("Kandidat belum ada. Jalankan --buat-daftar dulu.")
        return 1
    if not os.path.exists(SUMBER_WATCH):
        print("watch_database.json belum ada. Jalankan tmdb_watch.py dulu.")
        return 1

    with open(jalur_kandidat, encoding="utf-8") as f:
        kandidat = json.load(f)["kandidat"]
    judulAniList = {}
    if os.path.exists(SUMBER_JUDUL):
        with open(SUMBER_JUDUL, encoding="utf-8") as f:
            judulAniList = {int(k): v for k, v in json.load(f).items()}
    else:
        print("  PERINGATAN: anime_titles.json belum ada; jalankan --judul dulu, "
              "atau verifikasi ini akan menolak pemetaan yang benar.")
    with open(SUMBER_WATCH, encoding="utf-8") as f:
        watch = json.load(f)
    tmdb = {(x["type"], x["tmdbId"]): x for x in watch.get("titles", [])}
    anime = {a["id"]: a for a in muat_anime()}

    sah, gagal, hilang = {}, [], 0
    for idFilm, acuan in kandidat.items():
        a = anime.get(idFilm)
        if not a:
            continue
        acuan.setdefault("anilistId", anilist_dari(idFilm))
        w = tmdb.get((acuan["type"], acuan["tmdbId"]))
        if not w:
            hilang += 1
            continue

        semuaJudul = judul_anime(a, judulAniList.get(acuan.get("anilistId")))
        judulKita = {normal(x) for x in semuaJudul}
        judulKita |= {normal(tanpa_musim(x)) for x in semuaJudul}
        judulKita.discard("")
        judulTmdb = {normal(w.get("title")), normal(w.get("originalTitle")),
                     normal(tanpa_musim(w.get("title")))}
        judulTmdb.discard("")
        beririsan = bool(judulKita & judulTmdb)

        thKita, thTmdb = a.get("releaseYear"), w.get("year")
        tahunCocok = (
            isinstance(thKita, int) and isinstance(thTmdb, int)
            and abs(thKita - thTmdb) <= TOLERANSI_TAHUN
        )

        # Judul SAMA PERSIS sudah cukup. Kalau judulnya beda — lumrah untuk
        # anime, karena TMDB kerap memakai judul Inggris resmi sementara
        # AniList menyimpan romaji — maka tahun yang harus cocok. Keduanya
        # meleset berarti pemetaan itu tidak bisa dipertanggungjawabkan.
        if beririsan or tahunCocok:
            sah[idFilm] = {
                "tmdbId": acuan["tmdbId"],
                "type": acuan["type"],
                "lewat": "judul" if beririsan else "tahun",
            }
        else:
            gagal.append((idFilm, a.get("title"), w.get("title"), thKita, thTmdb))

    # --- Lintasan kedua: pewarisan dari musim yang sudah terbukti ---
    #
    # AniList memecah serial per musim, TMDB menyatukannya. Musim yang BELUM
    # TAYANG tidak punya judul Inggris di AniList (1.820 entri) dan tahunnya
    # tentu tidak cocok dengan tahun tayang perdana acaranya — mis. "Sousou no
    # Frieren 3rd Season" (2027) menunjuk ke "Frieren: Beyond Journey's End"
    # (2023). Lintasan pertama menolaknya padahal pemetaannya benar.
    #
    # Sinyal yang sah: identitas id TMDB itu SUDAH dibuktikan oleh musim lain
    # yang lolos lintasan pertama. Kandidat lain yang menunjuk id yang sama
    # mewarisi pembuktian itu. Ini bukan pelonggaran buta — hanya berlaku untuk
    # id yang sudah terverifikasi, dan dihitung terpisah agar terlihat.
    terbukti = {(v["type"], v["tmdbId"]) for v in sah.values()}
    warisan = 0
    masih_gagal = []
    for idFilm, judulKita_, judulTmdb_, thKita, thTmdb in gagal:
        acuan = kandidat.get(idFilm) or {}
        kunci = (acuan.get("type"), acuan.get("tmdbId"))
        if kunci in terbukti:
            sah[idFilm] = {"tmdbId": acuan["tmdbId"], "type": acuan["type"],
                           "lewat": "musim-saudara"}
            warisan += 1
        else:
            masih_gagal.append((idFilm, judulKita_, judulTmdb_, thKita, thTmdb))
    gagal = masih_gagal

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({"matches": sah}, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    total = len(kandidat)
    lewatJudul = sum(1 for v in sah.values() if v["lewat"] == "judul")
    print("[peta] %d/%d kandidat terverifikasi (%.1f%%)"
          % (len(sah), total, len(sah) * 100.0 / max(total, 1)))
    lewatTahun = sum(1 for v in sah.values() if v["lewat"] == "tahun")
    lewatSaudara = sum(1 for v in sah.values() if v["lewat"] == "musim-saudara")
    print("  judul sama persis: %d | tahun cocok: %d | warisan musim saudara: %d"
          % (lewatJudul, lewatTahun, lewatSaudara))
    print("  %d ditolak (judul DAN tahun meleset), %d belum terpanen"
          % (len(gagal), hilang))
    if gagal:
        print("  contoh yang ditolak:")
        for idFilm, k, t, a_, b_ in gagal[:5]:
            print("    %-22s %-30s vs %-30s (%s vs %s)"
                  % (idFilm, str(k)[:30], str(t)[:30], a_, b_))
    print("  -> %s" % KELUARAN)
    return 0


def panen_judul_semua():
    """
    Judul Inggris untuk SELURUH anime di katalog, bukan hanya kandidat TMDB.

    `--judul` hanya memanen id yang ikut dalam pemetaan TMDB (7.577 judul),
    padahal katalognya 13.094. Akibatnya terukur: 7.337 anime tidak punya
    judul Inggris sama sekali, dan karena anikai menamai judulnya dalam
    bahasa Inggris, judul-judul itu MUSTAHIL terjodohkan dengan lapisan
    server anime — berapa pun bagusnya aturan pencocokan.

    Hasilnya DIGABUNG, bukan menimpa: judul yang sudah ada dipertahankan,
    sehingga mode ini aman dijalankan ulang kapan saja.
    """
    anime = muat_anime()
    ids = sorted({int(x["anilistId"]) for x in anime if x.get("anilistId")})
    lama = {}
    if os.path.exists(SUMBER_JUDUL):
        try:
            with open(SUMBER_JUDUL, encoding="utf-8") as f:
                lama = json.load(f)
        except (OSError, json.JSONDecodeError):
            lama = {}
    # Yang dipanen ulang bukan yang "belum punya judul Inggris", melainkan yang
    # belum pernah dipanen dengan skema SEKARANG. Banyak anime memang tidak
    # punya judul Inggris resmi, jadi syarat lama membuat mereka dipanen
    # berulang kali tanpa hasil — sementara sinonimnya, yang justru berguna,
    # tidak pernah terambil.
    kurang = [i for i in ids if "synonyms" not in (lama.get(str(i)) or {})]
    print("[judul] katalog %d anime; %d sudah punya judul Inggris; "
          "%d akan dipanen" % (len(ids), len(ids) - len(kurang), len(kurang)))
    if not kurang:
        return 0

    baru = ambil_judul_anilist(kurang)
    lama.update(baru)
    sementara = SUMBER_JUDUL + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(lama, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, SUMBER_JUDUL)
    punya = sum(1 for v in lama.values() if v.get("english"))
    print("[judul] %d judul tersimpan (%d punya judul Inggris) -> %s"
          % (len(lama), punya, SUMBER_JUDUL))
    return 0


def main():
    if "--buat-daftar" in sys.argv:
        return buat_daftar()
    if "--judul-semua" in sys.argv:
        return panen_judul_semua()
    if "--judul" in sys.argv:
        return panen_judul()
    if "--verifikasi" in sys.argv:
        return verifikasi()
    print(__doc__)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
