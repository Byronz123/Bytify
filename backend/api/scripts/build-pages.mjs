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

// Katalog film Indonesia terbit sebagai berkas sendiri, dengan alasan yang
// sama seperti serial: catalog.json sudah 23,26 MiB dan Cloudflare Pages
// menolak berkas di atas 25 MiB. Isinya memakai server embed framextv, jadi
// film Indonesia bisa diputar tanpa mengubah apa pun pada katalog LK21.
const indoSource = path.join(backendDir, 'data', 'indonesia_catalog.json');
let indoCount = 0;
if (fs.existsSync(indoSource)) {
    const indo = readJson(indoSource); // validasi JSON sebelum menyalin
    indoCount = Array.isArray(indo.movies) ? indo.movies.length : 0;
    const indoOut = path.join(outputDir, 'api', 'indonesia.json');
    fs.mkdirSync(path.dirname(indoOut), { recursive: true });
    fs.copyFileSync(indoSource, indoOut);
}

// Anime juga terbit sebagai berkas sendiri. Sumbernya AniList, dan server
// pemutarnya rute embed anime framextv yang dikunci dengan AniList id — pola
// yang sama persis dengan film Indonesia yang memakai TMDB id.
const animeSource = path.join(backendDir, 'data', 'anime_catalog.json');
let animeCount = 0;
let animeEpisodeCount = 0;
if (fs.existsSync(animeSource)) {
    const anime = readJson(animeSource); // validasi JSON sebelum menyalin
    animeCount = Array.isArray(anime.movies) ? anime.movies.length : 0;
    animeEpisodeCount = (anime.movies || []).reduce(
        (jumlah, judul) => jumlah + (Array.isArray(judul.episodes) ? judul.episodes.length : 0),
        0,
    );
    const animeOut = path.join(outputDir, 'api', 'anime.json');
    fs.mkdirSync(path.dirname(animeOut), { recursive: true });
    fs.copyFileSync(animeSource, animeOut);
}

// Lapisan KETERSEDIAAN RESMI: tiap judul membawa daftar penyedia sah di
// Indonesia (data JustWatch lewat TMDB), bukan URL stream. Bentuknya beda dari
// keempat katalog di atas — kuncinya `titles` + kamus `providers`, bukan
// `movies` — karena ia bukan daftar yang bisa diputar di dalam aplikasi,
// melainkan rujukan tonton yang dibuka di luar lewat intent.
const watchSource = path.join(backendDir, 'data', 'watch_catalog.json');
let watchCount = 0;
let watchProviderCount = 0;
if (fs.existsSync(watchSource)) {
    const watch = readJson(watchSource); // validasi JSON sebelum menyalin
    watchCount = Array.isArray(watch.titles) ? watch.titles.length : 0;
    watchProviderCount = Object.keys(watch.providers || {}).length;
    const watchOut = path.join(outputDir, 'api', 'watch.json');
    fs.mkdirSync(path.dirname(watchOut), { recursive: true });
    fs.copyFileSync(watchSource, watchOut);
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
    `Copied ${movieCount} movies (${sectionCount} sections), ${seriesCount} series ` +
        `(${seriesEpisodeCount} episodes), ${indoCount} Indonesian films verbatim, ` +
        `${animeCount} anime (${animeEpisodeCount} episodes), ` +
        `${watchCount} titles with official availability ` +
        `(${watchProviderCount} providers), ` +
        `and generated ${channels.length} live channels in ${outputDir}.`,
);
