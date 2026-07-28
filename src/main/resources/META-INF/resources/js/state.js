const OVCState = {
  STORAGE_KEY: 'ovc-state',

  chunkEdits: new Map(),
  selectedSuggestions: new Set(),
  transcriptionCache: null,

  setChunkEdit(index, text) {
    const existing = this.chunkEdits.get(index);
    if (existing && existing.text === text && existing.synced) return;
    this.chunkEdits.set(index, { text, dirty: true, synced: false, timestamp: Date.now() });
    this.save();
  },

  getChunkEdit(index) {
    return this.chunkEdits.get(index);
  },

  markChunkSynced(index) {
    const edit = this.chunkEdits.get(index);
    if (edit) {
      edit.dirty = false;
      edit.synced = true;
      this.save();
    }
  },

  getUnsyncedEdits() {
    return Array.from(this.chunkEdits.entries())
      .filter(([_, v]) => v.dirty)
      .map(([k, v]) => ({ index: k, text: v.text, timestamp: v.timestamp }));
  },

  getUnsyncedCount() {
    return this.getUnsyncedEdits().length;
  },

  toggleSuggestion(id, selected) {
    if (selected === true) {
      this.selectedSuggestions.add(id);
    } else if (selected === false) {
      this.selectedSuggestions.delete(id);
    } else {
      if (this.selectedSuggestions.has(id)) {
        this.selectedSuggestions.delete(id);
      } else {
        this.selectedSuggestions.add(id);
      }
    }
    this.save();
  },

  isSuggestionSelected(id) {
    return this.selectedSuggestions.has(id);
  },

  getSelectedSuggestionIds() {
    return Array.from(this.selectedSuggestions);
  },

  setSelectedSuggestions(ids) {
    this.selectedSuggestions = new Set(ids);
    this.save();
  },

  getCache(key) {
    return this.transcriptionCache ? this.transcriptionCache[key] : null;
  },

  setCache(key, value) {
    if (!this.transcriptionCache) this.transcriptionCache = {};
    this.transcriptionCache[key] = value;
    this.save();
  },

  clearCache() {
    this.transcriptionCache = null;
    this.save();
  },

  save() {
    try {
      const data = {
        chunkEdits: Object.fromEntries(this.chunkEdits),
        selectedSuggestions: Array.from(this.selectedSuggestions),
        transcriptionCache: this.transcriptionCache
      };
      sessionStorage.setItem(this.STORAGE_KEY, JSON.stringify(data));
    } catch (e) {
      console.warn('OVCState.save failed:', e);
    }
  },

  load() {
    try {
      const raw = sessionStorage.getItem(this.STORAGE_KEY);
      if (!raw) return;
      const data = JSON.parse(raw);
      if (data.chunkEdits) {
        this.chunkEdits = new Map(Object.entries(data.chunkEdits));
      }
      if (data.selectedSuggestions) {
        this.selectedSuggestions = new Set(data.selectedSuggestions);
      }
      this.transcriptionCache = data.transcriptionCache || null;
    } catch (e) {
      console.warn('OVCState.load failed:', e);
    }
  },

  clear() {
    this.chunkEdits.clear();
    this.selectedSuggestions.clear();
    this.transcriptionCache = null;
    sessionStorage.removeItem(this.STORAGE_KEY);
  },

  clearChunkEdits() {
    this.chunkEdits.clear();
    this.save();
  }
};

OVCState.load();
window.addEventListener('beforeunload', function() { OVCState.save(); });
