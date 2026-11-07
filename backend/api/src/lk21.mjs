import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertPublicHttps, safeFetch, mapLimit, slug } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

export const LK21_GENRES = [
  'action', 'adventure', 'animation', 'biography', 'comedy', 'crime',
  'documentary', 'drama', 'family', 'fantasy', 'history', 'horror',
  'music', 'mystery', 'romance', 'school', 'sci-fi', 'sport', 'thriller', 'war',
];

export const LK21_COUNTRIES = [
  { parameter: 'indonesia', name: 'Indonesia' },
  { parameter: 'usa', name: 'United States' },
  { parameter: 'south-korea', name: 'South Korea' },
  { parameter: 'japan', name: 'Japan' },
  { parameter: 'china', name: 'China' },
  { parameter: 'thailand', name: 'Thailand' },
  { parameter: 'india', name: 'India' },
  { parameter: 'uk', name: 'United Kingdom' },
  { parameter: 'france', name: 'France' },
  { parameter: 'germany', name: 'Germany' },
  { parameter: 'spain', name: 'Spain' },
  { parameter: 'hong-kong', name: 'Hong Kong' },
  { parameter: 'taiwan', name: 'Taiwan' },
  { parameter: 'turkey', name: 'Turkey' },
  { parameter: 'malaysia', name: 'Malaysia' },
  { parameter: 'philippines', name: 'Philippines' },
  { parameter: 'australia', name: 'Australia' },
  { parameter: 'canada', name: 'Canada' },
];

const GENRE_MAP = {
  action: 'Aksi', adventure: 'Petualangan', animation: 'Animasi', biography: 'Biografi',
  comedy: 'Komedi', crime: 'Kriminal', documentary: 'Dokumenter', drama: 'Drama',
  family: 'Keluarga', fantasy: 'Fantasi', history: 'Sejarah', horror: 'Horor',
  music: 'Musikal', mystery: 'Misteri', romance: 'Romantis', school: 'Sekolah',
  'sci-fi': 'Fiksi Ilmiah', sport: 'Olahraga', thriller: 'Thriller', war: 'Perang',
};

function mapGenreName(raw) {
  const clean = String(raw || '').trim();
  if (!clean) return null;
  return GENRE_MAP[clean.toLowerCase()] || (clean.charAt(0).toUpperCase() + clean.slice(1));
}

function stripTags(html) {
  return String(html || '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#0*39;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

function fixUrl(raw, baseOrigin) {
  if (!raw || typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const candidate = trimmed.startsWith('//') ? `https:${trimmed}` : trimmed;
  try {
    const resolved = new URL(candidate, baseOrigin || 'https://tv.lk21official.live').toString();
    return assertPublicHttps(resolved, 'URL LK21').toString();
  } catch {
    return null;
  }
}

function parseYearFromText(...values) {
  for (const val of values) {
    const match = String(val || '').match(/\b(18\d{2}|19\d{2}|20\d{2}|21\d{2})\b/);
    if (match) {
      const year = Number.parseInt(match[1], 10);
      if (year >= 1888 && year <= 2200) return year;
    }
  }
  return null;
}

function parseDurationMin(...values) {
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
      return Math.round(value > 10000 ? value / 60000 : value);
    }
    const text = String(value || '').toLowerCase().trim();
    if (!text) continue;
    const hm = text.match(/(\d{1,2}):(\d{2})/);
    if (hm) return Number.parseInt(hm[1], 10) * 60 + Number.parseInt(hm[2], 10);
    const h = text.match(/(\d+)\s*(?:h|hour|jam)/);
    const m = text.match(/(\d+)\s*(?:m|min|minute|menit)/);
    if (h || m) return Number(h?.[1] || 0) * 60 + Number(m?.[1] || 0);
    const num = text.match(/\b(\d{2,3})\b/);
    if (num) return Number.parseInt(num[1], 10);
  }
  return null;
}

function parseRatingScore(...values) {
  for (const value of values) {
    const match = String(value ?? '').match(/\d+(?:[.,]\d+)?/);
    if (!match) continue;
    const num = Number(match[0].replace(',', '.'));
    if (!Number.isFinite(num)) continue;
    const score = num > 10 && num <= 100 ? num / 10 : num;
    if (score >= 0 && score <= 10) return Math.round(score * 10) / 10;
  }
  return null;
}

function parseGenres(value) {
  if (Array.isArray(value)) {
    return [...new Set(value.map(mapGenreName).filter(Boolean))];
  }
  if (typeof value === 'string') {
    return [...new Set(value.split(/[,|/]/).map(mapGenreName).filter(Boolean))];
  }
  return [];
}

/**
 * Mengurai daftar film/serial dari HTML grid LK21 & NontonDrama
 * (sesuai lk21-api-master/src/scrapers/movie.ts & series.ts).
 */
export function scrapeGridHtml(html, type = 'movie', baseOrigin = 'https://tv.lk21official.live') {
  const items = [];
  const articleRegex = /<article\b[^>]*class=["'][^"']*mega-item[^"']*["'][^>]*>([\s\S]*?)<\/article>/gi;
  let match;
  while ((match = articleRegex.exec(html)) !== null) {
    const block = match[1];
    const hrefMatch = block.match(/<figure[\s\S]*?<a\b[^>]*href=["']([^"']+)["']/i)
      || block.match(/<a\b[^>]*href=["']([^"']+)["']/i);
    if (!hrefMatch) continue;
    const rawId = hrefMatch[1].replace(/\/+$/, '').split('/').pop() || '';
    if (!rawId) continue;

    const imgAlt = block.match(/<img\b[^>]*alt=["']([^"']+)["']/i)?.[1];
    const imgSrc = block.match(/<img\b[^>]*src=["']([^"']+)["']/i)?.[1];
    const ratingText = stripTags(block.match(/<div\b[^>]*class=["'][^"']*rating[^"']*["'][^>]*>([\s\S]*?)<\/div>/i)?.[1] || '');
    const qualityText = stripTags(block.match(/<div\b[^>]*class=["'][^"']*quality[^"']*["'][^>]*>([\s\S]*?)<\/div>/i)?.[1] || '');
    const durationText = stripTags(block.match(/<div\b[^>]*class=["'][^"']*duration[^"']*["'][^>]*>([\s\S]*?)<\/div>/i)?.[1] || '');
    const episodeCount = Number.parseInt(
      stripTags(block.match(/<div\b[^>]*class=["'][^"']*last-episode[^"']*["'][^>]*>[\s\S]*?<span>([\s\S]*?)<\/span>/i)?.[1] || ''),
      10
    ) || 0;

    const genres = [];
    const genreRegex = /href=["'][^"']*\/genre\/([^/"']+)["']/gi;
    let gMatch;
    while ((gMatch = genreRegex.exec(block)) !== null) {
      const mapped = mapGenreName(gMatch[1]);
      if (mapped && !genres.includes(mapped)) genres.push(mapped);
    }

    const title = stripTags(imgAlt || rawId.replace(/-/g, ' '));
    if (!title) continue;
    const posterUrl = fixUrl(imgSrc, baseOrigin);

    items.push({
      _id: rawId,
      id: `lk21-${type === 'series' ? 'series' : 'movie'}-${rawId}`,
      type: type === 'series' ? 'series' : 'movie',
      title,
      posterImg: posterUrl,
      posterUrl,
      backdropUrl: posterUrl,
      releaseYear: parseYearFromText(rawId, title),
      durationMin: parseDurationMin(durationText),
      rating: parseRatingScore(ratingText),
      qualityResolution: qualityText || '',
      episode: episodeCount,
      genres,
    });
  }
  return items;
}

/**
 * Mengurai halaman pencarian (div.search-item) sesuai lk21-api-master/src/scrapers/search.ts.
 */
export function scrapeSearchHtml(html, baseOrigin = 'https://tv.lk21official.live') {
  const items = [];
  const itemRegex = /<div\b[^>]*class=["'][^"']*search-item[^"']*["'][^>]*>([\s\S]*?)<\/div>\s*<\/div>/gi;
  let match;
  while ((match = itemRegex.exec(html)) !== null) {
    const block = match[1];
    const linkMatch = block.match(/<h[23][^>]*>\s*<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/i);
    if (!linkMatch) continue;
    const rawId = linkMatch[1].replace(/\/+$/, '').split('/').pop() || '';
    const title = stripTags(linkMatch[2]);
    if (!rawId || !title || rawId.includes('series-ongoing')) continue;

    const imgSrc = block.match(/<img\b[^>]*src=["']([^"']+)["']/i)?.[1];
    const isSeries = /href=["'][^"']*\/series\//i.test(block) || /\bseries\b/i.test(title);
    const type = isSeries ? 'series' : 'movie';
    const genres = [];
    const genreRegex = /href=["'][^"']*\/genre\/([^/"']+)["']/gi;
    let gMatch;
    while ((gMatch = genreRegex.exec(block)) !== null) {
      const mapped = mapGenreName(gMatch[1]);
      if (mapped && !genres.includes(mapped)) genres.push(mapped);
    }

    const directors = [];
    const casts = [];
    const pRegex = /<p[^>]*>([\s\S]*?)<\/p>/gi;
    let pMatch;
    while ((pMatch = pRegex.exec(block)) !== null) {
      const pText = stripTags(pMatch[1]);
      if (/^sutradara\s*:/i.test(pText)) {
        directors.push(...pText.replace(/^sutradara\s*:/i, '').split(',').map((s) => s.trim()).filter(Boolean));
      } else if (/^bintang\s*:/i.test(pText)) {
        casts.push(...pText.replace(/^bintang\s*:/i, '').split(',').map((s) => s.trim()).filter(Boolean));
      }
    }

    const posterUrl = fixUrl(imgSrc, baseOrigin);
    items.push({
      _id: rawId,
      id: `lk21-${type}-${rawId}`,
      type,
      title,
      posterImg: posterUrl,
      posterUrl,
      backdropUrl: posterUrl,
      releaseYear: parseYearFromText(rawId, title),
      genres,
      directors,
      casts,
    });
  }
  return items;
}

/**
 * Mengurai detail film/serial + daftar season/episode sesuai lk21-api-master/src/scrapers/movie.ts & series.ts.
 */
export function scrapeDetailHtml(html, baseItem = {}, baseOrigin = 'https://tv.lk21official.live') {
  const synopsisBlock = html.match(/<blockquote[^>]*>([\s\S]*?)<\/blockquote>/i)?.[1] || '';
  const cleanedSynopsis = stripTags(synopsisBlock.replace(/<strong[^>]*>[\s\S]*?<\/strong>/gi, '').replace(/^synopsis\s*/i, ''));

  const posterSrc = html.match(/<div\b[^>]*class=["'][^"']*content-poster[^"']*["'][\s\S]*?<img\b[^>]*src=["']([^"']+)["']/i)?.[1];
  const posterAlt = html.match(/<div\b[^>]*class=["'][^"']*content-poster[^"']*["'][\s\S]*?<img\b[^>]*alt=["']([^"']+)["']/i)?.[1];

  const durationMatch = html.match(/<h2[^>]*>\s*Durasi\s*<\/h2>\s*<h3[^>]*>([\s\S]*?)<\/h3>/i)?.[1];
  const releaseMatch = html.match(/<h2[^>]*>\s*Diterbitkan\s*<\/h2>\s*<h3[^>]*>([\s\S]*?)<\/h3>/i)?.[1];
  const ratingMatch = html.match(/<h2[^>]*>\s*IMDb\s*<\/h2>\s*<h3[^>]*>([\s\S]*?)<\/h3>/i)?.[1];
  const statusMatch = html.match(/<h2[^>]*>\s*Status\s*<\/h2>\s*<h3[^>]*>([\s\S]*?)<\/h3>/i)?.[1];
  const qualityMatch = html.match(/<h2[^>]*>\s*Kualitas\s*<\/h2>\s*<h3[^>]*>([\s\S]*?)<\/h3>/i)?.[1];

  const trailerHref = html.match(/<a\b[^>]*class=["'][^"']*fancybox[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1]
    || html.match(/<div\b[^>]*class=["'][^"']*player-content[^"']*["'][\s\S]*?<iframe\b[^>]*src=["']([^"']+)["']/i)?.[1];

  const extractLinksAfterH2 = (label) => {
    const regex = new RegExp(`<h2[^>]*>\\s*${label}\\s*<\\/h2>([\\s\\S]*?)<\\/div>`, 'i');
    const sec = html.match(regex)?.[1] || '';
    const out = [];
    const aRegex = /<a[^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = aRegex.exec(sec)) !== null) {
      const val = stripTags(m[1]);
      if (val) out.push(val);
    }
    return out;
  };

  const genres = extractLinksAfterH2('Genre').map(mapGenreName).filter(Boolean);
  const directors = extractLinksAfterH2('Sutradara');
  const countries = extractLinksAfterH2('Negara');
  const casts = extractLinksAfterH2('Bintang Film');

  // Parse daftar season & jumlah episode untuk series (div.serial-wrapper > div.episode-list)
  const seasons = [];
  const epListRegex = /<div\b[^>]*class=["'][^"']*episode-list[^"']*["'][^>]*>([\s\S]*?)<\/div>/gi;
  const epBlocks = [];
  let epMatch;
  while ((epMatch = epListRegex.exec(html)) !== null) {
    epBlocks.push(epMatch[1]);
  }
  if (epBlocks.length > 0) {
    for (let i = epBlocks.length; i >= 1; i -= 1) {
      const block = epBlocks[epBlocks.length - i];
      const btnMatches = block.match(/<a\b[^>]*class=["'][^"']*btn-primary[^"']*["']/gi)
        || block.match(/<a\b[^>]*href=/gi)
        || [];
      seasons.push({
        season: i,
        totalEpisodes: btnMatches.length,
      });
    }
  }

  const posterUrl = fixUrl(posterSrc, baseOrigin) || baseItem.posterUrl || null;

  return {
    ...baseItem,
    title: stripTags(posterAlt) || baseItem.title || '',
    posterImg: posterUrl || '',
    posterUrl,
    backdropUrl: posterUrl,
    synopsis: cleanedSynopsis || baseItem.overview || '',
    overview: cleanedSynopsis || baseItem.overview || '',
    duration: stripTags(durationMatch) || '',
    durationMin: parseDurationMin(stripTags(durationMatch)) || baseItem.durationMin || null,
    releaseDate: stripTags(releaseMatch) || '',
    releaseYear: parseYearFromText(stripTags(releaseMatch)) || baseItem.releaseYear || null,
    rating: parseRatingScore(stripTags(ratingMatch)) ?? baseItem.rating ?? null,
    status: stripTags(statusMatch).toLowerCase() || undefined,
    quality: stripTags(qualityMatch) || undefined,
    trailerUrl: fixUrl(trailerHref, baseOrigin) || baseItem.trailerUrl || null,
    genres: genres.length ? genres : (baseItem.genres || []),
    directors,
    countries,
    casts,
    seasons,
  };
}

/**
 * Mengurai sumber streaming (div#load-sources) sesuai lk21-api-master/src/scrapers/stream.ts.
 */
export function scrapeStreamSourcesHtml(html) {
  const sources = [];
  const listBlock = html.match(/<div\b[^>]*id=["']load-sources["'][^>]*>([\s\S]*?)<\/div>/i)?.[1] || '';
  const liRegex = /<li[^>]*>([\s\S]*?)<\/li>/gi;
  let match;
  while ((match = liRegex.exec(listBlock)) !== null) {
    const item = match[1];
    const provider = stripTags(item.match(/<a[^>]*>([\s\S]*?)<\/a>/i)?.[1] || '');
    const rawUrl = item.match(/<a\b[^>]*href=["']([^"']+)["']/i)?.[1] || '';
    const url = rawUrl.startsWith('//') ? `https:${rawUrl}` : rawUrl;
    const resolutions = [];
    const spanRegex = /<span[^>]*>([\s\S]*?)<\/span>/gi;
    let sMatch;
    while ((sMatch = spanRegex.exec(item)) !== null) {
      const res = stripTags(sMatch[1]);
      if (res) resolutions.push(res);
    }
    if (url) sources.push({ provider: provider || 'Server', url, resolutions });
  }

  if (sources.length === 0) {
    const iframeSrc = html.match(/<iframe\b[^>]*id=["']player-iframe["'][^>]*src=["']([^"']+)["']/i)?.[1]
      || html.match(/<div\b[^>]*id=["']load-player["'][\s\S]*?<iframe\b[^>]*src=["']([^"']+)["']/i)?.[1];
    if (iframeSrc) {
      sources.push({
        provider: 'Primary',
        url: iframeSrc.startsWith('//') ? `https:${iframeSrc}` : iframeSrc,
        resolutions: ['HD'],
      });
    }
  }
  return sources;
}

/**
 * Mengurai daftar link download (tbody > tr) sesuai lk21-api-master/src/scrapers/download.ts.
 */
export function scrapeDownloadsHtml(html) {
  const downloads = [];
  const tbody = html.match(/<tbody[^>]*>([\s\S]*?)<\/tbody>/i)?.[1] || '';
  const trRegex = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
  let match;
  while ((match = trRegex.exec(tbody)) !== null) {
    const row = match[1];
    const server = stripTags(row.match(/<strong[^>]*>([\s\S]*?)<\/strong>/i)?.[1] || '');
    const link = row.match(/<a\b[^>]*href=["']([^"']+)["']/i)?.[1] || '';
    const cls = row.match(/<a\b[^>]*class=["']([^"']+)["']/i)?.[1] || '';
    const quality = cls.length >= 13 ? cls.substring(9, 13).trim() : 'HD';
    if (server && link) downloads.push({ server, link, quality });
  }
  return downloads;
}

/**
 * Membangun daftar EpisodeDto Bytify dari array seasons [{ season, totalEpisodes }].
 */
export function buildEpisodesFromSeasons(rawSlug, seasons, publicBaseUrl, durationMin = 45) {
  const base = (publicBaseUrl || 'http://10.0.2.2:8787').replace(/\/+$/, '');
  const episodes = [];
  for (const s of Array.isArray(seasons) ? seasons : []) {
    const sNum = Number.parseInt(s.season, 10) || 1;
    const total = Math.min(100, Math.max(1, Number.parseInt(s.totalEpisodes, 10) || 1));
    for (let ep = 1; ep <= total; ep += 1) {
      episodes.push({
        id: `lk21-series-${rawSlug}-s${sNum}e${ep}`,
        title: `Season ${sNum} · Episode ${ep}`,
        season: sNum,
        episode: ep,
        durationMin: durationMin || 45,
        videoUrl: `${base}/api/lk21/resolve/series/${encodeURIComponent(rawSlug)}?season=${sNum}&episode=${ep}`,
        subtitles: [],
      });
    }
  }
  return episodes;
}

/**
 * Menormalkan objek film/serial LK21 ke skema kontrak Bytify (`MovieDto`).
 */
export function normalizeLk21Item(raw, baseOrigin = 'https://tv.lk21official.live', publicBaseUrl = 'http://10.0.2.2:8787') {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const title = stripTags(raw.title || raw.name || '');
  if (!title) return null;

  const type = raw.type === 'series' ? 'series' : 'movie';
  const year = parseYearFromText(raw.releaseYear, raw.year, raw.releaseDate, raw._id, raw.id, title);
  const rawSlug = String(raw._id || raw.slug || raw.id || '')
    .trim()
    .replace(/^lk21-(?:movie|series)-/, '')
    .replace(/^lk21-/, '') || `${slug(title)}${year ? `-${year}` : ''}`;

  const id = `lk21-${type}-${rawSlug}`;
  const poster = fixUrl(raw.posterUrl || raw.posterImg || raw.poster, baseOrigin);
  const backdrop = fixUrl(raw.backdropUrl || raw.backdrop, baseOrigin) || poster;
  const genres = parseGenres(raw.genres);
  const overview = stripTags(raw.overview || raw.synopsis || '');
  const rating = parseRatingScore(raw.rating);
  const durationMin = parseDurationMin(raw.durationMin, raw.duration) || (type === 'series' ? 45 : 110);
  const base = (publicBaseUrl || 'http://10.0.2.2:8787').replace(/\/+$/, '');

  const trailerCandidate = fixUrl(raw.trailerUrl, baseOrigin);
  const trailerUrl =
    trailerCandidate ||
    `https://www.youtube.com/results?search_query=${encodeURIComponent(`${title} ${year || ''} official trailer`.trim())}`;

  let episodes = Array.isArray(raw.episodes) ? raw.episodes : [];
  if (type === 'series' && episodes.length === 0) {
    if (Array.isArray(raw.seasons) && raw.seasons.length > 0) {
      episodes = buildEpisodesFromSeasons(rawSlug, raw.seasons, base, durationMin);
    } else {
      const epCount = Math.max(1, Number.parseInt(raw.episode, 10) || 1);
      episodes = buildEpisodesFromSeasons(rawSlug, [{ season: 1, totalEpisodes: epCount }], base, durationMin);
    }
  }

  return {
    id,
    _rawSlug: rawSlug,
    type,
    title,
    overview: overview || `Tonton ${title} Subtitle Indonesia di Bytify.`,
    posterUrl: poster,
    backdropUrl: backdrop,
    releaseYear: year,
    durationMin,
    genres: genres.length ? genres : [type === 'series' ? 'Serial' : 'Film'],
    rating: rating ?? 7.5,
    language: 'id',
    videoUrl: `${base}/api/lk21/resolve/${type}/${encodeURIComponent(rawSlug)}`,
    trailerUrl,
    subtitles: [],
    episodes,
    license: 'LK21 / NontonDrama Stream Adapter',
    popularity: Number(raw.popularity) || 75,
    addedAtEpochMs: Number(raw.addedAtEpochMs) || Date.now(),
  };
}

export function lk21Provider(options = {}, log) {
  const lk21Url = options.lk21Url || 'https://tv.lk21official.live';
  const ndUrl = options.ndUrl || 'https://tv.nontondrama.lol';
  const dlUrl = options.dlUrl || 'https://dl.lk21static.xyz';
  const publicBaseUrl = options.publicBaseUrl || 'http://10.0.2.2:8787';
  const apiBaseUrl = (options.apiBaseUrl || '').trim().replace(/\/+$/, '');
  const maxPages = options.maxPages || 2;
  const timeoutMs = options.timeoutMs || 15000;
  const concurrency = options.concurrency || 4;
  const cacheFile = options.cacheFile
    ? path.resolve(HERE, '..', options.cacheFile)
    : path.resolve(HERE, '../data/lk21-cache.json');

  function mapApiItem(raw, typeOverride, origin) {
    if (!raw || typeof raw !== 'object') return null;
    const item = { ...raw, type: raw.type || typeOverride };
    const normalized = normalizeLk21Item({
      ...item,
      id: item._id || item.id || item.slug || item.url || `${typeOverride}-${Date.now()}`,
      title: item.title || item.name || item.original_title || 'Judul tidak diketahui',
      posterUrl: item.posterUrl || item.posterImg || item.poster || item.poster_url || item.image,
      backdropUrl: item.backdropUrl || item.backdrop || item.posterUrl || item.posterImg || item.poster,
      overview: item.overview || item.synopsis || item.description || '',
      genres: Array.isArray(item.genres) ? item.genres : typeof item.genres === 'string' ? item.genres.split(',') : [],
      rating: item.rating || item.imdb_rating || item.score,
      durationMin: item.durationMin || item.duration || item.runtime,
      releaseYear: item.releaseYear || item.release_date || item.year,
      trailerUrl: item.trailerUrl || item.trailer || null,
      popularity: Number(item.popularity || item.popular || 75),
      addedAtEpochMs: Number(item.addedAtEpochMs || item.created_at || Date.now()),
    }, origin, publicBaseUrl);
    return normalized;
  }

  async function fetchApiJson(url, label) {
    const res = await safeFetch(
      url,
      {
        headers: {
          accept: 'application/json',
          'user-agent': USER_AGENT,
        },
        signal: AbortSignal.timeout(timeoutMs),
      },
      label
    );
    if (!res.ok) throw new Error(`${label} HTTP ${res.status}`);
    return res.json();
  }

  async function loadFromApi() {
    if (!apiBaseUrl) return { items: [], sections: [] };
    const targets = [
      { title: 'Film Populer LK21', url: `${apiBaseUrl}/popular/movies?page=1`, type: 'movie', pop: 100 },
      { title: 'Film Terbaru LK21', url: `${apiBaseUrl}/movies?page=1`, type: 'movie', pop: 92 },
      { title: 'Serial Populer LK21', url: `${apiBaseUrl}/popular/series?page=1`, type: 'series', pop: 90 },
      { title: 'Serial Terbaru LK21', url: `${apiBaseUrl}/series?page=1`, type: 'series', pop: 85 },
    ];
    const merged = new Map();
    const sections = [];
    for (const target of targets) {
      try {
        const payload = await fetchApiJson(target.url, 'LK21 API');
        const rows = Array.isArray(payload) ? payload : Array.isArray(payload.data) ? payload.data : Array.isArray(payload.movies) ? payload.movies : [];
        const ids = [];
        for (const row of rows) {
          const item = mapApiItem(row, target.type, target.type === 'series' ? ndUrl : lk21Url);
          if (!item) continue;
          item.popularity = Math.max(item.popularity, target.pop);
          if (!merged.has(item.id)) merged.set(item.id, item);
          if (!ids.includes(item.id)) ids.push(item.id);
        }
        if (ids.length) sections.push({ title: target.title, movieIds: ids });
      } catch (error) {
        log?.debug('Lewati sumber custom LK21 API', { url: target.url, error: error.message });
      }
    }
    return { items: [...merged.values()], sections };
  }

  async function fetchHtml(url, referer = lk21Url) {
    const res = await safeFetch(
      url,
      {
        headers: {
          accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          'user-agent': USER_AGENT,
          referer,
        },
        signal: AbortSignal.timeout(timeoutMs),
      },
      'LK21 Web'
    );
    if (!res.ok) throw new Error(`LK21 HTTP ${res.status} pada ${url}`);
    return res.text();
  }

  async function saveCache(data) {
    try {
      await fs.mkdir(path.dirname(cacheFile), { recursive: true });
      await fs.writeFile(`${cacheFile}.tmp`, JSON.stringify({ cachedAtEpochMs: Date.now(), ...data }), 'utf8');
      await fs.rename(`${cacheFile}.tmp`, cacheFile);
    } catch (e) {
      log?.debug('Gagal menyimpan cache LK21', { error: e.message });
    }
  }

  async function readCache() {
    try {
      const parsed = JSON.parse(await fs.readFile(cacheFile, 'utf8'));
      if (parsed && Array.isArray(parsed.items) && Array.isArray(parsed.sections)) return parsed;
    } catch {
      // Abaikan jika belum ada cache
    }
    return null;
  }

  async function loadFromDirectScrape() {
    const targets = [
      { title: 'Film Populer LK21', origin: lk21Url, path: '/populer', type: 'movie', pop: 100 },
      { title: 'Film Terbaru LK21', origin: lk21Url, path: '/latest', type: 'movie', pop: 92 },
      { title: 'Rilis Terbaru LK21', origin: lk21Url, path: '/release', type: 'movie', pop: 88 },
      { title: 'Rating Tertinggi LK21', origin: lk21Url, path: '/rating', type: 'movie', pop: 95 },
      { title: 'Serial Populer LK21', origin: ndUrl, path: '/populer', type: 'series', pop: 90 },
      { title: 'Serial Terbaru LK21', origin: ndUrl, path: '/latest-series', type: 'series', pop: 85 },
    ];
    const merged = new Map();
    const sections = [];

    for (const target of targets) {
      const ids = [];
      for (let page = 1; page <= maxPages; page += 1) {
        try {
          const suffix = page > 1 ? `/page/${page}` : '';
          const html = await fetchHtml(`${target.origin}${target.path}${suffix}`, target.origin);
          const list = scrapeGridHtml(html, target.type, target.origin);
          if (list.length === 0) break;
          list.forEach((raw, idx) => {
            const norm = normalizeLk21Item({ ...raw, popularity: target.pop - idx }, target.origin, publicBaseUrl);
            if (norm) {
              norm._origin = target.origin;
              if (!merged.has(norm.id)) merged.set(norm.id, norm);
              if (!ids.includes(norm.id)) ids.push(norm.id);
            }
          });
        } catch (err) {
          log?.debug('Lewati halaman scrape LK21', { path: target.path, page, error: err.message });
          break;
        }
      }
      if (ids.length) sections.push({ title: target.title, movieIds: ids });
    }

    const toEnrich = [...merged.values()].slice(0, 16);
    await mapLimit(toEnrich, concurrency, async (item) => {
      if (!item._rawSlug) return;
      try {
        const html = await fetchHtml(`${item._origin}/${encodeURIComponent(item._rawSlug)}`, item._origin);
        const detailed = scrapeDetailHtml(html, item, item._origin);
        Object.assign(item, normalizeLk21Item({ ...item, ...detailed, _id: item._rawSlug }, item._origin, publicBaseUrl));
      } catch {
        // Biarkan ringkasan tetap utuh bila gagal
      }
    });

    for (const item of merged.values()) {
      delete item._rawSlug;
      delete item._origin;
    }

    return { items: [...merged.values()], sections };
  }

  async function searchRemote(query) {
    const q = String(query || '').trim();
    if (q.length < 2) return [];
    if (apiBaseUrl) {
      try {
        const payload = await fetchApiJson(`${apiBaseUrl}/search/${encodeURIComponent(q)}`, 'LK21 Search API');
        const rows = Array.isArray(payload) ? payload : Array.isArray(payload.data) ? payload.data : Array.isArray(payload.movies) ? payload.movies : [];
        return rows.map((row) => mapApiItem(row, row.type === 'series' ? 'series' : 'movie', row.type === 'series' ? ndUrl : lk21Url)).filter(Boolean);
      } catch (err) {
        log?.debug('Search API custom LK21 gagal', { query: q, error: err.message });
      }
    }
    try {
      const html = await fetchHtml(`${lk21Url}/?s=${encodeURIComponent(q)}`, lk21Url);
      return scrapeSearchHtml(html, lk21Url)
        .map((raw, idx) => normalizeLk21Item({ ...raw, popularity: 80 - idx }, raw.type === 'series' ? ndUrl : lk21Url, publicBaseUrl))
        .filter(Boolean);
    } catch (err) {
      log?.debug('Search remote LK21 gagal', { query: q, error: err.message });
      return [];
    }
  }

  async function fetchDetails(type, rawSlug) {
    const cleanType = type === 'series' ? 'series' : 'movie';
    const origin = cleanType === 'series' ? ndUrl : lk21Url;
    if (apiBaseUrl) {
      try {
        const detailUrl = `${apiBaseUrl}/${cleanType === 'series' ? 'series' : 'movies'}/${encodeURIComponent(rawSlug)}`;
        const payload = await fetchApiJson(detailUrl, 'LK21 Detail API');
        const item = mapApiItem(payload, cleanType, origin);
        return { raw: payload, item };
      } catch (err) {
        log?.debug('Detail API custom LK21 gagal', { type: cleanType, rawSlug, error: err.message });
      }
    }
    const html = await fetchHtml(`${origin}/${encodeURIComponent(rawSlug)}`, origin);
    const scraped = scrapeDetailHtml(html, { _id: rawSlug, type: cleanType }, origin);
    const normalized = normalizeLk21Item({ ...scraped, _id: rawSlug, type: cleanType }, origin, publicBaseUrl);
    return { raw: scraped, item: normalized };
  }

  async function fetchStreams(type, rawSlug, season = 1, episode = 1) {
    const cleanType = type === 'series' ? 'series' : 'movie';
    let targetUrl = `${lk21Url}/${encodeURIComponent(rawSlug)}`;
    let origin = lk21Url;

    if (cleanType === 'series') {
      origin = ndUrl;
      const parts = String(rawSlug).split('-');
      const last = parts[parts.length - 1];
      if (/^\d{4}$/.test(last)) {
        const year = parts.pop();
        const seriesBase = parts.join('-');
        targetUrl = `${ndUrl}/${encodeURIComponent(`${seriesBase}-season-${season}-episode-${episode}-${year}`)}`;
      } else {
        targetUrl = `${ndUrl}/${encodeURIComponent(`${rawSlug}-season-${season}-episode-${episode}`)}`;
      }
    }

    const html = await fetchHtml(targetUrl, origin);
    return scrapeStreamSourcesHtml(html);
  }

  async function fetchListByPath(originType, pathSuffix, page = 1) {
    const origin = originType === 'series' ? ndUrl : lk21Url;
    const pagePart = Number(page) > 1 ? `/page/${Number(page)}` : '';
    const html = await fetchHtml(`${origin}${pathSuffix}${pagePart}`, origin);
    return scrapeGridHtml(html, originType === 'series' ? 'series' : 'movie', origin);
  }

  async function fetchDownloads(type, rawSlug, season = 1, episode = 1) {
    let targetSlug = rawSlug;
    if (type === 'series') {
      const parts = String(rawSlug).split('-');
      const year = parts.pop();
      const seriesId = parts.join('-');
      targetSlug = `${seriesId}-season-${season}-episode-${episode}-${year}`;
    }
    const getRes = await safeFetch(`${dlUrl}/get/${encodeURIComponent(targetSlug)}`, {
      headers: { 'user-agent': USER_AGENT },
      signal: AbortSignal.timeout(timeoutMs),
    }, 'LK21 DL');
    const getHtml = await getRes.text();
    const marker = "setCookie('validate'";
    const idx = getHtml.indexOf(marker);
    const cookieVal = idx !== -1 ? `validate=${getHtml.substring(idx + 23, idx + 63)}` : '';

    const verifyRes = await safeFetch(`${dlUrl}/verifying.php`, {
      method: 'POST',
      headers: {
        'content-type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'user-agent': USER_AGENT,
        ...(cookieVal ? { cookie: cookieVal } : {}),
      },
      body: new URLSearchParams({ slug: targetSlug }).toString(),
      signal: AbortSignal.timeout(timeoutMs),
    }, 'LK21 Verify DL');
    const verifyHtml = await verifyRes.text();
    return scrapeDownloadsHtml(verifyHtml);
  }

  return {
    name: 'lk21',
    searchRemote,
    fetchDetails,
    fetchStreams,
    fetchListByPath,
    fetchDownloads,
    async load() {
      if (apiBaseUrl) {
        try {
          const apiData = await loadFromApi();
          if (apiData.items.length > 0) {
            await saveCache(apiData);
            return apiData;
          }
        } catch (err) {
          log?.warn('Sinkronisasi LK21 custom API gagal, fallback ke scrape', { error: err.message });
        }
      }
      try {
        const data = await loadFromDirectScrape();
        if (data.items.length > 0) {
          await saveCache(data);
          return data;
        }
      } catch (err) {
        log?.warn('Sinkronisasi langsung LK21 gagal, memeriksa cache disk', { error: err.message });
      }
      const cached = await readCache();
      if (cached && cached.items.length > 0) {
        return cached;
      }
      return { items: [], sections: [] };
    },
  };
}