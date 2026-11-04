const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

export function createLogger(level = 'info') {
  const min = LEVELS[level] ?? LEVELS.info;
  const write = (name) => (message, extra) => {
    if (LEVELS[name] < min) return;
    const line = { time: new Date().toISOString(), level: name, message, ...(extra || {}) };
    (name === 'error' || name === 'warn' ? console.error : console.log)(JSON.stringify(line));
  };
  return { debug: write('debug'), info: write('info'), warn: write('warn'), error: write('error') };
}

/** Error dengan kode HTTP dan kode mesin untuk respons yang konsisten. */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function isPrivateHost(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return host === 'localhost' || host.endsWith('.local') || host === '::1' ||
    /^(127\.|10\.|192\.168\.|169\.254\.|0\.)/.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host) || /^f[cd][0-9a-f]{2}:/.test(host);
}

export function assertPublicHttps(rawUrl, label) {
  const parsed = new URL(rawUrl);
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password) {
    throw new Error(`${label} harus berupa URL HTTPS tanpa kredensial tertanam.`);
  }
  if (isPrivateHost(parsed.hostname)) throw new Error(`${label} mengarah ke host privat/lokal.`);
  return parsed;
}

/** fetch yang memeriksa setiap redirect agar tidak berpindah ke host privat. */
export async function safeFetch(rawUrl, init, label) {
  let currentUrl = assertPublicHttps(rawUrl, label).toString();
  for (let redirectCount = 0; redirectCount <= 5; redirectCount += 1) {
    const response = await fetch(currentUrl, { ...init, redirect: 'manual' });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location');
      await response.body?.cancel().catch(() => {});
      if (!location) return response;
      currentUrl = assertPublicHttps(new URL(location, currentUrl).toString(), `${label} redirect`).toString();
      continue;
    }
    return response;
  }
  throw new Error(`${label} terlalu banyak redirect.`);
}

export function slug(value) {
  return String(value).toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 70) || 'item';
}

/** Menjalankan fungsi async dengan batas paralel. */
export async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const index = next++;
      results[index] = await fn(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

export function paginate(list, searchParams, { defaultSize = 20, maxSize = 100 } = {}) {
  const page = Math.max(1, Number.parseInt(searchParams.get('page') || '1', 10) || 1);
  const pageSize = Math.min(maxSize, Math.max(1, Number.parseInt(searchParams.get('pageSize') || `${defaultSize}`, 10) || defaultSize));
  const total = list.length;
  const start = (page - 1) * pageSize;
  return {
    items: list.slice(start, start + pageSize),
    meta: { page, pageSize, total, totalPages: Math.max(1, Math.ceil(total / pageSize)), hasNext: start + pageSize < total },
  };
}
