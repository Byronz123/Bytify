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

export function getMoviesCatalog() {
  try {
    if (!fs.existsSync(DB_PATH)) {
      return [];
    }
    const stats = fs.statSync(DB_PATH);
    // Jika file bytify_database.json baru saja diperbarui oleh scraper Python, muat ulang ke memori!
    if (stats.mtimeMs > lastModifiedTime) {
      const raw = fs.readFileSync(DB_PATH, "utf-8");
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        // Urutkan agar film yang baru masuk berada di urutan paling atas di APK
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