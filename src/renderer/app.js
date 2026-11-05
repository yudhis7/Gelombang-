'use strict';
/* Gelombang Radio — renderer */

const api = window.gelombang;
const I = window.ICONS;
const isMac = api.platform === 'darwin';
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const nf = new Intl.NumberFormat('en');
const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC_MAP[c]);

const PAGE = 100;
const MAX_RECENTS = 40;
const FALLBACK_TRENDING = ['AU', 'NZ', 'ID', 'MY', 'SG', 'TH', 'PH', 'VN', 'JP', 'KR', 'CN', 'HK', 'TW', 'IN', 'PK', 'BD'];
const HOME_CLOCKS = [
  ['Jakarta', 'Asia/Jakarta'], ['Singapore', 'Asia/Singapore'], ['Tokyo', 'Asia/Tokyo'],
  ['Sydney', 'Australia/Sydney'], ['Auckland', 'Pacific/Auckland'], ['Mumbai', 'Asia/Kolkata']
];
const SORTS = {
  popular: { label: 'Most popular', api: { order: 'clickcount', reverse: true }, cmp: (a, b) => b.clickcount - a.clickcount },
  trending: { label: 'Trending now', api: { order: 'clicktrend', reverse: true }, cmp: (a, b) => b.clicktrend - a.clicktrend },
  votes: { label: 'Most liked', api: { order: 'votes', reverse: true }, cmp: (a, b) => b.votes - a.votes },
  name: { label: 'Name A–Z', api: { order: 'name', reverse: false }, cmp: (a, b) => a.name.localeCompare(b.name) },
  bitrate: { label: 'Best quality', api: { order: 'bitrate', reverse: true }, cmp: (a, b) => b.bitrate - a.bitrate }
};

const state = {
  regions: null,
  countries: {},          // code -> { code, name, tz, group }
  counts: {},             // code -> station count
  countsState: 'loading', // loading | ok | error
  favorites: [],
  recents: [],
  custom: [],
  settings: {},
  view: { type: 'home' },
  prevView: null,
  list: [],               // stations in the current view (unfiltered)
  offset: 0,
  hasMore: false,
  loading: false,
  error: null,
  tag: null,
  sort: 'popular',
  loadToken: 0,
  index: new Map(),       // uuid -> station
  queue: [],              // uuids for next / previous
  current: null,
  status: 'idle',         // idle | connecting | playing | buffering | reconnecting | paused | error
  nowTitle: '',
  errorText: '',
  muted: false,
  sleep: null             // { endsAt, timer }
};

// ======================================================================
// Helpers
// ======================================================================

function countryName(code) { return state.countries[code]?.name || code || ''; }

function timeIn(tz, withZone = false) {
  try {
    const opts = { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: tz };
    if (withZone) opts.timeZoneName = 'short';
    return new Intl.DateTimeFormat('en-GB', opts).format(new Date());
  } catch (_) { return ''; }
}

function hueFor(s) {
  let h = 0;
  for (const ch of String(s)) h = (h * 31 + ch.codePointAt(0)) % 360;
  return h;
}

function initialsFor(name) {
  const words = String(name).replace(/[^\p{L}\p{N}\s]/gu, ' ').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '♪';
  const pick = words.length === 1 ? words[0].slice(0, 2) : words[0][0] + words[1][0];
  return pick.toUpperCase();
}

function artHtml(s, { eq = false } = {}) {
  const img = s.favicon && /^https?:\/\//i.test(s.favicon)
    ? `<img src="${esc(s.favicon)}" alt="" loading="lazy" referrerpolicy="no-referrer" draggable="false">`
    : '';
  const bars = eq ? `<div class="eq${state.status === 'playing' ? '' : ' paused'}"><i></i><i></i><i></i></div>` : '';
  return `<div class="art"><div class="initials" style="--h:${hueFor(s.name)}">${esc(initialsFor(s.name))}</div>${img}${bars}</div>`;
}

function techLabel(s) {
  return [s.codec, s.bitrate ? `${s.bitrate} kbps` : ''].filter(Boolean).join(' · ');
}

function remember(stations) {
  for (const s of stations) if (s && s.uuid) state.index.set(s.uuid, s);
}

function findStation(uuid) {
  return state.index.get(uuid)
    || state.favorites.find((s) => s.uuid === uuid)
    || state.recents.find((s) => s.uuid === uuid)
    || state.custom.find((s) => s.uuid === uuid)
    || (state.current?.uuid === uuid ? state.current : null);
}

const isFav = (uuid) => state.favorites.some((s) => s.uuid === uuid);
const isLibraryView = (v = state.view) => ['favorites', 'recents', 'custom'].includes(v.type);

function slimStation(s) {
  const { uuid, name, url, homepage, favicon, tags, country, countrycode, state: st, language, codec, bitrate, hls, votes, clickcount, clicktrend, custom } = s;
  return { uuid, name, url, homepage, favicon, tags: tags || [], country, countrycode, state: st, language, codec, bitrate, hls: !!hls, votes: votes || 0, clickcount: clickcount || 0, clicktrend: clicktrend || 0, custom: !!custom };
}

function saveSettings() { api.save('settings', state.settings); }

let toastTimer = 0;
function toast(msg, kind = '') {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.textContent = msg;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), 3800 + (toastTimer++ % 2));
}

function friendlyError(e) {
  const m = String(e?.message || e || '');
  const clean = m.replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
  if (/fetch failed|ENOTFOUND|ECONN|net::|unreachable|timed out/i.test(clean)) {
    return 'Could not reach the radio directory. Check your internet connection.';
  }
  return clean || 'Something went wrong.';
}

// ======================================================================
// Sidebar
// ======================================================================

function renderSidebar() {
  const v = state.view;
  const collapsed = new Set(state.settings.collapsedGroups || []);
  const active = (type, extra = {}) => v.type === type && Object.entries(extra).every(([k, val]) => v[k] === val);
  const countLabel = (code) => state.countsState === 'ok' ? nf.format(state.counts[code] || 0) : state.countsState === 'loading' ? '…' : '';

  const libItem = (type, icon, label, count) => `
    <button class="nav-item${active(type) ? ' active' : ''}" data-view="${type}">
      ${I[icon]}<span class="label">${label}</span>${count != null ? `<span class="count">${count || ''}</span>` : ''}
    </button>`;

  let html = `
    ${libItem('home', 'home', 'Home')}
    ${libItem('favorites', 'star', 'Favourites', state.favorites.length)}
    ${libItem('recents', 'clock', 'Recently played', null)}
    ${libItem('custom', 'radio', 'My stations', state.custom.length)}
    <div class="nav-label">Discover</div>
    ${libItem('trending', 'trend', 'Trending in APAC')}`;

  for (const g of state.regions.groups) {
    const total = g.countries.reduce((n, c) => n + (state.counts[c.code] || 0), 0);
    html += `
      <div class="group${collapsed.has(g.id) ? ' collapsed' : ''}" data-group="${g.id}">
        <div class="group-head">
          <button class="group-toggle" data-toggle="${g.id}" title="Show or hide countries">${I.chevron}</button>
          <button class="group-name${active('group', { id: g.id }) ? ' active' : ''}" data-view="group" data-id="${g.id}">
            <span>${esc(g.name)}</span><span class="count">${state.countsState === 'ok' ? nf.format(total) : ''}</span>
          </button>
        </div>
        <div class="group-body">
          ${g.countries.map((c) => `
            <button class="nav-item country${active('country', { code: c.code }) ? ' active' : ''}${state.countsState === 'ok' && !state.counts[c.code] ? ' dim' : ''}" data-view="country" data-code="${c.code}">
              <span class="cc">${c.code}</span><span class="label">${esc(c.name)}</span><span class="count">${countLabel(c.code)}</span>
            </button>`).join('')}
        </div>
      </div>`;
  }
  $('#sidebar').innerHTML = html;
}

$('#sidebar').addEventListener('click', (e) => {
  const tog = e.target.closest('[data-toggle]');
  if (tog) {
    const id = tog.dataset.toggle;
    const set = new Set(state.settings.collapsedGroups || []);
    set.has(id) ? set.delete(id) : set.add(id);
    state.settings.collapsedGroups = [...set];
    saveSettings();
    tog.closest('.group').classList.toggle('collapsed');
    return;
  }
  const b = e.target.closest('[data-view]');
  if (!b) return;
  const type = b.dataset.view;
  if (type === 'country') setView({ type, code: b.dataset.code });
  else if (type === 'group') setView({ type, id: b.dataset.id });
  else setView({ type });
});

// ======================================================================
// Views
// ======================================================================

function viewKey(v) { return JSON.stringify(v); }

async function setView(view, { keepSearch = false } = {}) {
  if (view.type !== 'search') {
    state.prevView = null;
    if (!keepSearch) clearSearchBox();
  }
  state.view = view;
  state.list = [];
  state.offset = 0;
  state.hasMore = false;
  state.error = null;
  state.tag = null;
  state.sort = isLibraryView(view) || view.type === 'search' ? 'default' : (state.settings.sort || 'popular');
  if (view.type !== 'search' && view.type !== 'home') {
    state.settings.lastView = view;
    saveSettings();
  }
  renderSidebar();
  $('#content').scrollTop = 0;
  await loadView();
}

async function loadView({ append = false } = {}) {
  const v = state.view;
  const token = ++state.loadToken;

  if (v.type === 'home') { renderHome(); return; }
  if (isLibraryView(v)) {
    state.list = v.type === 'favorites' ? state.favorites : v.type === 'recents' ? state.recents : state.custom;
    remember(state.list);
    renderList();
    return;
  }

  state.loading = true;
  state.error = null;
  if (!append) renderList();
  else updateLoadMore();

  try {
    let rows;
    if (v.type === 'country') {
      const sort = SORTS[state.sort] || SORTS.popular;
      rows = await api.stations(v.code, { offset: state.offset, limit: PAGE, tag: state.tag || undefined, ...sort.api });
      state.hasMore = rows.length === PAGE;
    } else if (v.type === 'group') {
      const g = state.regions.groups.find((x) => x.id === v.id);
      rows = await api.topAcross(g.countries.map((c) => c.code), g.countries.length > 4 ? 15 : 40);
      state.hasMore = false;
    } else if (v.type === 'trending') {
      rows = await api.topAcross(trendingCodes(), 12);
      state.hasMore = false;
    } else if (v.type === 'search') {
      rows = await api.search(v.q, v.code || null);
      state.hasMore = false;
    }
    if (token !== state.loadToken) return;
    remember(rows);
    const fresh = append ? rows.filter((r) => !state.list.some((s) => s.uuid === r.uuid)) : rows;
    state.list = append ? state.list.concat(fresh) : rows;
    state.offset += rows.length;
    state.loading = false;
    if (append) appendRows(fresh); else renderList();
  } catch (e) {
    if (token !== state.loadToken) return;
    state.loading = false;
    state.error = friendlyError(e);
    if (append) { updateLoadMore(); toast(state.error, 'err'); } else renderList();
  }
}

function trendingCodes() {
  if (state.countsState !== 'ok') return FALLBACK_TRENDING;
  return Object.entries(state.counts)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 16)
    .map(([c]) => c);
}

// ---------- list rendering ----------

function visibleStations() {
  let rows = state.list;
  const remoteTag = state.view.type === 'country';
  if (state.tag && !remoteTag) rows = rows.filter((s) => s.tags.includes(state.tag));
  if (state.sort !== 'default' && state.view.type !== 'country') {
    const sort = SORTS[state.sort];
    if (sort) rows = rows.slice().sort(sort.cmp);
  }
  return rows;
}

function topTags(rows, n = 12) {
  const counts = new Map();
  const skip = new Set(Object.values(state.countries).map((c) => c.name.toLowerCase()));
  for (const s of rows) for (const t of s.tags) {
    if (skip.has(t) || t.length < 3) continue;
    counts.set(t, (counts.get(t) || 0) + 1);
  }
  return [...counts.entries()].filter(([, c]) => c > 1).sort((a, b) => b[1] - a[1]).slice(0, n).map(([t]) => t);
}

function headerHtml() {
  const v = state.view;
  let title = '';
  const meta = [];
  if (v.type === 'country') {
    const c = state.countries[v.code];
    title = c?.name || v.code;
    if (state.countsState === 'ok') meta.push(`${nf.format(state.counts[v.code] || 0)} stations`);
    if (c?.tz) meta.push(`Local time ${timeIn(c.tz, true)}`);
  } else if (v.type === 'group') {
    const g = state.regions.groups.find((x) => x.id === v.id);
    title = g.name;
    meta.push(`Most popular across ${g.countries.length} ${g.countries.length === 1 ? 'country' : 'countries'}`);
  } else if (v.type === 'trending') {
    title = 'Trending in APAC';
    meta.push('Most played stations across the region right now');
  } else if (v.type === 'search') {
    title = `“${v.q}”`;
    meta.push(v.code ? `Search results in ${countryName(v.code)}` : 'Search results across Asia-Pacific, Australia and New Zealand');
  } else if (v.type === 'favorites') {
    title = 'Favourites';
    meta.push(state.favorites.length ? `${state.favorites.length} saved` : 'Nothing saved yet');
  } else if (v.type === 'recents') {
    title = 'Recently played';
  } else if (v.type === 'custom') {
    title = 'My stations';
    meta.push('Streams you added by URL');
  }

  const sortOpts = [];
  if (isLibraryView(v)) sortOpts.push(['default', v.type === 'recents' ? 'Most recent' : 'My order']);
  if (v.type === 'search') sortOpts.push(['default', 'Best match']);
  for (const [k, s] of Object.entries(SORTS)) sortOpts.push([k, s.label]);

  const groupChips = v.type === 'group'
    ? `<div class="chips">${state.regions.groups.find((x) => x.id === v.id).countries.map((c) =>
        `<button class="chip" data-goto="${c.code}"><span class="cc">${c.code}</span>${esc(c.name)}</button>`).join('')}</div>`
    : '';

  const extra = v.type === 'custom'
    ? `<button class="btn" data-act="add-custom">${I.plus}Add station</button>`
    : v.type === 'recents' && state.recents.length
      ? `<button class="btn" data-act="clear-recents">Clear</button>`
      : '';

  return `
    <div class="view-head">
      <div class="grow">
        <h1>${esc(title)}</h1>
        <div class="meta">${meta.map(esc).join('<span class="dot">•</span>')}</div>
      </div>
      ${extra}
      <select class="select" id="sort" title="Sort stations">
        ${sortOpts.map(([k, l]) => `<option value="${k}"${state.sort === k ? ' selected' : ''}>${l}</option>`).join('')}
      </select>
    </div>
    ${groupChips}`;
}

function rowHtml(s) {
  const cur = state.current?.uuid === s.uuid;
  const fav = isFav(s.uuid);
  const place = [s.state, s.countrycode && state.view.type !== 'country' ? countryName(s.countrycode) : ''].filter(Boolean).join(', ');
  const sub = [place, s.tags.slice(0, 3).join(', ')].filter(Boolean).join(' · ');
  return `
    <div class="row${cur ? ' current' : ''}" data-uuid="${esc(s.uuid)}" tabindex="0">
      ${artHtml(s, { eq: cur && ['playing', 'buffering', 'paused'].includes(state.status) })}
      <div class="info">
        <div class="name">${esc(s.name)}</div>
        <div class="sub">${s.countrycode ? `<span class="cc">${esc(s.countrycode)}</span>` : ''}<span class="txt">${esc(sub || (s.custom ? 'Custom stream' : ''))}</span></div>
      </div>
      <div class="tech">${s.hls ? '<span class="pill">HLS</span>' : ''}${esc(techLabel(s))}</div>
      <button class="ibtn fav${fav ? ' on' : ''}" data-act="fav" title="${fav ? 'Remove from favourites' : 'Add to favourites'}">${fav ? I.starFill : I.star}</button>
      <button class="ibtn" data-act="more" title="More">${I.more}</button>
    </div>`;
}

function stateHtml(icon, title, text, action) {
  return `<div class="state"><div class="big">${I[icon]}</div><h3>${esc(title)}</h3><p>${esc(text)}</p>${action || ''}</div>`;
}

function renderList() {
  const v = state.view;
  const content = $('#content');
  let body = '';
  if (state.loading && !state.list.length) {
    body = '<div class="list">' + '<div class="skeleton"></div>'.repeat(9) + '</div>';
  } else if (state.error && !state.list.length) {
    body = stateHtml('globe', 'Directory unavailable', state.error, `<button class="btn primary" data-act="retry">${I.retry}Try again</button>`);
  } else {
    const rows = visibleStations();
    const tags = topTags(state.list);
    const chips = tags.length > 2 && !isLibraryView(v)
      ? `<div class="chips"><button class="chip${!state.tag ? ' on' : ''}" data-tag="">All</button>${tags.map((t) => `<button class="chip${state.tag === t ? ' on' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}</div>`
      : state.tag ? `<div class="chips"><button class="chip" data-tag="">All</button><button class="chip on" data-tag="${esc(state.tag)}">${esc(state.tag)}</button></div>` : '';
    if (!rows.length) {
      if (v.type === 'favorites') body = stateHtml('star', 'No favourites yet', 'Click the star on any station to keep it here. Favourites also appear in the tray menu.');
      else if (v.type === 'recents') body = stateHtml('clock', 'Nothing played yet', 'Stations you listen to will show up here.');
      else if (v.type === 'custom') body = stateHtml('radio', 'Add your own streams', 'Have a stream link that is not in the directory? Add it here and it plays like any other station.', `<button class="btn primary" data-act="add-custom">${I.plus}Add station</button>`);
      else body = chips + stateHtml('search', 'No stations found', state.tag ? 'Try another genre or clear the filter.' : 'Try a different name or genre.');
    } else {
      body = chips + `<div class="list" id="list">${rows.map(rowHtml).join('')}</div>` + loadMoreHtml();
    }
  }
  content.innerHTML = headerHtml() + body;
  observeSentinel();
}

function loadMoreHtml() {
  if (state.view.type !== 'country' || !state.hasMore) return '';
  return `<div class="load-more" id="load-more">${state.loading ? '<span class="state" style="padding:0">Loading…</span>' : `<button class="btn" data-act="more-rows">Load more</button>`}</div><div class="sentinel" id="sentinel"></div>`;
}

function updateLoadMore() {
  const lm = $('#load-more');
  if (!lm) return;
  lm.outerHTML = loadMoreHtml().replace('<div class="sentinel" id="sentinel"></div>', '');
}

function appendRows(rows) {
  const list = $('#list');
  if (!list) { renderList(); return; }
  list.insertAdjacentHTML('beforeend', rows.map(rowHtml).join(''));
  $('#load-more')?.remove();
  $('#sentinel')?.remove();
  list.insertAdjacentHTML('afterend', loadMoreHtml());
  observeSentinel();
}

let sentinelObserver = null;
function observeSentinel() {
  sentinelObserver?.disconnect();
  const s = $('#sentinel');
  if (!s) return;
  sentinelObserver = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting) && state.hasMore && !state.loading) loadView({ append: true });
  }, { root: $('#content'), rootMargin: '400px' });
  sentinelObserver.observe(s);
}

// ---------- home ----------

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'Late night listening' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

function clocksHtml() {
  const mine = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return HOME_CLOCKS.map(([city, tz]) =>
    `<div class="clock${timeIn(tz) === timeIn(mine) ? ' mine' : ''}"><b>${timeIn(tz)}</b><span>${city}</span></div>`).join('');
}

function cardHtml(s) {
  const cur = state.current?.uuid === s.uuid;
  return `
    <div class="card${cur ? ' current' : ''}" data-uuid="${esc(s.uuid)}" tabindex="0" title="${esc(s.name)}">
      ${artHtml(s, { eq: cur && ['playing', 'buffering', 'paused'].includes(state.status) })}
      <div class="name">${esc(s.name)}</div>
      <div class="sub">${s.countrycode ? `<span class="cc">${esc(s.countrycode)}</span>` : ''}<span class="txt">${esc(s.state || countryName(s.countrycode) || 'Custom')}</span></div>
    </div>`;
}

function renderHome() {
  remember(state.favorites);
  remember(state.recents);
  const total = Object.values(state.counts).reduce((a, b) => a + b, 0);
  const favs = state.favorites.slice(0, 12);
  const recents = state.recents.slice(0, 6);
  $('#content').innerHTML = `
    <section class="hero">
      <h1>${greeting()}</h1>
      <p>${state.countsState === 'ok' ? `${nf.format(total)} live stations` : 'Live stations'} across Asia-Pacific, Australia and New Zealand, in one place.</p>
      <div class="clocks" id="clocks">${clocksHtml()}</div>
    </section>

    <section class="section">
      <div class="section-head"><h2>Favourites</h2>${state.favorites.length > 12 ? '<button class="link" data-goto-view="favorites">See all</button>' : ''}</div>
      ${favs.length ? `<div class="cards" data-queue="favorites">${favs.map(cardHtml).join('')}</div>`
        : '<div class="empty-card">Star a station and it lands here, ready to play in one click.</div>'}
    </section>

    ${recents.length ? `
    <section class="section">
      <div class="section-head"><h2>Recently played</h2><button class="link" data-goto-view="recents">See all</button></div>
      <div class="cards" data-queue="recents">${recents.map(cardHtml).join('')}</div>
    </section>` : ''}

    <section class="section">
      <div class="section-head"><h2>Explore the region</h2><button class="link" data-goto-view="trending">Trending in APAC</button></div>
      <div class="regions">
        ${state.regions.groups.map((g) => {
          const n = g.countries.reduce((a, c) => a + (state.counts[c.code] || 0), 0);
          return `<button class="region-card" data-goto-group="${g.id}">
            <h3>${esc(g.name)}</h3>
            <div class="muted">${state.countsState === 'ok' ? `${nf.format(n)} stations · ` : ''}${g.countries.length} ${g.countries.length === 1 ? 'country' : 'countries'}</div>
            <div class="codes">${g.countries.map((c) => `<span class="cc">${c.code}</span>`).join('')}</div>
          </button>`;
        }).join('')}
      </div>
    </section>`;
}

// ---------- content interactions ----------

$('#content').addEventListener('click', (e) => {
  const t = e.target;
  const act = t.closest('[data-act]')?.dataset.act;
  const item = t.closest('[data-uuid]');

  if (act === 'retry') { loadView(); return; }
  if (act === 'more-rows') { loadView({ append: true }); return; }
  if (act === 'add-custom') { openStationEditor(); return; }
  if (act === 'clear-recents') {
    state.recents = []; api.save('recents', []);
    if (state.view.type === 'recents') loadView();
    return;
  }

  const goto = t.closest('[data-goto]');
  if (goto) { setView({ type: 'country', code: goto.dataset.goto }); return; }
  const gotoGroup = t.closest('[data-goto-group]');
  if (gotoGroup) { setView({ type: 'group', id: gotoGroup.dataset.gotoGroup }); return; }
  const gotoView = t.closest('[data-goto-view]');
  if (gotoView) { setView({ type: gotoView.dataset.gotoView }); return; }

  const chip = t.closest('[data-tag]');
  if (chip) {
    state.tag = chip.dataset.tag || null;
    if (state.view.type === 'country') { state.offset = 0; state.list = []; loadView(); }
    else renderList();
    return;
  }

  if (!item) return;
  const st = findStation(item.dataset.uuid);
  if (!st) return;
  if (act === 'fav') { toggleFavorite(st); return; }
  if (act === 'more') { openStationMenu(t.closest('[data-act]'), st); return; }

  if (state.current?.uuid === st.uuid && state.status !== 'error') { togglePlay(); return; }
  const queueSrc = item.closest('[data-queue]')?.dataset.queue;
  const queue = queueSrc === 'favorites' ? state.favorites : queueSrc === 'recents' ? state.recents : visibleStations();
  playStation(st, { queue });
});

$('#content').addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  const item = e.target.closest('[data-uuid]');
  if (item && e.target === item) { e.preventDefault(); item.click(); }
});

$('#content').addEventListener('change', (e) => {
  if (e.target.id !== 'sort') return;
  state.sort = e.target.value;
  if (!isLibraryView() && state.view.type !== 'search' && state.sort !== 'default') {
    state.settings.sort = state.sort;
    saveSettings();
  }
  if (state.view.type === 'country') { state.offset = 0; state.list = []; loadView(); }
  else renderList();
});

// Broken favicons fall back to initials; 1×1 tracking pixels too.
document.addEventListener('error', (e) => {
  if (e.target.tagName === 'IMG' && e.target.closest('.art')) e.target.remove();
}, true);
document.addEventListener('load', (e) => {
  const img = e.target;
  if (img.tagName === 'IMG' && img.closest('.art') && img.naturalWidth < 8) img.remove();
}, true);

function syncRows() {
  for (const el of $$('[data-uuid]', $('#content'))) {
    const uuid = el.dataset.uuid;
    const cur = state.current?.uuid === uuid;
    el.classList.toggle('current', cur);
    const art = $('.art', el);
    const eq = art && $('.eq', art);
    const wantEq = cur && ['playing', 'buffering', 'paused'].includes(state.status);
    if (wantEq && !eq) art.insertAdjacentHTML('beforeend', '<div class="eq"><i></i><i></i><i></i></div>');
    if (!wantEq && eq) eq.remove();
    if (wantEq) $('.eq', art).classList.toggle('paused', state.status !== 'playing');
    const fav = $('[data-act="fav"]', el);
    if (fav) {
      const on = isFav(uuid);
      fav.classList.toggle('on', on);
      fav.innerHTML = on ? I.starFill : I.star;
      fav.title = on ? 'Remove from favourites' : 'Add to favourites';
    }
  }
}

// ======================================================================
// Library: favourites, recents, custom stations
// ======================================================================

function toggleFavorite(st) {
  if (!st) return;
  if (isFav(st.uuid)) {
    state.favorites = state.favorites.filter((s) => s.uuid !== st.uuid);
    toast(`Removed ${st.name} from favourites`);
  } else {
    state.favorites = [...state.favorites, slimStation(st)];
    toast(`Added ${st.name} to favourites`, 'ok');
  }
  api.save('favorites', state.favorites);
  renderSidebar();
  if (state.view.type === 'favorites') loadView();
  else if (state.view.type === 'home') renderHome();
  else syncRows();
  renderPlayer();
}

function addRecent(st) {
  state.recents = [slimStation(st), ...state.recents.filter((s) => s.uuid !== st.uuid)].slice(0, MAX_RECENTS);
  api.save('recents', state.recents);
}

function openStationMenu(anchor, st) {
  const items = [];
  items.push({ label: state.current?.uuid === st.uuid && isActiveStatus() ? 'Pause' : 'Play', icon: 'play', run: () => (state.current?.uuid === st.uuid ? togglePlay() : playStation(st, { queue: visibleStations() })) });
  items.push({ label: isFav(st.uuid) ? 'Remove from favourites' : 'Add to favourites', icon: 'star', run: () => toggleFavorite(st) });
  if (st.homepage) items.push({ label: 'Open station website', icon: 'external', run: () => api.openExternal(st.homepage) });
  items.push({ label: 'Copy stream link', icon: 'copy', run: () => copyText(st.url, 'Stream link copied') });
  if (st.custom) {
    items.push({ sep: true });
    items.push({ label: 'Edit station', icon: 'edit', run: () => openStationEditor(st) });
    items.push({ label: 'Delete station', icon: 'trash', danger: true, run: () => deleteCustom(st) });
  }
  if (state.view.type === 'recents') {
    items.push({ sep: true });
    items.push({ label: 'Remove from history', icon: 'trash', run: () => {
      state.recents = state.recents.filter((s) => s.uuid !== st.uuid);
      api.save('recents', state.recents);
      loadView();
    } });
  }
  showPopover(anchor, items);
}

function deleteCustom(st) {
  state.custom = state.custom.filter((s) => s.uuid !== st.uuid);
  state.favorites = state.favorites.filter((s) => s.uuid !== st.uuid);
  api.save('custom', state.custom);
  api.save('favorites', state.favorites);
  renderSidebar();
  loadView();
  toast(`Deleted ${st.name}`);
}

async function copyText(text, msg) {
  try { await navigator.clipboard.writeText(text); toast(msg, 'ok'); }
  catch (_) { toast('Could not copy to the clipboard', 'err'); }
}

/** Refresh saved directory stations: stream URLs and logos change over time. */
async function refreshFavorites() {
  const ids = state.favorites.filter((s) => !s.custom && s.uuid).map((s) => s.uuid);
  if (!ids.length) return;
  try {
    const fresh = await api.byUuids(ids);
    const byId = new Map(fresh.map((s) => [s.uuid, s]));
    let changed = false;
    state.favorites = state.favorites.map((s) => {
      const f = byId.get(s.uuid);
      if (!f || !f.url) return s;
      if (f.url !== s.url || f.favicon !== s.favicon || f.bitrate !== s.bitrate) changed = true;
      return slimStation({ ...s, ...f });
    });
    if (changed) {
      api.save('favorites', state.favorites);
      remember(state.favorites);
      if (state.view.type === 'home') renderHome();
    }
  } catch (_) { /* offline: keep saved copies */ }
}

// ======================================================================
// Popover menus
// ======================================================================

function closePopover() { $('#popover-root').innerHTML = ''; }

function showPopover(anchor, items, { align = 'end', above = false } = {}) {
  closePopover();
  const pop = document.createElement('div');
  pop.className = 'popover';
  pop.innerHTML = items.map((it, i) => it.sep ? '<hr>' : it.head
    ? `<div class="head">${esc(it.head)}</div>`
    : `<button data-i="${i}" class="${it.on ? 'on' : ''}${it.danger ? ' danger' : ''}">${it.icon ? I[it.icon] : ''}<span>${esc(it.label)}</span></button>`).join('');
  $('#popover-root').appendChild(pop);
  const r = anchor.getBoundingClientRect();
  const pr = pop.getBoundingClientRect();
  let left = align === 'end' ? r.right - pr.width : r.left;
  let top = above ? r.top - pr.height - 6 : r.bottom + 6;
  if (top + pr.height > window.innerHeight - 8) top = r.top - pr.height - 6;
  if (top < 8) top = 8;
  left = Math.max(8, Math.min(left, window.innerWidth - pr.width - 8));
  pop.style.left = `${left}px`;
  pop.style.top = `${top}px`;
  pop.addEventListener('click', (e) => {
    const b = e.target.closest('[data-i]');
    if (!b) return;
    closePopover();
    items[Number(b.dataset.i)].run?.();
  });
  setTimeout(() => {
    const off = (e) => {
      if (!pop.contains(e.target)) { closePopover(); document.removeEventListener('mousedown', off, true); }
    };
    document.addEventListener('mousedown', off, true);
  });
}

// ======================================================================
// Player engine
// ======================================================================

const audio = new Audio();
audio.preload = 'none';
let hls = null;
const engine = { session: 0, retries: 0, everPlayed: false, stallTimer: 0, retryTimer: 0 };

const isActiveStatus = () => ['connecting', 'playing', 'buffering', 'reconnecting'].includes(state.status);
const volCurve = (v) => Math.pow(Math.max(0, Math.min(100, v)) / 100, 2);

function applyVolume() {
  let v = volCurve(state.settings.volume ?? 70);
  if (state.sleep) {
    const left = state.sleep.endsAt - Date.now();
    if (left < 30000) v *= Math.max(0, left / 30000);
  }
  audio.volume = v;
  audio.muted = state.muted;
}

function teardown() {
  clearTimeout(engine.stallTimer);
  clearTimeout(engine.retryTimer);
  if (hls) { try { hls.destroy(); } catch (_) { /* ignore */ } hls = null; }
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
}

function startStream(session) {
  const st = state.current;
  const url = st.url;
  applyVolume();
  const isHls = st.hls || /\.m3u8(\?|$)/i.test(url);
  const nativeHls = audio.canPlayType('application/vnd.apple.mpegurl') !== '';
  if (isHls && !nativeHls && window.Hls && window.Hls.isSupported()) {
    hls = new window.Hls({ enableWorker: true, lowLatencyMode: false, manifestLoadingMaxRetry: 2, levelLoadingMaxRetry: 2, fragLoadingMaxRetry: 4 });
    hls.on(window.Hls.Events.ERROR, (_e, data) => {
      if (session !== engine.session || !data.fatal) return;
      if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR) { hls.recoverMediaError(); return; }
      handleFailure(session);
    });
    hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
      if (session === engine.session) audio.play().catch((e) => onPlayRejected(session, e));
    });
    hls.loadSource(url);
    hls.attachMedia(audio);
  } else {
    audio.src = url;
    audio.play().catch((e) => onPlayRejected(session, e));
  }
  armWatchdog(session, 20000);
}

function armWatchdog(session, ms) {
  clearTimeout(engine.stallTimer);
  engine.stallTimer = setTimeout(() => {
    if (session === engine.session && state.status !== 'playing' && state.status !== 'paused') handleFailure(session);
  }, ms);
}

function onPlayRejected(session, e) {
  if (session !== engine.session) return;
  if (e?.name === 'AbortError') return;
  if (e?.name === 'NotAllowedError') { setStatus('paused'); return; }
  handleFailure(session);
}

function handleFailure(session) {
  if (session !== engine.session || !state.current) return;
  clearTimeout(engine.stallTimer);
  const max = engine.everPlayed ? 6 : 1;
  if (engine.retries < max) {
    engine.retries++;
    const delay = engine.everPlayed ? Math.min(2000 * 2 ** (engine.retries - 1), 30000) : 1200;
    setStatus(engine.everPlayed ? 'reconnecting' : 'connecting');
    engine.retryTimer = setTimeout(() => {
      if (session !== engine.session) return;
      teardown();
      startStream(session);
    }, delay);
    return;
  }
  teardown();
  api.nowPlayingStop();
  state.errorText = engine.everPlayed ? 'Lost the connection to this stream' : 'Stream unavailable right now';
  setStatus('error');
  toast(engine.everPlayed
    ? `${state.current.name} dropped out and could not reconnect. Press play to try again.`
    : `${state.current.name} would not play. It may be offline, or only available inside its own country.`, 'err');
}

audio.addEventListener('playing', () => {
  if (!state.current) return;
  engine.everPlayed = true;
  engine.retries = 0;
  clearTimeout(engine.stallTimer);
  setStatus('playing');
});
audio.addEventListener('waiting', () => {
  if (!state.current || !isActiveStatus()) return;
  setStatus(engine.everPlayed ? 'buffering' : 'connecting');
  armWatchdog(engine.session, 15000);
});
audio.addEventListener('stalled', () => {
  if (state.status === 'playing' || state.status === 'buffering') armWatchdog(engine.session, 15000);
});
audio.addEventListener('error', () => {
  if (!audio.error || !state.current || !isActiveStatus()) return;
  handleFailure(engine.session);
});
audio.addEventListener('ended', () => {
  if (state.current && isActiveStatus()) handleFailure(engine.session);
});

function playStation(st, { queue, resume = false } = {}) {
  if (!st?.url) { toast('This station has no stream link', 'err'); return; }
  if (queue) state.queue = queue.map((s) => s.uuid);
  teardown();
  const session = ++engine.session;
  engine.retries = 0;
  engine.everPlayed = false;
  const switching = state.current?.uuid !== st.uuid;
  state.current = slimStation(st);
  remember([state.current]);
  if (switching) state.nowTitle = '';
  state.errorText = '';
  setStatus('connecting');
  startStream(session);
  if (!state.current.hls) api.nowPlayingStart(state.current);
  addRecent(st);
  if (!resume && !st.custom) api.click(st.uuid);
  state.settings.lastStation = slimStation(st);
  saveSettings();
}

function pausePlayback() {
  if (!state.current) return;
  engine.session++;
  teardown();
  api.nowPlayingStop();
  setStatus('paused');
}

function stopPlayback() {
  engine.session++;
  teardown();
  api.nowPlayingStop();
  state.current = null;
  state.nowTitle = '';
  setStatus('idle');
}

function togglePlay() {
  if (!state.current) {
    const first = state.settings.lastStation || state.favorites[0];
    if (first) playStation(first, { queue: state.favorites, resume: true });
    return;
  }
  if (isActiveStatus()) pausePlayback();
  else playStation(state.current, { resume: true });
}

function step(dir) {
  const queue = state.queue.length ? state.queue : state.favorites.map((s) => s.uuid);
  if (!queue.length) return;
  const idx = state.current ? queue.indexOf(state.current.uuid) : -1;
  const next = queue[(idx + dir + queue.length) % queue.length];
  const st = findStation(next);
  if (st) playStation(st);
}

function setVolume(v) {
  state.settings.volume = Math.max(0, Math.min(100, Math.round(v)));
  if (state.muted && v > 0) state.muted = false;
  applyVolume();
  updateVolumeUi();
  clearTimeout(setVolume.t);
  setVolume.t = setTimeout(saveSettings, 400);
}

function setStatus(s) {
  state.status = s;
  renderPlayer();
  syncRows();
  updateMediaSession();
  api.playerState({
    playing: isActiveStatus(),
    station: state.current ? { uuid: state.current.uuid, name: state.current.name } : null,
    title: state.nowTitle
  });
}

// ---------- now playing (ICY titles from main process) ----------

api.onNowPlaying((d) => {
  if (!state.current || d.uuid !== state.current.uuid) return;
  const title = d.supported ? (d.title || '') : '';
  if (title === state.nowTitle) return;
  state.nowTitle = title;
  renderPlayer();
  updateMediaSession();
  api.playerState({ playing: isActiveStatus(), station: { uuid: state.current.uuid, name: state.current.name }, title });
});

// ---------- system media controls ----------

function updateMediaSession() {
  if (!('mediaSession' in navigator)) return;
  const st = state.current;
  if (!st) { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; return; }
  const artwork = st.favicon && /^https?:/i.test(st.favicon) ? [{ src: st.favicon, sizes: '256x256' }] : [];
  navigator.mediaSession.metadata = new MediaMetadata({
    title: state.nowTitle || st.name,
    artist: state.nowTitle ? st.name : (countryName(st.countrycode) || 'Live radio'),
    album: 'Gelombang Radio',
    artwork
  });
  navigator.mediaSession.playbackState = isActiveStatus() ? 'playing' : 'paused';
}

if ('mediaSession' in navigator) {
  const ms = navigator.mediaSession;
  ms.setActionHandler('play', () => { if (!isActiveStatus()) togglePlay(); });
  ms.setActionHandler('pause', () => { if (isActiveStatus()) pausePlayback(); });
  ms.setActionHandler('stop', stopPlayback);
  ms.setActionHandler('nexttrack', () => step(1));
  ms.setActionHandler('previoustrack', () => step(-1));
}

// ---------- sleep timer ----------

function setSleep(minutes) {
  if (state.sleep) clearInterval(state.sleep.timer);
  state.sleep = null;
  if (minutes) {
    state.sleep = { endsAt: Date.now() + minutes * 60000, timer: setInterval(sleepTick, 1000) };
    toast(`Sleep timer set for ${minutes} minutes`, 'ok');
  }
  applyVolume();
  renderPlayer();
}

function sleepTick() {
  if (!state.sleep) return;
  const left = state.sleep.endsAt - Date.now();
  if (left <= 0) {
    clearInterval(state.sleep.timer);
    state.sleep = null;
    pausePlayback();
    applyVolume();
    toast('Sleep timer ended. Good night.');
    renderPlayer();
    return;
  }
  if (left < 30000) applyVolume();
  const lbl = $('#sleep-label');
  if (lbl) lbl.textContent = fmtLeft(left);
}

function fmtLeft(ms) {
  const s = Math.ceil(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
}

// ---------- player bar ----------

function statusHtml() {
  const st = state.current;
  if (!st) return 'Pick a station to start listening';
  switch (state.status) {
    case 'connecting': return 'Connecting…';
    case 'buffering': return 'Buffering…';
    case 'reconnecting': return 'Reconnecting…';
    case 'paused': return 'Paused';
    case 'error': return esc(state.errorText);
    case 'playing': {
      const detail = state.nowTitle
        ? `<span class="title" title="${esc(state.nowTitle)}">${esc(state.nowTitle)}</span><button class="copy" data-pact="copy-title" title="Copy song title">${I.copy}</button>`
        : `<span class="title">${esc([countryName(st.countrycode), techLabel(st)].filter(Boolean).join(' · ') || 'Live radio')}</span>`;
      return `<span class="live">LIVE</span>${detail}`;
    }
    default: return esc([countryName(st.countrycode), techLabel(st)].filter(Boolean).join(' · ') || 'Ready');
  }
}

function renderPlayer() {
  const st = state.current;
  const active = isActiveStatus();
  const busy = ['connecting', 'buffering', 'reconnecting'].includes(state.status);
  const fav = st && isFav(st.uuid);
  const hasQueue = (state.queue.length || state.favorites.length) > 1;
  $('#player').innerHTML = `
    <div class="p-left">
      ${st ? artHtml(st) : '<div class="art"><div class="initials" style="--h:200">♪</div></div>'}
      <div class="p-meta">
        <div class="p-name">${st ? esc(st.name) : 'Gelombang Radio'}</div>
        <div class="p-status${state.status === 'error' ? ' err' : ''}"${state.status === 'error' ? ' title="Offline, or only available inside its own country. Press play to retry."' : ''}>${statusHtml()}</div>
      </div>
      ${st ? `<button class="ctl fav${fav ? ' on' : ''}" data-pact="fav" title="${fav ? 'Remove from favourites' : 'Add to favourites'}">${fav ? I.starFill : I.star}</button>` : ''}
    </div>
    <div class="p-center">
      <button class="ctl" data-pact="prev" title="Previous station" ${hasQueue ? '' : 'disabled'}>${I.prev}</button>
      <button class="ctl main${busy ? ' busy' : ''}" data-pact="toggle" title="${active ? 'Pause' : 'Play'} (Space)" ${st || state.settings.lastStation || state.favorites.length ? '' : 'disabled'}>${active && !busy ? I.pause : busy ? I.wave : I.play}</button>
      <button class="ctl" data-pact="stop" title="Stop" ${st ? '' : 'disabled'}>${I.stop}</button>
      <button class="ctl" data-pact="next" title="Next station" ${hasQueue ? '' : 'disabled'}>${I.next}</button>
    </div>
    <div class="p-right">
      ${state.sleep ? `<span class="sleep-label" id="sleep-label">${fmtLeft(state.sleep.endsAt - Date.now())}</span>` : ''}
      <button class="ctl${state.sleep ? ' on' : ''}" data-pact="sleep" title="Sleep timer">${I.moon}</button>
      ${st?.homepage ? `<button class="ctl" data-pact="home" title="Open station website">${I.external}</button>` : ''}
      <button class="ctl" data-pact="mute" id="mute-btn" title="Mute">${volumeIcon()}</button>
      <input class="volume" id="volume" type="range" min="0" max="100" step="1" value="${state.settings.volume ?? 70}" title="Volume">
    </div>`;
  updateVolumeUi();
}

function volumeIcon() {
  const v = state.settings.volume ?? 70;
  return state.muted || v === 0 ? I.mute : v < 45 ? I.volumeLow : I.volume;
}

function updateVolumeUi() {
  const slider = $('#volume');
  if (!slider) return;
  const v = state.settings.volume ?? 70;
  if (Number(slider.value) !== v) slider.value = v;
  slider.style.setProperty('--fill', `${state.muted ? 0 : v}%`);
  const mb = $('#mute-btn');
  if (mb) { mb.innerHTML = volumeIcon(); mb.title = state.muted ? 'Unmute' : 'Mute'; }
}

$('#player').addEventListener('input', (e) => {
  if (e.target.id === 'volume') setVolume(Number(e.target.value));
});
$('#player').addEventListener('wheel', (e) => {
  if (!e.target.closest('.p-right')) return;
  e.preventDefault();
  setVolume((state.settings.volume ?? 70) + (e.deltaY < 0 ? 5 : -5));
}, { passive: false });

$('#player').addEventListener('click', (e) => {
  const b = e.target.closest('[data-pact]');
  if (!b || b.disabled) return;
  const a = b.dataset.pact;
  if (a === 'toggle') togglePlay();
  else if (a === 'stop') stopPlayback();
  else if (a === 'next') step(1);
  else if (a === 'prev') step(-1);
  else if (a === 'fav') toggleFavorite(state.current);
  else if (a === 'home') api.openExternal(state.current.homepage);
  else if (a === 'copy-title') copyText(state.nowTitle, 'Song title copied');
  else if (a === 'mute') { state.muted = !state.muted; applyVolume(); updateVolumeUi(); }
  else if (a === 'sleep') {
    const opts = [15, 30, 45, 60, 90, 120];
    showPopover(b, [
      { head: 'Stop playing after' },
      ...opts.map((m) => ({ label: m < 60 ? `${m} minutes` : m === 60 ? '1 hour' : `${m / 60} hours`, run: () => setSleep(m) })),
      ...(state.sleep ? [{ sep: true }, { label: 'Turn off sleep timer', danger: true, run: () => { setSleep(0); toast('Sleep timer off'); } }] : [])
    ], { above: true });
  }
});

// ======================================================================
// Search
// ======================================================================

const searchInput = $('#search');
const scopeBtn = $('#search-scope');
let searchTimer = 0;

function clearSearchBox() {
  searchInput.value = '';
  scopeBtn.hidden = true;
  delete scopeBtn.dataset.code;
}

function currentScope() { return scopeBtn.hidden ? null : scopeBtn.dataset.code || null; }

function runSearch() {
  const q = searchInput.value.trim();
  if (q.length < 2) {
    if (!q && state.view.type === 'search') leaveSearch();
    return;
  }
  if (state.view.type !== 'search') state.prevView = state.view;
  const prev = state.prevView;
  setView({ type: 'search', q, code: currentScope() });
  state.prevView = prev;
}

function leaveSearch() {
  const back = state.prevView || { type: 'home' };
  clearSearchBox();
  state.prevView = null;
  setView(back);
}

searchInput.addEventListener('focus', () => {
  if (state.view.type === 'country' && scopeBtn.hidden && !searchInput.value) {
    scopeBtn.dataset.code = state.view.code;
    scopeBtn.innerHTML = `in ${esc(countryName(state.view.code))} ${I.close}`;
    scopeBtn.title = 'Search all of Asia-Pacific instead';
    scopeBtn.hidden = false;
  }
});
searchInput.addEventListener('blur', () => {
  if (!searchInput.value && state.view.type !== 'search') clearSearchBox();
});
searchInput.addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(runSearch, 450);
});
searchInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { clearTimeout(searchTimer); runSearch(); }
  if (e.key === 'Escape') {
    e.preventDefault();
    if (state.view.type === 'search') leaveSearch(); else clearSearchBox();
    searchInput.blur();
  }
});
scopeBtn.addEventListener('mousedown', (e) => e.preventDefault());
scopeBtn.addEventListener('click', () => {
  scopeBtn.hidden = true;
  delete scopeBtn.dataset.code;
  searchInput.focus();
  if (searchInput.value.trim().length >= 2) runSearch();
});

// ======================================================================
// Modals: add/edit station, settings
// ======================================================================

function closeModal() { $('#modal-root').innerHTML = ''; }

function openModal(html, onMount) {
  closeModal();
  $('#modal-root').innerHTML = `<div class="backdrop"><div class="modal" role="dialog" aria-modal="true">${html}</div></div>`;
  const back = $('#modal-root .backdrop');
  back.addEventListener('mousedown', (e) => { if (e.target === back) closeModal(); });
  onMount?.($('#modal-root .modal'));
}

function countryOptions(selected) {
  return `<option value="">Not set</option>` + state.regions.groups.map((g) =>
    `<optgroup label="${esc(g.name)}">${g.countries.map((c) => `<option value="${c.code}"${selected === c.code ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}</optgroup>`).join('');
}

function openStationEditor(existing) {
  const s = existing || {};
  openModal(`
    <h2>${existing ? 'Edit station' : 'Add a station'}</h2>
    <p class="lead">Paste a direct stream link (MP3, AAC, Ogg or HLS .m3u8). Playlist links like .pls or .m3u that point to a stream also work if you import them from Settings.</p>
    <div class="field"><label for="f-name">Station name</label><input id="f-name" type="text" value="${esc(s.name || '')}" placeholder="e.g. Kampus FM"></div>
    <div class="field"><label for="f-url">Stream link</label><input id="f-url" type="url" value="${esc(s.url || '')}" placeholder="https://stream.example.com/live.mp3"></div>
    <div class="field-row">
      <div class="field"><label for="f-country">Country</label><select id="f-country">${countryOptions(s.countrycode)}</select></div>
      <div class="field"><label for="f-tags">Genres</label><input id="f-tags" type="text" value="${esc((s.tags || []).join(', '))}" placeholder="news, pop"></div>
    </div>
    <div class="field"><label for="f-home">Website (optional)</label><input id="f-home" type="url" value="${esc(s.homepage || '')}" placeholder="https://"></div>
    <div class="modal-actions">
      <button class="btn" data-m="test">${I.play}Test</button>
      <span class="spacer"></span>
      <button class="btn" data-m="cancel">Cancel</button>
      <button class="btn primary" data-m="save">${existing ? 'Save' : 'Add station'}</button>
    </div>`, (m) => {
    $('#f-name', m).focus();
    const read = () => {
      const url = $('#f-url', m).value.trim();
      const code = $('#f-country', m).value;
      return {
        ...(existing || {}),
        uuid: existing?.uuid || `custom-${Date.now().toString(36)}`,
        custom: true,
        name: $('#f-name', m).value.trim() || url,
        url,
        homepage: $('#f-home', m).value.trim(),
        countrycode: code,
        country: code ? countryName(code) : '',
        tags: $('#f-tags', m).value.split(',').map((t) => t.trim().toLowerCase()).filter(Boolean).slice(0, 8),
        hls: /\.m3u8(\?|$)/i.test(url)
      };
    };
    const valid = (st) => {
      if (!/^https?:\/\/[^\s]+$/i.test(st.url)) { toast('Enter a stream link that starts with http:// or https://', 'err'); $('#f-url', m).focus(); return false; }
      return true;
    };
    m.addEventListener('click', (e) => {
      const a = e.target.closest('[data-m]')?.dataset.m;
      if (a === 'cancel') closeModal();
      if (a === 'test') { const st = read(); if (valid(st)) playStation(st, { resume: true }); }
      if (a === 'save') {
        const st = slimStation(read());
        if (!valid(st)) return;
        const exists = state.custom.some((x) => x.uuid === st.uuid);
        state.custom = exists ? state.custom.map((x) => (x.uuid === st.uuid ? st : x)) : [...state.custom, st];
        if (isFav(st.uuid)) state.favorites = state.favorites.map((x) => (x.uuid === st.uuid ? st : x));
        if (state.current?.uuid === st.uuid) state.current = st;
        api.save('custom', state.custom);
        api.save('favorites', state.favorites);
        remember([st]);
        closeModal();
        toast(exists ? 'Station updated' : `Added ${st.name}`, 'ok');
        renderSidebar();
        if (isLibraryView() || state.view.type === 'home') loadView();
        renderPlayer();
      }
    });
    m.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.target.tagName === 'INPUT') $('[data-m="save"]', m).click();
    });
  });
}

function openSettings() {
  const s = state.settings;
  const sw = (id, on) => `<label class="switch"><input type="checkbox" id="${id}"${on ? ' checked' : ''}><span></span></label>`;
  openModal(`
    <h2>Settings</h2>
    <p class="lead">Everything is stored only on this computer.</p>
    <div class="toggle-row">
      <div><div class="t">Keep playing when the window is closed</div><div class="d">The radio stays in the ${isMac ? 'menu bar' : 'system tray'}. Quit from there.</div></div>
      ${sw('s-tray', s.keepInTray !== false)}
    </div>
    <div class="toggle-row">
      <div><div class="t">Resume the last station on start</div><div class="d">Start playing as soon as the app opens.</div></div>
      ${sw('s-resume', !!s.resumeOnStart)}
    </div>
    <div class="toggle-row">
      <div><div class="t">Open on</div><div class="d">The screen you see when the app starts.</div></div>
      <select class="select" id="s-start">
        <option value="home"${s.startView === 'home' ? ' selected' : ''}>Home</option>
        <option value="favorites"${s.startView === 'favorites' ? ' selected' : ''}>Favourites</option>
        <option value="last"${s.startView === 'last' ? ' selected' : ''}>Last place I was</option>
      </select>
    </div>
    <div class="settings-group">
      <h3>Favourites</h3>
      <div class="btn-row">
        <button class="btn" data-s="export-m3u">${I.download}Export .m3u</button>
        <button class="btn" data-s="export-json">${I.download}Export backup (.json)</button>
        <button class="btn" data-s="import">${I.upload}Import…</button>
      </div>
    </div>
    <div class="about">
      Station data comes from <a data-s="rb">Radio Browser</a>, a free community directory. Some stations only stream inside their own country, and listings can be out of date.
      <br>Gelombang Radio 1.0 · Keyboard: Space play/pause · ${isMac ? '⌘' : 'Ctrl'}+F search · ${isMac ? '⌘' : 'Ctrl'}+D favourite · ${isMac ? '⌘' : 'Ctrl'}+↑/↓ volume · ${isMac ? '⌘' : 'Ctrl'}+←/→ previous/next
    </div>
    <div class="modal-actions"><button class="btn primary" data-s="close">Done</button></div>`, (m) => {
    m.addEventListener('change', (e) => {
      if (e.target.id === 's-tray') s.keepInTray = e.target.checked;
      if (e.target.id === 's-resume') s.resumeOnStart = e.target.checked;
      if (e.target.id === 's-start') s.startView = e.target.value;
      saveSettings();
    });
    m.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-s]')?.dataset.s;
      if (!a) return;
      if (a === 'close') closeModal();
      if (a === 'rb') api.openExternal('https://www.radio-browser.info/');
      if (a === 'export-m3u' || a === 'export-json') {
        if (!state.favorites.length) { toast('No favourites to export yet'); return; }
        const path = await api.exportFavorites(a === 'export-json' ? 'json' : 'm3u', state.favorites);
        if (path) toast('Favourites exported', 'ok');
      }
      if (a === 'import') importStations();
    });
  });
}

async function importStations() {
  let list;
  try { list = await api.importFavorites(); }
  catch (e) { toast(`Could not read that file: ${friendlyError(e)}`, 'err'); return; }
  if (!list) return;
  let added = 0;
  for (const raw of list) {
    if (!raw.url || !/^https?:\/\//i.test(raw.url)) continue;
    if (state.favorites.some((f) => f.url === raw.url || (raw.uuid && f.uuid === raw.uuid))) continue;
    const fromDirectory = raw.uuid && !String(raw.uuid).startsWith('custom-') && !raw.custom;
    const st = slimStation({
      ...raw,
      uuid: fromDirectory ? raw.uuid : raw.uuid || `custom-${Date.now().toString(36)}-${added}`,
      custom: !fromDirectory,
      tags: Array.isArray(raw.tags) ? raw.tags : [],
      countrycode: String(raw.countrycode || '').toUpperCase(),
      hls: raw.hls || /\.m3u8(\?|$)/i.test(raw.url)
    });
    if (st.custom && !state.custom.some((c) => c.url === st.url)) state.custom.push(st);
    state.favorites.push(st);
    added++;
  }
  api.save('favorites', state.favorites);
  api.save('custom', state.custom);
  remember(state.favorites);
  renderSidebar();
  if (isLibraryView() || state.view.type === 'home') loadView();
  toast(added ? `Imported ${added} station${added === 1 ? '' : 's'}` : 'Nothing new to import', added ? 'ok' : '');
}

$('#btn-add').addEventListener('click', () => openStationEditor());
$('#btn-settings').addEventListener('click', openSettings);

// ======================================================================
// Keyboard + commands from the main process (tray, menu)
// ======================================================================

function runCommand(cmd, arg) {
  switch (cmd) {
    case 'toggle': togglePlay(); break;
    case 'stop': stopPlayback(); break;
    case 'next': step(1); break;
    case 'prev': step(-1); break;
    case 'vol-up': setVolume((state.settings.volume ?? 70) + 5); break;
    case 'vol-down': setVolume((state.settings.volume ?? 70) - 5); break;
    case 'toggle-fav': if (state.current) toggleFavorite(state.current); break;
    case 'focus-search': searchInput.focus(); searchInput.select(); break;
    case 'settings': openSettings(); break;
    case 'play-uuid': {
      const st = findStation(arg);
      if (st) playStation(st, { queue: state.favorites });
      break;
    }
    default: break;
  }
}
api.onCommand(runCommand);

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if ($('#popover-root').firstChild) { closePopover(); return; }
    if ($('#modal-root').firstChild) { closeModal(); return; }
  }
  const tag = e.target.tagName;
  const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
  const mod = isMac ? e.metaKey : e.ctrlKey;

  // On macOS these shortcuts live in the app menu, which handles them.
  if (mod && !isMac) {
    const map = { f: 'focus-search', d: 'toggle-fav', ArrowUp: 'vol-up', ArrowDown: 'vol-down', ArrowRight: 'next', ArrowLeft: 'prev', ',': 'settings', '.': 'stop' };
    const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
    if (map[key] && !(typing && key.startsWith('Arrow'))) { e.preventDefault(); runCommand(map[key]); return; }
  }
  if (typing || $('#modal-root').firstChild) return;
  if (e.key === ' ' && !e.target.closest('button')) { e.preventDefault(); togglePlay(); }
  if (e.key === '/') { e.preventDefault(); searchInput.focus(); }
});

// ======================================================================
// Start-up
// ======================================================================

async function loadCounts() {
  state.countsState = 'loading';
  try {
    state.counts = await api.countryCounts();
    state.countsState = 'ok';
  } catch (_) {
    state.countsState = 'error';
  }
  renderSidebar();
  if (state.view.type === 'home') renderHome();
  else if (state.view.type === 'country' && !state.loading) {
    const meta = $('.view-head .meta');
    if (meta) renderList();
  }
}

async function init() {
  document.body.classList.add(`platform-${api.platform}`);
  $('#search-icon').innerHTML = I.search;
  $('#btn-add').innerHTML = I.plus;
  $('#btn-settings').innerHTML = I.sliders;
  $('#search-kbd').textContent = isMac ? '⌘F' : 'Ctrl F';

  const [regions, saved] = await Promise.all([api.getRegions(), api.loadState()]);
  state.regions = regions;
  for (const g of regions.groups) for (const c of g.countries) state.countries[c.code] = { ...c, group: g.id };
  state.favorites = saved.favorites || [];
  state.recents = saved.recents || [];
  state.custom = saved.custom || [];
  state.settings = saved.settings || {};
  remember(state.favorites); remember(state.recents); remember(state.custom);

  const last = state.settings.lastStation;
  if (last?.url) state.current = last;
  state.status = last?.url ? 'paused' : 'idle';
  renderPlayer();

  const sv = state.settings.startView;
  const lastView = state.settings.lastView;
  const startView = sv === 'favorites' ? { type: 'favorites' } : sv === 'last' && lastView?.type ? lastView : { type: 'home' };
  setView(startView);

  loadCounts();
  refreshFavorites();
  setInterval(() => {
    const c = $('#clocks');
    if (c) c.innerHTML = clocksHtml();
  }, 30000);

  if (state.settings.resumeOnStart && last?.url) playStation(last, { queue: state.favorites, resume: true });
  else setStatus(state.status);
}

init();
