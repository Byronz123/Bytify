// ==========================================
// Bytify/backend/api/src/subtitles.mjs
// ==========================================
//
// Proxy tipis ke API resmi OpenSubtitles.
//
// KENAPA PROXY, BUKAN DIPANGGIL LANGSUNG DARI APK:
// kunci API yang ditanam di APK bisa diambil siapa pun yang membongkar berkasnya
// (apktool, strings). Sekali bocor, kunci itu dipakai orang lain sampai kuotanya
// habis atau dicabut. Jadi kunci HANYA ada di sisi server, dan APK memanggil
// endpoint ini. Tidak ada kunci di source — semuanya dari environment.
//
// KENAPA SAAT TAYANG, BUKAN DIPANEN LALU DITERBITKAN:
// 1. 25 ribu film x 2 bahasa x ~50 KB kira-kira 2,5 GB, sementara batas keras
//    Cloudflare Pages 25 MiB per berkas. Tidak akan pernah muat.
// 2. Memanen lalu menerbitkannya berarti Bytify menyalurkan ulang ribuan berkas
//    subtitle buatan orang lain dari CDN sendiri. Dengan permintaan saat tayang,
//    tiap pengguna mengambilnya dari OpenSubtitles di bawah ketentuan mereka
//    sendiri — pola yang sama dipakai VLC, Kodi, Plex, dan Jellyfin.
//
// KUOTA: pencarian tidak memakan kuota unduh, tetapi /download memakan. Karena
// itu endpoint ini dipisah dua: daftar dulu (murah), tautan hanya saat pengguna
// benar-benar menyalakan subtitle.

const BASIS_DEFAULT = 'https://api.opensubtitles.com/api/v1';

/** Bahasa yang boleh diminta; kode ISO-639-1 dua huruf yang didukung OpenSubtitles. */
const BAHASA_SAH = /^[a-z]{2}(-[a-z]{2})?$/;

/** Hasil pencarian disimpan sebentar supaya membuka ulang judul yang sama tidak menembak API lagi. */
const SINGGAHAN_MS = 10 * 60 * 1000;

function normalkanBahasa(nilai, bawaan = ['id', 'en']) {
  const daftar = String(nilai || '')
    .split(',')
    .map((x) => x.trim().toLowerCase())
    .filter((x) => BAHASA_SAH.test(x));
  const unik = [...new Set(daftar)];
  return unik.length ? unik.slice(0, 5) : bawaan;
}

/**
 * Satu baris hasil yang dikirim ke APK. Sengaja ramping: hanya yang dipakai
 * untuk menampilkan pilihan dan meminta tautannya nanti.
 */
function rapikan(baris) {
  const a = baris?.attributes || {};
  const berkas = Array.isArray(a.files) ? a.files : [];
  const utama = berkas.find((f) => Number.isFinite(f?.file_id)) || null;
  if (!utama) return null;
  return {
    fileId: utama.file_id,
    fileName: utama.file_name || null,
    lang: String(a.language || '').toLowerCase() || null,
    release: a.release || null,
    downloads: Number.isFinite(a.download_count) ? a.download_count : 0,
    // Subtitle untuk penonton dengan gangguan pendengaran: DITAWARKAN, bukan
    // disaring. Itu justru alasan utama fitur subtitle ada.
    hearingImpaired: a.hearing_impaired === true,
    fps: Number.isFinite(a.fps) ? a.fps : null,
    uploadedAt: a.upload_date || null,
  };
}

/**
 * Pilih yang terbaik per bahasa: paling banyak diunduh. `download_count` adalah
 * satu-satunya sinyal kualitas yang diberikan API; tanpa itu urutannya acak.
 */
function terbaikPerBahasa(daftar, bahasa) {
  const keluar = [];
  for (const kode of bahasa) {
    const sama = daftar.filter((x) => x.lang === kode);
    if (!sama.length) continue;
    sama.sort((a, b) => b.downloads - a.downloads);
    keluar.push(sama[0]);
    // Satu alternatif untuk penonton dengan gangguan pendengaran, bila ada dan
    // berbeda dari yang terpilih.
    const hi = sama.find((x) => x.hearingImpaired && x.fileId !== sama[0].fileId);
    if (hi) keluar.push(hi);
  }
  return keluar;
}

/**
 * Mengambil berkas .srt PERTAMA dari sebuah arsip ZIP.
 *
 * SubDL menyajikan subtitlenya sebagai ZIP, sementara APK mengharapkan teks
 * SRT apa adanya. Membongkarnya di server adalah satu-satunya pilihan: APK
 * tidak punya pembongkar ZIP, dan menambahkannya hanya untuk ini jauh lebih
 * mahal daripada 60 baris di sini.
 *
 * `DecompressionStream('deflate-raw')` ada di Cloudflare Workers maupun Node
 * 18+, jadi tidak ada ketergantungan baru. Entri yang disimpan tanpa kompresi
 * (metode 0) ikut ditangani karena ZIP kecil sering memakainya.
 */
async function bukaZipSrt(buffer) {
  const data = new Uint8Array(buffer);
  const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
  let i = 0;
  while (i + 30 <= data.length) {
    if (dv.getUint32(i, true) !== 0x04034b50) break;
    const metode = dv.getUint16(i + 8, true);
    let terkompresi = dv.getUint32(i + 18, true);
    const panjangNama = dv.getUint16(i + 26, true);
    const panjangExtra = dv.getUint16(i + 28, true);
    const awalNama = i + 30;
    const nama = new TextDecoder().decode(data.subarray(awalNama, awalNama + panjangNama));
    const awalData = awalNama + panjangNama + panjangExtra;
    // Ukuran 0 di header lokal berarti ukurannya ada di data descriptor
    // SESUDAH isinya. Entri seperti itu dilewati, bukan ditebak.
    if (terkompresi === 0 && metode !== 0) break;
    if (/\.(srt|vtt)$/i.test(nama)) {
      const potong = data.subarray(awalData, awalData + terkompresi);
      if (metode === 0) return new TextDecoder('utf-8').decode(potong);
      const aliran = new Blob([potong]).stream()
        .pipeThrough(new DecompressionStream('deflate-raw'));
      const isi = await new Response(aliran).arrayBuffer();
      return new TextDecoder('utf-8').decode(new Uint8Array(isi));
    }
    i = awalData + terkompresi;
  }
  return null;
}

/** Bentuk baris SubDL -> bentuk yang sama dengan hasil OpenSubtitles. */
function rapikanSubdl(baris) {
  const mentah = typeof baris?.url === 'string' ? baris.url : '';
  // KUNCI API DIPOTONG DI SINI. SubDL menempelkan `?api_key=...` ke setiap
  // `url` yang dipulangkannya, dan `fileId` ini diteruskan apa adanya ke APK —
  // jadi tanpa pemotongan ini kuncinya ikut terbit ke setiap pengguna, persis
  // yang dihindari 3g. Terukur TIDAK diperlukan: `dl.subdl.com` memulangkan
  // ZIP 23.856 bait yang sama dengan maupun tanpa kunci.
  const jalur = mentah.split('?')[0];
  if (!jalur) return null;
  const kode = String(baris.language || baris.lang || '').toLowerCase();
  return {
    // Awalan `subdl:` adalah penanda sumbernya. `tautan()` memakainya untuk
    // memilih jalur unduh, jadi id ini TIDAK boleh berupa angka saja.
    fileId: `subdl:${jalur}`,
    fileName: baris.release_name || baris.name || null,
    lang: kode.startsWith('indo') ? 'id' : kode.slice(0, 2) || null,
    release: baris.release_name || null,
    downloads: 0,
    hearingImpaired: baris.hi === true,
    fps: null,
    uploadedAt: null,
    sumber: 'subdl',
  };
}

/**
 * Tuan rumah yang TIDAK boleh diambil berkasnya.
 *
 * `tautan()` mengunduh URL yang dipulangkan penyedia apa adanya, jadi tanpa
 * penjagaan ini sebuah URL yang menunjuk ke dalam jaringan server sendiri
 * akan ikut diambil dan isinya dikirim ke pemanggil (SSRF).
 */
function tuanRumahLokal(host) {
  const h = String(host || '').toLowerCase();
  return h === 'localhost'
    || h.endsWith('.localhost')
    || h.endsWith('.internal')
    || h === '::1'
    || h === '[::1]'
    || /^127\./.test(h)
    || /^10\./.test(h)
    || /^192\.168\./.test(h)
    || /^169\.254\./.test(h)
    || /^172\.(1[6-9]|2\d|3[01])\./.test(h);
}

/** Bentuk baris Wyzie -> bentuk yang sama dengan hasil OpenSubtitles. */
function rapikanWyzie(baris) {
  // Bentuk jawaban Wyzie DITERIMA LONGGAR dengan sengaja: dokumentasinya
  // menyebut `language`, `display`, `format`, dan `source`, sementara versi
  // lamanya memakai `url`/`download`. Menolak baris hanya karena nama
  // fieldnya berbeda berarti sumber ini diam tanpa alasan, padahal kuncinya
  // sudah dibayar.
  const mentah = String(baris?.url || baris?.download || baris?.link || '');
  if (!/^https:\/\//i.test(mentah)) return null;
  let host = '';
  try {
    host = new URL(mentah).hostname;
  } catch (e) {
    return null;
  }
  if (tuanRumahLokal(host)) return null;
  const kode = String(baris.language || baris.lang || '').toLowerCase();
  const lang = kode.startsWith('indo') ? 'id' : kode.slice(0, 2);
  if (!lang) return null;
  return {
    // Awalan `wyzie:` memilih jalur unduh di `tautan()`, sama seperti `subdl:`.
    fileId: `wyzie:${mentah}`,
    fileName: baris.display || baris.release || null,
    lang,
    release: baris.release || null,
    downloads: 0,
    hearingImpaired: baris.isHearingImpaired === true || baris.hi === true,
    fps: null,
    uploadedAt: null,
    sumber: 'wyzie',
  };
}

export function createSubtitleService(options = {}, log = console) {
  const {
    apiKey = '',
    appName = 'Bytify v1.0',
    baseUrl = BASIS_DEFAULT,
    timeoutMs = 15000,
    defaultLanguages = ['id', 'en'],
    termsAccepted = false,
    // DIIKAT ke globalThis. `globalThis.fetch` yang diambil lepas lalu
    // dipanggil sebagai fungsi telanjang membuat `this` undefined; Node 18+
    // memaafkannya, tetapi runtime Cloudflare Workers melempar
    // "Illegal invocation" — yang muncul sebagai error 1101 / HTTP 500 dan
    // TIDAK pernah terlihat di uji lokal. Sudah terjadi sekali di produksi.
    fetchImpl = (...a) => globalThis.fetch(...a),
  } = options;

  const singgahan = new Map();

  const siap = Boolean(apiKey) && termsAccepted;

  /** Alasan tidak siap, supaya endpoint bisa menjawab 503 yang informatif. */
  // --- SubDL: sumber KEDUA, menambal yang tidak dimiliki OpenSubtitles ---
  //
  // Diukur: OpenSubtitles tidak punya subtitle Indonesia untuk rilis Indonesia
  // baru sama sekali (`tmdb 1525059` dan `tmdb 1589663` nol berkas), sementara
  // SubDL memilikinya — halaman "Project Sacrifice (2026)" mencatat 8 subtitle
  // dalam 6 bahasa termasuk Indonesia. Tanpa sumber kedua, film Indonesia
  // tidak akan pernah punya subtitle Indonesia.
  //
  // Tanpa kunci, seluruh jalur ini DIAM: hasilnya persis seperti sebelumnya.
  // DUA kunci, bukan satu, dan itu disengaja: tingkat gratis SubDL dibatasi
  // 1.000 permintaan per hari. Kunci kedua dipakai hanya kalau yang pertama
  // ditolak karena kuota atau kredensial (401/403/429), jadi urutannya tetap
  // dan hasilnya tidak berubah selama kunci pertama masih sehat.
  const subdlKeys = (Array.isArray(options.subdlKeys) ? options.subdlKeys : [])
    .map((k) => String(k || '').trim())
    .filter(Boolean);
  const subdlKey = subdlKeys[0] || '';
  const subdlBase = String(options.subdlBase || 'https://api.subdl.com').replace(/\/+$/, '');
  const subdlUnduh = String(options.subdlUnduh || 'https://dl.subdl.com').replace(/\/+$/, '');

  /** Kode yang berarti "coba kunci berikutnya", bukan "judulnya tidak ada". */
  function kunciHabis(status) {
    return status === 401 || status === 403 || status === 429;
  }

  async function cariSubdl({ id, bahasa, jenis, season, episode }) {
    if (!subdlKeys.length || !bahasa.length) return [];
    for (let ke = 0; ke < subdlKeys.length; ke += 1) {
      const q = new URLSearchParams({
        api_key: subdlKeys[ke],
        tmdb_id: String(id),
        // SubDL memakai kode BESAR dua huruf.
        languages: bahasa.map((b) => b.toUpperCase()).join(','),
        subs_per_page: '30',
      });
      if (jenis === 'episode') {
        if (Number.isFinite(Number(season))) q.set('season_number', String(Number(season)));
        if (Number.isFinite(Number(episode))) q.set('episode_number', String(Number(episode)));
      }
      const resp = await fetchImpl(`${subdlBase}/api/v1/subtitles?${q.toString()}`, {
        headers: { Accept: 'application/json' },
      });
      if (!resp.ok) {
        log.warn?.(`[subtitles] subdl kunci#${ke + 1} tmdb=${id} gagal: HTTP ${resp.status}`);
        // Kunci berikutnya HANYA untuk penolakan kredensial/kuota. Galat lain
        // (5xx, 404) berlaku sama untuk semua kunci, jadi mengulangnya cuma
        // menghabiskan kuota kunci kedua tanpa mengubah hasil.
        if (kunciHabis(resp.status) && ke + 1 < subdlKeys.length) continue;
        return [];
      }
      const data = await resp.json().catch(() => null);
      const baris = Array.isArray(data?.subtitles) ? data.subtitles : [];
      return baris.map(rapikanSubdl).filter((x) => x && x.lang);
    }
    return [];
  }

  // --- Wyzie: sumber KETIGA, satu kunci untuk 13 penyedia ---
  //
  // Diukur sebelum dipasang: OpenSubtitles + SubDL hanya memberi subtitle
  // Indonesia untuk 2 dari 48 film Indonesia yang diuji, dan SubDL sendiri 3
  // dari 60. Wyzie mengagregasi 13 penyedia sekaligus, jadi ia bisa menutup
  // sisanya -- TETAPI itu belum terbukti, karena endpoint-nya menjawab 503
  // "Down for maintenance" saat ini ditulis. Karena itu ia dipasang persis
  // seperti SubDL: tanpa kunci seluruh jalurnya DIAM, dan hasilnya sama
  // seperti sebelum ia ada.
  //
  // Ia dipanggil PALING AKHIR dan hanya untuk bahasa yang masih kosong, jadi
  // satu judul paling banyak memakai SATU permintaan kuotanya -- dan tidak
  // satu pun kalau OpenSubtitles sudah lengkap.
  const wyzieKey = String(options.wyzieKey || '').trim();
  const wyzieBase = String(options.wyzieBase || 'https://sub.wyzie.ru').replace(/\/+$/, '');

  async function cariWyzie({ id, bahasa, jenis, season, episode }) {
    if (!wyzieKey || !bahasa.length) return [];
    const q = new URLSearchParams({
      id: String(id),
      key: wyzieKey,
      language: bahasa.join(','),
      format: 'srt',
    });
    if (jenis === 'episode') {
      if (Number.isFinite(Number(season))) q.set('season', String(Number(season)));
      if (Number.isFinite(Number(episode))) q.set('episode', String(Number(episode)));
    }
    let resp;
    try {
      resp = await fetchImpl(`${wyzieBase}/search?${q.toString()}`, {
        headers: { Accept: 'application/json' },
      });
    } catch (e) {
      log.warn?.(`[subtitles] wyzie tmdb=${id} tidak terjangkau: ${e?.message || e}`);
      return [];
    }
    if (!resp.ok) {
      log.warn?.(`[subtitles] wyzie tmdb=${id} gagal: HTTP ${resp.status}`);
      return [];
    }
    const data = await resp.json().catch(() => null);
    const baris = Array.isArray(data) ? data : (Array.isArray(data?.data) ? data.data : []);
    return baris.map(rapikanWyzie).filter((x) => x && x.lang);
  }

  function alasanTidakSiap() {
    if (!apiKey) return 'OPENSUBTITLES_API_KEY belum diatur di environment.';
    if (!termsAccepted) {
      return 'OPENSUBTITLES_TERMS_ACCEPTED belum "true". '
        + 'Baca ketentuan pemakaian API di https://www.opensubtitles.com/ lalu setel ke true.';
    }
    return 'Layanan subtitle belum siap.';
  }

  /**
   * Nilai header HTTP hanya boleh ASCII cetak. Satu karakter liar — baris
   * baru yang terbawa saat menempel nilai, spasi tak-putus, tanda kutip
   * melengkung — membuat runtime melempar "Invalid header value", dan di
   * Workers itu keluar sebagai error 1101 tanpa menyebut header mana.
   *
   * Dibersihkan DAN dilaporkan: kalau masih ada yang tak sah setelah
   * dibersihkan, namanya disebut supaya ketahuan variabel mana yang salah isi.
   */
  function headerAman(nama, nilai) {
    const bersih = String(nilai ?? '').replace(/[\r\n\t]/g, '').trim();
    if (!/^[\x20-\x7E]*$/.test(bersih)) {
      const kode = [...bersih]
        .filter((ch) => ch < ' ' || ch > '~')
        .map((ch) => 'U+' + ch.codePointAt(0).toString(16).toUpperCase().padStart(4, '0'));
      throw new Error(
        `Nilai ${nama} memuat karakter yang tidak sah untuk header HTTP (${[...new Set(kode)].join(', ')}). `
        + 'Periksa isi variabel itu di environment.',
      );
    }
    return bersih;
  }

  async function panggil(path, { method = 'GET', body = null } = {}) {
    const kendali = new AbortController();
    const jam = setTimeout(() => kendali.abort(), timeoutMs);
    try {
      const resp = await fetchImpl(`${baseUrl}${path}`, {
        method,
        signal: kendali.signal,
        headers: {
          'Api-Key': headerAman('OPENSUBTITLES_API_KEY', apiKey),
          // OpenSubtitles mewajibkan User-Agent yang menyebut aplikasinya.
          'User-Agent': headerAman('OPENSUBTITLES_APP_NAME', appName),
          Accept: 'application/json',
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      const teks = await resp.text();
      let data = null;
      try {
        data = teks ? JSON.parse(teks) : null;
      } catch {
        data = null;
      }
      return { status: resp.status, ok: resp.ok, data, teks };
    } finally {
      clearTimeout(jam);
    }
  }

  /** Terjemahkan kegagalan upstream ke sesuatu yang bisa ditindak. */
  function galat(status, data) {
    if (status === 401 || status === 403) {
      return { status: 502, error: 'Kunci API OpenSubtitles ditolak. Periksa OPENSUBTITLES_API_KEY.' };
    }
    if (status === 429) {
      return {
        status: 429,
        error: 'Kuota OpenSubtitles tercapai. Coba lagi nanti.',
        resetTime: data?.reset_time || null,
      };
    }
    if (status === 406) {
      return { status: 429, error: 'Kuota unduh harian OpenSubtitles habis.' };
    }
    return { status: 502, error: `OpenSubtitles menjawab HTTP ${status}.` };
  }

  /**
   * Pembungkus: kegagalan tak terduga apa pun dipetakan ke jawaban
   * terstruktur. Tanpa ini, satu exception di Workers keluar sebagai error
   * 1101 / HTTP 500 mentah yang tidak memberi tahu apa pun kepada APK maupun
   * kepada yang men-debug.
   */
  async function aman(nama, fn) {
    try {
      return await fn();
    } catch (e) {
      log.warn?.(`[subtitles] ${nama} melempar: ${e?.message || e}`);
      return { status: 502, error: `Layanan subtitle gagal: ${e?.message || 'galat tak dikenal'}` };
    }
  }

  /**
   * Daftar subtitle untuk satu judul. TIDAK memakan kuota unduh.
   *
   * [tmdbId] wajib: pencarian berbasis id jauh lebih tepat daripada berbasis
   * judul, dan id itu sudah tersedia dari pencocokan ketat di tmdb_match.py.
   */
  async function cari({ tmdbId, languages, type = 'movie', season = null, episode = null } = {}) {
    if (!siap) return { status: 503, error: alasanTidakSiap() };
    const id = Number.parseInt(tmdbId, 10);
    if (!Number.isFinite(id) || id <= 0) {
      return { status: 400, error: 'tmdbId wajib berupa angka positif.' };
    }
    const bahasa = normalkanBahasa(languages, defaultLanguages);
    const jenis = type === 'episode' ? 'episode' : 'movie';

    const kunci = JSON.stringify([id, bahasa, jenis, season, episode]);
    const tersimpan = singgahan.get(kunci);
    if (tersimpan && Date.now() - tersimpan.pada < SINGGAHAN_MS) {
      return { status: 200, ...tersimpan.nilai, cached: true };
    }

    const q = new URLSearchParams({
      tmdb_id: String(id),
      languages: bahasa.join(','),
      type: jenis,
    });
    if (jenis === 'episode') {
      if (Number.isFinite(Number(season))) q.set('season_number', String(Number(season)));
      if (Number.isFinite(Number(episode))) q.set('episode_number', String(Number(episode)));
    }

    const hasil = await panggil(`/subtitles?${q.toString()}`);
    if (!hasil.ok) {
      log.warn?.(`[subtitles] pencarian tmdb=${id} gagal: HTTP ${hasil.status}`);
      return galat(hasil.status, hasil.data);
    }

    const baris = Array.isArray(hasil.data?.data) ? hasil.data.data : [];
    const semua = baris.map(rapikan).filter(Boolean);
    const terpilih = terbaikPerBahasa(semua, bahasa);

    // SubDL hanya menambal bahasa yang BELUM ada, tidak menggeser apa pun.
    // Urutannya disengaja: OpenSubtitles punya `download_count` sebagai sinyal
    // kualitas, SubDL tidak, jadi yang terukur didahulukan.
    const kurang = bahasa.filter((k) => !terpilih.some((x) => x.lang === k));
    let dariSubdl = [];
    if (kurang.length) {
      dariSubdl = await cariSubdl({ id, bahasa: kurang, jenis, season, episode });
      for (const kode of kurang) {
        const cocok = dariSubdl.find((x) => x.lang === kode);
        if (cocok) terpilih.push(cocok);
      }
    }

    // Wyzie menutup sisanya, dan urutannya sama alasannya: yang punya sinyal
    // kualitas terukur didahulukan, yang hanya menambal menyusul.
    const masihKurang = bahasa.filter((k) => !terpilih.some((x) => x.lang === k));
    let dariWyzie = [];
    if (masihKurang.length) {
      dariWyzie = await cariWyzie({ id, bahasa: masihKurang, jenis, season, episode });
      for (const kode of masihKurang) {
        const cocok = dariWyzie.find((x) => x.lang === kode);
        if (cocok) terpilih.push(cocok);
      }
    }

    const sumberPakai = ['OpenSubtitles.com'];
    if (dariSubdl.length) sumberPakai.push('SubDL.com');
    if (dariWyzie.length) sumberPakai.push('Wyzie Subs');
    const akhir = sumberPakai[sumberPakai.length - 1];
    const nilai = {
      tmdbId: id,
      languages: bahasa,
      subtitles: terpilih,
      totalFound: semua.length + dariSubdl.length + dariWyzie.length,
      attribution: sumberPakai.length > 1
        ? `Subtitle dari ${sumberPakai.slice(0, -1).join(', ')} dan ${akhir}`
        : `Subtitle dari ${akhir}`,
    };
    singgahan.set(kunci, { pada: Date.now(), nilai });
    return { status: 200, ...nilai };
  }

  /**
   * Tautan unduh sementara untuk satu berkas. MEMAKAN kuota, jadi hanya dipanggil
   * ketika pengguna benar-benar menyalakan subtitle — bukan saat membuka detail.
   */
  async function tautan({ fileId } = {}) {
    if (!siap) return { status: 503, error: alasanTidakSiap() };

    // Berkas SubDL dikirim sebagai ISI, bukan pengalihan: yang ada di sana ZIP,
    // sementara APK mengharapkan teks SRT. Membongkarnya di server membuat
    // kontrak `/api/subtitles/file` tidak berubah sama sekali bagi APK.
    const teksId = String(fileId || '');
    if (teksId.startsWith('subdl:')) {
      if (!subdlKeys.length) {
        return { status: 503, error: 'SUBDL_API_KEY belum diatur di environment.' };
      }
      const jalur = teksId.slice('subdl:'.length);
      if (!jalur.startsWith('/')) {
        return { status: 400, error: 'Jalur berkas SubDL tidak sah.' };
      }
      const resp = await fetchImpl(`${subdlUnduh}${jalur}`, {
        headers: { Accept: 'application/zip,*/*' },
      });
      if (!resp.ok) {
        return { status: 502, error: `SubDL menjawab HTTP ${resp.status}.` };
      }
      const srt = await bukaZipSrt(await resp.arrayBuffer());
      if (!srt) {
        return { status: 502, error: 'Arsip SubDL tidak berisi berkas .srt.' };
      }
      return {
        status: 200,
        body: srt,
        contentType: 'application/x-subrip; charset=utf-8',
        fileName: jalur.split('/').pop() || null,
        attribution: 'Subtitle dari SubDL.com',
      };
    }

    // Berkas Wyzie dikirim sebagai ISI juga: yang dipulangkannya URL langsung
    // ke berkas penyedia, dan menjawabnya dengan 302 berarti APK mengambil
    // dari tuan rumah yang tidak kita kenal.
    if (teksId.startsWith('wyzie:')) {
      if (!wyzieKey) {
        return { status: 503, error: 'WYZIE_API_KEY belum diatur di environment.' };
      }
      const alamat = teksId.slice('wyzie:'.length);
      let host = '';
      try {
        host = new URL(alamat).hostname;
      } catch (e) {
        host = '';
      }
      if (!/^https:\/\//i.test(alamat) || !host || tuanRumahLokal(host)) {
        return { status: 400, error: 'Alamat berkas Wyzie tidak sah.' };
      }
      const resp = await fetchImpl(alamat, { headers: { Accept: 'text/plain,*/*' } });
      if (!resp.ok) {
        return { status: 502, error: `Wyzie menjawab HTTP ${resp.status}.` };
      }
      const teks = await resp.text();
      if (!teks || !teks.trim()) {
        return { status: 502, error: 'Berkas Wyzie kosong.' };
      }
      return {
        status: 200,
        body: teks,
        contentType: 'application/x-subrip; charset=utf-8',
        fileName: alamat.split('/').pop()?.split('?')[0] || null,
        attribution: 'Subtitle dari Wyzie Subs',
      };
    }

    const id = Number.parseInt(fileId, 10);
    if (!Number.isFinite(id) || id <= 0) {
      return { status: 400, error: 'fileId wajib berupa angka positif.' };
    }
    const hasil = await panggil('/download', { method: 'POST', body: { file_id: id } });
    if (!hasil.ok) {
      log.warn?.(`[subtitles] tautan file=${id} gagal: HTTP ${hasil.status}`);
      return galat(hasil.status, hasil.data);
    }
    const tautanUnduh = hasil.data?.link;
    if (typeof tautanUnduh !== 'string' || !/^https:\/\//i.test(tautanUnduh)) {
      return { status: 502, error: 'OpenSubtitles tidak memberi tautan HTTPS yang sah.' };
    }
    return {
      status: 200,
      link: tautanUnduh,
      fileName: hasil.data?.file_name || null,
      // Sisa kuota diteruskan apa adanya supaya bisa dipantau dari log aplikasi.
      remaining: Number.isFinite(hasil.data?.remaining) ? hasil.data.remaining : null,
      resetTime: hasil.data?.reset_time || null,
      attribution: 'Subtitle dari OpenSubtitles.com',
    };
  }

  return {
    cari: (opsi) => aman('cari', () => cari(opsi)),
    tautan: (opsi) => aman('tautan', () => tautan(opsi)),
    get siap() { return siap; },
    alasanTidakSiap,
  };
}

/**
 * Rakit layanan dari environment. Dipakai server dan fungsi Cloudflare, supaya
 * tidak ada dua tempat yang membaca nama variabel yang sama dengan ejaan beda.
 */
// `process` tidak ada di Cloudflare Workers, jadi bawaannya dijaga: fungsi
// Cloudflare memanggil ini dengan `env` miliknya sendiri.
const ENV_BAWAAN = typeof process !== 'undefined' && process.env ? process.env : {};

export function subtitleServiceDariEnv(env = ENV_BAWAAN, log = console) {
  return createSubtitleService({
    apiKey: (env.OPENSUBTITLES_API_KEY || '').trim(),
    appName: (env.OPENSUBTITLES_APP_NAME || 'Bytify v1.0').trim(),
    baseUrl: (env.OPENSUBTITLES_BASE_URL || BASIS_DEFAULT).trim().replace(/\/+$/, ''),
    timeoutMs: Number.parseInt(env.OPENSUBTITLES_TIMEOUT_MS || '', 10) || 15000,
    defaultLanguages: normalkanBahasa(env.SUBTITLE_LANGUAGES || 'id,en'),
    // Gerbang sadar: sama seperti LIVE_SOURCE_RIGHTS_CONFIRMED pada lapisan
    // Live TV, pemakaian API pihak ketiga harus diakui eksplisit dulu.
    termsAccepted: env.OPENSUBTITLES_TERMS_ACCEPTED === 'true',
    // Sumber kedua. Tanpa kunci, seluruh jalurnya diam dan hasilnya persis
    // seperti sebelum SubDL ada.
    // Tiga ejaan diterima: satu kunci, atau dua yang dipakai bergantian.
    // Yang kosong dibuang, jadi mengisi salah satunya saja tetap sah.
    subdlKeys: [env.SUBDL_API_KEY, env.SUBDL_API_KEY_1, env.SUBDL_API_KEY_2]
      .map((k) => (k || '').trim())
      .filter(Boolean),
    wyzieKey: (env.WYZIE_API_KEY || '').trim(),
    wyzieBase: (env.WYZIE_BASE_URL || 'https://sub.wyzie.ru').trim(),
    subdlBase: (env.SUBDL_BASE_URL || 'https://api.subdl.com').trim(),
    subdlUnduh: (env.SUBDL_DOWNLOAD_URL || 'https://dl.subdl.com').trim(),
  }, log);
}

export { normalkanBahasa, rapikan, rapikanSubdl, terbaikPerBahasa, bukaZipSrt, BASIS_DEFAULT };
