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

        // Serial terbit terpisah justru supaya batas 25 MiB tidak dilanggar;
        // kalau berkasnya ada, ia dijaga dengan ambang yang sama dan episode
        // tiap serial wajib punya videoUrl - tanpa itu judulnya tidak bisa
        // diputar sama sekali.
        const serialSumber = path.join(backendDir, 'data', 'series_catalog.json');
        if (fs.existsSync(serialSumber)) {
            const serialPath = path.join(outDir, 'api', 'series.json');
            const serialMiB = fs.statSync(serialPath).size / (1024 * 1024);
            assert.ok(
                serialMiB < AMAN_MIB,
                `series.json ${serialMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB.`,
            );
            const serial = JSON.parse(fs.readFileSync(serialPath, 'utf8'));
            assert.ok(serial.movies.length > 0, 'series.json tidak boleh kosong');
            assert.ok(
                serial.movies.every((satu) => satu.type === 'series'),
                'setiap entri series.json harus bertipe series',
            );
            assert.ok(
                serial.movies.every(
                    (satu) => Array.isArray(satu.episodes) && satu.episodes.length > 0 &&
                        satu.episodes.every((ep) => typeof ep.videoUrl === 'string' && ep.videoUrl),
                ),
                'setiap serial harus punya episode dan tiap episode harus punya videoUrl',
            );
            assert.ok(
                fs.readFileSync(serialPath).equals(fs.readFileSync(serialSumber)),
                'series.json must be copied byte-for-byte',
            );
        }

        // Katalog film Indonesia: dijaga batas ukurannya, dan setiap film
        // WAJIB punya videoUrl berupa embed - tanpa itu judulnya tampil lalu
        // gagal diputar, yang lebih buruk daripada tidak ada sama sekali.
        const indoSumber = path.join(backendDir, 'data', 'indonesia_catalog.json');
        if (fs.existsSync(indoSumber)) {
            const indoPath = path.join(outDir, 'api', 'indonesia.json');
            const indoMiB = fs.statSync(indoPath).size / (1024 * 1024);
            assert.ok(
                indoMiB < AMAN_MIB,
                `indonesia.json ${indoMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB.`,
            );
            const indo = JSON.parse(fs.readFileSync(indoPath, 'utf8'));
            assert.ok(indo.movies.length > 0, 'indonesia.json tidak boleh kosong');
            assert.ok(
                indo.movies.every((satu) => typeof satu.videoUrl === 'string' && satu.videoUrl),
                'setiap film Indonesia harus punya videoUrl',
            );
            assert.ok(
                indo.movies.every((satu) => Array.isArray(satu.servers) && satu.servers.length > 0),
                'setiap film Indonesia harus punya daftar server',
            );
            assert.ok(
                indo.movies.every((satu) => (satu.countries || []).includes('Indonesia')),
                'setiap entri harus bertanda negara Indonesia',
            );
            assert.ok(
                fs.readFileSync(indoPath).equals(fs.readFileSync(indoSumber)),
                'indonesia.json must be copied byte-for-byte',
            );
        }

        // Katalog anime: penjagaan yang sama, ditambah satu hal khas anime —
        // setiap judul harus membawa daftar episode, karena pemutarnya
        // dikunci per episode (/embed/anime/<anilistId>/<n>). Judul tanpa
        // episode akan tampil tetapi tidak bisa dibuka.
        const animeSumber = path.join(backendDir, 'data', 'anime_catalog.json');
        if (fs.existsSync(animeSumber)) {
            const animePath = path.join(outDir, 'api', 'anime.json');
            const animeMiB = fs.statSync(animePath).size / (1024 * 1024);
            assert.ok(
                animeMiB < AMAN_MIB,
                `anime.json ${animeMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB.`,
            );
            const anime = JSON.parse(fs.readFileSync(animePath, 'utf8'));
            assert.ok(anime.movies.length > 0, 'anime.json tidak boleh kosong');
            assert.ok(
                anime.movies.every((satu) => typeof satu.videoUrl === 'string' && satu.videoUrl),
                'setiap anime harus punya videoUrl',
            );
            assert.ok(
                anime.movies.every(
                    (satu) => Array.isArray(satu.episodes) && satu.episodes.length > 0,
                ),
                'setiap anime harus punya minimal satu episode',
            );
            // Id ganda pernah membuat aplikasi mati di SubcomposeLayout
            // ("Key ... was already used"), jadi ini dijaga di CI.
            const id = new Set(anime.movies.map((satu) => satu.id));
            assert.equal(id.size, anime.movies.length, 'id anime tidak boleh ganda');
            assert.ok(
                fs.readFileSync(animePath).equals(fs.readFileSync(animeSumber)),
                'anime.json must be copied byte-for-byte',
            );
        }

        // Lapisan ketersediaan resmi. Kontraknya BEDA dari keempat katalog di
        // atas, dan bedanya itu yang dijaga: lapisan ini TIDAK boleh membawa
        // URL yang bisa diputar. Ia hanya menunjuk ke penyedia sah, dan
        // aplikasi membukanya di luar. Kalau suatu saat ada yang menyelipkan
        // videoUrl/servers ke sini, uji ini gagal di CI.
        const watchSumber = path.join(backendDir, 'data', 'watch_catalog.json');
        if (fs.existsSync(watchSumber)) {
            const watchPath = path.join(outDir, 'api', 'watch.json');
            const watchMiB = fs.statSync(watchPath).size / (1024 * 1024);
            assert.ok(
                watchMiB < AMAN_MIB,
                `watch.json ${watchMiB.toFixed(2)} MiB melewati ambang aman ${AMAN_MIB} MiB.`,
            );
            const watch = JSON.parse(fs.readFileSync(watchPath, 'utf8'));
            assert.ok(watch.titles.length > 0, 'watch.json tidak boleh kosong');
            assert.ok(watch.attribution, 'atribusi JustWatch/TMDB wajib ikut terbit');
            assert.equal(watch.region, 'ID', 'wilayah lapisan ini Indonesia');

            const JENIS = ['flatrate', 'free', 'ads', 'rent', 'buy'];
            assert.ok(
                watch.titles.every((satu) =>
                    JENIS.some((jenis) => (satu.watch?.[jenis] || []).length > 0),
                ),
                'setiap judul harus punya minimal satu penyedia sah',
            );
            // Integritas rujukan: id penyedia di judul harus ada di kamus,
            // kalau tidak chip-nya tampil tanpa nama.
            const kamus = new Set(Object.keys(watch.providers || {}));
            const hilang = new Set();
            for (const satu of watch.titles) {
                for (const jenis of JENIS) {
                    for (const pid of satu.watch?.[jenis] || []) {
                        if (!kamus.has(String(pid))) hilang.add(pid);
                    }
                }
            }
            assert.equal(
                hilang.size,
                0,
                `id penyedia tanpa entri di kamus: ${[...hilang].join(', ')}`,
            );
            // Lapisan rujukan, bukan lapisan pemutaran.
            assert.ok(
                watch.titles.every(
                    (satu) => !satu.videoUrl && !satu.servers && !satu.streamUrl,
                ),
                'lapisan ketersediaan tidak boleh membawa URL pemutaran',
            );
            // Alias id katalog -> TMDB id. Dipakai DUA lapisan (ketersediaan dan
            // subtitle), jadi alias SAH menunjuk ke judul yang tidak ada di
            // `titles`: sebuah film bisa tanpa layanan sah tapi punya subtitle.
            // Yang dijaga: bentuknya, bukan keberadaan judulnya.
            const alias = watch.aliases || {};
            const kunciAlias = Object.keys(alias);
            if (kunciAlias.length) {
                assert.ok(
                    kunciAlias.every((k) => typeof k === 'string' && k.length > 0),
                    'id katalog pada alias tidak boleh kosong',
                );
                assert.ok(
                    kunciAlias.every((k) => Number.isInteger(alias[k].tmdbId) && alias[k].tmdbId > 0),
                    'setiap alias harus punya tmdbId bulat positif',
                );
                assert.ok(
                    kunciAlias.every((k) => alias[k].type === 'movie' || alias[k].type === 'tv'),
                    'type alias hanya boleh movie atau tv',
                );
                // Satu id katalog tidak boleh menunjuk ke dua TMDB id; itu akan
                // membuat subtitle film lain tampil. Kunci objek JSON sudah unik,
                // jadi yang perlu dijaga adalah tidak ada nilai yang cacat.
                assert.ok(
                    kunciAlias.every((k) => Object.keys(alias[k]).length === 2),
                    'alias hanya boleh membawa tmdbId dan type',
                );
            }

            const idWatch = new Set(watch.titles.map((satu) => satu.id));
            assert.equal(idWatch.size, watch.titles.length, 'id watch tidak boleh ganda');
            assert.ok(
                fs.readFileSync(watchPath).equals(fs.readFileSync(watchSumber)),
                'watch.json must be copied byte-for-byte',
            );
        }

        // --- Lapisan SERVER anime (anikai) ---
        //
        // Lapisan kunci pemutar, bukan katalog. Yang dijaga di sini persis
        // sifat yang membuatnya aman dan kecil: tidak ada judul/poster/sinopsis
        // yang ikut, tiap entri benar-benar bisa dipakai, dan polanya lengkap —
        // sebab URL tiap episode dibentuk aplikasi DARI pola itu, jadi pola
        // yang cacat berarti seluruh anime kehilangan server cadangannya.
        const srvSumber = path.join(backendDir, 'data', 'anime_servers.json');
        if (fs.existsSync(srvSumber)) {
            const srvPath = path.join(outDir, 'api', 'anime-servers.json');
            assert.ok(fs.existsSync(srvPath), 'anime-servers.json harus ikut terbit');

            const mib = fs.statSync(srvPath).size / 1024 / 1024;
            assert.ok(mib < 24, `anime-servers.json ${mib.toFixed(2)} MiB melewati ambang 24 MiB`);

            const srv = JSON.parse(fs.readFileSync(srvPath, 'utf8'));
            assert.ok(srv.player && srv.player.startsWith('https://'), 'player harus https');
            for (const tanda of ['{player}', '{seal}', '{episode}', '{server}', '{audio}']) {
                assert.ok(
                    String(srv.pattern || '').includes(tanda),
                    `pattern harus memuat ${tanda}`,
                );
            }

            const entri = Object.entries(srv.titles || {});
            assert.ok(entri.length > 0, 'lapisan server anime tidak boleh kosong');
            for (const [idAnilist, nilai] of entri) {
                assert.ok(/^\d+$/.test(idAnilist), `kunci harus AniList id: ${idAnilist}`);
                assert.ok(nilai.seal, `seal kosong untuk ${idAnilist}`);
                assert.ok(nilai.server > 0, `server 0 tidak boleh terbit: ${idAnilist}`);
                // Hanya kunci pemutar yang boleh ada. Kalau suatu saat ada yang
                // menyelipkan metadata ke sini, lapisannya berhenti kecil.
                // `season` ikut diizinkan: ia bukan metadata judul melainkan
                // nomor musim TMDB, yang dipakai meminta subtitle musim yang
                // BENAR untuk anime (lihat 3p).
                for (const kunci of Object.keys(nilai)) {
                    assert.ok(
                        ['seal', 'server', 'season', 'eps'].includes(kunci),
                        `entri ${idAnilist} membawa kunci tak dikenal: ${kunci}`,
                    );
                }
                if (nilai.season !== undefined) {
                    assert.ok(
                        Number.isInteger(nilai.season) && nilai.season >= 1 && nilai.season <= 50,
                        `season tidak wajar untuk ${idAnilist}: ${nilai.season}`,
                    );
                }
            }

            assert.ok(
                fs.readFileSync(srvPath).equals(fs.readFileSync(srvSumber)),
                'anime-servers.json must be copied byte-for-byte',
            );
        }

        // Disalin apa adanya: byte-identik dengan sumber.
        assert.ok(
            fs.readFileSync(builtPath).equals(fs.readFileSync(sourcePath)),
            'catalog.json must be copied byte-for-byte',
        );
    } finally {
        fs.rmSync(outDir, { recursive: true, force: true });
    }
});
