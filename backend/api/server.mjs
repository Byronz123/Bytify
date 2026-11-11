import cors from 'cors';
import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const MOVIE_DB_PATH = path.resolve(__dirname, '../data/bytify_database.json');
const LIVE_DB_PATH = path.resolve(__dirname, '../data/live_tv_database.json');

function createDatabaseReader() {
    const cache = new Map();
    return (filePath) => {
        const stats = fs.statSync(filePath);
        const cached = cache.get(filePath);
        if (cached?.mtimeMs === stats.mtimeMs) {
            return cached.items;
        }

        const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8') || '[]');
        if (!Array.isArray(parsed)) {
            throw new Error(`Catalog at ${filePath} must contain a JSON array.`);
        }

        cache.set(filePath, { mtimeMs: stats.mtimeMs, items: parsed });
        return parsed;
    };
}

function firstUrl(...values) {
    for (const value of values) {
        const candidates = Array.isArray(value) ? value : [value];
        const url = candidates.find((candidate) => typeof candidate === 'string' && candidate.trim());
        if (url) return url.trim();
    }
    return null;
}

export function toMovie(item) {
    const rawYear = item.releaseYear ?? item.release_year ?? item.year;
    const parsedYear = Number.parseInt(String(rawYear ?? ''), 10);
    const rawRating = Number.parseFloat(String(item.rating ?? ''));
    const streamUrls = [
        ...new Set(
            [item.streamUrl, item.stream_url, item.streamUrls, item.stream_urls]
                .flatMap((value) => Array.isArray(value) ? value : [value])
                .filter((url) => typeof url === 'string' && url.trim())
                .map((url) => url.trim()),
        ),
    ];

    return {
        id: String(item.id ?? ''),
        type: 'movie',
        title: String(item.title ?? ''),
        overview: String(item.overview ?? item.description ?? ''),
        posterUrl: item.posterUrl ?? item.poster_url ?? item.poster ?? null,
        backdropUrl: item.backdropUrl ?? item.backdrop_url ?? null,
        releaseYear: Number.isInteger(parsedYear) && parsedYear >= 1888 && parsedYear <= 2200 ? parsedYear : null,
        durationMin: item.durationMin ?? item.duration_min ?? null,
        genres: Array.isArray(item.genres) ? item.genres : [],
        rating: Number.isFinite(rawRating) && rawRating >= 0 && rawRating <= 10 ? rawRating : null,
        language: item.language ?? null,
        streamUrl: item.streamUrl ?? item.stream_url ?? streamUrls[0] ?? null,
        streamUrls,
        videoUrl: item.videoUrl ?? item.video_url ?? firstUrl(streamUrls, item.dl_urls),
        sourceUrl: item.sourceUrl ?? item.source_url ?? null,
        trailerUrl: item.trailerUrl ?? item.trailer_url ?? null,
        subtitles: Array.isArray(item.subtitles) ? item.subtitles : [],
        license: item.license ?? null,
        episodes: Array.isArray(item.episodes) ? item.episodes : [],
    };
}

export function toLiveChannel(item, checkedAtEpochMs) {
    const streamUrl = firstUrl(item.streamUrl, item.stream_url);
    if (!streamUrl) return null;

    let parsedUrl;
    try {
        parsedUrl = new URL(streamUrl);
    } catch {
        return null;
    }
    if (parsedUrl.protocol !== 'https:' || parsedUrl.username || parsedUrl.password) return null;

    return {
        id: String(item.id ?? ''),
        name: String(item.name ?? ''),
        description: String(item.description ?? ''),
        logoUrl: item.logoUrl ?? item.logo ?? null,
        category: String(item.category ?? 'General'),
        country: String(item.country ?? 'ID'),
        language: String(item.language ?? ''),
        streamUrl: parsedUrl.toString(),
        streamType: /\.mp4(?:$|[?#])/i.test(parsedUrl.href) ? 'mp4' : 'hls',
        isAvailable: true,
        statusMessage: null,
        checkedAtEpochMs,
        license: item.license ?? null,
    };
}

export function createApp({ movieDatabasePath = MOVIE_DB_PATH, liveDatabasePath = LIVE_DB_PATH } = {}) {
    const app = express();
    const readDatabase = createDatabaseReader();

    app.use(cors());
    app.use(express.json());

    app.get('/', (_req, res) => {
        res.json({
            ok: true,
            service: 'Bytify Backend API',
            endpoints: ['/health', '/api/catalog.json', '/api/live/channels'],
        });
    });

    app.get('/health', (_req, res) => {
        res.json({ ok: true });
    });

    app.get('/api/catalog.json', (_req, res) => {
        try {
            const movies = readDatabase(movieDatabasePath).map(toMovie)
                .filter((movie) => movie.id && movie.title);
            res.json({ movies, sections: [], generatedAtEpochMs: Date.now() });
        } catch (error) {
            console.error('[Bytify API] Failed to load movie catalog:', error);
            res.status(503).json({ ok: false, error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' } });
        }
    });

    app.get('/api/live/channels', (_req, res) => {
        try {
            const generatedAtEpochMs = Date.now();
            const channels = readDatabase(liveDatabasePath).map((item) => toLiveChannel(item, generatedAtEpochMs))
                .filter((channel) => channel && channel.id && channel.name);
            res.json({ schemaVersion: 1, generatedAtEpochMs, refreshAfterSeconds: 300, channels });
        } catch (error) {
            console.error('[Bytify API] Failed to load live catalog:', error);
            res.status(503).json({ ok: false, error: { code: 'LIVE_CATALOG_UNAVAILABLE', message: 'Katalog Live TV belum tersedia.' } });
        }
    });

    app.get('/api/lk21/search', (req, res) => {
        try {
            const query = String(req.query.q ?? '').trim().toLowerCase();
            const movies = readDatabase(movieDatabasePath).filter((item) =>
                String(item.title ?? '').toLowerCase().includes(query),
            ).map(toMovie);
            res.json({ movies });
        } catch (error) {
            console.error('[Bytify API] Failed to search movie catalog:', error);
            res.status(503).json({ ok: false, error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' } });
        }
    });

    app.get('/api/movies/search', (req, res) => {
        try {
            const query = String(req.query.q ?? '').trim().toLowerCase();
            const movies = readDatabase(movieDatabasePath).filter((item) =>
                String(item.title ?? '').toLowerCase().includes(query),
            );
            res.json({ ok: true, total: movies.length, data: movies });
        } catch (error) {
            console.error('[Bytify API] Failed to search movie catalog:', error);
            res.status(503).json({ ok: false, error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' } });
        }
    });

    app.get('/api/movies/:id', (req, res) => {
        try {
            const movie = readDatabase(movieDatabasePath).find((item) => item.id === req.params.id);
            if (!movie) {
                return res.status(404).json({ ok: false, error: { code: 'NOT_FOUND', message: 'Film tidak ditemukan.' } });
            }
            return res.json({ ok: true, data: movie });
        } catch (error) {
            console.error('[Bytify API] Failed to load movie detail:', error);
            return res.status(503).json({ ok: false, error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' } });
        }
    });

    app.get('/api/movies', (_req, res) => {
        try {
            const movies = readDatabase(movieDatabasePath);
            res.json({ ok: true, total: movies.length, data: movies });
        } catch (error) {
            console.error('[Bytify API] Failed to load movie catalog:', error);
            res.status(503).json({ ok: false, error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' } });
        }
    });

    app.use((req, res) => {
        res.status(404).json({
            ok: false,
            error: { code: 'NOT_FOUND', message: `Endpoint ${req.path} tidak ditemukan.` },
        });
    });

    return app;
}

export function startServer() {
    const port = Number(process.env.PORT) || 8787;
    const host = process.env.HOST || '0.0.0.0';
    const server = createApp().listen(port, host, () => {
        console.log(`[Bytify API] Server listening on ${host}:${port}`);
    });
    return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
    startServer();
}
