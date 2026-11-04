import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.mjs';
import { createMovieService, normalizeItem, buildSections } from '../src/movies.mjs';
import { createLiveService } from '../src/live.mjs';

const quiet = { debug() {}, info() {}, warn() {}, error() {} };

const fixture = {
  items: [
    { id: 'm1', type: 'movie', title: 'Night Classic', overview: 'Horor klasik', genres: ['Horor'], releaseYear: 1968,
      videoUrl: 'https://media.example/m1.mp4', license: 'Public domain', popularity: 50, addedAtEpochMs: 10,
      subtitles: [{ lang: 'id', label: 'Indonesia', url: 'https://media.example/m1.id.vtt' }] },
    { id: 'm2', type: 'movie', title: 'Comedy Day', genres: ['Komedi'], videoUrl: 'https://media.example/m2.mp4', license: 'CC0', popularity: 90, addedAtEpochMs: 5 },
    { id: 's1', type: 'series', title: 'Ranger Show', license: 'Public domain', popularity: 70,
      episodes: [{ title: 'Pilot', videoUrl: 'https://media.example/s1e1.mp4' }, { title: 'Bad', videoUrl: 'http://insecure.example/x.mp4' }] },
    { id: 'nolicense', title: 'Tanpa lisensi', videoUrl: 'https://media.example/x.mp4' },
  ],
  sections: [],
};

async function setup({ withLive = false } = {}) {
  const movies = createMovieService({ providers: [], refreshMs: 1e9 }, quiet, [{ name: 'fixture', load: async () => fixture }]);
  await movies.sync();
  const live = createLiveService({ sourceUrls: withLive ? ['https://p.example/a.m3u'] : [], rightsConfirmed: withLive, licenseLabel: withLive ? 'test' : '', healthCheckMs: 120000 }, quiet);
  if (withLive) live.load([
    { id: 'news.id', name: 'News ID', category: 'News', country: 'ID', streamUrl: 'https://s.example/n.m3u8', streamType: 'hls', isAvailable: true },
    { id: 'sport.us', name: 'Sport US', category: 'Sports', country: 'US', streamUrl: 'https://s.example/s.m3u8', streamType: 'hls', isAvailable: false },
  ]);
  const app = createApp({ movies, live, log: quiet, config: {} });
  const server = app.server();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const get = async (path, headers) => { const res = await fetch(base + path, { headers }); return { status: res.status, headers: res.headers, body: res.status === 304 ? null : await res.json() }; };
  return { get, close: () => new Promise((r) => server.close(r)) };
}

test('normalizeItem rejects items without license and drops insecure episode URLs', () => {
  assert.equal(normalizeItem(fixture.items[3], 'x'), null);
  const series = normalizeItem(fixture.items[2], 'x');
  assert.equal(series.episodes.length, 1);
  assert.equal(series.videoUrl, 'https://media.example/s1e1.mp4');
});

test('buildSections creates popular, latest, series and genre sections', () => {
  const items = fixture.items.map((i) => normalizeItem(i, 'x')).filter(Boolean);
  const titles = buildSections(items).map((s) => s.title);
  assert.deepEqual(titles.slice(0, 3), ['Populer', 'Terbaru ditambahkan', 'Serial TV']);
  assert.ok(titles.includes('Horor'));
});

test('GET /api/catalog.json returns the {movies, sections} contract with ETag', async () => {
  const api = await setup();
  try {
    const res = await api.get('/api/catalog.json');
    assert.equal(res.status, 200);
    assert.equal(res.body.movies.length, 3);
    assert.ok(Array.isArray(res.body.sections));
    assert.equal(res.body.movies[0].provider, undefined);
    const again = await api.get('/api/catalog.json', { 'if-none-match': res.headers.get('etag') });
    assert.equal(again.status, 304);
  } finally { await api.close(); }
});

test('movies, series, detail and pagination', async () => {
  const api = await setup();
  try {
    const list = await api.get('/api/movies?pageSize=1');
    assert.equal(list.body.ok, true);
    assert.equal(list.body.data.length, 1);
    assert.equal(list.body.data[0].id, 'm2'); // urut popularitas
    assert.deepEqual([list.body.meta.total, list.body.meta.totalPages, list.body.meta.hasNext], [2, 2, true]);
    const series = await api.get('/api/series');
    assert.equal(series.body.data[0].episodeCount, 1);
    const detail = await api.get('/api/movies/m1');
    assert.equal(detail.body.data.subtitles[0].lang, 'id');
    const missing = await api.get('/api/movies/nope');
    assert.equal(missing.status, 404);
    assert.equal(missing.body.error.code, 'NOT_FOUND');
  } finally { await api.close(); }
});

test('search validates query and covers movies and live channels', async () => {
  const api = await setup({ withLive: true });
  try {
    assert.equal((await api.get('/api/search?q=a')).status, 400);
    const res = await api.get('/api/search?q=news');
    assert.deepEqual(res.body.data.map((d) => d.id), ['news.id']);
    const horror = await api.get('/api/search?q=horor');
    assert.equal(horror.body.data[0].id, 'm1');
  } finally { await api.close(); }
});

test('live endpoints filter, page, and report status', async () => {
  const api = await setup({ withLive: true });
  try {
    const all = await api.get('/api/live/channels');
    assert.equal(all.body.channels.length, 2);
    assert.equal((await api.get('/api/live/channels?country=us')).body.channels[0].id, 'sport.us');
    const paged = await api.get('/api/live/channels?page=1&pageSize=1');
    assert.equal(paged.body.meta.totalPages, 2);
    const cats = await api.get('/api/live/categories');
    assert.deepEqual(cats.body.categories, ['News', 'Sports']);
    assert.equal((await api.get('/api/live/channel/news.id')).body.data.name, 'News ID');
    const status = await api.get('/api/sync/status');
    assert.equal(status.body.data.live.available, 1);
    assert.equal(status.body.data.movies.series, 1);
  } finally { await api.close(); }
});

test('live endpoints answer 503 when no provider is configured', async () => {
  const api = await setup();
  try {
    const res = await api.get('/api/live/channels');
    assert.equal(res.status, 503);
    assert.equal(res.body.error.code, 'LIVE_NOT_CONFIGURED');
  } finally { await api.close(); }
});
