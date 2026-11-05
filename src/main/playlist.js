'use strict';
// Favourites import / export: JSON backup, M3U and PLS playlists.


function toM3U(stations) {
  const lines = ['#EXTM3U'];
  for (const s of stations) {
    lines.push(`#EXTINF:-1 tvg-logo="${(s.favicon || '').replace(/"/g, '')}" group-title="${s.countrycode || ''}",${s.name}`);
    lines.push(s.url);
  }
  return lines.join('\n') + '\n';
}

// Title is everything after the first comma that is not inside a quoted attribute.
function extinfTitle(line) {
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') quoted = !quoted;
    else if (ch === ',' && !quoted) return line.slice(i + 1).trim();
  }
  return '';
}

function parsePlaylist(text) {
  const t = text.replace(/^\uFEFF/, '').trim();
  if (t.startsWith('{') || (t.startsWith('[') && !/^\[playlist\]/i.test(t))) {
    const data = JSON.parse(t);
    const list = Array.isArray(data) ? data : data.favorites || data.stations || [];
    return list.filter((s) => s && s.url).map((s) => ({ ...s, name: s.name || s.url }));
  }
  const out = [];
  if (/^\[playlist\]/i.test(t)) { // PLS
    const files = {}; const titles = {};
    for (const line of t.split(/\r?\n/)) {
      const m = line.match(/^(File|Title)(\d+)=(.*)$/i);
      if (m) (m[1].toLowerCase() === 'file' ? files : titles)[m[2]] = m[3].trim();
    }
    for (const k of Object.keys(files)) out.push({ name: titles[k] || files[k], url: files[k] });
    return out;
  }
  let pending = null; // M3U
  for (const raw of t.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      const name = extinfTitle(line);
      const logo = (line.match(/tvg-logo="([^"]*)"/) || [])[1] || '';
      const cc = (line.match(/group-title="([A-Za-z]{2})"/) || [])[1] || '';
      pending = { name, favicon: logo, countrycode: cc.toUpperCase() };
    } else if (!line.startsWith('#') && /^https?:\/\//i.test(line)) {
      out.push({ ...(pending || {}), name: pending?.name || line, url: line });
      pending = null;
    }
  }
  return out;
}

module.exports = { toM3U, parsePlaylist };
