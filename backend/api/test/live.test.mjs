import test from 'node:test';
import assert from 'node:assert/strict';

import { parseM3u as parse, probeChannel } from '../src/live.mjs';

const parseM3u = (text, url) => parse(text, url, { licenseLabel: 'unit-test-license', defaultCountry: 'ID' });

const sourceUrl = 'https://playlist.example/authorized.m3u';

test('parses an authorized HLS channel and maps its catalog metadata', () => {
  const playlist = [
    '#EXTM3U',
    '#EXTINF:-1 tvg-id="news.id" tvg-name="News ID" tvg-logo="https://cdn.example/news.png" group-title="News" tvg-country="ID" tvg-language="id",News Indonesia',
    'https://stream.example/live/news/index.m3u8',
  ].join('\n');

  const [channel] = parseM3u(playlist, sourceUrl);
  assert.equal(channel.id, 'news.id');
  assert.equal(channel.name, 'News ID');
  assert.equal(channel.category, 'News');
  assert.equal(channel.country, 'ID');
  assert.equal(channel.language, 'id');
  assert.equal(channel.streamType, 'hls');
  assert.equal(channel.isAvailable, false);
  assert.equal(channel.license, 'unit-test-license');
});

test('rejects non-HTTPS stream URLs', () => {
  const playlist = '#EXTM3U\n#EXTINF:-1 tvg-id="bad",Bad Channel\nhttp://bad.example/live.m3u8';
  assert.equal(parseM3u(playlist, sourceUrl).length, 0);
});

test('rejects malformed playlist documents', () => {
  assert.throws(() => parseM3u('not-a-playlist', sourceUrl), /valid/);
});


test('marks a valid HLS manifest as available', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response('#EXTM3U\n#EXT-X-VERSION:3\n', { status: 200 });
  try {
    const [channel] = parseM3u(
      '#EXTM3U\n#EXTINF:-1 tvg-id="test.hls",Test HLS\nhttps://stream.example/test/index.m3u8',
      sourceUrl,
    );
    const checked = await probeChannel(channel);
    assert.equal(checked.isAvailable, true);
    assert.equal(checked.statusMessage, 'Online');
    assert.ok(checked.checkedAtEpochMs > 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('marks an HTML response as unavailable for an HLS channel', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response('<html>temporary error</html>', { status: 200 });
  try {
    const [channel] = parseM3u(
      '#EXTM3U\n#EXTINF:-1 tvg-id="test.down",Test Down\nhttps://stream.example/down/index.m3u8',
      sourceUrl,
    );
    const checked = await probeChannel(channel);
    assert.equal(checked.isAvailable, false);
    assert.equal(checked.statusMessage, 'Respons bukan manifest HLS');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
