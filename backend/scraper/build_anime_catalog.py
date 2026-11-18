# ==========================================
# Bytify\backend\scraper\build_anime_catalog.py
# ==========================================
"""
anime_database.json (AniList) -> anime_catalog.json (ramping, di-git).

Dua batasan yang membentuk berkas ini:

1. **Batas 25 MiB per berkas di Cloudflare Pages.** Karena itu anime terbit
   sebagai berkas sendiri, bukan ditambahkan ke catalog.json yang sudah
   23,26 MiB — pola yang sama dengan serial dan film Indonesia.

2. **Episode adalah penyumbang ukuran terbesar.** Satu entri episode sekitar
   120 byte; judul sepanjang Hunter x Hunter (148 episode) atau One Piece
   (ribuan) bisa sendirian memakan ratusan kilobyte. Karena itu jumlah episode
   yang disimpan DIBATASI [BATAS_EPISODE]. URL-nya deterministik
   (/embed/anime/<id>/<n>), jadi pembatasan ini hanya memotong daftar yang
   tampil, bukan merusak pemutaran judul lain.

URL pemutar memakai rute embed anime yang DIPUBLIKASIKAN framextv:
/embed/anime/:anilistId dan /embed/anime/:anilistId/:episode.
"""
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
SUMBER = os.path.join(DIR_DATA, "anime_database.json")
KELUARAN = os.path.join(DIR_DATA, "anime_catalog.json")

LAPISAN_SERVER = os.path.join(DIR_DATA, "anime_servers.json")

#: Pemutar anime. Polanya sama persis dengan yang diterbitkan di
#: anime_servers.json, dan keduanya dibaca dari kode situs anikai sendiri.
PEMUTAR_ANIKAI = "https://play.anikai.org"
POLA_ANIKAI = "{player}/watch#{seal}.{episode}.{server}.{audio}"

#: Episode yang disimpan per judul. Dinaikkan kalau berkasnya masih jauh di
#: bawah ambang; diturunkan kalau mendekat.
BATAS_EPISODE = 26

#: Potong sinopsis anime. Tak ada enrichIfNeeded untuk id anilist-, jadi ini
#: permanen; 400 karakter menyisakan sinopsis yang utuh maknanya.
PANJANG_SINOPSIS = 400

BATAS_AMAN_MIB = 24


def _potong(teks):
    if len(teks) <= PANJANG_SINOPSIS:
        return teks
    potong = teks[:PANJANG_SINOPSIS].rsplit(" ", 1)[0]
    return potong.rstrip(".,; ") + "..."


def ramping(a, lapisan):
    aid = a.get("anilistId")
    judul = (a.get("title") or "").strip()
    if not aid or not judul:
        return None

    # Hanya anime yang BISA DITONTON yang diterbitkan.
    #
    # Pemutar anime dulu embed framextv berkunci AniList id, dan itu terbukti
    # tidak bisa dipakai: keempat "cermin"-nya satu server yang sama (semua
    # dialihkan ke framextv.cc, backend api.framextv.tech membalas HTTP 429),
    # dan dua anime yang diuji di Galaxy A36 sama-sama gagal sampai ambang
    # waktu habis. Menerbitkan judul yang hanya punya server itu berarti
    # mengisi katalog dengan judul yang bisa dibuka tetapi tidak bisa ditonton
    # — lebih buruk daripada tidak menampilkannya sama sekali.
    #
    # AniList TETAP dipakai, tetapi hanya sebagai sumber METADATA (judul,
    # poster, sinopsis, genre, jumlah episode). Metadata anikai sendiri tidak
    # bisa dipakai menurut 3f: rating "8.2" dan tahun "2026" konstanta.
    kunci = lapisan.get(str(aid))
    if not kunci:
        return None

    jumlah = a.get("episodeCount")
    if not isinstance(jumlah, int):
        jumlah = 0
    # Jumlah menurut ANIKAI menang kalau lebih banyak, karena anikai yang
    # memutarnya. AniList memulangkan episodeCount kosong untuk serial yang
    # masih berjalan — terukur: One Piece 0 di AniList, 1.180 di anikai, dan
    # 84 judul seperti itu kehilangan 10.156 episode kalau angka AniList yang
    # dipakai.
    jumlah = max(jumlah, int(kunci.get("eps") or 0))
    if jumlah < 1:
        # Judul yang jumlah episodenya belum diketahui (masih tayang) tetap
        # bisa dibuka; satu episode cukup untuk masuk ke pemutar.
        jumlah = 1
    disimpan = min(jumlah, BATAS_EPISODE)

    def alamat(n):
        return POLA_ANIKAI.format(
            player=PEMUTAR_ANIKAI, seal=kunci["seal"], episode=n,
            server=kunci["server"], audio="sub",
        )

    episodes = [{
        "id": f"anilist-{aid}-e{n}",
        "title": f"Episode {n}",
        "season": 1,
        "episode": n,
        "videoUrl": alamat(n),
    } for n in range(1, disimpan + 1)]

    server = [{"server": "anikai", "url": alamat(1)}]

    hasil = {
        "id": a["id"],
        "type": "series",
        # Jumlah episode SEBENARNYA, bukan yang tersimpan.
        #
        # Daftar di bawah tetap dipotong BATAS_EPISODE supaya berkas ini muat
        # di batas keras 25 MiB, tetapi aplikasi MELENGKAPI sisanya sendiri:
        # URL episode deterministik (.../embed/anime/<id>/<n>), jadi menyimpan
        # satu baris per episode hanya menyalin ulang sesuatu yang bisa
        # dihitung. Tanpa angka ini, Jujutsu Kaisen tampil 8 episode padahal
        # aslinya 24 — terukur, dan berlaku untuk 6.297 judul lain.
        "episodeCount": jumlah,
        "title": judul,
        "overview": _potong((a.get("overview") or "").strip()),
        "posterUrl": a.get("posterUrl"),
        "releaseYear": a.get("releaseYear"),
        "durationMin": a.get("durationMin"),
        "genres": list(dict.fromkeys(a.get("genres") or [])),
        "language": "ja",
        "videoUrl": episodes[0]["videoUrl"],
        "sourceUrl": server[0]["url"],
        "servers": server,
        "episodes": episodes,
    }
    if isinstance(a.get("rating"), (int, float)) and a["rating"] > 0:
        hasil["rating"] = a["rating"]
    return hasil


def main():
    if not os.path.exists(SUMBER):
        print(f"Sumber belum ada: {SUMBER}. Jalankan anilist_anime.py dulu.")
        return 1
    with open(SUMBER, encoding="utf-8") as f:
        mentah = json.load(f)

    if not os.path.exists(LAPISAN_SERVER):
        print(f"Lapisan server belum ada: {LAPISAN_SERVER}. "
              f"Jalankan anikai_servers.py lalu build_anime_servers.py dulu.")
        return 1
    with open(LAPISAN_SERVER, encoding="utf-8") as f:
        lapisan = (json.load(f).get("titles") or {})

    keluar, dibuang, terlihat = [], 0, set()
    for satu in mentah:
        rapi = ramping(satu, lapisan)
        if rapi is None or rapi["id"] in terlihat:
            dibuang += 1
            continue
        terlihat.add(rapi["id"])
        keluar.append(rapi)

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({"movies": keluar, "sections": []}, f,
                  ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    byte = os.path.getsize(KELUARAN)
    mib = byte / 1024 / 1024
    episode = sum(len(x["episodes"]) for x in keluar)
    print(f"{len(keluar)} anime, {episode} episode, {mib:.2f} MiB -> {KELUARAN}")
    if dibuang:
        print(f"  {dibuang} entri dibuang (tanpa id/judul, atau ganda)")
    if mib > BATAS_AMAN_MIB:
        print(f"  PERINGATAN: {mib:.2f} MiB melewati ambang aman {BATAS_AMAN_MIB} MiB. "
              f"Turunkan BATAS_EPISODE.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
