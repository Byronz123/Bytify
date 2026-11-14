# ==========================================
# Bytify\backend\scraper\build_indonesia_catalog.py
# ==========================================
"""
tmdb_indonesia_database.json -> indonesia_catalog.json (ramping, di-git).

**Kenapa berkas terpisah, bukan digabung ke catalog.json.** catalog.json sudah
23,26 MiB sementara Cloudflare Pages MENOLAK berkas di atas 25 MiB. Menambah
7.839 film ke dalamnya akan melewati batas itu. Pola yang sama sudah dipakai
serial (series_catalog.json), dan aplikasi memuat keduanya ke tabel Room yang
sama.

**Kenapa framextv ditambahkan sebagai SERVER, bukan mengganti LK21.** Terukur
di Galaxy A36: embed framextv memang berputar (The Raid 2 terbukti), tetapi
tidak setiap judul punya sumber — embednya sendiri melaporkan "No server could
play this title" pada film 2026 yang obscure. Mengganti LK21 berarti menukar
25.186 film yang tautannya tersimpan dan terbukti dengan katalog yang
ketersediaannya baru diketahui saat tombol putar ditekan. Jadi keduanya
digabung: film ini memakai framextv, film LK21 tetap memakai servernya sendiri,
dan film yang ada di dua-duanya nanti punya dua pilihan server.
"""
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
SUMBER = os.path.join(DIR_DATA, "tmdb_indonesia_database.json")
KATALOG_LK21 = os.path.join(DIR_DATA, "catalog.json")
KELUARAN = os.path.join(DIR_DATA, "indonesia_catalog.json")

#: Cermin embed framextv. Yang pertama dipakai; sisanya tercatat sebagai
#: pilihan server supaya satu cermin tumbang tidak mematikan seluruh katalog.
CERMIN = ("https://framextv.xyz", "https://framextv.cc",
          "https://framextv.tech", "https://framextv.pro")

BATAS_AMAN_MIB = 24

#: Sinopsis dibiarkan utuh: film ini TIDAK punya jalur enrichIfNeeded seperti
#: film LK21 (yang id-nya berawalan "lk21-"), jadi potongan tidak akan pernah
#: dilengkapi di halaman detail.


def embed(tmdb_id, basis):
    return f"{basis}/embed/movie/{tmdb_id}"


def ramping(f):
    tmdb = f.get("tmdbId")
    if not tmdb or not (f.get("title") or "").strip():
        return None
    server = [{"server": "framextv", "url": embed(tmdb, CERMIN[0])}]
    # Cermin lain jadi pilihan cadangan di menu server aplikasi.
    for i, basis in enumerate(CERMIN[1:], start=2):
        server.append({"server": f"framextv-{i}", "url": embed(tmdb, basis)})

    hasil = {
        "id": f["id"],
        "type": "movie",
        "title": f["title"],
        "overview": (f.get("overview") or "").strip(),
        "posterUrl": f.get("posterUrl"),
        "releaseYear": f.get("releaseYear"),
        # TMDB sesekali memulangkan genre_ids kembar, sehingga daftar genre
        # berisi nama yang sama dua kali. Itu membuat film yang sama masuk dua
        # kali ke satu deret genre di aplikasi, dan Compose menolak dua item
        # berkunci sama -> aplikasi mati. Disaring di sini.
        "genres": list(dict.fromkeys(f.get("genres") or [])),
        "countries": ["Indonesia"],
        "language": "id",
        "videoUrl": server[0]["url"],
        # Referer: halaman embed itu sendiri, sama seperti film LK21 memakai
        # halaman filmnya. Tanpa Referer yang benar, embed mengalihkan keluar.
        "sourceUrl": server[0]["url"],
        "servers": server,
    }
    nilai = f.get("rating")
    if isinstance(nilai, (int, float)) and nilai > 0:
        hasil["rating"] = nilai
    return hasil


def _kunci(judul, tahun):
    t = (judul or "").lower()
    t = re.sub(r"\(.*?\)", " ", t)
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return (" ".join(t.split()), tahun)


def peta_lk21():
    """
    Film LK21 menurut judul+tahun, untuk digabung alih-alih diduplikasi.

    Terukur hanya 24 judul yang tumpang tindih dari 33 ribu entri — LK21 memang
    nyaris tidak memuat film Indonesia. Tetapi justru 24 itu yang paling
    berharga: mereka bisa punya DUA set server sekaligus, sehingga kalau embed
    framextv tidak menemukan sumber, server LK21 masih ada di menu yang sama.
    """
    if not os.path.exists(KATALOG_LK21):
        return {}
    with open(KATALOG_LK21, encoding="utf-8") as f:
        kat = json.load(f).get("movies", [])
    peta = {}
    for m in kat:
        peta.setdefault(_kunci(m.get("title"), m.get("releaseYear")), m)
    return peta


def main():
    if not os.path.exists(SUMBER):
        print(f"Sumber belum ada: {SUMBER}. Jalankan tmdb_indonesia.py dulu.")
        return 1
    with open(SUMBER, encoding="utf-8") as f:
        mentah = json.load(f)

    lk21 = peta_lk21()
    keluar, dibuang, terlihat, digabung = [], 0, set(), 0
    for satu in mentah:
        rapi = ramping(satu)
        if rapi is None:
            dibuang += 1
            continue
        kembar = lk21.get(_kunci(rapi["title"], rapi.get("releaseYear")))
        if kembar:
            # Terbitkan di bawah id LK21 dengan KEDUA set server. Upsert di
            # aplikasi memakai id sebagai kunci, jadi entri ini menggantikan
            # baris LK21-nya — karena itu medan aslinya harus ikut dibawa,
            # bukan hanya ditimpa dengan data TMDB.
            gabung = dict(kembar)
            gabung["servers"] = (kembar.get("servers") or []) + rapi["servers"]
            gabung["countries"] = ["Indonesia"]
            if not (gabung.get("overview") or "").strip():
                gabung["overview"] = rapi["overview"]
            if not gabung.get("posterUrl"):
                gabung["posterUrl"] = rapi.get("posterUrl")
            rapi = gabung
            digabung += 1
        if rapi["id"] in terlihat:
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

    mib = os.path.getsize(KELUARAN) / 1024 / 1024
    print(f"{len(keluar)} film Indonesia, {mib:.2f} MiB -> {KELUARAN}")
    if digabung:
        print(f"  {digabung} film digabung dengan entri LK21 (dua set server)")
    if dibuang:
        print(f"  {dibuang} entri dibuang (tanpa TMDB id/judul, atau ganda)")
    if mib > BATAS_AMAN_MIB:
        print(f"  PERINGATAN: {mib:.2f} MiB melewati ambang aman {BATAS_AMAN_MIB} MiB")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
