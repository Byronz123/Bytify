# ==========================================
# Bytify\backend\scraper\build_watch_catalog.py
# ==========================================
"""
watch_database.json -> watch_catalog.json (ramping, di-git).

Lapisan KETERSEDIAAN RESMI: tiap judul membawa daftar penyedia sah di Indonesia
(Netflix, Prime Video, Vidio, Viu, Disney+ Hotstar, Catchplay, Bstation, dll)
dipecah per jenis langganan. Tidak ada URL stream di sini — yang disimpan adalah
tautan resmi ke halaman tonton, dan aplikasi membukanya di luar lewat intent.

Dua hal yang membentuk berkas ini:

1. **Hanya judul yang BENAR punya ketersediaan** yang masuk. Judul tanpa
   penyedia sah di Indonesia tidak ada gunanya di lapisan ini — mereka cuma
   menambah ukuran berkas dan baris tabel.

2. **Kamus penyedia dipisah**, dan tiap judul hanya menyimpan id penyedianya.
   Mengulang "Amazon Prime Video" di puluhan ribu entri jauh lebih mahal
   daripada satu kamus 46 baris. Kamus juga dipangkas: hanya penyedia yang
   betul-betul dirujuk ikut terbit.

Sinopsis dipotong [PANJANG_SINOPSIS]. BERBEDA dari film lk21: id di sini
berawalan "tmdb-", yang TIDAK punya jalur enrichIfNeeded (itu hanya untuk
"lk21-"), jadi pemotongan ini permanen — sama seperti anime.
"""
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
SUMBER = os.path.join(DIR_DATA, "watch_database.json")
KELUARAN = os.path.join(DIR_DATA, "watch_catalog.json")

#: Jembatan id katalog -> TMDB id. DUA sumber, dengan cara yang berbeda:
#:   - tmdb_match.json     film LK21, lewat pencocokan judul+tahun yang ketat
#:   - anime_tmdb_map.json anime, lewat dataset pemetaan publik yang sudah
#:                         diverifikasi ulang terhadap judul dari TMDB
#: Keduanya opsional: kalau belum ada, berkasnya terbit tanpa alias dan
#: lapisan ini tetap jalan untuk judul yang TMDB id-nya sudah ada di dalam
#: id-nya sendiri ("tmdb-<id>").
SUMBER_ALIAS = (
    os.path.join(DIR_DATA, "tmdb_match.json"),
    os.path.join(DIR_DATA, "anime_tmdb_map.json"),
)

#: Jenis langganan yang dipertahankan, urut dari yang paling berguna bagi
#: pengguna: termasuk langganan, gratis, gratis beriklan, sewa, beli.
JENIS = ("flatrate", "free", "ads", "rent", "buy")

PANJANG_SINOPSIS = 300
BATAS_AMAN_MIB = 24


def _potong(teks):
    if not teks or len(teks) <= PANJANG_SINOPSIS:
        return teks
    potong = teks[:PANJANG_SINOPSIS].rsplit(" ", 1)[0]
    return potong.rstrip(".,; ") + "..."


def ramping(t, dikenal=None):
    """
    [dikenal] = himpunan id penyedia yang ada namanya di kamus.

    Id yang tidak dikenal DIBUANG, bukan diterbitkan: chip tanpa nama lebih
    buruk daripada chip yang tidak ada. Ini terukur — daftar `watch_region=ID`
    hanya memuat 46 penyedia sementara ketersediaan judul bisa menyebut
    penyedia di luar daftar itu (id 3 Google Play Movies, id 2782 Filmtap).
    Kamus sekarang ditambal dari daftar global, jadi penyaringan ini mestinya
    tidak membuang apa pun; ia ada supaya bolong berikutnya tidak lolos.
    """
    w = t.get("watch") or {}
    tersedia = {}
    for j in JENIS:
        ids = w.get(j) or []
        if dikenal is not None:
            ids = [i for i in ids if str(i) in dikenal]
        if ids:
            tersedia[j] = ids
    if not tersedia:
        return None
    if not t.get("id") or not t.get("title"):
        return None

    hasil = {
        "id": t["id"],
        "tmdbId": t.get("tmdbId"),
        "type": t.get("type"),
        "title": t["title"],
        "posterUrl": t.get("posterUrl"),
        "year": t.get("year"),
        "overview": _potong(t.get("overview")),
        "genres": list(dict.fromkeys(t.get("genres") or [])),
        "watch": tersedia,
    }
    if w.get("link"):
        hasil["watchLink"] = w["link"]
    for opsional in ("rating", "runtimeMin", "imdbId", "language", "originalTitle"):
        if t.get(opsional):
            hasil[opsional] = t[opsional]
    return {k: v for k, v in hasil.items() if v not in (None, [], {})}


def muat_alias():
    """
    Alias dipakai DUA lapisan: ketersediaan resmi dan subtitle. Karena itu
    SELURUH hasil cocok diterbitkan, bukan hanya yang punya ketersediaan —
    sebuah judul bisa tidak ada di layanan sah mana pun tapi tetap punya
    subtitle, dan sebaliknya.
    """
    keluar = {}
    for jalur in SUMBER_ALIAS:
        if not os.path.exists(jalur):
            continue
        try:
            with open(jalur, encoding="utf-8") as f:
                d = json.load(f)
        except Exception:
            continue
        for idFilm, acuan in (d.get("matches") or {}).items():
            tid, jenis = acuan.get("tmdbId"), acuan.get("type")
            if isinstance(tid, int) and tid > 0 and jenis in ("movie", "tv"):
                # Satu id katalog tidak boleh menunjuk dua TMDB id. Sumbernya
                # terpisah (lk21- vs anilist-), jadi bentrokan berarti ada yang
                # salah — yang pertama dipertahankan dan bentrokannya dihitung.
                if idFilm in keluar and keluar[idFilm]["tmdbId"] != tid:
                    continue
                keluar[idFilm] = {"tmdbId": tid, "type": jenis}
    return keluar


def main():
    if not os.path.exists(SUMBER):
        print("Sumber belum ada: %s. Jalankan tmdb_watch.py dulu." % SUMBER)
        return 1
    with open(SUMBER, encoding="utf-8") as f:
        mentah = json.load(f)

    judul = mentah.get("titles") or []
    penyedia_semua = mentah.get("providers") or {}

    dikenal = set(penyedia_semua)
    keluar, lihat, tanpa = [], set(), 0
    for satu in judul:
        rapi = ramping(satu, dikenal)
        if rapi is None:
            tanpa += 1
            continue
        if rapi["id"] in lihat:
            continue
        lihat.add(rapi["id"])
        keluar.append(rapi)

    keluar.sort(key=lambda x: x["id"])

    # Pangkas kamus penyedia: hanya yang dirujuk.
    dipakai = set()
    for x in keluar:
        for j in JENIS:
            for pid in x["watch"].get(j, []):
                dipakai.add(str(pid))
    penyedia = {k: v for k, v in penyedia_semua.items() if k in dipakai}

    alias = muat_alias()

    sementara = KELUARAN + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump({
            "attribution": mentah.get("attribution"),
            "region": mentah.get("region", "ID"),
            "providers": penyedia,
            "titles": keluar,
            "aliases": alias,
        }, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, KELUARAN)

    mib = os.path.getsize(KELUARAN) / 1024 / 1024
    hitung = {}
    for j in JENIS:
        hitung[j] = sum(1 for x in keluar if x["watch"].get(j))
    print("%d judul tersedia resmi, %d penyedia dirujuk, %.2f MiB -> %s"
          % (len(keluar), len(penyedia), mib, KELUARAN))
    print("  per jenis: " + ", ".join("%s=%d" % (j, hitung[j]) for j in JENIS))
    print("  %d judul dilewati (tanpa penyedia sah di Indonesia)" % tanpa)
    if alias:
        import collections as _c
        asal = _c.Counter(k.split("-")[0] for k in alias)
        print("  alias per sumber: " + ", ".join("%s=%d" % kv for kv in asal.most_common()))
        cocok_tersedia = sum(
            1 for v in alias.values()
            if ("tmdb-%s-%d" % (v["type"], v["tmdbId"])) in lihat
        )
        print("  %d alias id katalog -> TMDB id; %d di antaranya punya ketersediaan"
              % (len(alias), cocok_tersedia))
    dirujuk_mentah = set()
    for satu in judul:
        for j in JENIS:
            for pid in (satu.get("watch") or {}).get(j) or []:
                dirujuk_mentah.add(str(pid))
    tak_dikenal = dirujuk_mentah - dikenal
    if tak_dikenal:
        print("  PERINGATAN: %d id penyedia dibuang karena tidak ada di kamus: %s"
              % (len(tak_dikenal), ", ".join(sorted(tak_dikenal))))
    teratas = sorted(penyedia.items(), key=lambda kv: -sum(
        1 for x in keluar for j in JENIS if int(kv[0]) in x["watch"].get(j, [])))[:10]
    for pid, info in teratas:
        n = sum(1 for x in keluar for j in JENIS if int(pid) in x["watch"].get(j, []))
        print("    %-26s %d judul" % (info.get("name", pid), n))
    if mib > BATAS_AMAN_MIB:
        print("  PERINGATAN: %.2f MiB melewati ambang aman %d MiB. "
              "Turunkan PANJANG_SINOPSIS." % (mib, BATAS_AMAN_MIB))
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
