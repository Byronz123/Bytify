import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { createApp } from '../server.mjs';

test('serves movie and HTTPS live catalog contracts for the APK', async (t) => {
    const directory = mkdtempSync(path.join(os.tmpdir(), 'bytify-api-'));
    const movieDatabasePath = path.join(directory, 'movies.json');
    const liveDatabasePath = path.join(directory, 'live.json');
    writeFileSync(movieDatabasePath, JSON.stringify([
        {
            id: 'public-domain-film',
            title: 'Public Domain Film',
            poster_url: 'https://media.example/poster.jpg',
            stream_urls: ['https://media.example/film.mp4'],
        },
    ]));
    writeFileSync(liveDatabasePath, JSON.stringify([
        {
            id: 'authorized-channel',
            name: 'Authorized Channel',
            country: 'ID',
            category: 'News',
            logo: 'https://media.example/logo.png',
            stream_url: 'https://media.example/live.m3u8',
        },
        {
            id: 'insecure-channel',
            name: 'Insecure Channel',
            stream_url: 'http://media.example/live.m3u8',
        },
    ]));

    const server = createApp({ movieDatabasePath, liveDatabasePath }).listen(0);
    t.after(async () => {
        await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
        rmSync(directory, { recursive: true, force: true });
    });

    const address = server.address();
    assert.ok(address && typeof address === 'object');
    const baseUrl = `http://127.0.0.1:${address.port}`;

    const healthResponse = await fetch(`${baseUrl}/health`);
    assert.equal(healthResponse.status, 200);
    assert.deepEqual(await healthResponse.json(), { ok: true });

    const movieResponse = await fetch(`${baseUrl}/api/catalog.json`);
    assert.equal(movieResponse.status, 200);
    const movieCatalog = await movieResponse.json();
    assert.equal(movieCatalog.movies[0].id, 'public-domain-film');
    assert.equal(movieCatalog.movies[0].posterUrl, 'https://media.example/poster.jpg');
    assert.equal(movieCatalog.movies[0].videoUrl, 'https://media.example/film.mp4');

    const liveResponse = await fetch(`${baseUrl}/api/live/channels`);
    assert.equal(liveResponse.status, 200);
    const liveCatalog = await liveResponse.json();
    assert.equal(liveCatalog.channels.length, 1);
    assert.equal(liveCatalog.channels[0].streamType, 'hls');
    assert.equal(liveCatalog.channels[0].isAvailable, true);
    assert.ok(liveCatalog.channels[0].checkedAtEpochMs > 0);
});
