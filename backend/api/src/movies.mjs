// ==========================================
// Bytify\backend\api\src\movies.mjs (Auto Hot-Reload Database)
// ==========================================
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DB_PATH = path.resolve(__dirname, "../../data/bytify_database.json");

let cachedMovies = [];
let lastModifiedTime = 0;

function toSecureUrl(value) {
  const candidates = Array.isArray(value) ? value : [value];
  for (const candidate of candidates) {
    if (typeof candidate !== "string") continue;
    const trimmed = candidate.trim();
    if (!trimmed) continue;
    try {
      const parsed = new URL(trimmed);
      if (parsed.protocol === "https:") return parsed.toString();
    } catch {
      // ignore malformed URLs and keep scanning other values.
    }
  }
  return null;
}

function normalizeEpisodes(episodes) {
  if (!Array.isArray(episodes)) return [];
  const seen = new Set();
  const cleaned = [];
  for (const episode of episodes) {
    if (!episode || typeof episode !== "object") continue;
    const url = toSecureUrl(episode.videoUrl ?? episode.video_url ?? episode.url ?? episode.streamUrl ?? episode.stream_url ?? episode.link);
    if (!url) continue;
    const normalized = {
      id: episode.id ?? `${episode.title ?? "episode"}-${url}`,
      title: String(episode.title ?? "Episode"),
      videoUrl: url,
      streamUrl: url,
      ...episode,
    };
    const key = `${normalized.id}:${normalized.videoUrl}`;
    if (!seen.has(key)) {
      seen.add(key);
      cleaned.push(normalized);
    }
  }
  return cleaned;
}

export function normalizeItem(rawItem, licenseOverride = "") {
  if (!rawItem || typeof rawItem !== "object") return null;
  const explicitLicense = [rawItem.license, rawItem.licenseName, rawItem.licenseLabel].find((value) => typeof value === "string" && value.trim());
  const license = String(explicitLicense ?? "").trim();
  if (!license || !explicitLicense) return null;

  const type = rawItem.type === "series" ? "series" : "movie";
  const title = String(rawItem.title ?? "").trim();
  if (!title) return null;

  const safeImage = toSecureUrl(rawItem.posterUrl ?? rawItem.poster_url ?? rawItem.poster ?? rawItem.posterImg ?? rawItem.image);
  const streamUrl = toSecureUrl(rawItem.streamUrl ?? rawItem.stream_url ?? rawItem.videoUrl ?? rawItem.video_url ?? rawItem.streamUrls ?? rawItem.stream_urls ?? rawItem.url);
  const firstEpisodeUrl = normalizeEpisodes(rawItem.episodes)[0]?.videoUrl ?? null;
  const videoUrl = toSecureUrl(rawItem.videoUrl ?? rawItem.video_url ?? rawItem.streamUrl ?? rawItem.stream_url ?? rawItem.streamUrls ?? rawItem.stream_urls ?? rawItem.url) ?? firstEpisodeUrl;

  const normalized = {
    id: String(rawItem.id ?? `${type}-${title}`),
    title,
    type,
    overview: String(rawItem.overview ?? rawItem.description ?? ""),
    posterUrl: safeImage,
    backdropUrl: toSecureUrl(rawItem.backdropUrl ?? rawItem.backdrop_url ?? rawItem.backdrop ?? null) ?? safeImage,
    releaseYear: Number.parseInt(String(rawItem.releaseYear ?? rawItem.release_year ?? rawItem.year ?? ""), 10) || null,
    durationMin: Number(rawItem.durationMin ?? rawItem.duration_min ?? rawItem.duration ?? 0) || null,
    genres: Array.isArray(rawItem.genres) ? [...new Set(rawItem.genres.map((genre) => String(genre)).filter(Boolean))] : [],
    rating: Number.parseFloat(String(rawItem.rating ?? "")) || null,
    language: rawItem.language ?? rawItem.lang ?? null,
    streamUrl: streamUrl ?? videoUrl,
    streamUrls: [
      ...(Array.isArray(rawItem.streamUrls) ? rawItem.streamUrls : Array.isArray(rawItem.stream_urls) ? rawItem.stream_urls : []),
      ...(Array.isArray(rawItem.streamUrls) ? [] : []),
    ].map((value) => toSecureUrl(value)).filter(Boolean),
    videoUrl: videoUrl ?? streamUrl,
    sourceUrl: toSecureUrl(rawItem.sourceUrl ?? rawItem.source_url ?? rawItem.source ?? null),
    trailerUrl: toSecureUrl(rawItem.trailerUrl ?? rawItem.trailer_url ?? rawItem.trailer ?? null),
    subtitles: Array.isArray(rawItem.subtitles) ? rawItem.subtitles : [],
    license,
    episodes: normalizeEpisodes(rawItem.episodes),
    popularity: Number(rawItem.popularity ?? 0) || 0,
    addedAtEpochMs: Number(rawItem.addedAtEpochMs ?? rawItem.addedAt ?? rawItem.added_at ?? 0) || 0,
    provider: rawItem.provider ?? null,
  };

  if (normalized.type === "series" && normalized.episodes.length > 0) {
    normalized.videoUrl = normalized.episodes[0].videoUrl;
  }

  if (normalized.type === "series" && normalized.episodes.length > 0) {
    const secureEpisodes = normalized.episodes.filter((episode) => episode.videoUrl);
    normalized.episodes = secureEpisodes;
    if (secureEpisodes.length) normalized.videoUrl = secureEpisodes[0].videoUrl;
  }

  if (normalized.type === "movie") {
    normalized.streamUrls = [...new Set([normalized.streamUrl, normalized.videoUrl, ...(Array.isArray(rawItem.streamUrls) ? rawItem.streamUrls : []), ...(Array.isArray(rawItem.stream_urls) ? rawItem.stream_urls : [])].map((candidate) => toSecureUrl(candidate)).filter(Boolean))];
  }

  return normalized;
}

export function buildSections(items) {
  const all = Array.isArray(items) ? items : [];
  const sections = [];

  const popularItems = [...all].sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
  if (popularItems.length > 0) {
    sections.push({ title: "Populer", items: popularItems.slice(0, 6) });
  }

  const newestItems = [...all].sort((a, b) => (b.addedAtEpochMs || 0) - (a.addedAtEpochMs || 0));
  if (newestItems.length > 0) {
    sections.push({ title: "Terbaru ditambahkan", items: newestItems.slice(0, 6) });
  }

  const seriesItems = [...all].filter((item) => item.type === "series");
  if (seriesItems.length > 0) {
    sections.push({ title: "Serial TV", items: seriesItems.slice(0, 6) });
  }

  const genreNames = [...new Set(all.flatMap((item) => Array.isArray(item.genres) ? item.genres : []))].sort();
  for (const genreName of genreNames) {
    const genreItems = all.filter((item) => (item.genres || []).includes(genreName)).slice(0, 6);
    if (genreItems.length > 0) {
      sections.push({ title: genreName, items: genreItems });
    }
  }

  return sections;
}

export function createMovieService(config = {}, log = console, providers = []) {
  const logger = log || console;
  const state = {
    items: [],
    sections: [],
    version: 0,
    lastSyncAt: 0,
    syncing: false,
    lastError: null,
  };

  function mergeRawItems(rawList) {
    const map = new Map();
    for (const rawItem of rawList) {
      const normalized = normalizeItem(rawItem, config.licenseLabel || "");
      if (!normalized) continue;
      map.set(normalized.id, normalized);
    }
    return [...map.values()].sort((a, b) => {
      if ((b.popularity || 0) !== (a.popularity || 0)) return (b.popularity || 0) - (a.popularity || 0);
      return (b.addedAtEpochMs || 0) - (a.addedAtEpochMs || 0);
    });
  }

  async function sync() {
    state.syncing = true;
    state.lastError = null;
    try {
      const merged = [];
      for (const provider of providers) {
        if (!provider || typeof provider.load !== "function") continue;
        const loaded = await provider.load();
        const rawList = Array.isArray(loaded) ? loaded : loaded && Array.isArray(loaded.items) ? loaded.items : [];
        merged.push(...rawList);
      }
      const items = mergeRawItems(merged);
      state.items = items;
      state.sections = buildSections(items);
      state.version += 1;
      state.lastSyncAt = Date.now();
      logger.info?.(`Movie catalog synced: ${items.length} items.`);
    } catch (error) {
      state.lastError = error?.message || String(error);
      logger.error?.(`Movie catalog sync failed: ${state.lastError}`);
      throw error;
    } finally {
      state.syncing = false;
    }
  }

  function list(options = {}) {
    let items = [...state.items];
    const type = options.type;
    const genre = String(options.genre || "").trim();
    const sort = String(options.sort || "").toLowerCase();
    if (type) items = items.filter((item) => item.type === type);
    if (genre) items = items.filter((item) => (item.genres || []).includes(genre));
    if (sort === "newest") {
      items.sort((a, b) => (b.addedAtEpochMs || 0) - (a.addedAtEpochMs || 0));
    } else {
      items.sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
    }
    return items;
  }

  function search(query = "", filterType) {
    const term = String(query || "").trim().toLowerCase();
    if (!term) return [...state.items];
    const filtered = state.items.filter((item) => {
      const matchesType = !filterType || item.type === filterType;
      const haystack = `${item.title || ""} ${item.overview || ""} ${(item.genres || []).join(" ")}`.toLowerCase();
      return matchesType && haystack.includes(term);
    });
    return filtered.sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
  }

  function get(id) {
    return state.items.find((item) => item.id === id) || null;
  }

  return {
    state,
    sync,
    list,
    search,
    searchAsync: search,
    get,
    getOrEnrich: get,
    genres: () => [...new Set(state.items.flatMap((item) => item.genres || []))].sort(),
  };
}

export function getMoviesCatalog() {
  try {
    if (!fs.existsSync(DB_PATH)) {
      return [];
    }
    const stats = fs.statSync(DB_PATH);
    if (stats.mtimeMs > lastModifiedTime) {
      const raw = fs.readFileSync(DB_PATH, "utf-8");
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        cachedMovies = parsed.slice().reverse();
        lastModifiedTime = stats.mtimeMs;
        console.log(`[Auto-Sync] Katalog Film diperbarui otomatis: ${cachedMovies.length} film siap disajikan.`);
      }
    }
  } catch (err) {
    console.error("[!] Gagal memuat ulang bytify_database.json:", err.message);
  }
  return cachedMovies;
}

export function searchMovies(query = "") {
  const catalog = getMoviesCatalog();
  const q = String(query).trim().toLowerCase();
  if (!q) return catalog;
  return catalog.filter((item) =>
    (item.title || "").toLowerCase().includes(q) ||
    (item.id || "").toLowerCase().includes(q)
  );
}

export function getMovieById(id) {
  const catalog = getMoviesCatalog();
  return catalog.find((item) => item.id === id) || null;
}