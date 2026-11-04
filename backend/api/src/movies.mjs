import { readFile } from 'node:fs/promises';
import { assertPublicHttps, safeFetch, mapLimit, slug } from './util.mjs';

const USER_AGENT = 'Bytify-MovieCatalog/1.0 (+https://github.com/Byronz123/Bytify)';
const ARCHIVE = 'https://archive.org';

/* ------------------------------------------------------------------ */
/* Normalisasi: semua adapter menghasilkan bentuk item yang sama.      */
/* ------------------------------------------------------------------ */

function cleanUrl(value) {
  if (!value || typeof value !== 'string') return null;
  try { return assertPublicHttps(value.trim(), 'URL').toString(); } catch { return null; }
}

function cleanSubtitles(list) {
  return (Array.isArray(list) ? list : [])
    .map((s) => ({ lang: String(s.lang || '').slice(0, 12), label: String(s.label || s.lang || 'Subtitle').slice(0, 60), url: cleanUrl(s.url) }))
    .filter((s) => s.url);
}

/** Memvalidasi satu item katalog. Item tanpa id/judul/lisensi ditolak. */
export function normalizeItem(raw, provider) {
  const id = String(raw.id || '').trim().slice(0, 160);
  const title = String(raw.title || '').trim().slice(0, 300);
  const license = String(raw.license || '').trim();
  if (!id || !title || !license) return null;
  const type = raw.type === 'series' ? 'series' : 'movie';
  const year = Number.parseInt(raw.releaseYear, 10);
  const rating = Number(raw.rating);
  const episodes = type === 'series'
    ? (Array.isArray(raw.episodes) ? raw.episodes : [])
      .map((e, i) => ({
        id: String(e.id || `${id}-e${i + 1}`).slice(0, 200),
        title: String(e.title || `Episode ${i + 1}`).slice(0, 300),
        season: Number.parseInt(e.season, 10) || 1,
        episode: Number.parseInt(e.episode, 10) || i + 1,
        durationMin: Number.parseInt(e.durationMin, 10) || null,
        videoUrl: cleanUrl(e.videoUrl),
        subtitles: cleanSubtitles(e.subtitles),
      }))
      .filter((e) => e.videoUrl)
    : [];
  const videoUrl = cleanUrl(raw.videoUrl) || episodes[0]?.videoUrl || null;
  return {
    id,
    type,
    title,
    overview: String(raw.overview || '').slice(0, 4000),
    posterUrl: cleanUrl(raw.posterUrl),
    backdropUrl: cleanUrl(raw.backdropUrl) || cleanUrl(raw.posterUrl),
    releaseYear: year >= 1888 && year <= 2200 ? year : null,
    durationMin: Number.parseInt(raw.durationMin, 10) > 0 ? Number.parseInt(raw.durationMin, 10) : null,
    genres: [...new Set((Array.isArray(raw.genres) ? raw.genres : []).map((g) => String(g).trim()).filter(Boolean))].slice(0, 6),
    rating: Number.isFinite(rating) && rating >= 0 && rating <= 10 ? Math.round(rating * 10) / 10 : null,
    language: raw.language ? String(raw.language).slice(0, 12) : null,
    videoUrl,
    trailerUrl: cleanUrl(raw.trailerUrl),
    subtitles: cleanSubtitles(raw.subtitles).length ? cleanSubtitles(raw.subtitles) : (episodes[0]?.subtitles || []),
    episodes,
    license,
    tags: Array.isArray(raw.tags) ? raw.tags.map((t) => String(t).toLowerCase()) : [],
    provider,
    addedAtEpochMs: Number(raw.addedAtEpochMs) || 0,
    popularity: Number(raw.popularity) || 0,
  };
}

/* ------------------------------------------------------------------ */
/* Adapter 1: file JSON yang dikurasi operator ({ movies, sections }). */
/* ------------------------------------------------------------------ */

export function fileProvider(path) {
  return {
    name: 'file',
    async load() {
      if (!path) throw new Error('MOVIE_CATALOG_FILE belum diisi.');
      const data = JSON.parse(await readFile(path, 'utf8'));
      return { items: Array.isArray(data.movies) ? data.movies : [], sections: Array.isArray(data.sections) ? data.sections : [] };
    },
  };
}

/* ------------------------------------------------------------------ */
/* Adapter 2: Internet Archive, hanya item berlisensi domain publik.   */
/* ------------------------------------------------------------------ */

const GENRE_ID = {
  comedy: 'Komedi', drama: 'Drama', horror: 'Horor', 'science fiction': 'Fiksi Ilmiah', 'sci-fi': 'Fiksi Ilmiah',
  western: 'Western', mystery: 'Misteri', thriller: 'Thriller', crime: 'Kriminal', 'film noir': 'Film Noir', noir: 'Film Noir',
  romance: 'Romantis', adventure: 'Petualangan', action: 'Aksi', animation: 'Animasi', cartoon: 'Animasi', cartoons: 'Animasi',
  musical: 'Musikal', war: 'Perang', fantasy: 'Fantasi', documentary: 'Dokumenter', family: 'Keluarga', silent: 'Film Bisu',
  'silent film': 'Film Bisu',
};

function toGenres(subject) {
  const list = Array.isArray(subject) ? subject : String(subject || '').split(/[;,]/);
  return [...new Set(list.map((s) => GENRE_ID[String(s).trim().toLowerCase()]).filter(Boolean))];
}

function parseRuntime(value) {
  if (!value) return null;
  const text = String(Array.isArray(value) ? value[0] : value).trim();
  const parts = text.split(':').map((p) => Number.parseInt(p, 10));
  if (parts.length >= 2 && parts.every(Number.isFinite)) {
    const seconds = parts.reduce((acc, p) => acc * 60 + p, 0);
    return Math.max(1, Math.round(seconds / 60));
  }
  const minutes = Number.parseInt(text, 10);
  return Number.isFinite(minutes) && minutes > 0 ? minutes : null;
}

/** Panjang berkas Archive bisa "1500.4" (detik) atau "25:03". */
function lengthToMinutes(value) {
  if (!value) return null;
  if (String(value).includes(':')) return parseRuntime(value);
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0 ? Math.max(1, Math.round(seconds / 60)) : null;
}

function stripHtml(value) {
  return String(Array.isArray(value) ? value.join('\n') : value || '')
    .replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\n{3,}/g, '\n\n').trim();
}

function isPublicDomain(licenseUrl) {
  return /creativecommons\.org\/(publicdomain|licenses\/publicdomain)/i.test(String(licenseUrl || ''));
}

function subtitleLang(name) {
  const n = name.toLowerCase();
  if (/(^|[._-])(id|ind|indonesian|indonesia)([._-]|$)/.test(n)) return { lang: 'id', label: 'Indonesia' };
  if (/\.asr\./.test(n)) return { lang: 'en', label: 'English (otomatis)' };
  return { lang: 'en', label: 'English' };
}

function pickVideos(files) {
  const mp4 = files.filter((f) => /\.mp4$/i.test(f.name) && !/\.ia\.mp4$/i.test(f.name));
  // Satu video per "dasar nama" berkas; utamakan h.264, lalu 512Kb.
  const byBase = new Map();
  for (const f of mp4) {
    const base = f.name.replace(/(_512kb)?\.mp4$/i, '');
    const score = /h\.264/i.test(f.format || '') ? 3 : (/512kb/i.test(f.name) ? 2 : 1);
    const prev = byBase.get(base);
    if (!prev || score > prev.score) byBase.set(base, { ...f, base, score });
  }
  return [...byBase.values()].sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
}

export function archiveProvider(options, log) {
  async function getJson(url) {
    const response = await safeFetch(url, { headers: { accept: 'application/json', 'user-agent': USER_AGENT }, signal: AbortSignal.timeout(options.timeoutMs) }, 'Archive');
    if (!response.ok) throw new Error(`Archive HTTP ${response.status}`);
    return response.json();
  }

  async function search(collection, sort, rows) {
    const params = new URLSearchParams({ q: `collection:${collection} AND mediatype:movies AND licenseurl:*publicdomain* AND -collection:feature_films_unsorted`, rows: String(rows), output: 'json' });
    for (const field of ['identifier', 'title', 'downloads', 'addeddate']) params.append('fl[]', field);
    params.append('sort[]', sort);
    const data = await getJson(`${ARCHIVE}/advancedsearch.php?${params}`);
    return data.response?.docs || [];
  }

  async function detail(doc, type) {
    const data = await getJson(`${ARCHIVE}/metadata/${encodeURIComponent(doc.identifier)}`);
    const meta = data.metadata || {};
    if (!isPublicDomain(meta.licenseurl)) return null; // Periksa ulang lisensi di level item.
    const files = Array.isArray(data.files) ? data.files : [];
    const videos = pickVideos(files);
    if (videos.length === 0) return null;
    const dl = (name) => `${ARCHIVE}/download/${encodeURIComponent(doc.identifier)}/${name.split('/').map(encodeURIComponent).join('/')}`;
    const subtitlesFor = (base) => files
      .filter((f) => /\.(srt|vtt)$/i.test(f.name) && f.name.startsWith(base))
      .map((f) => ({ ...subtitleLang(f.name), url: dl(f.name) }));
    const year = Number.parseInt(String(meta.year || meta.date || '').slice(0, 4), 10);
    // Film wajib punya tahun rilis yang masuk akal; serial boleh tanpa tahun.
    if (Number.isFinite(year) ? year > options.maxYear : type === 'movie') return null;
    const poster = `${ARCHIVE}/services/img/${encodeURIComponent(doc.identifier)}`;
    const base = {
      id: `ia-${slug(doc.identifier)}`,
      type,
      title: stripHtml(meta.title || doc.title).slice(0, 300),
      overview: stripHtml(meta.description).slice(0, 4000),
      posterUrl: poster,
      backdropUrl: poster,
      releaseYear: Number.isFinite(year) ? year : null,
      genres: toGenres(meta.subject),
      tags: (Array.isArray(meta.subject) ? meta.subject : String(meta.subject || '').split(/[;,]/)).map((t) => String(t).trim().toLowerCase()).filter(Boolean),
      language: Array.isArray(meta.language) ? meta.language[0] : (meta.language || null),
      license: `Domain publik (${meta.licenseurl}) via Internet Archive: ${ARCHIVE}/details/${doc.identifier}`,
      addedAtEpochMs: Date.parse(doc.addeddate || meta.addeddate || '') || 0,
      popularity: Number(doc.downloads) || 0,
    };
    if (type === 'series') {
      return {
        ...base,
        episodes: videos.slice(0, 60).map((v, i) => {
          const name = v.base.split('/').pop();
          const se = name.match(/s(\d{1,2})\s*e(\d{1,3})/i);
          const title = stripHtml(v.title || name.replace(/s\d{1,2}\s*e\d{1,3}/i, '').replace(/[_-]+/g, ' ')
            .replace(/([a-z])([A-Z])/g, '$1 $2')).trim();
          return {
          id: `${base.id}-e${i + 1}`,
          title: title || `Episode ${i + 1}`,
          season: se ? Number(se[1]) : 1,
          episode: se ? Number(se[2]) : i + 1,
          durationMin: lengthToMinutes(v.length),
          videoUrl: dl(v.name),
          subtitles: subtitlesFor(v.base),
          };
        }),
      };
    }
    const main = videos.reduce((best, v) => (Number(v.size) > Number(best.size) && v.score >= best.score ? v : best), videos[0]);
    return {
      ...base,
      durationMin: parseRuntime(meta.runtime) || lengthToMinutes(main.length),
      videoUrl: dl(main.name),
      subtitles: subtitlesFor(main.base),
    };
  }

  async function collect(collection, type, limit) {
    if (limit <= 0) return [];
    const [popular, latest] = await Promise.all([
      search(collection, 'downloads desc', limit),
      search(collection, 'addeddate desc', Math.ceil(limit / 2)),
    ]);
    const docs = [...new Map([...popular, ...latest].map((d) => [d.identifier, d])).values()];
    const items = await mapLimit(docs, options.concurrency, async (doc) => {
      try { return await detail(doc, type); } catch (error) { log.debug('Lewati item Archive', { id: doc.identifier, error: error.message }); return null; }
    });
    return items.filter(Boolean);
  }

  return {
    name: 'archive',
    async load() {
      const [movies, series] = await Promise.all([
        collect(options.movieCollection, 'movie', options.maxMovies),
        collect(options.seriesCollection, 'series', options.maxSeries),
      ]);
      return { items: [...movies, ...series], sections: [] };
    },
  };
}

/* ------------------------------------------------------------------ */
/* Layanan katalog: gabungkan adapter, buat section, cache, sinkron.  */
/* ------------------------------------------------------------------ */

export function buildSections(items, explicit = []) {
  const ids = new Set(items.map((m) => m.id));
  const sections = explicit
    .map((s) => ({ title: String(s.title || '').trim(), movieIds: (s.movieIds || []).filter((id) => ids.has(id)) }))
    .filter((s) => s.title && s.movieIds.length);
  const movies = items.filter((m) => m.type === 'movie');
  const series = items.filter((m) => m.type === 'series');
  const add = (title, list) => { if (list.length && !sections.some((s) => s.title === title)) sections.push({ title, movieIds: list.slice(0, 30).map((m) => m.id) }); };
  add('Populer', [...movies].sort((a, b) => b.popularity - a.popularity));
  add('Terbaru ditambahkan', [...movies].sort((a, b) => b.addedAtEpochMs - a.addedAtEpochMs));
  add('Serial TV', [...series].sort((a, b) => b.popularity - a.popularity));
  const genreCount = new Map();
  for (const m of movies) for (const g of m.genres) genreCount.set(g, (genreCount.get(g) || 0) + 1);
  for (const [genre] of [...genreCount].sort((a, b) => b[1] - a[1]).slice(0, 6)) add(genre, movies.filter((m) => m.genres.includes(genre)));
  return sections;
}

export function createMovieService(config, log, providerOverride) {
  const providers = providerOverride || config.providers.map((name) => {
    if (name === 'archive') return archiveProvider(config.archive, log);
    if (name === 'file') return fileProvider(config.catalogFile);
    throw new Error(`MOVIE_PROVIDERS berisi adapter tidak dikenal: ${name}`);
  });
  const blockedIds = new Set(config.blockedIds || []);
  const blockedTerms = config.blockedTerms || [];
  const isBlocked = (item) => blockedIds.has(item.id) || blockedTerms.some((t) =>
    item.title.toLowerCase().includes(t) || item.genres.some((g) => g.toLowerCase().includes(t)) ||
    (item.tags || []).some((g) => g.includes(t)));
  const state = { items: [], byId: new Map(), sections: [], lastSyncAt: 0, lastError: null, syncing: false, version: 0 };

  async function sync() {
    if (state.syncing) return false;
    state.syncing = true;
    const started = Date.now();
    try {
      const results = await Promise.allSettled(providers.map((p) => p.load()));
      const items = new Map();
      const explicit = [];
      const errors = [];
      results.forEach((result, index) => {
        const name = providers[index].name;
        if (result.status === 'rejected') { errors.push(`${name}: ${result.reason?.message || result.reason}`); return; }
        for (const raw of result.value.items) {
          const item = normalizeItem(raw, name);
          if (item && (item.videoUrl || item.trailerUrl) && !items.has(item.id) && !isBlocked(item)) items.set(item.id, item);
        }
        explicit.push(...result.value.sections);
      });
      state.lastError = errors.length ? errors.join('; ') : null;
      if (items.size === 0) {
        log.error('Sinkronisasi film tidak menghasilkan item', { error: state.lastError });
        return false;
      }
      state.items = [...items.values()];
      state.byId = items;
      state.sections = buildSections(state.items, explicit);
      state.lastSyncAt = Date.now();
      state.version += 1;
      log.info('Katalog film diperbarui', { items: items.size, ms: Date.now() - started, errors: errors.length });
      return true;
    } finally {
      state.syncing = false;
    }
  }

  function search(query, type) {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return state.items
      .filter((m) => (!type || m.type === type))
      .map((m) => {
        const title = m.title.toLowerCase();
        const score = title === q ? 3 : title.startsWith(q) ? 2 : title.includes(q) ? 1.5
          : (m.genres.some((g) => g.toLowerCase().includes(q)) || m.overview.toLowerCase().includes(q)) ? 1 : 0;
        return { m, score };
      })
      .filter((r) => r.score > 0)
      .sort((a, b) => b.score - a.score || b.m.popularity - a.m.popularity)
      .map((r) => r.m);
  }

  function list({ type, genre, sort } = {}) {
    let result = state.items.filter((m) => (!type || m.type === type) && (!genre || m.genres.some((g) => g.toLowerCase() === genre.toLowerCase())));
    if (sort === 'latest') result = [...result].sort((a, b) => b.addedAtEpochMs - a.addedAtEpochMs);
    else if (sort === 'rating') result = [...result].sort((a, b) => (b.rating ?? -1) - (a.rating ?? -1));
    else if (sort === 'title') result = [...result].sort((a, b) => a.title.localeCompare(b.title));
    else result = [...result].sort((a, b) => b.popularity - a.popularity);
    return result;
  }

  // Gagal sinkron (mis. jaringan putus) dicoba ulang 1, 2, 4 ... maks 15 menit; sukses kembali ke jadwal normal.
  let retryDelay = 60_000;
  async function syncSafely() {
    let okSync = false;
    try { okSync = await sync(); } catch (e) { log.error('Sinkronisasi film gagal', { error: e.message }); }
    if (okSync) { retryDelay = 60_000; return; }
    log.warn('Sinkronisasi film akan dicoba ulang', { inSeconds: retryDelay / 1000 });
    setTimeout(() => { void syncSafely(); }, retryDelay).unref();
    retryDelay = Math.min(retryDelay * 2, 15 * 60_000);
  }

  function start() {
    void syncSafely();
    setInterval(() => { void syncSafely(); }, config.refreshMs).unref();
  }

  return { state, sync, search, list, start, get: (id) => state.byId.get(id), genres: () => [...new Set(state.items.flatMap((m) => m.genres))].sort() };
}
