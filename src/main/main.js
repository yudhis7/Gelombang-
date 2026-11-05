'use strict';

const path = require('path');
const fs = require('fs');
const {
  app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell, session, net, dialog
} = require('electron');
const { RadioBrowser } = require('./radiobrowser');
const { NowPlaying } = require('./icy');
const { Store } = require('./store');
const { toM3U, parsePlaylist } = require('./playlist');
const regions = require('../shared/regions.json');

const isMac = process.platform === 'darwin';
const isWin = process.platform === 'win32';
const APP_NAME = 'Gelombang Radio';
const ASSETS = path.join(__dirname, '..', '..', 'assets');

app.setName(APP_NAME);
if (isWin) app.setAppUserModelId('com.yudhis7.gelombangradio');
// Let the app resume the last station on launch without a click.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();

let win = null;
let tray = null;
let store = null;
let rb = null;
let nowPlaying = null;
let isQuitting = false;
let player = { playing: false, station: null, title: '' };

const regionCodes = regions.groups.flatMap((g) => g.countries.map((c) => c.code));

function send(channel, ...args) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

function showWindow() {
  if (!win) return createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createWindow() {
  const saved = store.get('windowBounds');
  win = new BrowserWindow({
    width: saved?.width || 1180,
    height: saved?.height || 760,
    x: saved?.x,
    y: saved?.y,
    minWidth: 880,
    minHeight: 580,
    show: false,
    title: APP_NAME,
    backgroundColor: '#0d0f14',
    icon: isMac ? undefined : path.join(ASSETS, 'icon.png'),
    ...(isMac
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 16, y: 16 } }
      : isWin
        ? { titleBarStyle: 'hidden', titleBarOverlay: { color: '#0d0f14', symbolColor: '#c3cad8', height: 48 } }
        : {}),
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
      spellcheck: false
    }
  });

  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.once('ready-to-show', () => {
    if (saved?.maximized) win.maximize();
    win.show();
  });

  const saveBounds = () => {
    if (!win || win.isDestroyed()) return;
    const maximized = win.isMaximized();
    const b = maximized ? (store.get('windowBounds') || win.getBounds()) : win.getBounds();
    store.set('windowBounds', { ...b, maximized });
  };
  let boundsTimer;
  const debounced = () => { clearTimeout(boundsTimer); boundsTimer = setTimeout(saveBounds, 500); };
  win.on('resize', debounced);
  win.on('move', debounced);

  win.on('close', (e) => {
    saveBounds();
    const keep = store.get('settings')?.keepInTray !== false;
    if (!isQuitting && keep) {
      e.preventDefault();
      win.hide();
    }
  });
  win.on('closed', () => { win = null; });

  // Never navigate the app window away; open links in the default browser.
  win.webContents.setWindowOpenHandler(({ url }) => {
    openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) { e.preventDefault(); openExternal(url); }
  });
}

function openExternal(url) {
  try {
    const u = new URL(url);
    if (u.protocol === 'http:' || u.protocol === 'https:') shell.openExternal(u.toString());
  } catch (_) { /* ignore bad URLs */ }
}

// ---------- Tray / menu bar ----------

function trayImage() {
  if (isMac) {
    const img = nativeImage.createFromPath(path.join(ASSETS, 'trayTemplate.png'));
    img.setTemplateImage(true);
    return img;
  }
  return nativeImage.createFromPath(path.join(ASSETS, 'tray.png'));
}

function truncate(s, n) {
  s = String(s || '');
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

function updateTray() {
  if (!tray) return;
  const favs = (store.get('favorites') || []).slice(0, 15);
  const st = player.station;
  const label = st ? truncate(st.name, 48) : 'Nothing playing';
  const items = [
    { label, enabled: false },
    ...(player.title ? [{ label: truncate(player.title, 60), enabled: false }] : []),
    { type: 'separator' },
    { label: player.playing ? 'Pause' : 'Play', enabled: !!st, click: () => send('cmd', 'toggle') },
    { label: 'Stop', enabled: !!st, click: () => send('cmd', 'stop') },
    { label: 'Next station', enabled: !!st || favs.length > 0, click: () => send('cmd', 'next') },
    {
      label: 'Favourites',
      enabled: favs.length > 0,
      submenu: favs.map((f) => ({
        label: truncate(f.name, 48),
        type: 'radio',
        checked: st?.uuid === f.uuid,
        click: () => send('cmd', 'play-uuid', f.uuid)
      }))
    },
    { type: 'separator' },
    { label: `Show ${APP_NAME}`, click: showWindow },
    { label: 'Quit', click: () => { isQuitting = true; app.quit(); } }
  ];
  tray.setContextMenu(Menu.buildFromTemplate(items));
  const tip = st ? `${st.name}${player.title ? ` — ${player.title}` : ''}` : APP_NAME;
  tray.setToolTip(truncate(tip, 120));
}

function createTray() {
  tray = new Tray(trayImage());
  if (!isMac) tray.on('click', () => {
    if (win && win.isVisible() && win.isFocused()) win.hide(); else showWindow();
  });
  updateTray();
}

// ---------- App menu (macOS needs Edit roles for copy/paste in inputs) ----------

function buildMenu() {
  if (!isMac) { Menu.setApplicationMenu(null); return; }
  const template = [
    {
      label: APP_NAME,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { label: 'Settings…', accelerator: 'Cmd+,', click: () => send('cmd', 'settings') },
        { type: 'separator' },
        { role: 'hide' }, { role: 'hideOthers' }, { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' }
      ]
    },
    { role: 'editMenu' },
    {
      label: 'Playback',
      submenu: [
        { label: 'Play / Pause', click: () => send('cmd', 'toggle') },
        { label: 'Stop', accelerator: 'Cmd+.', click: () => send('cmd', 'stop') },
        { label: 'Next Station', accelerator: 'Cmd+Right', click: () => send('cmd', 'next') },
        { label: 'Previous Station', accelerator: 'Cmd+Left', click: () => send('cmd', 'prev') },
        { type: 'separator' },
        { label: 'Volume Up', accelerator: 'Cmd+Up', click: () => send('cmd', 'vol-up') },
        { label: 'Volume Down', accelerator: 'Cmd+Down', click: () => send('cmd', 'vol-down') },
        { type: 'separator' },
        { label: 'Toggle Favourite', accelerator: 'Cmd+D', click: () => send('cmd', 'toggle-fav') },
        { label: 'Find Station', accelerator: 'Cmd+F', click: () => send('cmd', 'focus-search') }
      ]
    },
    { role: 'viewMenu' },
    { role: 'windowMenu' }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ---------- IPC ----------

function registerIpc() {
  ipcMain.handle('regions:get', () => regions);
  ipcMain.handle('rb:counts', () => rb.countryCounts());
  ipcMain.handle('rb:stations', (_e, code, opts) => rb.stationsByCountry(code, opts || {}));
  ipcMain.handle('rb:top', (_e, codes, perCountry) => rb.topAcross(codes, perCountry));
  ipcMain.handle('rb:search', (_e, q, code) => rb.search(q, code || null));
  ipcMain.handle('rb:byUuids', (_e, uuids) => rb.byUuids(uuids || []));
  ipcMain.on('rb:click', (_e, uuid) => { rb.click(uuid); });

  ipcMain.handle('store:load', () => store.all());
  ipcMain.on('store:set', (_e, key, value) => {
    store.set(key, value);
    if (key === 'favorites') updateTray();
  });

  ipcMain.on('np:start', (_e, st) => nowPlaying.start(st));
  ipcMain.on('np:stop', () => nowPlaying.stop());

  ipcMain.on('player:state', (_e, s) => {
    player = { playing: !!s.playing, station: s.station || null, title: s.title || '' };
    if (win && !win.isDestroyed()) {
      win.setTitle(player.station ? `${player.station.name} — ${APP_NAME}` : APP_NAME);
    }
    updateTray();
  });

  ipcMain.on('shell:open', (_e, url) => openExternal(url));

  ipcMain.handle('fav:export', async (_e, format, stations) => {
    const ext = format === 'json' ? 'json' : 'm3u';
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: 'Export favourites',
      defaultPath: path.join(app.getPath('documents'), `gelombang-favourites.${ext}`),
      filters: [ext === 'json' ? { name: 'JSON', extensions: ['json'] } : { name: 'M3U playlist', extensions: ['m3u', 'm3u8'] }]
    });
    if (canceled || !filePath) return null;
    const body = ext === 'json'
      ? JSON.stringify({ app: APP_NAME, exported: new Date().toISOString(), favorites: stations }, null, 2)
      : toM3U(stations);
    fs.writeFileSync(filePath, body, 'utf8');
    return filePath;
  });

  ipcMain.handle('fav:import', async () => {
    const { canceled, filePaths } = await dialog.showOpenDialog(win, {
      title: 'Import stations',
      properties: ['openFile'],
      filters: [{ name: 'Playlists', extensions: ['json', 'm3u', 'm3u8', 'pls'] }]
    });
    if (canceled || !filePaths?.[0]) return null;
    const text = fs.readFileSync(filePaths[0], 'utf8');
    return parsePlaylist(text);
  });
}

// ---------- Network tweaks ----------

function configureSession() {
  const ses = session.defaultSession;
  // HLS streams are fetched by hls.js; many radio CDNs omit CORS headers. This is a
  // single-user desktop app, so allow cross-origin reads for media/XHR responses.
  ses.webRequest.onHeadersReceived((details, callback) => {
    if (details.url.startsWith('file:') || details.url.startsWith('devtools:')) return callback({});
    const headers = { ...(details.responseHeaders || {}) };
    for (const k of Object.keys(headers)) {
      if (k.toLowerCase() === 'access-control-allow-origin') delete headers[k];
    }
    headers['Access-Control-Allow-Origin'] = ['*'];
    callback({ responseHeaders: headers });
  });
  ses.setPermissionRequestHandler((_wc, permission, cb) => cb(permission === 'clipboard-sanitized-write'));
}

// ---------- Lifecycle ----------

app.on('second-instance', showWindow);

app.whenReady().then(() => {
  if (!gotLock) return;
  store = new Store(app.getPath('userData'));
  rb = new RadioBrowser({
    fetchImpl: (url, init) => net.fetch(url, init),
    regionCodes,
    baseOverride: process.env.GELOMBANG_API_BASE
  });
  nowPlaying = new NowPlaying((d) => send('np:update', d));

  configureSession();
  registerIpc();
  buildMenu();
  createWindow();
  createTray();

  app.on('activate', showWindow);
});

app.on('before-quit', () => {
  isQuitting = true;
  nowPlaying?.stop();
  store?.flush();
});

app.on('window-all-closed', () => {
  // With "keep in tray" off, closing the window quits on every platform.
  app.quit();
});
