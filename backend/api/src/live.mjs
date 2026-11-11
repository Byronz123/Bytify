// ==========================================
// Bytify\backend\api\src\live.mjs (TV Garden Auto-Sync)
// ==========================================
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const LIVE_JSON_PATH = path.resolve(__dirname, "../../data/live_tv_database.json");

let cachedChannels = [];
let lastLiveMtime = 0;

function normalizeLicenseLabel(value) {
  return String(value ?? "").trim();
}

function isValidPlaylistText(text) {
  return typeof text === "string" && text.includes("#EXTM3U");
}

function parseAttributes(raw) {
  const attrs = {};
  if (!raw) return attrs;
  const tokenRegex = /([A-Za-z0-9-]+)=(?:"([^"]*)"|([^\s,]+))/g;
  let match;
  while ((match = tokenRegex.exec(raw)) !== null) {
    const key = match[1]?.trim();
    const value = (match[2] ?? match[3] ?? "").trim();
    if (key) attrs[key] = value;
  }
  return attrs;
}

export function parseM3u(text, sourceUrl = "", options = {}) {
  const raw = String(text ?? "");
  if (!isValidPlaylistText(raw)) {
    throw new Error("Playlist tidak valid: harus berisi tag #EXTM3U.");
  }

  const { licenseLabel = "", defaultCountry = "ID" } = options;
  const lines = raw.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const channels = [];
  let current = null;

  for (const line of lines) {
    if (line.startsWith("#EXTINF")) {
      current = { id: "", name: "", category: "General", country: defaultCountry, language: "", streamUrl: "", logoUrl: null, license: normalizeLicenseLabel(licenseLabel) };
      const meta = line.slice(8).trim();
      const entry = meta.match(/^(?:-?\d+\s+)?(.*)$/);
      const payload = entry ? entry[1] : meta;
      const attrs = parseAttributes(payload.split(",")[0] || "");
      const label = payload.includes(",") ? payload.slice(payload.indexOf(",") + 1).trim() : "";
      current.id = String(attrs["tvg-id"] || attrs.id || slugify(label || attrs["tvg-name"] || sourceUrl || `channel-${channels.length + 1}`));
      current.name = String(attrs["tvg-name"] || label || current.id);
      current.category = String(attrs["group-title"] || attrs.category || "General");
      current.country = String(attrs["tvg-country"] || defaultCountry || "ID");
      current.language = String(attrs["tvg-language"] || "");
      current.logoUrl = attrs["tvg-logo"] || attrs.logo || null;
      continue;
    }

    if (!current) continue;
    if (line.startsWith("#")) continue;
    try {
      const parsedUrl = new URL(line);
      if (parsedUrl.protocol !== "https:") {
        current = null;
        continue;
      }
      current.streamUrl = parsedUrl.toString();
      current.streamType = /\.(m3u8|mp4)(\?|$)/i.test(parsedUrl.href) ? (/\.mp4(?:$|[?#])/i.test(parsedUrl.href) ? "mp4" : "hls") : "hls";
      current.isAvailable = false;
      current.checkedAtEpochMs = Date.now();
      channels.push({ ...current });
    } catch {
      // Skip invalid URLs.
    }
    current = null;
  }

  return channels;
}

export async function probeChannel(channel) {
  const checkedAtEpochMs = Date.now();
  if (!channel || !channel.streamUrl) {
    return { ...channel, isAvailable: false, statusMessage: "URL tidak valid", checkedAtEpochMs };
  }

  try {
    const response = await fetch(channel.streamUrl, { method: "GET", redirect: "follow" });
    const contentType = response.headers.get("content-type") || "";
    const text = await response.text();
    const isManifest = /(^|\s)#EXTM3U|application\/vnd\.apple\.mpegurl|application\/x-mpegURL/i.test(contentType + "\n" + text);
    if (!isManifest) {
      return { ...channel, isAvailable: false, statusMessage: "Respons bukan manifest HLS", checkedAtEpochMs };
    }
    return { ...channel, isAvailable: true, statusMessage: "Online", checkedAtEpochMs };
  } catch {
    return { ...channel, isAvailable: false, statusMessage: "Gagal mengecek URL", checkedAtEpochMs };
  }
}

export function createLiveService(options = {}, log = console) {
  const logger = log || console;
  const config = {
    sourceUrls: [],
    rightsConfirmed: false,
    licenseLabel: "",
    defaultCountry: "ID",
    healthCheckMs: 120000,
    probeTimeoutMs: 5000,
    maxChannels: 200,
    concurrency: 4,
    ...options,
  };

  const state = {
    items: [],
    enabled: Boolean(config.rightsConfirmed && config.sourceUrls && config.sourceUrls.length > 0),
    lastSourceSyncAt: 0,
    sourceSyncError: null,
  };

  function load(channels) {
    state.items = Array.isArray(channels) ? channels.map((channel) => ({ ...channel, isAvailable: Boolean(channel.isAvailable), checkedAtEpochMs: Number(channel.checkedAtEpochMs || Date.now()) })) : [];
    state.enabled = Boolean(config.rightsConfirmed && state.items.length > 0);
    return state.items;
  }

  function list() {
    return [...state.items];
  }

  function get(id) {
    return state.items.find((channel) => channel.id === id) || null;
  }

  async function refreshSources() {
    if (config.xtreamUrl && config.xtreamUsername && config.xtreamPassword) {
      try {
        const params = new URLSearchParams({
          username: config.xtreamUsername,
          password: config.xtreamPassword,
          action: 'get_live_channels',
        });
        const playerApiUrl = new URL('/player_api.php', config.xtreamUrl);
        playerApiUrl.search = params.toString();

        const response = await fetch(playerApiUrl.toString(), { method: 'GET' });
        if (!response.ok) throw new Error(`Xtream API status ${response.status}`);
        const payload = await response.json();
        const channels = Array.isArray(payload) ? payload : Array.isArray(payload?.data) ? payload.data : [];
        state.items = channels
          .filter((channel) => channel && channel.name)
          .slice(0, config.maxChannels)
          .map((channel) => {
            const streamId = channel.stream_id ?? channel.id;
            const format = (config.xtreamFormat || 'm3u8').toLowerCase();
            const streamUrl = new URL(`/live/${config.xtreamUsername}/${config.xtreamPassword}/${streamId}.${format}`, config.xtreamUrl).toString();
            return {
              id: String(streamId ?? slugify(channel.name)),
              name: String(channel.name),
              category: String(channel.category || 'General'),
              country: String(channel.country || config.defaultCountry),
              language: String(channel.language || ''),
              logoUrl: channel.stream_icon || channel.logo || null,
              streamUrl,
              streamType: format.includes('mp4') ? 'mp4' : 'hls',
              isAvailable: true,
              statusMessage: 'Online',
              checkedAtEpochMs: Date.now(),
              license: config.licenseLabel || 'xtream',
            };
          });
        state.enabled = state.items.length > 0 && config.rightsConfirmed;
        state.lastSourceSyncAt = Date.now();
        state.sourceSyncError = state.enabled ? null : 'NO_CHANNELS_FOUND';
        return state.enabled;
      } catch (error) {
        logger.warn?.(`Xtream source sync failed: ${error?.message || error}`);
      }
    }

    if (!config.rightsConfirmed || !Array.isArray(config.sourceUrls) || config.sourceUrls.length === 0) {
      state.enabled = false;
      state.sourceSyncError = 'LIVE_NOT_CONFIGURED';
      return false;
    }

    const uniqueChannels = [];
    for (const sourceUrl of config.sourceUrls) {
      try {
        const response = await fetch(sourceUrl, { method: "GET" });
        const text = await response.text();
        if (!text || !text.includes("#EXTM3U")) continue;
        const parsed = parseM3u(text, sourceUrl, { licenseLabel: config.licenseLabel, defaultCountry: config.defaultCountry });
        for (const channel of parsed) uniqueChannels.push(channel);
      } catch (error) {
        logger.warn?.(`Live source sync failed for ${sourceUrl}: ${error?.message || error}`);
      }
    }

    state.items = uniqueChannels.slice(0, config.maxChannels);
    state.enabled = state.items.length > 0 && config.rightsConfirmed;
    state.lastSourceSyncAt = Date.now();
    state.sourceSyncError = state.enabled ? null : 'NO_CHANNELS_FOUND';
    return state.enabled;
  }

  return { state, enabled: state.enabled, load, list, get, refreshSources };
}

function slugify(value) {
  return String(value || "channel")
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "channel";
}

export function getLiveChannelsCatalog() {
  try {
    if (!fs.existsSync(LIVE_JSON_PATH)) {
      return [];
    }
    const stats = fs.statSync(LIVE_JSON_PATH);
    if (stats.mtimeMs > lastLiveMtime) {
      const raw = fs.readFileSync(LIVE_JSON_PATH, "utf-8");
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        cachedChannels = parsed;
        lastLiveMtime = stats.mtimeMs;
        console.log(`[Auto-Sync] Saluran TV Garden diperbarui: ${cachedChannels.length} channel aktif.`);
      }
    }
  } catch (err) {
    console.error("[!] Gagal membaca live_tv_database.json:", err.message);
  }
  return cachedChannels;
}

export function searchLiveChannels({ query = "", country = "", category = "" } = {}) {
  let list = getLiveChannelsCatalog();
  const q = String(query).trim().toLowerCase();
  const c = String(country).trim().toUpperCase();
  const cat = String(category).trim().toLowerCase();

  if (c) {
    list = list.filter((ch) => (ch.country || "").toUpperCase() === c);
  }
  if (cat) {
    list = list.filter((ch) => (ch.category || "").toLowerCase().includes(cat));
  }
  if (q) {
    list = list.filter((ch) => (ch.name || "").toLowerCase().includes(q));
  }
  return list;
}

export function getLiveChannelById(id) {
  const list = getLiveChannelsCatalog();
  return list.find((ch) => ch.id === id) || null;
}