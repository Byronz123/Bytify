# ==========================================
# Bytify\backend\scraper\build_series_catalog.py
# ==========================================
"""
series_database.json (hasil panen) -> series_catalog.json (ramping, di-git).

Sejajar dengan build_database.py untuk film, dengan tiga keputusan yang berbeda
dan sengaja:

1. **Serial terbit sebagai berkas sendiri, bukan digabung ke catalog.json.**
   Cloudflare Pages MENOLAK berkas di atas 25 MiB; katalog film sendiri sudah
   23,26 MiB. Puluhan ribu episode tidak akan muat di sisa ruangnya.
2. **Sinopsis TIDAK dipotong.** Pada film, sinopsis dipotong 150 karakter dan
   dilengkapi `enrichIfNeeded` di halaman detail. Jalur itu hanya bekerja untuk
   id berawalan "lk21-"; serial memakai siput situsnya sendiri, jadi sinopsis
   terpotong tidak akan pernah dilengkapi.
3. **Server alternatif per episode dibuang.** Pemutar memakai `episode.videoUrl`
   dengan `movie.sourceUrl` sebagai Referer; daftar `servers` hanya dipakai
   pemilih server tingkat film. Menyimpannya untuk tiap episode menggandakan
   ukuran berkas tanpa ada yang membacanya.
"""
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(__file__), "..", "data")
SUMBER = os.path.join(DIR_DATA, "series_database.json")
KELUARAN = os.path.join(DIR_DATA, "series_catalog.json")

# Sama dengan ambang di uji kontrak; disetel jauh di bawah 25 MiB.
BATAS_AMAN_MIB = 24


def ramping(serial):
    """Buang medan yang tidak dibaca aplikasi, pertahankan yang dipakai UI."""
    episodes = []
    for ep in serial.get("episodes") or []:
        video = (ep.get("videoUrl") or "").strip()
        if not video:
            continue
        episodes.append({
            "id": ep["id"],
            "title": ep.get("title") or f"Episode {ep.get('episode', 1)}",
            "season": int(ep.get("season") or 1),
            "episode": int(ep.get("episode") or 1),
            "videoUrl": video,
        })
    if not episodes:
        return None

    poster = (serial.get("posterUrl") or "").strip() or None
    genre = [g for g in (serial.get("genres") or []) if g]
    hasil = {
        "id": "series-" + serial["id"],
        "type": "series",
        "title": serial["title"],
        "overview": (serial.get("overview") or "").strip(),
        "posterUrl": poster,
        "releaseYear": serial.get("releaseYear"),
        "genres": genre,
        "sourceUrl": serial.get("sourceUrl"),
        "episodes": episodes,
    }
    nilai = serial.get("rating")
    if isinstance(nilai, (int, float)) and nilai > 0:
        hasil["rating"] = nilai
    return hasil


def main():
    if not os.path.exists(SUMBER):
        print(f"Sumber belum ada: {SUMBER}")
        return 1
    with open(SUMBER, encoding="utf-8") as f:
        mentah = json.load(f)

    keluar, dibuang = [], 0
    terlihat = set()
    for satu in mentah:
        if not satu.get("id") or not satu.get("title"):
            dibuang += 1
            continue
        if satu["id"] in terlihat:
            dibuang += 1
            continue
        terlihat.add(satu["id"])
        rapi = ramping(satu)
        if rapi is None:
            dibuang += 1
            continue
        keluar.append(rapi)

    keluar.sort(key=lambda s: ((s.get("releaseYear") or 0), s["title"]), reverse=True)
    muatan = {"movies": keluar, "sections": []}

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(muatan, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    byte = os.path.getsize(KELUARAN)
    mib = byte / 1024 / 1024
    episode = sum(len(s["episodes"]) for s in keluar)
    print(f"{len(keluar)} serial, {episode} episode, {mib:.2f} MiB -> {KELUARAN}")
    if dibuang:
        print(f"  {dibuang} entri dibuang (tanpa id/judul/episode, atau ganda)")
    if mib > BATAS_AMAN_MIB:
        print(f"  PERINGATAN: {mib:.2f} MiB melewati ambang aman {BATAS_AMAN_MIB} MiB")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
