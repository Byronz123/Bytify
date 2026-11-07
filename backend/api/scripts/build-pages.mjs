import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { toLiveChannel, toMovie } from '../server.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const backendApiDir = path.resolve(__dirname, '..');
const backendDir = path.resolve(backendApiDir, '..');
const outputDir = path.resolve(process.argv[2] || path.join(backendApiDir, 'dist'));

function readArray(filePath) {
    const value = JSON.parse(fs.readFileSync(filePath, 'utf8'));
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
const movies = readArray(path.join(backendDir, 'data', 'bytify_database.json'))
    .map(toMovie)
    .filter((movie) => movie.id && movie.title);
const channels = readArray(path.join(backendDir, 'data', 'live_tv_database.json'))
    .map((channel) => toLiveChannel(channel, generatedAtEpochMs))
    .filter((channel) => channel && channel.id && channel.name);

writeJson(path.join(outputDir, 'api', 'catalog.json'), {
    movies,
    sections: [],
    generatedAtEpochMs,
});
writeJson(path.join(outputDir, 'api', 'live', 'channels.json'), {
    schemaVersion: 1,
    generatedAtEpochMs,
    refreshAfterSeconds: 300,
    channels,
});
writeJson(path.join(outputDir, 'health'), { ok: true });

console.log(`Generated ${movies.length} movies and ${channels.length} live channels in ${outputDir}.`);
