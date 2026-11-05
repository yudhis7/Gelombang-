'use strict';
// Client for the Radio Browser community directory (https://www.radio-browser.info).
// Finds a working API mirror, fails over between mirrors, caches results,
// and returns stations in a compact shape the UI uses.

const dns = require('dns').promises;

const USER_AGENT = 'GelombangRadio/1.0 (personal desktop player)';
const FALLBACK_HOSTS = [
  'de1.api.radio-browser.info',
  'de2.api.radio-browser.info',
  'fi1.api.radio-browser.info',
  'nl1.api.radio-browser.info',
  'at1.api.radio-browser.info'
];
const TTL_LIST = 15 * 60 * 1000;
const TTL_COUNTS = 6 * 60 * 60 * 1000;
const REQUEST_TIMEOUT = 12000;

function shuffle(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function withTimeout(promise, ms, label) {
  let t;
  return Promise.race([
    promise,
    new Promise((_, rej) => { t = setTimeout(() => rej(new Error(`${label || 'request'} timed out`)), ms); })
  ]).finally(() => clearTimeout(t));
}

function normalizeStation(s) {
  const url = (s.url_resolved || s.url || '').trim();
  const tags = String(s.tags || '')
    .split(',')
    .map((t) => t.trim().toLowerCase())
    .filter((t) => t && t.length <= 30);
  return {
    uuid: s.stationuuid,
    name: String(s.name || '').replace(/\s+/g, ' ').trim() || 'Unnamed station',
    url,
    homepage: s.homepage || '',
    favicon: s.favicon || '',
    tags: [...new Set(tags)].slice(0, 8),
    country: s.country || '',
    countrycode: String(s.countrycode || '').toUpperCase(),
    state: s.state || '',
    language: s.language || '',
    codec: s.codec && s.codec !== 'UNKNOWN' ? s.codec : '',
    bitrate: Number(s.bitrate) || 0,
    hls: Number(s.hls) === 1 || /\.m3u8(\?|$)/i.test(url),
    votes: Number(s.votes) || 0,
    clickcount: Number(s.clickcount) || 0,
    clicktrend: Number(s.clicktrend) || 0
  };
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const i = next++;
      try { out[i] = await fn(items[i], i); } catch (e) { out[i] = e; }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

class RadioBrowser {
  /**
   * @param {object} opts
   * @param {Function} opts.fetchImpl  fetch-compatible function (Electron net.fetch in the app)
   * @param {string[]} opts.regionCodes ISO country codes that count as "in region"
   * @param {string}   [opts.baseOverride] fixed API base URL (used for testing)
   */
  constructor({ fetchImpl, regionCodes, baseOverride }) {
    this.fetch = fetchImpl;
    this.regionCodes = new Set(regionCodes.map((c) => c.toUpperCase()));
    this.baseOverride = baseOverride || null;
    this.cache = new Map();
    this.serversPromise = null;
    this.current = 0;
  }

  async _fetchJson(url) {
    const res = await withTimeout(
      this.fetch(url, { headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' } }),
      REQUEST_TIMEOUT,
      'Radio directory'
    );
    if (!res.ok) throw new Error(`Radio directory answered ${res.status}`);
    return res.json();
  }

  async _discover() {
    if (this.baseOverride) return [this.baseOverride.replace(/\/$/, '')];
    let hosts = [];
    try {
      const srv = await withTimeout(dns.resolveSrv('_api._tcp.radio-browser.info'), 4000, 'DNS');
      hosts = srv.map((r) => r.name);
    } catch (_) { /* fall through */ }
    if (!hosts.length) {
      try {
        const list = await this._fetchJson('https://all.api.radio-browser.info/json/servers');
        hosts = list.map((s) => s.name).filter(Boolean);
      } catch (_) { /* fall through */ }
    }
    if (!hosts.length) hosts = FALLBACK_HOSTS;
    return shuffle([...new Set(hosts)]).map((h) => `https://${h}`);
  }

  _servers() {
    if (!this.serversPromise) this.serversPromise = this._discover();
    return this.serversPromise;
  }

  async request(path, params = {}, ttl = TTL_LIST) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
    }
    const rel = path + (qs.toString() ? `?${qs}` : '');
    const hit = this.cache.get(rel);
    if (hit && Date.now() - hit.t < ttl) return hit.data;

    const servers = await this._servers();
    let lastErr;
    for (let i = 0; i < servers.length; i++) {
      const idx = (this.current + i) % servers.length;
      try {
        const data = await this._fetchJson(servers[idx] + rel);
        this.current = idx;
        this.cache.set(rel, { t: Date.now(), data });
        return data;
      } catch (e) {
        lastErr = e;
      }
    }
    this.serversPromise = null; // rediscover next time
    if (hit) return hit.data; // stale beats nothing
    throw lastErr || new Error('Radio directory unreachable');
  }

  /** Station counts for every country in the region: { ID: 1234, AU: 987, ... } */
  async countryCounts() {
    const out = {};
    try {
      const rows = await this.request('/json/countrycodes', { hidebroken: true }, TTL_COUNTS);
      for (const r of rows) {
        const code = String(r.name || '').toUpperCase();
        if (this.regionCodes.has(code)) out[code] = (out[code] || 0) + (Number(r.stationcount) || 0);
      }
    } catch (e) {
      // Older mirrors: fall back to the country list, keyed by ISO code.
      const rows = await this.request('/json/countries', { hidebroken: true }, TTL_COUNTS);
      for (const r of rows) {
        const code = String(r.iso_3166_1 || r.countrycode || '').toUpperCase();
        if (this.regionCodes.has(code)) out[code] = (out[code] || 0) + (Number(r.stationcount) || 0);
      }
    }
    return out;
  }

  /** One page of stations for a country. */
  async stationsByCountry(code, { offset = 0, limit = 100, order = 'clickcount', reverse = true, tag } = {}) {
    const rows = await this.request('/json/stations/search', {
      countrycode: code,
      hidebroken: true,
      order,
      reverse,
      offset,
      limit,
      tag: tag || undefined
    });
    return rows.map(normalizeStation).filter((s) => s.url);
  }

  /** Most popular stations across several countries, merged. */
  async topAcross(codes, perCountry = 15) {
    const results = await mapLimit(codes, 5, (code) =>
      this.stationsByCountry(code, { limit: perCountry, order: 'clickcount', reverse: true })
    );
    const seen = new Set();
    const merged = [];
    let failures = 0;
    for (const r of results) {
      if (r instanceof Error) { failures++; continue; }
      for (const s of r) {
        if (!seen.has(s.uuid)) { seen.add(s.uuid); merged.push(s); }
      }
    }
    if (failures === codes.length && codes.length) throw results.find((r) => r instanceof Error);
    merged.sort((a, b) => b.clickcount - a.clickcount);
    return merged;
  }

  /** Search by station name and by tag, limited to the region (or one country). */
  async search(query, code) {
    const q = String(query || '').trim();
    if (q.length < 2) return [];
    const base = { hidebroken: true, order: 'clickcount', reverse: true };
    const scope = code ? { countrycode: code, limit: 300 } : { limit: 1000 };
    const [byName, byTag] = await Promise.allSettled([
      this.request('/json/stations/search', { ...base, ...scope, name: q }),
      this.request('/json/stations/search', { ...base, ...scope, tag: q.toLowerCase() })
    ]);
    if (byName.status === 'rejected' && byTag.status === 'rejected') throw byName.reason;
    const rows = [
      ...(byName.status === 'fulfilled' ? byName.value : []),
      ...(byTag.status === 'fulfilled' ? byTag.value : [])
    ];
    const seen = new Set();
    const out = [];
    for (const raw of rows) {
      const s = normalizeStation(raw);
      if (!s.url || seen.has(s.uuid)) continue;
      if (!code && !this.regionCodes.has(s.countrycode)) continue;
      seen.add(s.uuid);
      out.push(s);
    }
    out.sort((a, b) => b.clickcount - a.clickcount);
    return out.slice(0, 300);
  }

  /** Fresh details for saved stations (stream URLs change over time). */
  async byUuids(uuids) {
    const ids = uuids.filter(Boolean);
    const out = [];
    for (let i = 0; i < ids.length; i += 50) {
      const chunk = ids.slice(i, i + 50);
      const rows = await this.request('/json/stations/byuuid', { uuids: chunk.join(',') }, 60 * 1000);
      out.push(...rows.map(normalizeStation));
    }
    return out;
  }

  /** Tell the directory a station was played (helps its popularity ranking). */
  async click(uuid) {
    if (!uuid) return;
    try {
      const servers = await this._servers();
      await this._fetchJson(`${servers[this.current % servers.length]}/json/url/${encodeURIComponent(uuid)}`);
    } catch (_) { /* best effort */ }
  }
}

module.exports = { RadioBrowser, normalizeStation, USER_AGENT };
