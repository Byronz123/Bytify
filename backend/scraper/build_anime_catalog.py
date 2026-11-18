# ==========================================
# Bytify\backend\scraper\build_anime_catalog.py
# ==========================================
"""
anikai_database.json -> anime_catalog.json (ramping, di-git).

**Katalog anime sepenuhnya milik anikai.** AniList tidak lagi dipakai, baik
sebagai sumber metadata maupun sebagai ruang id. Alasannya terukur:

- Pemutar berkunci AniList id (embed framextv) tidak bisa dipakai: keempat
  "cermin"-nya satu server yang sama dan backend-nya membalas HTTP 429.
- Selama katalog berid "anilist-", judul yang anikai punya tetapi AniList
  tidak kenal tidak bisa diterbitkan sama sekali — 1.466 judul yang
  sebenarnya bisa diputar ikut terbuang hanya karena penamaan.

Metadata diambil dari blok `application/ld+json` halaman anikai, yang membawa
`name`, `alternateName`, `description`, `genre`, `image`, dan `datePublished`.
Itu metadata ASLI milik situsnya — berbeda dari keluaran `scrape_anikai.js`
yang dilarang 3f, yang rating dan tahunnya konstanta buatan.

Yang TIDAK tersedia dari anikai, dan karena itu tidak diterbitkan: skor
rating. Jangan mengisinya dengan angka tebakan.

Jembatan AniList masih dipakai di `build_anime_servers.py`, tetapi hanya
sebagai PENGAYA untuk mendapatkan TMDB id (subtitle) — bukan sebagai penyaring
dan bukan sebagai id katalog.
"""
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
SUMBER = os.path.join(DIR_DATA, "anikai_database.json")
KELUARAN = os.path.join(DIR_DATA, "anime_catalog.json")

#: Pemutar anime. Polanya dibaca dari kode situs anikai sendiri (js/main.js)
#: dan harus sama persis dengan yang diterbitkan di anime_servers.json.
PEMUTAR = "https://play.anikai.org"
POLA = "{player}/watch#{seal}.{episode}.{server}.{audio}"
AUDIO = "sub"

#: Episode yang disimpan per judul. Sisanya dilengkapi aplikasi dari
#: `episodeCount`, karena URL tiap episode hanya menyisipkan nomornya.
#: 26 menutup anime dua-cour secara utuh.
BATAS_EPISODE = 14

#: Sinopsis dipotong. Anime tidak punya jalur `enrichIfNeeded` (itu hanya
#: untuk id "lk21-"), jadi pemotongan ini PERMANEN.
PANJANG_SINOPSIS = 400

BATAS_AMAN_MIB = 24


def _potong(teks):
    if not teks or len(teks) <= PANJANG_SINOPSIS:
        return teks or ""
    potong = teks[:PANJANG_SINOPSIS].rsplit(" ", 1)[0]
    return potong.rstrip(".,; ") + "..."


def ramping(a):
    aid = a.get("anikaiId")
    judul = (a.get("title") or "").strip()
    seal = a.get("seal")
    server = a.get("server")
    # `server == 0` berarti situsnya sendiri menyatakan judul ini belum punya
    # sumber. Menerbitkannya berarti mengisi katalog dengan judul yang bisa
    # dibuka tetapi tidak bisa ditonton.
    if not aid or not judul or not seal or not server:
        return None

    nomor = a.get("episodes") or [1]
    jumlah = len(nomor)
    disimpan = nomor[:BATAS_EPISODE]

    def alamat(n):
        return POLA.format(player=PEMUTAR, seal=seal, episode=n,
                           server=server, audio=AUDIO)

    episodes = [{
        "id": f"anikai-{aid}-e{n}",
        "title": f"Episode {n}",
        "season": 1,
        "episode": n,
        "videoUrl": alamat(n),
    } for n in disimpan]

    hasil = {
        "id": f"anikai-{aid}",
        "type": "series",
        # Jumlah episode SEBENARNYA menurut anikai, bukan yang tersimpan.
        "episodeCount": jumlah,
        "title": judul,
        "overview": _potong((a.get("overview") or "").strip()),
        "posterUrl": a.get("poster"),
        "releaseYear": a.get("year"),
        "durationMin": a.get("durationMin"),
        "genres": list(dict.fromkeys(a.get("genres") or [])),
        "language": "ja",
        "videoUrl": episodes[0]["videoUrl"],
        # Referer: alamat pemutarnya sendiri, sama seperti embed pihak ketiga
        # lain. Pemutar dimuat LANGSUNG, tanpa pembungkus iframe.
        "sourceUrl": episodes[0]["videoUrl"],
        "servers": [{"server": "anikai", "url": episodes[0]["videoUrl"]}],
        "episodes": episodes,
    }
    return hasil


def main():
    if not os.path.exists(SUMBER):
        print(f"Sumber belum ada: {SUMBER}. Jalankan anikai_servers.py dulu.")
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

    keluar.sort(key=lambda x: ((x.get("releaseYear") or 0), x["title"]), reverse=True)

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({"movies": keluar, "sections": []}, f,
                  ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    byte = os.path.getsize(KELUARAN)
    mib = byte / 1024 / 1024
    episode = sum(len(x["episodes"]) for x in keluar)
    total = sum(x["episodeCount"] for x in keluar)
    tanpa_poster = sum(1 for x in keluar if not x["posterUrl"])
    print(f"{len(keluar)} anime, {episode} episode tersimpan "
          f"({total} sebenarnya), {mib:.2f} MiB -> {KELUARAN}")
    if dibuang:
        print(f"  {dibuang} entri dibuang (tanpa seal/judul, atau ganda)")
    if tanpa_poster:
        print(f"  {tanpa_poster} entri tanpa poster")
    if mib > BATAS_AMAN_MIB:
        print(f"  PERINGATAN: {mib:.2f} MiB melewati ambang aman {BATAS_AMAN_MIB} MiB. "
              f"Turunkan BATAS_EPISODE.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
