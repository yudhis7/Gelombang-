'use strict';
// Tiny JSON store in the user's app-data folder. Writes are debounced and atomic.

const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  favorites: [],
  recents: [],
  custom: [],
  settings: {
    volume: 70,
    keepInTray: true,
    resumeOnStart: false,
    startView: 'home',
    sort: 'popular',
    lastView: null,
    lastStation: null,
    collapsedGroups: []
  },
  windowBounds: null
};

class Store {
  constructor(dir) {
    this.file = path.join(dir, 'gelombang-data.json');
    this.timer = null;
    this.data = this._load();
  }

  _load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return {
        ...DEFAULTS,
        ...raw,
        settings: { ...DEFAULTS.settings, ...(raw.settings || {}) }
      };
    } catch (_) {
      return JSON.parse(JSON.stringify(DEFAULTS));
    }
  }

  get(key) { return this.data[key]; }

  all() {
    const { favorites, recents, custom, settings } = this.data;
    return { favorites, recents, custom, settings };
  }

  set(key, value) {
    if (!(key in DEFAULTS)) return;
    this.data[key] = value;
    this._scheduleSave();
  }

  _scheduleSave() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.flush(), 400);
  }

  flush() {
    clearTimeout(this.timer);
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = `${this.file}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
      fs.renameSync(tmp, this.file);
    } catch (e) {
      console.error('Could not save data:', e.message);
    }
  }
}

module.exports = { Store };
