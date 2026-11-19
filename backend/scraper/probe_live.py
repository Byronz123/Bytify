# ==========================================
# Bytify\backend\scraper\probe_live.py
# ==========================================
"""
Memeriksa kanal Live TV mana yang stream-nya BENAR-BENAR hidup.

**Kenapa alat ini ada.** `isAvailable` selama ini selalu `true` dari server,
sehingga status yang tampil di aplikasi hanya menandakan KESEGARAN DATA, bukan
hasil pengecekan stream. iptv-org memulangkan daftar URL apa adanya; sebagian
origin-nya sudah mati bertahun-tahun, dan penonton baru tahu setelah menekan
kanalnya dan menunggu pemutar gagal.

**Apa yang diukur, dan apa yang TIDAK.** Yang diukur: alamatnya menjawab, dan
yang dijawabnya memang playlist HLS atau berkas media. Yang TIDAK diukur:
apakah videonya benar-benar bisa didekode, apakah segmennya ada, dan apakah
kanal itu bisa diakses dari negara penonton. Jadi ini syarat PERLU, bukan
syarat cukup — sama seperti `probe_servers.py`.

**Kenapa sepotong badan ikut dibaca, bukan cuma kode status.** Banyak CDN
memulangkan 200 untuk halaman galat, halaman "domain for sale", atau pengalih
berisi HTML. Memeriksa `#EXTM3U` di awal badan memisahkan playlist sungguhan
dari 200 yang kosong isinya. Permintaannya tetap kecil: `Range: bytes=0-2047`,
dan koneksinya ditutup begitu potongan itu terbaca.

**HEAD tidak dipakai.** Terukur pada contoh 200 kanal: banyak origin HLS
menjawab 405 atau 403 untuk HEAD padahal GET-nya normal, jadi HEAD akan
menandai kanal sehat sebagai mati.

Hasilnya ditulis BALIK ke `live_tv_database.json` sebagai field `alive`,
`checkedAtEpochMs`, dan `probeStatus`. Tidak ada berkas baru, tidak ada tabel
Room baru: `toLiveChannel` di `backend/api/server.mjs` membacanya saat terbit.

Pemakaian:
    python probe_live.py                # seluruh kanal
    python probe_live.py --jumlah 200   # contoh kecil, untuk memeriksa alat
    python probe_live.py --hanya-baru   # lewati yang sudah punya hasil
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR_DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
BERKAS = os.path.join(DIR_DATA, "live_tv_database.json")

UTAS = 48
BATAS_DETIK = 8
POTONG = 2048

#: User-Agent menyebut nama aplikasi dan repositorinya — etiket API biasa,
#: sama seperti yang diwajibkan OpenSubtitles dan AniList (3i). BUKAN menyamar
#: sebagai peramban.
UA = "Bytify/1.3 (+https://github.com/Byronz123/Bytify)"

#: Awalan badan yang menandakan playlist HLS sungguhan.
TANDA_HLS = b"#EXTM3U"

#: Tipe isi yang diterima sebagai media, untuk kanal yang bukan HLS.
TIPE_MEDIA = ("video/", "audio/", "application/vnd.apple.mpegurl",
              "application/x-mpegurl", "application/octet-stream")


def periksa(kanal):
    """(id, hidup, status) untuk satu kanal."""
    url = (kanal.get("stream_url") or kanal.get("streamUrl") or "").strip()
    kid = kanal.get("id")
    if not url:
        return kid, False, "tanpa url"
    permintaan = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "*/*",
        # Potongan kecil saja: kita hanya perlu tahu bentuk jawabannya.
        "Range": "bytes=0-%d" % (POTONG - 1),
    })
    try:
        with urllib.request.urlopen(permintaan, timeout=BATAS_DETIK) as jawab:
            kode = jawab.status
            tipe = (jawab.headers.get("Content-Type") or "").lower()
            badan = jawab.read(POTONG)
    except urllib.error.HTTPError as e:
        return kid, False, "http %d" % e.code
    except Exception as e:
        nama = type(e).__name__
        pesan = str(e) or nama
        return kid, False, pesan[:60]

    if kode not in (200, 206):
        return kid, False, "http %d" % kode
    if TANDA_HLS in badan[:256]:
        return kid, True, "hls"
    if any(t in tipe for t in TIPE_MEDIA):
        return kid, True, "media"
    # 200 yang isinya HTML adalah halaman galat atau pengalih, bukan stream.
    if b"<html" in badan[:400].lower():
        return kid, False, "halaman html"
    return kid, False, "isi tak dikenali"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--jumlah", type=int, default=0,
                   help="hanya periksa N kanal pertama (untuk uji alat)")
    p.add_argument("--hanya-baru", action="store_true",
                   help="lewati kanal yang sudah punya hasil pemeriksaan")
    a = p.parse_args()

    if not os.path.exists(BERKAS):
        print("Berkas kanal belum ada: %s" % BERKAS)
        return 1
    with open(BERKAS, encoding="utf-8") as f:
        kanal = json.load(f)
    if not isinstance(kanal, list):
        print("Bentuk %s bukan larik" % BERKAS)
        return 1

    sasaran = kanal
    if a.hanya_baru:
        sasaran = [c for c in kanal if "alive" not in c]
    if a.jumlah > 0:
        sasaran = sasaran[:a.jumlah]

    print("Memeriksa %d dari %d kanal, %d utas, batas %d detik"
          % (len(sasaran), len(kanal), UTAS, BATAS_DETIK), flush=True)

    peta = {}
    t0, selesai, hidup = time.time(), 0, 0
    with ThreadPoolExecutor(UTAS) as ex:
        for kid, ok, status in ex.map(periksa, sasaran):
            selesai += 1
            hidup += 1 if ok else 0
            if kid:
                peta[kid] = (ok, status)
            if selesai % 500 == 0:
                laju = selesai / max(time.time() - t0, 1)
                print("  %d/%d  %.1f/s  hidup=%d  sisa~%.0fm"
                      % (selesai, len(sasaran), laju, hidup,
                         (len(sasaran) - selesai) / max(laju, 0.1) / 60), flush=True)

    sekarang = int(time.time() * 1000)
    for c in kanal:
        hasil = peta.get(c.get("id"))
        if not hasil:
            continue
        c["alive"] = hasil[0]
        c["probeStatus"] = hasil[1]
        c["checkedAtEpochMs"] = sekarang

    sementara = BERKAS + ".tmp"
    with open(sementara, "w", encoding="utf-8") as f:
        json.dump(kanal, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(sementara, BERKAS)

    diperiksa = sum(1 for c in kanal if "alive" in c)
    total_hidup = sum(1 for c in kanal if c.get("alive"))
    sebab = {}
    for c in kanal:
        if "alive" in c and not c.get("alive"):
            sebab[c.get("probeStatus")] = sebab.get(c.get("probeStatus"), 0) + 1
    print("\n%d kanal diperiksa, %d hidup (%.1f%%), %d mati"
          % (diperiksa, total_hidup, total_hidup * 100.0 / max(diperiksa, 1),
             diperiksa - total_hidup))
    for s, n in sorted(sebab.items(), key=lambda x: -x[1])[:10]:
        print("  %-28s %d" % (s, n))
    print("\nCATATAN: ini syarat PERLU, bukan bukti tayang. Yang diukur alamatnya "
          "menjawab dan bentuk jawabannya playlist/media — bukan bahwa segmennya "
          "bisa didekode atau bisa diakses dari negara penonton.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
