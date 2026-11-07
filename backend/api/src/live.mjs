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