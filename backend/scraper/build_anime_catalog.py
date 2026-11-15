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

CERMIN = ("https://framextv.xyz", "https://framextv.cc",
          "https://framextv.tech", "https://framextv.pro")

#: Episode yang disimpan per judul. Dinaikkan kalau berkasnya masih jauh di
#: bawah ambang; diturunkan kalau mendekat.
BATAS_EPISODE = 8

#: Potong sinopsis anime. Tak ada enrichIfNeeded untuk id anilist-, jadi ini
#: permanen; 400 karakter menyisakan sinopsis yang utuh maknanya.
PANJANG_SINOPSIS = 400

BATAS_AMAN_MIB = 24


def _potong(teks):
    if len(teks) <= PANJANG_SINOPSIS:
        return teks
    potong = teks[:PANJANG_SINOPSIS].rsplit(" ", 1)[0]
    return potong.rstrip(".,; ") + "..."


def ramping(a):
    aid = a.get("anilistId")
    judul = (a.get("title") or "").strip()
    if not aid or not judul:
        return None

    jumlah = a.get("episodeCount")
    if not isinstance(jumlah, int) or jumlah < 1:
        # Judul yang jumlah episodenya belum diketahui (masih tayang) tetap
        # bisa dibuka; satu episode cukup untuk masuk ke pemutar.
        jumlah = 1
    disimpan = min(jumlah, BATAS_EPISODE)

    episodes = [{
        "id": f"anilist-{aid}-e{n}",
        "title": f"Episode {n}",
        "season": 1,
        "episode": n,
        "videoUrl": f"{CERMIN[0]}/embed/anime/{aid}/{n}",
    } for n in range(1, disimpan + 1)]

    server = [{"server": "framextv", "url": f"{CERMIN[0]}/embed/anime/{aid}"}]
    for i, basis in enumerate(CERMIN[1:], start=2):
        server.append({"server": f"framextv-{i}", "url": f"{basis}/embed/anime/{aid}"})

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

    keluar, dibuang, terlihat = [], 0, set()
    for satu in mentah:
        rapi = ramping(satu)
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
