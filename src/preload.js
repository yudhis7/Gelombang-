'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('gelombang', {
  platform: process.platform,

  getRegions: () => ipcRenderer.invoke('regions:get'),
  countryCounts: () => ipcRenderer.invoke('rb:counts'),
  stations: (code, opts) => ipcRenderer.invoke('rb:stations', code, opts),
  topAcross: (codes, perCountry) => ipcRenderer.invoke('rb:top', codes, perCountry),
  search: (q, code) => ipcRenderer.invoke('rb:search', q, code),
  byUuids: (uuids) => ipcRenderer.invoke('rb:byUuids', uuids),
  click: (uuid) => ipcRenderer.send('rb:click', uuid),

  loadState: () => ipcRenderer.invoke('store:load'),
  save: (key, value) => ipcRenderer.send('store:set', key, value),

  nowPlayingStart: (station) => ipcRenderer.send('np:start', {
    uuid: station.uuid, url: station.url, countrycode: station.countrycode
  }),
  nowPlayingStop: () => ipcRenderer.send('np:stop'),
  playerState: (s) => ipcRenderer.send('player:state', s),

  openExternal: (url) => ipcRenderer.send('shell:open', url),
  exportFavorites: (format, stations) => ipcRenderer.invoke('fav:export', format, stations),
  importFavorites: () => ipcRenderer.invoke('fav:import'),

  onNowPlaying: (cb) => ipcRenderer.on('np:update', (_e, d) => cb(d)),
  onCommand: (cb) => ipcRenderer.on('cmd', (_e, cmd, arg) => cb(cmd, arg))
});
