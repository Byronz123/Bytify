import http from 'node:http';
import { createHash } from 'node:crypto';
import { ApiError, paginate } from './util.mjs';
import { LK21_COUNTRIES, LK21_GENRES } from './lk21.mjs';

/**
 * Router HTTP Bytify API. Respons kontrak aplikasi (`/api/catalog.json`, `/api/live/channels`)
 * memakai bentuk JSON yang dibaca APK; endpoint lain memakai amplop { ok, data, meta } / { ok:false, error }.
 */
export function createApp({ movies, live, log, config = {} }) {
  const startedAt = Date.now();
  const cache = new Map(); // key -> { version, body, etag }

  function send(request, response, status, body, { maxAge = 0, cacheKey, version, redirectLocation } = {}) {
    if (redirectLocation) {
      const headers = {
        location: redirectLocation,
        'cache-control': 'no-store',
      };
      if (config.corsOrigin) headers['access-control-allow-origin'] = config.corsOrigin;
      response.writeHead(status, headers);
      return response.end();
    }

    let payload;
    let etag;
    if (cacheKey && version !== undefined) {
      const hit = cache.get(cacheKey);
      if (hit && hit.version === version) ({ body: payload, etag } = hit);
    }
    if (!payload) {
      payload = JSON.stringify(body);
      etag = `"${createHash('sha1').update(payload).digest('base64url').slice(0, 27)}"`;
      if (cacheKey && version !== undefined) {
        if (cache.size > 500) cache.clear();
        cache.set(cacheKey, { version, body: payload, etag });
      }
    }
    const headers = {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': maxAge > 0 ? `public, max-age=${maxAge}` : 'no-store',
      'x-content-type-options': 'nosniff',
      etag,
    };
    if (config.corsOrigin) headers['access-control-allow-origin'] = config.corsOrigin;
    if (status === 200 && request.headers['if-none-match'] === etag) {
      response.writeHead(304, headers);
      return response.end();
    }
    headers['content-length'] = Buffer.byteLength(payload);
    response.writeHead(status, headers);
    response.end(request.method === 'HEAD' ? undefined : payload);
  }

  const ok = (data, meta) => (meta ? { ok: true, data, meta } : { ok: true, data });
  const summary = (m) => ({
    id: m.id, type: m.type, title: m.title, posterUrl: m.posterUrl, backdropUrl: m.backdropUrl,
    releaseYear: m.releaseYear, durationMin: m.durationMin, genres: m.genres, rating: m.rating,
    episodeCount: m.episodes.length,
  });
  const contract = ({ provider, popularity, addedAtEpochMs, tags, _rawSlug, _origin, ...rest }) => rest;

  function requireMovies() {
    if (movies.state.items.length === 0) {
      throw new ApiError(503, 'CATALOG_NOT_READY', movies.state.lastError
        ? `Katalog film belum tersedia: ${movies.state.lastError}`
        : 'Katalog film sedang disinkronkan. Coba lagi sebentar lagi.');
    }
  }

  function requireLive() {
    if (!live.enabled) throw new ApiError(503, 'LIVE_NOT_CONFIGURED', 'Live TV belum dikonfigurasi pada server.');
  }

  const routes = [
    ['/health', () => [200, { ok: true, uptimeSeconds: Math.round((Date.now() - startedAt) / 1000) }]],

    ['/api/catalog.json', () => {
      requireMovies();
      return [200, { movies: movies.state.items.map(contract), sections: movies.state.sections, generatedAtEpochMs: movies.state.lastSyncAt },
        { maxAge: 300, cacheKey: 'catalog', version: movies.state.version }];
    }],

    ['/api/movies', (url) => {
      requireMovies();
      const page = paginate(movies.list({ type: 'movie', genre: url.searchParams.get('genre'), sort: url.searchParams.get('sort') }), url.searchParams);
      return [200, ok(page.items.map(summary), page.meta), { maxAge: 120 }];
    }],

    [/^\/api\/movies\/([^/]+)$/, async (url, [, rawId]) => {
      const id = decodeURIComponent(rawId);
      const item = movies.getOrEnrich ? await movies.getOrEnrich(id) : movies.get(id);
      if (!item) throw new ApiError(404, 'NOT_FOUND', 'Film atau serial tidak ditemukan.');
      return [200, ok(contract(item)), { maxAge: 300 }];
    }],

    ['/api/series', (url) => {
      requireMovies();
      const page = paginate(movies.list({ type: 'series', genre: url.searchParams.get('genre'), sort: url.searchParams.get('sort') }), url.searchParams);
      return [200, ok(page.items.map(summary), page.meta), { maxAge: 120 }];
    }],

    ['/api/genres', () => { requireMovies(); return [200, ok(movies.genres()), { maxAge: 600 }]; }],

    ['/api/search', async (url) => {
      const q = (url.searchParams.get('q') || '').trim();
      if (q.length < 2) throw new ApiError(400, 'QUERY_TOO_SHORT', 'Parameter q minimal 2 karakter.');
      if (q.length > 100) throw new ApiError(400, 'QUERY_TOO_LONG', 'Parameter q maksimal 100 karakter.');
      const type = url.searchParams.get('type');
      const ql = q.toLowerCase();
      const filterType = type === 'movie' || type === 'series' ? type : undefined;
      const foundMovies = movies.searchAsync ? await movies.searchAsync(q, filterType) : movies.search(q, filterType);
      const movieHits = foundMovies.map(summary);
      const channelHits = type && type !== 'live' ? [] : live.list().filter((c) =>
        c.name.toLowerCase().includes(ql) || c.category.toLowerCase().includes(ql)).map((c) => ({ ...c, type: 'live' }));
      const page = paginate([...movieHits, ...channelHits], url.searchParams);
      return [200, ok(page.items, { ...page.meta, query: q }), { maxAge: 60 }];
    }],

    /* ------------------------------------------------------------------ */
    /* Endpoint Khusus Integrasi LK21 untuk Bytify APK & lk21-api-master  */
    /* ------------------------------------------------------------------ */

    [/^\/api\/lk21\/details\/(movie|series)\/([^/]+)$/, async (_url, [, type, slugParam]) => {
      const rawSlug = decodeURIComponent(slugParam);
      const fullId = `lk21-${type}-${rawSlug}`;
      const enriched = movies.getOrEnrich ? await movies.getOrEnrich(fullId) : null;
      if (!enriched) throw new ApiError(404, 'NOT_FOUND', 'Detail judul LK21 tidak ditemukan.');
      return [200, contract(enriched), { maxAge: 300 }];
    }],

    [/^\/api\/lk21\/streams\/(movie|series)\/([^/]+)$/, async (url, [, type, slugParam]) => {
      const rawSlug = decodeURIComponent(slugParam);
      const season = Number.parseInt(url.searchParams.get('season') || '1', 10) || 1;
      const episode = Number.parseInt(url.searchParams.get('episode') || '1', 10) || 1;
      const sources = await movies.lk21.fetchStreams(type, rawSlug, season, episode);
      return [200, { ok: true, sources }];
    }],

    [/^\/api\/lk21\/resolve\/(movie|series)\/([^/]+)$/, async (url, [, type, slugParam]) => {
      const rawSlug = decodeURIComponent(slugParam);
      const season = Number.parseInt(url.searchParams.get('season') || '1', 10) || 1;
      const episode = Number.parseInt(url.searchParams.get('episode') || '1', 10) || 1;
      const sources = await movies.lk21.fetchStreams(type, rawSlug, season, episode);
      if (!sources || sources.length === 0) {
        throw new ApiError(404, 'STREAM_NOT_FOUND', 'Sumber stream tidak ditemukan untuk judul ini.');
      }
      const direct = sources.find((s) => /\.(m3u8|mp4)(\?|$)/i.test(s.url));
      const chosen = direct || sources[0];
      return [302, null, { redirectLocation: chosen.url }];
    }],

    ['/api/lk21/search', async (url) => {
      const q = (url.searchParams.get('q') || '').trim();
      if (q.length < 2) return [200, { movies: [] }];
      const results = movies.searchAsync ? await movies.searchAsync(q) : movies.search(q);
      return [200, { movies: results.map(contract) }];
    }],

    // Route kompatibilitas langsung lk21-api-master:
    ['/movies', async (url) => [200, await movies.lk21.fetchListByPath('movie', '/latest', url.searchParams.get('page') || 1)]],
    ['/popular/movies', async (url) => [200, await movies.lk21.fetchListByPath('movie', '/populer', url.searchParams.get('page') || 1)]],
    ['/recent-release/movies', async (url) => [200, await movies.lk21.fetchListByPath('movie', '/release', url.searchParams.get('page') || 1)]],
    ['/top-rated/movies', async (url) => [200, await movies.lk21.fetchListByPath('movie', '/rating', url.searchParams.get('page') || 1)]],
    [/^\/movies\/([^/]+)$/, async (_url, [, id]) => {
      const { raw } = await movies.lk21.fetchDetails('movie', decodeURIComponent(id));
      return [200, raw];
    }],
    [/^\/movies\/([^/]+)\/streams$/, async (_url, [, id]) => [200, await movies.lk21.fetchStreams('movie', decodeURIComponent(id))]],
    [/^\/movies\/([^/]+)\/download$/, async (_url, [, id]) => [200, await movies.lk21.fetchDownloads('movie', decodeURIComponent(id))]],

    ['/series', async (url) => [200, await movies.lk21.fetchListByPath('series', '/latest-series', url.searchParams.get('page') || 1)]],
    ['/popular/series', async (url) => [200, await movies.lk21.fetchListByPath('series', '/populer', url.searchParams.get('page') || 1)]],
    ['/recent-release/series', async (url) => [200, await movies.lk21.fetchListByPath('series', '/release', url.searchParams.get('page') || 1)]],
    ['/top-rated/series', async (url) => [200, await movies.lk21.fetchListByPath('series', '/rating', url.searchParams.get('page') || 1)]],
    [/^\/series\/([^/]+)$/, async (_url, [, id]) => {
      const { raw } = await movies.lk21.fetchDetails('series', decodeURIComponent(id));
      return [200, raw];
    }],
    [/^\/series\/([^/]+)\/streams$/, async (url, [, id]) => {
      const season = Number.parseInt(url.searchParams.get('season') || '1', 10) || 1;
      const episode = Number.parseInt(url.searchParams.get('episode') || '1', 10) || 1;
      return [200, await movies.lk21.fetchStreams('series', decodeURIComponent(id), season, episode)];
    }],
    [/^\/series\/([^/]+)\/downloads$/, async (url, [, id]) => {
      const season = Number.parseInt(url.searchParams.get('season') || '1', 10) || 1;
      const episode = Number.parseInt(url.searchParams.get('episode') || '1', 10) || 1;
      return [200, await movies.lk21.fetchDownloads('series', decodeURIComponent(id), season, episode)];
    }],
    ['/genres', () => [200, LK21_GENRES.map((g) => ({ parameter: g, name: g.charAt(0).toUpperCase() + g.slice(1), url: `/genres/${g}` }))]],
    [/^\/genres\/([^/]+)$/, async (url, [, genre]) => [200, await movies.lk21.fetchListByPath('movie', `/genre/${encodeURIComponent(genre.toLowerCase())}`, url.searchParams.get('page') || 1)]],
    ['/countries', () => [200, LK21_COUNTRIES.map((c) => ({ ...c, url: `/countries/${c.parameter}` }))]],
    [/^\/countries\/([^/]+)$/, async (url, [, country]) => [200, await movies.lk21.fetchListByPath('movie', `/country/${encodeURIComponent(country.toLowerCase())}`, url.searchParams.get('page') || 1)]],
    [/^\/years\/([^/]+)$/, async (url, [, year]) => [200, await movies.lk21.fetchListByPath('movie', `/year/${encodeURIComponent(year)}`, url.searchParams.get('page') || 1)]],
    [/^\/search\/([^/]+)$/, async (_url, [, title]) => [200, await movies.lk21.searchRemote(decodeURIComponent(title))]],

    /* ------------------------------------------------------------------ */
    /* Live TV & Sync Status                                              */
    /* ------------------------------------------------------------------ */

    ['/api/live/channels', (url) => {
      requireLive();
      let list = live.list();
      const country = url.searchParams.get('country');
      const category = url.searchParams.get('category');
      if (country) list = list.filter((c) => c.country.toLowerCase() === country.toLowerCase());
      if (category) list = list.filter((c) => c.category.toLowerCase() === category.toLowerCase());
      if (url.searchParams.get('available') === 'true') list = list.filter((c) => c.isAvailable);
      const body = {
        schemaVersion: 1,
        generatedAtEpochMs: Date.now(),
        refreshAfterSeconds: Math.max(60, Math.floor((config.live?.healthCheckMs || 120000) / 1000)),
        sourceSyncError: live.state.sourceSyncError,
      };
      if (url.searchParams.has('page')) {
        const page = paginate(list, url.searchParams, { defaultSize: 50, maxSize: 200 });
        return [200, { ...body, channels: page.items, meta: page.meta }];
      }
      return [200, { ...body, channels: list }];
    }],

    ['/api/live/categories', () => {
      requireLive();
      const counts = new Map();
      for (const c of live.list()) counts.set(c.category, (counts.get(c.category) || 0) + 1);
      const countries = [...new Set(live.list().map((c) => c.country))].sort();
      return [200, { categories: [...counts.keys()].sort(), counts: Object.fromEntries(counts), countries }];
    }],

    [/^\/api\/live\/channels?\/([^/]+)$/, (url, [, id]) => {
      requireLive();
      const channel = live.get(decodeURIComponent(id));
      if (!channel) throw new ApiError(404, 'NOT_FOUND', 'Kanal tidak ditemukan.');
      return [200, ok(channel)];
    }],

    ['/api/sync/status', () => [200, ok({
      movies: {
        items: movies.state.items.length,
        series: movies.state.items.filter((m) => m.type === 'series').length,
        lastSyncAtEpochMs: movies.state.lastSyncAt,
        syncing: movies.state.syncing,
        lastError: movies.state.lastError,
      },
      live: {
        enabled: live.enabled,
        channels: live.list().length,
        available: live.list().filter((c) => c.isAvailable).length,
        lastSourceSyncAtEpochMs: live.state.lastSourceSyncAt,
        lastHealthCheckAtEpochMs: live.state.lastHealthCheckAt,
        lastError: live.state.sourceSyncError,
      },
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
    })]],
  ];

  async function handle(request, response) {
    const started = Date.now();
    const url = new URL(request.url || '/', 'http://localhost');
    try {
      if (request.method === 'OPTIONS' && config.corsOrigin) {
        response.writeHead(204, { 'access-control-allow-origin': config.corsOrigin, 'access-control-allow-methods': 'GET, HEAD' });
        return response.end();
      }
      if (request.method !== 'GET' && request.method !== 'HEAD') throw new ApiError(405, 'METHOD_NOT_ALLOWED', 'Hanya GET yang didukung.');
      for (const [pattern, handler] of routes) {
        const match = typeof pattern === 'string' ? (url.pathname === pattern ? [url.pathname] : null) : url.pathname.match(pattern);
        if (!match) continue;
        const [status, body, options] = await handler(url, match);
        return send(request, response, status, body, options);
      }
      throw new ApiError(404, 'NOT_FOUND', 'Endpoint tidak ditemukan.');
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 500;
      if (status >= 500 && !(error instanceof ApiError)) log.error('Kesalahan server', { path: url.pathname, error: error.stack || error.message });
      send(request, response, status, { ok: false, error: { code: error.code || 'INTERNAL_ERROR', message: status === 500 ? 'Terjadi kesalahan pada server.' : error.message } });
    } finally {
      log.info('request', { method: request.method, path: url.pathname, status: response.statusCode, ms: Date.now() - started });
    }
  }

  return { handle, server: () => http.createServer((req, res) => { void handle(req, res); }) };
}