import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const backendApiDir = path.resolve(__dirname, '..');
const backendDir = path.resolve(backendApiDir, '..');
const scriptPath = path.join(backendApiDir, 'scripts', 'build-pages.mjs');
const sourcePath = path.join(backendDir, 'data', 'catalog.json');

test('build-pages deploys backend/data/catalog.json as the single source of truth', () => {
    const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bytify-pages-'));
    try {
        const result = spawnSync(process.execPath, [scriptPath, outDir], { encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr);

        const builtPath = path.join(outDir, 'api', 'catalog.json');
        const built = JSON.parse(fs.readFileSync(builtPath, 'utf8'));
        const source = JSON.parse(fs.readFileSync(sourcePath, 'utf8'));

        // Sections harus ikut ter-deploy (bug lama: build menulis sections: []).
        assert.ok(
            Array.isArray(built.sections) && built.sections.length > 0,
            'dist/api/catalog.json must have non-empty sections',
        );

        // Jumlah film harus sama persis dengan sumber (bug lama: rebuild dari
        // data mentah melewati penyaring redirect & perampingan build_database.py).
        assert.equal(
            built.movies.length,
            source.movies.length,
            'dist film count must equal backend/data/catalog.json',
        );

        // Cloudflare Pages MENOLAK berkas di atas 25 MiB. Pernah terjadi:
        // katalog 31,6 MB lolos semua uji lalu gagal di langkah terbit.
        // Dijaga di sini supaya ketahuan sebelum deploy, bukan sesudahnya.
        const BATAS_MIB = 25;
        const AMAN_MIB = 24;
        const ukuranMiB = fs.statSync(builtPath).size / (1024 * 1024);
        assert.ok(
            ukuranMiB < AMAN_MIB,
            `catalog.json ${ukuranMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB ` +
                `(Cloudflare Pages menolak di atas ${BATAS_MIB} MiB). Rampingkan katalog dulu.`,
        );

        // Kanal Live TV tumbuh mengikuti iptv-org (kini 13.006), jadi berkasnya
        // ikut dijaga terhadap batas yang sama.
        const kanalPath = path.join(outDir, 'api', 'live', 'channels.json');
        const kanalMiB = fs.statSync(kanalPath).size / (1024 * 1024);
        assert.ok(
            kanalMiB < AMAN_MIB,
            `channels.json ${kanalMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB.`,
        );
        const kanal = JSON.parse(fs.readFileSync(kanalPath, 'utf8'));
        assert.ok(kanal.channels.length > 0, 'channels.json tidak boleh kosong');

        // Disalin apa adanya: byte-identik dengan sumber.
        assert.ok(
            fs.readFileSync(builtPath).equals(fs.readFileSync(sourcePath)),
            'catalog.json must be copied byte-for-byte',
        );
    } finally {
        fs.rmSync(outDir, { recursive: true, force: true });
    }
});
