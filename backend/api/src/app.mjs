import http from 'node:http';
import { createHash } from 'node:crypto';
import { ApiError, paginate } from './util.mjs';

/**
 * Router HTTP Bytify API. Respons kontrak aplikasi (`/api/catalog.json`, `/api/live/channels`)
 * memakai bentuk JSON yang dibaca APK; endpoint lain memakai amplop { ok, data, meta } / { ok:false, error }.
 */
export function createApp({ movies, live, log, config = {} }) {
  const startedAt = Date.now();
  const cache = new Map(); // key -> { version, body, etag }

  function send(request, response, status, body, { maxAge = 0, cacheKey, version } = {}) {
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
  // Bentuk kontrak APK: field internal (provider, popularity) tidak dikirim.
  const contract = ({ provider, popularity, addedAtEpochMs, tags, ...rest }) => rest;

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

    [/^\/api\/movies\/([^/]+)$/, (url, [, id]) => {
      requireMovies();
      const item = movies.get(decodeURIComponent(id));
      if (!item) throw new ApiError(404, 'NOT_FOUND', 'Film atau serial tidak ditemukan.');
      return [200, ok(contract(item)), { maxAge: 300 }];
    }],

    ['/api/series', (url) => {
      requireMovies();
      const page = paginate(movies.list({ type: 'series', genre: url.searchParams.get('genre'), sort: url.searchParams.get('sort') }), url.searchParams);
      return [200, ok(page.items.map(summary), page.meta), { maxAge: 120 }];
    }],

    ['/api/genres', () => { requireMovies(); return [200, ok(movies.genres()), { maxAge: 600 }]; }],

    ['/api/search', (url) => {
      const q = (url.searchParams.get('q') || '').trim();
      if (q.length < 2) throw new ApiError(400, 'QUERY_TOO_SHORT', 'Parameter q minimal 2 karakter.');
      if (q.length > 100) throw new ApiError(400, 'QUERY_TOO_LONG', 'Parameter q maksimal 100 karakter.');
      const type = url.searchParams.get('type');
      const ql = q.toLowerCase();
      const movieHits = movies.search(q, type === 'movie' || type === 'series' ? type : undefined).map(summary);
      const channelHits = type && type !== 'live' ? [] : live.list().filter((c) =>
        c.name.toLowerCase().includes(ql) || c.category.toLowerCase().includes(ql)).map((c) => ({ ...c, type: 'live' }));
      const page = paginate([...movieHits, ...channelHits], url.searchParams);
      return [200, ok(page.items, { ...page.meta, query: q }), { maxAge: 60 }];
    }],

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
      // Tanpa ?page APK menerima seluruh daftar (kompatibel dengan v1.3.0).
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
