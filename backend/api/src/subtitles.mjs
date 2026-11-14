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
    const nilai = {
      tmdbId: id,
      languages: bahasa,
      subtitles: terbaikPerBahasa(semua, bahasa),
      totalFound: semua.length,
      attribution: 'Subtitle dari OpenSubtitles.com',
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
  }, log);
}

export { normalkanBahasa, rapikan, terbaikPerBahasa, BASIS_DEFAULT };
