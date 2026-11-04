import { readFile } from 'node:fs/promises';
import { assertPublicHttps, safeFetch, slug, mapLimit } from './util.mjs';

const USER_AGENT = 'Bytify-LiveCatalog/1.1';

function parseAttributes(text) {
  const result = {};
  for (const match of text.matchAll(/([\w-]+)="([^"]*)"/g)) result[match[1].toLowerCase()] = match[2].trim();
  return result;
}

/** Mengubah playlist M3U menjadi daftar kanal. Entri non-HTTPS / non-HLS/MP4 dilewati. */
export function parseM3u(text, sourceUrl, { licenseLabel = '', defaultCountry = 'ID', log } = {}) {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).map((line) => line.trim());
  if (!lines.some((line) => line.startsWith('#EXTM3U'))) throw new Error('Respons sumber bukan playlist M3U yang valid.');
  const parsed = [];
  let current = null;
  let currentGroup = '';

  for (const line of lines) {
    if (!line) continue;
    if (line.startsWith('#EXTINF:')) {
      const comma = line.lastIndexOf('",') >= 0 ? line.lastIndexOf('",') + 1 : line.indexOf(',');
      const metadata = comma >= 0 ? line.slice(8, comma) : line.slice(8);
      const name = comma >= 0 ? line.slice(comma + 1).trim() : '';
      current = { attributes: parseAttributes(metadata), name, group: currentGroup };
      continue;
    }
    if (line.startsWith('#EXTGRP:')) {
      currentGroup = line.slice(8).trim();
      if (current) current.group = currentGroup;
      continue;
    }
    if (line.startsWith('#') || !current) continue;

    try {
      const streamUrl = new URL(line, sourceUrl).toString();
      assertPublicHttps(streamUrl, 'URL siaran');
      const name = current.attributes['tvg-name'] || current.name || current.attributes['tvg-id'];
      const streamType = /\.m3u8(?:$|[?#])/i.test(streamUrl) ? 'hls' : (/\.mp4(?:$|[?#])/i.test(streamUrl) ? 'mp4' : null);
      if (name && streamType) {
        const category = (current.attributes['group-title'] || current.group || 'General').split(';')[0].trim() || 'General';
        const country = (current.attributes['tvg-country'] || defaultCountry).split(/[;,]/)[0].trim().toUpperCase();
        const id = current.attributes['tvg-id'] || `${slug(name)}-${slug(country)}`;
        let logoUrl = null;
        if (current.attributes['tvg-logo']) {
          try { logoUrl = assertPublicHttps(new URL(current.attributes['tvg-logo'], sourceUrl).toString(), 'URL logo').toString(); } catch { logoUrl = null; }
        }
        parsed.push({
          id: id.slice(0, 160),
          name: name.slice(0, 160),
          description: '',
          logoUrl,
          category: category.slice(0, 80),
          country: country.slice(0, 8),
          language: current.attributes['tvg-language'] || '',
          streamUrl,
          streamType,
          isAvailable: false,
          statusMessage: 'Belum diperiksa',
          checkedAtEpochMs: 0,
          license: licenseLabel,
        });
      }
    } catch (error) {
      log?.debug('Lewati entri playlist tidak valid', { reason: error.message });
    }
    current = null;
  }
  return parsed;
}

export async function probeChannel(channel, timeoutMs = 5000) {
  const checkedAtEpochMs = Date.now();
  try {
    const response = await safeFetch(channel.streamUrl, {
      method: 'GET',
      headers: { range: 'bytes=0-2047', 'user-agent': USER_AGENT, accept: '*/*' },
      signal: AbortSignal.timeout(timeoutMs),
    }, 'URL siaran');
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return { ...channel, isAvailable: false, statusMessage: `HTTP ${response.status}`, checkedAtEpochMs };
    }
    const reader = response.body?.getReader();
    const first = reader ? await reader.read() : { value: undefined };
    await reader?.cancel().catch(() => {});
    const prefix = first.value ? new TextDecoder().decode(first.value.slice(0, 2048)).trimStart() : '';
    if (channel.streamType === 'hls' && !prefix.startsWith('#EXTM3U')) {
      return { ...channel, isAvailable: false, statusMessage: 'Respons bukan manifest HLS', checkedAtEpochMs };
    }
    return { ...channel, isAvailable: true, statusMessage: 'Online', checkedAtEpochMs };
  } catch (error) {
    return { ...channel, isAvailable: false, statusMessage: error.name === 'TimeoutError' ? 'Timeout' : 'Sumber tidak merespons', checkedAtEpochMs };
  }
}

/** Menyimpan kanal di memori, menyinkronkan playlist, dan memeriksa status stream berkala. */
export function createLiveService(config, log) {
  const channels = new Map();
  const state = { lastSourceSyncAt: 0, lastHealthCheckAt: 0, sourceSyncError: null, checking: false, version: 0 };
  const enabled = config.sourceUrls.length > 0 && config.rightsConfirmed && Boolean(config.licenseLabel);

  async function fetchPlaylist(sourceUrl) {
    // Playlist lokal (dikurasi operator) boleh berupa path berkas; URL siaran di dalamnya tetap wajib HTTPS.
    if (!/^https?:\/\//i.test(sourceUrl)) return readFile(sourceUrl.replace(/^file:\/\//i, ''), 'utf8');
    const response = await safeFetch(sourceUrl, {
      headers: { accept: 'application/x-mpegURL, audio/x-mpegurl, text/plain;q=0.9, */*;q=0.8', 'user-agent': USER_AGENT },
      signal: AbortSignal.timeout(Math.max(config.probeTimeoutMs, 15000)),
    }, 'URL playlist');
    if (!response.ok) throw new Error(`Playlist HTTP ${response.status}`);
    const text = await response.text();
    if (text.length > 8 * 1024 * 1024) throw new Error('Playlist melebihi batas 8 MB.');
    return text;
  }

  async function refreshSources() {
    if (!enabled) return false;
    const discovered = new Map();
    const errors = [];
    for (const sourceUrl of config.sourceUrls) {
      try {
        const text = await fetchPlaylist(sourceUrl);
        for (const channel of parseM3u(text, /^https?:/i.test(sourceUrl) ? sourceUrl : 'https://local.invalid/',  { licenseLabel: config.licenseLabel, defaultCountry: config.defaultCountry, log })) {
          if (!discovered.has(channel.id)) discovered.set(channel.id, channel);
          if (discovered.size >= config.maxChannels) break;
        }
      } catch (error) {
        errors.push(`${/^https?:/i.test(sourceUrl) ? new URL(sourceUrl).hostname : 'file'}: ${error.message}`);
      }
      if (discovered.size >= config.maxChannels) break;
    }
    if (discovered.size === 0) {
      state.sourceSyncError = errors.join('; ') || 'Tidak ada kanal valid dari sumber.';
      log.error('Sinkronisasi live tidak menghasilkan kanal', { error: state.sourceSyncError });
      return false;
    }
    let changed = discovered.size !== channels.size;
    for (const [id, next] of discovered) {
      const old = channels.get(id);
      if (!old || old.streamUrl !== next.streamUrl || old.name !== next.name || old.category !== next.category) changed = true;
      if (old && old.streamUrl === next.streamUrl) {
        discovered.set(id, { ...next, isAvailable: old.isAvailable, statusMessage: old.statusMessage, checkedAtEpochMs: old.checkedAtEpochMs });
      }
    }
    channels.clear();
    for (const [id, channel] of discovered) channels.set(id, channel);
    state.sourceSyncError = errors.length ? errors.join('; ') : null;
    state.lastSourceSyncAt = Date.now();
    if (changed) state.version += 1;
    log.info('Sumber live diperbarui', { channels: channels.size, changed });
    return true;
  }

  async function checkAll() {
    if (state.checking || channels.size === 0) return;
    state.checking = true;
    try {
      const checked = await mapLimit([...channels.values()], config.concurrency, (item) => probeChannel(item, config.probeTimeoutMs));
      for (const item of checked) if (channels.has(item.id)) channels.set(item.id, item);
      state.lastHealthCheckAt = Date.now();
      state.version += 1;
      log.info('Pemeriksaan live selesai', { available: checked.filter((c) => c.isAvailable).length, total: checked.length });
    } finally {
      state.checking = false;
    }
  }

  function list() {
    return [...channels.values()].sort((a, b) => Number(b.isAvailable) - Number(a.isAvailable) ||
      a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
  }

  function start() {
    if (!enabled) {
      log.warn('Live TV nonaktif: isi LIVE_PLAYLIST_URLS, LIVE_SOURCE_RIGHTS_CONFIRMED=true, dan LIVE_SOURCE_LICENSE_LABEL.');
      return;
    }
    const initial = async (attempt = 0) => {
      if (await refreshSources()) return checkAll();
      const delay = Math.min(60_000 * 2 ** attempt, 15 * 60_000);
      log.warn('Sinkronisasi live akan dicoba ulang', { inSeconds: delay / 1000 });
      setTimeout(() => { void initial(attempt + 1).catch(() => {}); }, delay).unref();
    };
    void initial().catch((e) => log.error('Sinkronisasi live gagal', { error: e.message }));
    setInterval(() => { void checkAll(); }, config.healthCheckMs).unref();
    setInterval(async () => { if (await refreshSources()) await checkAll(); }, config.refreshMs).unref();
  }

  return {
    enabled, state, list, start, refreshSources, checkAll,
    get: (id) => channels.get(id),
    load: (items) => { channels.clear(); for (const item of items) channels.set(item.id, item); state.version += 1; },
  };
}
