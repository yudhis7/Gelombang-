'use strict';
// "Now playing" for Icecast/SHOUTcast streams.
// Browsers cannot read in-stream ICY metadata, so the main process opens a short
// side connection every so often, reads the first metadata block (~16 KB), and closes.

const net = require('net');
const tls = require('tls');
const { USER_AGENT } = require('./radiobrowser');

const POLL_MS = 20000;
const RETRY_MS = 30000;
const TIMEOUT_MS = 8000;
const MAX_BLOCKS = 3;

// Legacy encodings some regional stations still use for titles.
const COUNTRY_ENCODINGS = {
  CN: 'gb18030', TW: 'big5', HK: 'big5', MO: 'big5',
  JP: 'shift_jis', KR: 'euc-kr', TH: 'windows-874', VN: 'windows-1258'
};

function decodeMeta(buf, countrycode) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf);
  } catch (_) { /* not UTF-8 */ }
  const enc = COUNTRY_ENCODINGS[String(countrycode || '').toUpperCase()];
  if (enc) {
    try { return new TextDecoder(enc, { fatal: true }).decode(buf); } catch (_) { /* fall through */ }
  }
  return new TextDecoder('windows-1252').decode(buf);
}

function parseStreamTitle(text) {
  const clean = String(text || '').replace(/\0+$/g, '');
  const start = clean.indexOf("StreamTitle='");
  if (start < 0) return '';
  const rest = clean.slice(start + 13);
  const m = rest.match(/^([\s\S]*?)';(?=\s*(?:Stream\w+=|$))/);
  const title = (m ? m[1] : rest.replace(/';?\s*$/, '')).trim();
  // Some encoders send placeholders instead of a real title.
  if (/^(-|\s|unknown|n\/a)*$/i.test(title)) return '';
  return title;
}

/** One request; resolves { redirect } or { headers, supported, raw }. */
function readOnce(u, timeout) {
  return new Promise((resolve, reject) => {
    const isTls = u.protocol === 'https:';
    if (!isTls && u.protocol !== 'http:') return reject(new Error('unsupported protocol'));
    const port = u.port ? Number(u.port) : isTls ? 443 : 80;
    const host = u.hostname.replace(/^\[|\]$/g, '');
    const opts = { host, port };
    if (isTls && !net.isIP(host)) opts.servername = host;
    const sock = isTls ? tls.connect(opts) : net.connect(opts);

    let buf = Buffer.alloc(0);
    let headers = null;
    let metaint = 0;
    let pos = 0;
    let blocks = 0;
    let finished = false;

    const timer = setTimeout(() => finish(new Error('timeout')), timeout);
    function finish(err, value) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      sock.destroy();
      if (err) reject(err); else resolve(value);
    }

    sock.once(isTls ? 'secureConnect' : 'connect', () => {
      const path = (u.pathname || '/') + (u.search || '');
      sock.write(
        `GET ${path} HTTP/1.0\r\n` +
        `Host: ${u.host}\r\n` +
        `User-Agent: ${USER_AGENT}\r\n` +
        'Accept: */*\r\n' +
        'Icy-MetaData: 1\r\n' +
        'Connection: close\r\n\r\n'
      );
    });

    sock.on('data', (chunk) => {
      if (finished) return;
      buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;

      if (!headers) {
        let idx = buf.indexOf('\r\n\r\n');
        let sep = 4;
        if (idx === -1) { idx = buf.indexOf('\n\n'); sep = 2; }
        if (idx === -1) {
          if (buf.length > 32768) finish(new Error('header too large'));
          return;
        }
        const head = buf.subarray(0, idx).toString('latin1');
        buf = buf.subarray(idx + sep);
        const lines = head.split(/\r?\n/);
        const m = lines[0].match(/^(?:HTTP\/\d(?:\.\d)?|ICY)\s+(\d{3})/i);
        if (!m) return finish(new Error('bad response'));
        const status = Number(m[1]);
        headers = {};
        for (const line of lines.slice(1)) {
          const i = line.indexOf(':');
          if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
        }
        if (status >= 300 && status < 400 && headers.location) {
          return finish(null, { redirect: new URL(headers.location, u).toString() });
        }
        if (status !== 200) return finish(new Error(`HTTP ${status}`));
        metaint = parseInt(headers['icy-metaint'], 10) || 0;
        if (!metaint) return finish(null, { headers, supported: false });
      }

      // Walk metadata blocks: [metaint audio bytes][1 length byte][length*16 meta bytes]
      while (!finished) {
        if (buf.length < pos + metaint + 1) return;
        const len = buf[pos + metaint] * 16;
        if (len === 0) {
          pos += metaint + 1;
          blocks++;
          if (blocks >= MAX_BLOCKS) return finish(null, { headers, supported: true, raw: null });
          // drop consumed audio so the buffer stays small
          buf = buf.subarray(pos);
          pos = 0;
          continue;
        }
        if (buf.length < pos + metaint + 1 + len) return;
        const raw = buf.subarray(pos + metaint + 1, pos + metaint + 1 + len);
        return finish(null, { headers, supported: true, raw: Buffer.from(raw) });
      }
    });

    sock.on('error', (e) => finish(e));
    sock.on('end', () => finish(new Error('closed before metadata')));
    sock.on('close', () => finish(new Error('closed before metadata')));
  });
}

/** Fetch the current ICY title for a stream URL, following redirects. */
async function fetchIcy(url, { countrycode, timeout = TIMEOUT_MS, maxRedirects = 5 } = {}) {
  let current = new URL(url);
  for (let i = 0; i <= maxRedirects; i++) {
    const r = await readOnce(current, timeout);
    if (r.redirect) { current = new URL(r.redirect); continue; }
    const h = r.headers || {};
    return {
      supported: r.supported,
      title: r.raw ? parseStreamTitle(decodeMeta(r.raw, countrycode)) : '',
      icyName: h['icy-name'] || '',
      icyGenre: h['icy-genre'] || '',
      icyBitrate: parseInt(h['icy-br'], 10) || 0
    };
  }
  throw new Error('too many redirects');
}

/** Polls the playing station and reports title changes through `send`. */
class NowPlaying {
  constructor(send) {
    this.send = send;
    this.token = 0;
    this.timer = null;
  }

  start(station) {
    this.stop();
    if (!station || !station.url || /\.m3u8(\?|$)/i.test(station.url)) return;
    const token = ++this.token;
    let lastTitle = null;
    let failures = 0;

    const tick = async () => {
      if (token !== this.token) return;
      try {
        const info = await fetchIcy(station.url, { countrycode: station.countrycode });
        if (token !== this.token) return;
        failures = 0;
        if (!info.supported) {
          this.send({ uuid: station.uuid, supported: false, title: '' });
          return; // station has no in-stream titles; stop polling
        }
        if (info.title !== lastTitle) {
          lastTitle = info.title;
          this.send({ uuid: station.uuid, supported: true, title: info.title, icyName: info.icyName });
        }
        this.timer = setTimeout(tick, POLL_MS);
      } catch (_) {
        if (token !== this.token) return;
        failures++;
        if (failures >= 3) {
          this.send({ uuid: station.uuid, supported: false, title: '' });
          return;
        }
        this.timer = setTimeout(tick, RETRY_MS);
      }
    };
    // Give the audio connection a head start.
    this.timer = setTimeout(tick, 1500);
  }

  stop() {
    this.token++;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}

module.exports = { NowPlaying, fetchIcy, parseStreamTitle, decodeMeta };
