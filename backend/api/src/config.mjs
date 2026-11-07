import { readFileSync } from 'node:fs';

/** Membaca backend/api/.env bila ada; variabel dari process manager tetap diutamakan. */
export function loadEnvFile(url = new URL('../.env', import.meta.url)) {
  try {
    const text = readFileSync(url, 'utf8');
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const equal = line.indexOf('=');
      if (equal < 1) continue;
      const key = line.slice(0, equal).trim();
      const value = line.slice(equal + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
      if (process.env[key] === undefined) process.env[key] = value;
    }
  } catch {
    // .env opsional.
  }
}

function intEnv(env, name, fallback, min, max) {
  const parsed = Number.parseInt(env[name] || '', 10);
  return Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
}

function listEnv(env, name) {
  return (env[name] || '').split(';').map((value) => value.trim()).filter(Boolean);
}

/** Semua konfigurasi berasal dari environment variable; tidak ada kunci di source. */
export function readConfig(env = process.env) {
  const port = intEnv(env, 'PORT', 8787, 1, 65535);
  const publicBaseUrl = (env.PUBLIC_BASE_URL || `http://10.0.2.2:${port}`).trim().replace(/\/+$/, '');

  return {
    port,
    host: env.HOST || '0.0.0.0',
    publicBaseUrl,
    logLevel: env.LOG_LEVEL || 'info',
    corsOrigin: env.CORS_ORIGIN || '*',
    live: {
      sourceUrls: listEnv(env, 'LIVE_PLAYLIST_URLS'),
      xtreamUrl: (env.LIVE_XTREAM_URL || '').trim().replace(/\/+$/, ''),
      xtreamUsername: (env.LIVE_XTREAM_USERNAME || '').trim(),
      xtreamPassword: (env.LIVE_XTREAM_PASSWORD || '').trim(),
      xtreamFormat: (env.LIVE_XTREAM_FORMAT || 'm3u8').trim().toLowerCase(),
      rightsConfirmed: env.LIVE_SOURCE_RIGHTS_CONFIRMED === 'true',
      licenseLabel: (env.LIVE_SOURCE_LICENSE_LABEL || '').trim(),
      refreshMs: intEnv(env, 'LIVE_SOURCE_REFRESH_SECONDS', 300, 60, 3600) * 1000,
      healthCheckMs: intEnv(env, 'LIVE_HEALTH_CHECK_SECONDS', 120, 60, 600) * 1000,
      probeTimeoutMs: intEnv(env, 'LIVE_PROBE_TIMEOUT_MS', 5000, 1000, 30000),
      maxChannels: intEnv(env, 'LIVE_MAX_CHANNELS', 120, 1, 2000),
      concurrency: intEnv(env, 'LIVE_PROBE_CONCURRENCY', 10, 1, 32),
      defaultCountry: (env.LIVE_DEFAULT_COUNTRY || 'ID').toUpperCase(),
    },
    movies: {
      // Daftar adapter dipisah titik koma, contoh: "lk21;archive;file".
      providers: listEnv(env, 'MOVIE_PROVIDERS').length ? listEnv(env, 'MOVIE_PROVIDERS') : ['lk21', 'archive'],
      refreshMs: intEnv(env, 'MOVIE_REFRESH_SECONDS', 21600, 300, 604800) * 1000,
      catalogFile: env.MOVIE_CATALOG_FILE || '',
      publicBaseUrl,
      archive: {
        movieCollection: env.ARCHIVE_MOVIE_COLLECTION || 'feature_films',
        seriesCollection: env.ARCHIVE_SERIES_COLLECTION || 'classic_tv',
        maxMovies: intEnv(env, 'ARCHIVE_MAX_MOVIES', 60, 1, 500),
        maxSeries: intEnv(env, 'ARCHIVE_MAX_SERIES', 20, 0, 200),
        timeoutMs: intEnv(env, 'ARCHIVE_TIMEOUT_MS', 15000, 2000, 60000),
        concurrency: intEnv(env, 'ARCHIVE_CONCURRENCY', 6, 1, 16),
        maxYear: intEnv(env, 'ARCHIVE_MAX_YEAR', 1977, 1888, 2100),
      },
      lk21: {
        publicBaseUrl,
        lk21Url: (env.LK21_URL || 'https://tv.lk21official.live').trim().replace(/\/+$/, ''),
        ndUrl: (env.ND_URL || 'https://tv.nontondrama.lol').trim().replace(/\/+$/, ''),
        dlUrl: (env.DL_URL || 'https://dl.lk21static.xyz').trim().replace(/\/+$/, ''),
        apiBaseUrl: (env.LK21_API_BASE_URL || '').trim().replace(/\/+$/, ''),
        siteOrigin: (env.LK21_SITE_ORIGIN || '').trim().replace(/\/+$/, ''),
        maxPages: intEnv(env, 'LK21_MAX_PAGES', 2, 1, 10),
        timeoutMs: intEnv(env, 'LK21_TIMEOUT_MS', 15000, 2000, 60000),
        concurrency: intEnv(env, 'LK21_CONCURRENCY', 4, 1, 12),
        cacheFile: env.CACHE_FILE || './data/lk21-cache.json',
      },
      blockedIds: listEnv(env, 'MOVIE_BLOCKLIST'),
      blockedTerms: (env.MOVIE_BLOCKED_TERMS ?? 'sex;nudity;nudist;naked;erotic;exploitation;burlesque;striptease;adult;xxx;porn')
        .split(';').map((t) => t.trim().toLowerCase()).filter(Boolean),
    },
  };
}