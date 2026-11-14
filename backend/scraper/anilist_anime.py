# ==========================================
# Bytify\backend\scraper\anilist_anime.py
# ==========================================
"""
Pemanen katalog anime dari AniList.

**Kenapa AniList, bukan mengikis anikai.org.** anikai.org memang terbuka dan
dirender server, tetapi tautan tontonnya TIDAK ada di HTML — terukur nol
iframe, nol .m3u8, nol .mp4; pemutarnya dimuat JavaScript. Jadi yang bisa
diambil dari sana hanyalah metadata, persis masalah filmindonesia.or.id.

AniList memberi metadata yang lebih lengkap DAN memberi **AniList id**, yang
justru kunci pemutarnya: framextv memublikasikan rute /embed/anime/:anilistId
dan /watch/anime/:anilistId/:episode. Jadi satu sumber ini menyelesaikan
katalog sekaligus cara memutarnya — pola yang sama persis dengan TMDB untuk
film Indonesia, dan itu sudah terbukti berputar di Galaxy A36.

API-nya GraphQL publik, gratis, tanpa kunci. Batas lajunya 90 permintaan per
menit, dan satu kueri hanya memulangkan 5.000 judul; karena itu panen dipecah
per tahun supaya katalognya tidak terpotong di angka itu.
"""
import json
import os
import sys
import time

import requests

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

API = "https://graphql.anilist.co"
BERKAS_HASIL = os.path.join(
    os.path.dirname(__file__), "..", "data", "anime_database.json"
)

#: AniList memulangkan maksimal 50 per halaman, 5.000 per kueri.
PER_HALAMAN = 50

#: Jeda antar permintaan. Batasnya 90/menit; 0,8 detik memberi marjin aman.
JEDA = 0.8

KUERI = """
query ($page: Int, $perPage: Int, $tahun: Int) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage lastPage total }
    media(type: ANIME, seasonYear: $tahun, sort: POPULARITY_DESC, isAdult: false) {
      id
      title { romaji english }
      description(asHtml: false)
      episodes
      duration
      seasonYear
      format
      status
      averageScore
      genres
      coverImage { large }
    }
  }
}
"""

sesi = requests.Session()


def _tanya(variabel, percobaan=5):
    for ke in range(percobaan):
        try:
            r = sesi.post(API, json={"query": KUERI, "variables": variabel}, timeout=40)
            if r.status_code == 429:
                tunggu = float(r.headers.get("Retry-After", 60)) + 1
                print(f"    laju dibatasi, tunggu {tunggu:.0f} detik")
                time.sleep(tunggu)
                continue
            r.raise_for_status()
            d = r.json()
            if "errors" in d:
                return None
            return d["data"]["Page"]
        except Exception:
            if ke == percobaan - 1:
                return None
            time.sleep(2 * (ke + 1))
    return None


def panen_tahun(tahun):
    hasil, halaman = [], 1
    while True:
        p = _tanya({"page": halaman, "perPage": PER_HALAMAN, "tahun": tahun})
        time.sleep(JEDA)
        if not p:
            break
        hasil.extend(p["media"])
        if not p["pageInfo"]["hasNextPage"]:
            break
        halaman += 1
    return hasil


def panen(berkas=BERKAS_HASIL):
    tahun_kini = time.gmtime().tm_year
    semua = {}
    for tahun in range(1960, tahun_kini + 3):
        isi = panen_tahun(tahun)
        if not isi:
            continue
        for m in isi:
            semua[m["id"]] = m
        print(f"  {tahun}: +{len(isi):<4} total {len(semua)}")

    keluar = []
    for m in semua.values():
        judul = (m["title"].get("english") or m["title"].get("romaji") or "").strip()
        if not judul:
            continue
        sinopsis = (m.get("description") or "")
        # AniList menyelipkan <br> dan <i> walau diminta tanpa HTML.
        sinopsis = (sinopsis.replace("<br>", " ").replace("<br/>", " ")
                    .replace("<i>", "").replace("</i>", "")
                    .replace("<b>", "").replace("</b>", "").strip())
        nilai = m.get("averageScore")
        keluar.append({
            "id": f"anilist-{m['id']}",
            "anilistId": m["id"],
            "type": "series",
            "title": judul,
            "originalTitle": (m["title"].get("romaji") or "").strip() or None,
            "overview": sinopsis,
            "posterUrl": (m.get("coverImage") or {}).get("large"),
            "releaseYear": m.get("seasonYear"),
            "episodeCount": m.get("episodes"),
            "durationMin": m.get("duration"),
            "format": m.get("format"),
            "status": m.get("status"),
            # AniList memakai skala 0-100; disamakan ke 0-10 seperti katalog lain.
            "rating": round(nilai / 10.0, 1) if isinstance(nilai, int) and nilai > 0 else None,
            "genres": list(dict.fromkeys(m.get("genres") or [])),
            "sourceUrl": f"https://anilist.co/anime/{m['id']}",
        })

    keluar.sort(key=lambda x: ((x.get("releaseYear") or 0), x["title"]), reverse=True)
    sementara = berkas + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(keluar, f, ensure_ascii=False)
    os.replace(sementara, berkas)

    mib = os.path.getsize(berkas) / 1024 / 1024
    print(f"[anime] selesai: {len(keluar)} judul, {mib:.2f} MiB -> {berkas}")
    return keluar


if __name__ == "__main__":
    panen()
