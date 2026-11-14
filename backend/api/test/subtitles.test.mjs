import assert from 'node:assert/strict';
import test from 'node:test';

import { createApp } from '../server.mjs';
import { createSubtitleService, normalkanBahasa } from '../src/subtitles.mjs';

/**
 * Upstream OpenSubtitles ditiru supaya kontrak endpoint ini teruji di CI tanpa
 * kunci API nyata dan tanpa memakan kuota siapa pun. Bentuk jawaban tiruan
 * mengikuti API v1: `data[].attributes.files[].file_id`.
 */
function tiruanFetch(peta) {
    const dipanggil = [];
    const impl = async (url, opsi = {}) => {
        dipanggil.push({ url: String(url), method: opsi.method || 'GET', body: opsi.body || null });
        for (const [pola, jawab] of peta) {
            if (String(url).includes(pola)) {
                const { status = 200, body = {} } = typeof jawab === 'function' ? jawab() : jawab;
                return {
                    ok: status >= 200 && status < 300,
                    status,
                    text: async () => JSON.stringify(body),
                };
            }
        }
        throw new Error(`URL tiruan tak terdaftar: ${url}`);
    };
    impl.dipanggil = dipanggil;
    return impl;
}

const SATU_HASIL = {
    data: [
        {
            attributes: {
                language: 'id',
                download_count: 12,
                hearing_impaired: false,
                release: 'WEB-DL',
                files: [{ file_id: 111, file_name: 'film.id.srt' }],
            },
        },
        {
            attributes: {
                language: 'id',
                download_count: 980,
                hearing_impaired: false,
                release: 'BluRay',
                files: [{ file_id: 222, file_name: 'film.bluray.id.srt' }],
            },
        },
        {
            attributes: {
                language: 'en',
                download_count: 40,
                hearing_impaired: true,
                release: 'SDH',
                files: [{ file_id: 333, file_name: 'film.en.sdh.srt' }],
            },
        },
        // Tanpa file_id: harus dibuang, bukan membuat seluruh jawaban gagal.
        { attributes: { language: 'en', download_count: 5, files: [] } },
    ],
};

test('layanan subtitle menolak jalan sebelum kunci dan ketentuan diatur', async () => {
    const tanpaKunci = createSubtitleService({ termsAccepted: true });
    assert.equal(tanpaKunci.siap, false);
    let hasil = await tanpaKunci.cari({ tmdbId: 27205 });
    assert.equal(hasil.status, 503);
    assert.match(hasil.error, /OPENSUBTITLES_API_KEY/);

    // Kunci ada tetapi ketentuan belum diakui: tetap menolak. Gerbang sadar ini
    // sejajar dengan LIVE_SOURCE_RIGHTS_CONFIRMED pada lapisan Live TV.
    const tanpaTerms = createSubtitleService({ apiKey: 'kunci-uji' });
    assert.equal(tanpaTerms.siap, false);
    hasil = await tanpaTerms.cari({ tmdbId: 27205 });
    assert.equal(hasil.status, 503);
    assert.match(hasil.error, /TERMS_ACCEPTED/);
});

test('pencarian memetakan jawaban upstream dan memilih terbaik per bahasa', async () => {
    const fetchImpl = tiruanFetch([['/subtitles?', { body: SATU_HASIL }]]);
    const layanan = createSubtitleService({
        apiKey: 'kunci-uji',
        termsAccepted: true,
        fetchImpl,
    });

    const hasil = await layanan.cari({ tmdbId: 27205, languages: 'id,en' });
    assert.equal(hasil.status, 200);
    assert.deepEqual(hasil.languages, ['id', 'en']);

    // Bahasa Indonesia: yang 980 unduhan menang atas yang 12.
    const indo = hasil.subtitles.filter((x) => x.lang === 'id');
    assert.equal(indo.length, 1);
    assert.equal(indo[0].fileId, 222);

    // Inggris: satu-satunya yang punya file_id, dan ditandai gangguan pendengaran.
    const eng = hasil.subtitles.filter((x) => x.lang === 'en');
    assert.equal(eng.length, 1);
    assert.equal(eng[0].fileId, 333);
    assert.equal(eng[0].hearingImpaired, true);

    // Baris tanpa file_id dibuang, tidak ikut terhitung sebagai pilihan.
    assert.ok(hasil.subtitles.every((x) => Number.isFinite(x.fileId)));
    assert.equal(hasil.totalFound, 3);
    assert.ok(hasil.attribution, 'atribusi OpenSubtitles wajib ikut');

    // Kunci API TIDAK boleh ikut di URL; ia header, bukan query string.
    assert.ok(fetchImpl.dipanggil.every((p) => !p.url.includes('kunci-uji')));
    assert.ok(fetchImpl.dipanggil[0].url.includes('tmdb_id=27205'));
});

test('pencarian kedua dilayani dari singgahan agar kuota tidak terbuang', async () => {
    const fetchImpl = tiruanFetch([['/subtitles?', { body: SATU_HASIL }]]);
    const layanan = createSubtitleService({ apiKey: 'k', termsAccepted: true, fetchImpl });

    await layanan.cari({ tmdbId: 27205, languages: 'id' });
    const kedua = await layanan.cari({ tmdbId: 27205, languages: 'id' });
    assert.equal(kedua.cached, true);
    assert.equal(fetchImpl.dipanggil.length, 1, 'upstream hanya ditembak sekali');
});

test('tmdbId yang tidak sah ditolak sebelum menembak upstream', async () => {
    const fetchImpl = tiruanFetch([]);
    const layanan = createSubtitleService({ apiKey: 'k', termsAccepted: true, fetchImpl });
    for (const buruk of ['', 'abc', '0', '-5', null, undefined]) {
        const hasil = await layanan.cari({ tmdbId: buruk });
        assert.equal(hasil.status, 400, `tmdbId ${JSON.stringify(buruk)} harus ditolak`);
    }
    assert.equal(fetchImpl.dipanggil.length, 0, 'tidak boleh ada permintaan keluar');
});

test('kuota dan kunci ditolak dipetakan ke status yang bisa ditindak', async () => {
    const kuota = createSubtitleService({
        apiKey: 'k',
        termsAccepted: true,
        fetchImpl: tiruanFetch([['/subtitles?', { status: 429, body: { reset_time: '24h' } }]]),
    });
    const h1 = await kuota.cari({ tmdbId: 1 });
    assert.equal(h1.status, 429);

    // 406 dari OpenSubtitles berarti kuota unduh harian habis.
    const harian = createSubtitleService({
        apiKey: 'k',
        termsAccepted: true,
        fetchImpl: tiruanFetch([['/download', { status: 406, body: {} }]]),
    });
    const h2 = await harian.tautan({ fileId: 222 });
    assert.equal(h2.status, 429);

    const ditolak = createSubtitleService({
        apiKey: 'salah',
        termsAccepted: true,
        fetchImpl: tiruanFetch([['/subtitles?', { status: 401, body: {} }]]),
    });
    const h3 = await ditolak.cari({ tmdbId: 1 });
    assert.equal(h3.status, 502);
    assert.match(h3.error, /OPENSUBTITLES_API_KEY/);
});

test('tautan unduh wajib HTTPS dan memakai POST', async () => {
    const fetchImpl = tiruanFetch([
        ['/download', { body: { link: 'https://dl.opensubtitles.example/x.srt', file_name: 'x.srt', remaining: 19 } }],
    ]);
    const layanan = createSubtitleService({ apiKey: 'k', termsAccepted: true, fetchImpl });
    const hasil = await layanan.tautan({ fileId: 222 });
    assert.equal(hasil.status, 200);
    assert.match(hasil.link, /^https:\/\//);
    assert.equal(hasil.remaining, 19);
    assert.equal(fetchImpl.dipanggil[0].method, 'POST');
    assert.match(fetchImpl.dipanggil[0].body, /"file_id":222/);

    // Tautan non-HTTPS dari upstream ditolak: APK tidak boleh diarahkan ke HTTP.
    const takAman = createSubtitleService({
        apiKey: 'k',
        termsAccepted: true,
        fetchImpl: tiruanFetch([['/download', { body: { link: 'http://dl.example/x.srt' } }]]),
    });
    const buruk = await takAman.tautan({ fileId: 1 });
    assert.equal(buruk.status, 502);
});

test('endpoint HTTP subtitle mengikuti bentuk jawaban Bytify', async () => {
    const layanan = createSubtitleService({
        apiKey: 'k',
        termsAccepted: true,
        fetchImpl: tiruanFetch([['/subtitles?', { body: SATU_HASIL }]]),
    });
    const app = createApp({ subtitleService: layanan });
    const server = app.listen(0);
    try {
        const port = server.address().port;
        const resp = await fetch(`http://127.0.0.1:${port}/api/subtitles?tmdbId=27205&lang=id,en`);
        assert.equal(resp.status, 200);
        const body = await resp.json();
        assert.equal(body.ok, true);
        assert.equal(body.tmdbId, 27205);
        assert.ok(Array.isArray(body.subtitles) && body.subtitles.length > 0);

        // tmdbId hilang -> 400 dengan bentuk galat yang sama seperti endpoint lain.
        const buruk = await fetch(`http://127.0.0.1:${port}/api/subtitles`);
        assert.equal(buruk.status, 400);
        const bodyBuruk = await buruk.json();
        assert.equal(bodyBuruk.ok, false);
        assert.ok(bodyBuruk.error?.code);
    } finally {
        server.close();
    }
});

test('normalisasi bahasa membuang yang tidak berbentuk kode ISO', () => {
    assert.deepEqual(normalkanBahasa('id,en'), ['id', 'en']);
    assert.deepEqual(normalkanBahasa('ID, EN '), ['id', 'en']);
    assert.deepEqual(normalkanBahasa('id,id,en'), ['id', 'en'], 'duplikat dibuang');
    assert.deepEqual(normalkanBahasa('!!!'), ['id', 'en'], 'sampah jatuh ke bawaan');
    assert.equal(normalkanBahasa('a,b,c,d,e,f,g,h').length <= 5, true, 'dibatasi 5 bahasa');
});
