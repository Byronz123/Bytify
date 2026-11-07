import assert from 'node:assert/strict';
import { test } from 'node:test';
import { onRequestGet as searchMovies } from '../../../functions/api/lk21/search.js';
import { onRequestGet as getLiveChannels } from '../../../functions/api/live/channels.js';
import { onRequest as health } from '../../../functions/health.js';

test('Pages search function filters the static movie catalog', async () => {
    const request = new Request('https://bytify.pages.dev/api/lk21/search?q=film');
    const env = {
        ASSETS: {
            fetch: async (url) => {
                assert.equal(new URL(url).pathname, '/api/catalog.json');
                return Response.json({
                    movies: [
                        { id: '1', title: 'Film One' },
                        { id: '2', title: 'Another Movie' },
                    ],
                });
            },
        },
    };

    const response = await searchMovies({ request, env });
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).movies.map((movie) => movie.id), ['1']);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
});

test('Pages live function serves the static live catalog', async () => {
    const request = new Request('https://bytify.pages.dev/api/live/channels');
    const env = {
        ASSETS: {
            fetch: async (url) => {
                assert.equal(new URL(url).pathname, '/api/live/channels.json');
                return Response.json({ channels: [{ id: 'channel-1' }] });
            },
        },
    };

    const response = await getLiveChannels({ request, env });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
    assert.deepEqual(await response.json(), { channels: [{ id: 'channel-1' }] });
});

test('Pages health function returns a successful JSON response', async () => {
    const response = health();
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true });
});
