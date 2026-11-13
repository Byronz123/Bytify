import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { toLiveChannel } from '../server.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const backendApiDir = path.resolve(__dirname, '..');
const backendDir = path.resolve(backendApiDir, '..');
const outputDir = path.resolve(process.argv[2] || path.join(backendApiDir, 'dist'));

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function readArray(filePath) {
    const value = readJson(filePath);
    if (!Array.isArray(value)) {
        throw new Error(`Expected a JSON array in ${filePath}.`);
    }
    return value;
}

function writeJson(filePath, value) {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, `${JSON.stringify(value)}\n`);
}

const generatedAtEpochMs = Date.now();

// Katalog film: backend/data/catalog.json adalah SATU-SATUNYA sumber kebenaran.
// Berkas itu hasil build_database.py (koreksi_tahun, penyaring redirect,
// perampingan field, sections). Salin apa adanya — jangan petakan ulang, jangan
// sentuh sections — agar yang ter-deploy persis sama dengan yang diuji & di-git,
// dan pipeline tak lagi butuh bytify_database.json (29 MB, sudah di-untrack).
const catalogSource = path.join(backendDir, 'data', 'catalog.json');
const catalog = readJson(catalogSource); // validasi JSON sebelum menyalin
const catalogOut = path.join(outputDir, 'api', 'catalog.json');
fs.mkdirSync(path.dirname(catalogOut), { recursive: true });
fs.copyFileSync(catalogSource, catalogOut);

// Katalog serial terbit sebagai berkas sendiri. Cloudflare Pages menolak
// berkas di atas 25 MiB dan catalog.json sudah 23,26 MiB, jadi puluhan ribu
// episode tidak mungkin ikut di dalamnya. Berkasnya opsional: selama panen
// belum pernah dijalankan, deploy tetap berhasil tanpa serial.
const seriesSource = path.join(backendDir, 'data', 'series_catalog.json');
let seriesCount = 0;
let seriesEpisodeCount = 0;
if (fs.existsSync(seriesSource)) {
    const series = readJson(seriesSource); // validasi JSON sebelum menyalin
    seriesCount = Array.isArray(series.movies) ? series.movies.length : 0;
    seriesEpisodeCount = (series.movies || []).reduce(
        (jumlah, satu) => jumlah + (Array.isArray(satu.episodes) ? satu.episodes.length : 0),
        0,
    );
    const seriesOut = path.join(outputDir, 'api', 'series.json');
    fs.mkdirSync(path.dirname(seriesOut), { recursive: true });
    fs.copyFileSync(seriesSource, seriesOut);
}

// Live TV tetap dibangun dari database kanal (tidak berubah).
const channels = readArray(path.join(backendDir, 'data', 'live_tv_database.json'))
    .map((channel) => toLiveChannel(channel, generatedAtEpochMs))
    .filter((channel) => channel && channel.id && channel.name);
writeJson(path.join(outputDir, 'api', 'live', 'channels.json'), {
    schemaVersion: 1,
    generatedAtEpochMs,
    refreshAfterSeconds: 300,
    channels,
});
writeJson(path.join(outputDir, 'health'), { ok: true });

const movieCount = Array.isArray(catalog.movies) ? catalog.movies.length : 0;
const sectionCount = Array.isArray(catalog.sections) ? catalog.sections.length : 0;
console.log(
    `Copied ${movieCount} movies (${sectionCount} sections) and ${seriesCount} series ` +
        `(${seriesEpisodeCount} episodes) verbatim and generated ` +
        `${channels.length} live channels in ${outputDir}.`,
);
