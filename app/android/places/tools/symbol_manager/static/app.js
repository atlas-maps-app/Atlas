'use strict';
/* The symbol manager's page: the symbols database's kinds, every drawing Atlas has, each shown where the app shows it,
   and a swap that writes kinds.tsv back. The server (serve.py) reads the files live; this page polls it. */

const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

/* KindsTest's families whose places stand side by side on the map: a look-alike across them misleads too. */
const NEIGHBOURS = [['fun', 'water'], ['nature', 'sport'], ['shops', 'groceries'], ['fun', 'sport'], ['water', 'sport'], ['nature', 'water']];
const SET_NAMES = {board: 'Ours (the boards)', ui: 'Interface', atlas: "Atlas's own", temaki: 'Temaki', maki: 'Maki', pinhead: 'Pinhead', lucide: 'Lucide (compare only)', behind: 'Behind ours'};
const SET_ORDER = ['board', 'ui', 'atlas', 'temaki', 'maki', 'pinhead', 'lucide'];
const GENERIC = new Set(('amenity shop leisure tourism craft office building man_made natural historic public_transport highway emergency ' +
  'healthcare club sport landuse waterway aeroway railway power place station stop position platform point the and for with type yes ' +
  'store stores center centre facility venue establishment outlet supply supplies service services general').split(' '));
const FILTERS = [['all', 'All'], ['new', 'Not looked at'], ['redo', 'Redo'], ['sets', "On a set's drawing"], ['ours', 'On ours'], ['mine', 'Changed here']];
const STATE_WORD = {'': 'Not looked at', ok: 'Right', redo: 'Redo', drawn: 'Drawn'};

const S = {
  data: null, byKind: new Map(), famByName: new Map(), wearers: new Map(),
  sel: null, preview: null, previewRaw: false, filter: 'all', query: '',
  candTab: 'suggested', candSet: 'all', candQuery: '', candList: [], candShown: 0, candIndex: -1,
  version: null, writing: false, lastWrite: 0, mine: new Set(), advance: true, note: '', noteDirty: false, alikeMemo: null,
  view: 'list', focus: null, altIndex: -1, expanded: new Set(), listTop: 0,
  sym: null, symBack: null, symPick: null, symOff: new Set(), symQuery: '',
};

/* ------------------------------------------------------------------------------------------------------ server */

async function getJSON(url) {
  const r = await fetch(url, {cache: 'no-store'});
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return r.json();
}

async function post(url, body) {
  const r = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  let j = {};
  try { j = await r.json(); } catch (e) { /* an empty answer */ }
  return {status: r.status, ...j};
}

/** Reads everything again and draws it; what changed in kinds.tsv since the last read, by name. Whatever is being
    typed keeps its place, as the files change under it whenever another session writes. */
async function load() {
  const [d, hist] = await Promise.all([getJSON('/api/data'), getJSON('/api/history').catch(() => [])]);
  const before = S.data ? new Map(S.data.kinds.map(k => [k.kind, k.line])) : null;
  const changedKinds = before ? d.kinds.filter(k => before.get(k.kind) !== k.line) : [];
  const changed = changedKinds.map(k => k.name);
  const files = S.data ? Object.keys(d.version).filter(f => d.version[f] !== S.data.version[f]) : [];
  const a = document.activeElement, focus = a && a.id && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA')
    ? {id: a.id, start: a.selectionStart, end: a.selectionEnd} : null;
  S.data = d;
  S.version = JSON.stringify(d.version);
  S.byKind = new Map(d.kinds.map(k => [k.kind, k]));
  S.famByName = new Map(d.families.map(f => [f.name, f]));
  S.mine = new Set(hist.map(e => e.kind));
  // the drawings stay as they were while only the kinds' lines move (another session's writes): keep what was drawn
  const kindFiles = ['kinds.tsv', 'kind_names.tsv', 'kind_colours.tsv', 'categories.json', 'cuisine.json'];
  if (!files.length || files.some(f => !kindFiles.includes(f))) {
    svgCache.clear(); lookCache.clear(); scoreCache.clear(); tokCache.clear(); docFreq = null; boardNames = null;
  }
  S.alikeMemo = null;
  S.wearers = new Map();
  for (const k of d.kinds) {
    const key = resolve(k.symbol)?.key;
    if (key) { if (!S.wearers.has(key)) S.wearers.set(key, []); S.wearers.get(key).push(k); }
  }
  if (!S.sel || !S.byKind.has(S.sel)) S.sel = (visibleKinds()[0] || d.kinds[0] || {}).kind;
  renderFilters();
  if (S.view === 'list') {
    const top = $('#listView').scrollTop;
    if (!before || !$('#list .row') || files.some(f => !kindFiles.includes(f))) { renderList(); $('#listView').scrollTop = top; }
    else refreshRows(changedKinds.map(k => k.kind));
  } else if (S.view === 'symbol') {
    const top = $('#symbolView').scrollTop;
    renderSymbol();
    $('#symbolView').scrollTop = top;
  } else {
    const kindsTop = $('#kinds').scrollTop, detailTop = $('#detail').scrollTop;
    renderKinds();
    renderDetail();
    $('#kinds').scrollTop = kindsTop;
    $('#detail').scrollTop = detailTop;
  }
  const el = focus && document.getElementById(focus.id);
  if (el) {
    el.focus();
    try { el.setSelectionRange(focus.start, focus.end); } catch (e) { /* a field without a caret */ }
  }
  return {changed, files};
}

/* ---------------------------------------------------------------------------------------------------- drawings */

/** The drawing the app draws for [id]: a board id, an id the boards stand in for (an alias), or a set's id. */
function resolve(id) {
  if (!id || !S.data) return null;
  const D = S.data.drawings;
  if (D[id] && D[id].set === 'board') return {key: id, d: D[id]};
  const a = S.data.aliases[id];
  if (a && D[a]) return {key: a, d: D[a]};
  return D[id] ? {key: id, d: D[id]} : null;
}

function argb(s) {
  if (!s || s.length !== 9) return null;
  const a = parseInt(s.slice(1, 3), 16) / 255;
  return `rgba(${parseInt(s.slice(3, 5), 16)},${parseInt(s.slice(5, 7), 16)},${parseInt(s.slice(7, 9), 16)},${+a.toFixed(3)})`;
}

/** A drawing's SVG insides, its tinted parts in currentColor, as Symbols and BoardSymbols draw them. */
function markup(d, uid) {
  let defs = '', body = '';
  const vb = String(d.vb).split(/[\s,]+/).map(Number);
  if (d.set === 'board') {
    d.p.forEach((p, i) => {
      const flags = p[0], path = p[1], o = p[2] && typeof p[2] === 'object' ? p[2] : {};
      let c = o.c ? argb(o.c) : 'currentColor';
      if (o.g && Array.isArray(o.g.p) && Array.isArray(o.g.s) && o.g.s.length >= 2) {
        const gid = ('g' + uid + '_' + i).replace(/[^A-Za-z0-9_]/g, '_');
        const stops = o.g.s.map(([off, col]) => `<stop offset="${off}" stop-color="${argb(col) || 'currentColor'}"/>`).join('');
        const [x1, y1, x2, y2] = o.g.p;
        defs += o.g.k === 'r'
          ? `<radialGradient id="${gid}" gradientUnits="userSpaceOnUse" cx="${x1}" cy="${y1}" r="${Math.hypot(x2 - x1, y2 - y1)}">${stops}</radialGradient>`
          : `<linearGradient id="${gid}" gradientUnits="userSpaceOnUse" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}">${stops}</linearGradient>`;
        c = `url(#${gid})`;
      }
      let a = ` d="${esc(path)}" fill="${flags.includes('f') ? c : 'none'}"`;
      if (flags.includes('e')) a += ' fill-rule="evenodd"';
      if (flags.includes('s')) {
        a += ` stroke="${c}" stroke-width="${o.w ?? 1}" stroke-linecap="${({r: 'round', s: 'square'})[o.cap] || 'butt'}"` +
          ` stroke-linejoin="${({r: 'round', b: 'bevel'})[o.join] || 'miter'}"`;
        if (Array.isArray(o.dash)) a += ` stroke-dasharray="${o.dash.join(' ')}"`;
      }
      if (o.o != null && o.o !== 1) a += ` opacity="${o.o}"`;
      body += `<path${a}/>`;
    });
    for (const t of d.t || []) {
      const fam = ({serif: 'Georgia, serif', mono: 'ui-monospace, monospace'})[t.f] || 'system-ui, sans-serif';
      body += `<text x="${t.x || 0}" y="${t.y || 0}" font-size="${t.z || 16}" font-weight="${t.w || 400}" font-family="${fam}"` +
        ` text-anchor="${({middle: 'middle', end: 'end'})[t.a] || 'start'}" fill="${t.c ? argb(t.c) : 'currentColor'}"` +
        `${t.o != null && t.o !== 1 ? ` opacity="${t.o}"` : ''}${t.i ? ' font-style="italic"' : ''}${t.l ? ` letter-spacing="${t.l}"` : ''}` +
        `${t.r ? ` transform="rotate(${t.r} ${t.x || 0} ${t.y || 0})"` : ''}>${esc(t.s)}</text>`;
    }
  } else {
    const w = vb[2] / 12;  // the sets' strokes: 2 on a 24 grid, round
    for (const [flags, path] of d.p) {
      body += `<path d="${esc(path)}" fill="${flags.includes('f') ? 'currentColor' : 'none'}"${flags.includes('e') ? ' fill-rule="evenodd"' : ''}` +
        `${flags.includes('s') ? ` stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"` : ''}/>`;
    }
  }
  return {vb: d.vb, html: (defs ? `<defs>${defs}</defs>` : '') + body};
}

const svgCache = new Map();
/** [id]'s markup as the app draws it, or with [raw] the set's own drawing behind an alias. */
function inner(id, raw) {
  const ck = (raw ? '~' : '') + id;
  if (svgCache.has(ck)) return svgCache.get(ck);
  const d = raw ? S.data.drawings[id] : resolve(id)?.d;
  const m = d ? markup(d, ck) : null;
  svgCache.set(ck, m);
  return m;
}

function nested(m, x, size) {
  return m ? `<svg x="${x}" y="${x}" width="${size}" height="${size}" viewBox="${m.vb}" style="color:#fff">${m.html}</svg>` : '';
}

/** A result row's tile: white, 22 on a 40 tile of the family's colour. */
function tileSVG(id, colour, scale = 1, raw = false) {
  const s = 40 * scale;
  return `<svg width="${s}" height="${s}" viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="12" fill="${colour}"/>${nested(inner(id, raw), 9, 22)}</svg>`;
}

/** A map pin: white, 14 on a 26 disc of the family's colour in a 2 white ring; a closed place's half way to white. */
function pinSVG(id, colour, scale = 1, closed = false, raw = false) {
  const s = 30 * scale;
  return `<svg width="${s}" height="${s}" viewBox="0 0 30 30" aria-hidden="true"><circle cx="15" cy="15" r="15" fill="#fff"/>` +
    `<circle cx="15" cy="15" r="13" fill="${closed ? fade(colour) : colour}"/>${nested(inner(id, raw), 8, 14)}</svg>`;
}

function pinAt(id, colour, x, y, raw) {
  return `<g transform="translate(${x} ${y})"><circle cx="15" cy="15" r="15" fill="#fff"/><circle cx="15" cy="15" r="13" fill="${colour}"/>${nested(inner(id, raw), 8, 14)}</g>`;
}

function discSVG(id, colour, size, raw) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 26 26" aria-hidden="true"><circle cx="13" cy="13" r="13" fill="${colour}"/>${nested(inner(id, raw), 6, 14)}</svg>`;
}

function bigIcon(id, raw, size = 88) {
  const m = inner(id, raw);
  return m ? `<svg width="${size}" height="${size}" viewBox="${m.vb}" style="color:#15171c" aria-hidden="true">${m.html}</svg>` : '<span class="muted">no drawing</span>';
}

function fade(hex) {
  const n = parseInt(String(hex).slice(1, 7), 16);
  const ch = s => { const c = (n >> s) & 255; return Math.round(c + (255 - c) * 0.5); };
  return `rgb(${ch(16)},${ch(8)},${ch(0)})`;
}

function famOf(k) { return S.famByName.get(k.family) || {colour: '#5A6478', id: 'other', name: k.family || 'Other'}; }
function colourOf(k) { return S.data.kindColours[k.kind] || famOf(k).colour; }
function setOf(d) { return d.aliasedTo ? 'behind' : d.set; }
function setLabel(s) { return SET_NAMES[s] || S.data.sets.find(x => x.prefix === s)?.name || s; }
function label(id, raw) {
  const r = raw ? {key: id, d: S.data.drawings[id]} : resolve(id);
  if (!r) return id;
  return `${r.key}${r.key !== id ? ' (as ' + id + ')' : ''} · ${setLabel(setOf(r.d))}`;
}

/* ------------------------------------------------------------------------------------------ look-alikes (estimate) */
/* LookAlikes.kt in a canvas: each drawing white at 20 px with 2 round it, 4× over, softened; the overlap of the ink
   and the agreement of the outlines, each with a pixel's slack; from 0.70 two read alike. */

const SIDE = 20, PAD = 2, N = SIDE + 2 * PAD, SS = 4, ALIKE = 0.70;
const lookCache = new Map(), scoreCache = new Map();
let lookCtx = null;

function look(id, raw) {
  const r = raw ? {key: '~' + id, d: S.data.drawings[id]} : resolve(id);
  if (!r || !r.d) return null;
  if (lookCache.has(r.key)) return lookCache.get(r.key);
  const big = N * SS;
  if (!lookCtx) {
    const c = document.createElement('canvas');
    c.width = c.height = big;
    lookCtx = c.getContext('2d', {willReadFrequently: true});
  }
  const ctx = lookCtx, d = r.d, vb = String(d.vb).split(/[\s,]+/).map(Number);
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, big, big);
  const sc = SIDE * SS / Math.max(vb[2], vb[3]);
  ctx.setTransform(sc, 0, 0, sc, PAD * SS - vb[0] * sc, PAD * SS - vb[1] * sc);
  ctx.fillStyle = '#fff';
  ctx.strokeStyle = '#fff';
  for (const p of d.p) {
    let path;
    try { path = new Path2D(p[1]); } catch (e) { continue; }
    const flags = p[0], board = d.set === 'board', o = board && p[2] && typeof p[2] === 'object' ? p[2] : {};
    let alpha = o.o ?? 1;
    if (o.c && o.c.length === 9) alpha *= parseInt(o.c.slice(1, 3), 16) / 255;
    ctx.globalAlpha = alpha;
    if (flags.includes('f')) ctx.fill(path, flags.includes('e') ? 'evenodd' : 'nonzero');
    if (flags.includes('s')) {
      ctx.lineWidth = board ? (o.w ?? 1) : vb[2] / 12;
      ctx.lineCap = board ? (({r: 'round', s: 'square'})[o.cap] || 'butt') : 'round';
      ctx.lineJoin = board ? (({r: 'round', b: 'bevel'})[o.join] || 'miter') : 'round';
      ctx.setLineDash(Array.isArray(o.dash) ? o.dash : []);
      ctx.stroke(path);
      ctx.setLineDash([]);
    }
  }
  ctx.globalAlpha = 1;
  const px = ctx.getImageData(0, 0, big, big).data;
  const ink = new Float32Array(N * N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let s = 0;
    for (let j = 0; j < SS; j++) for (let i = 0; i < SS; i++) s += px[((y * SS + j) * big + x * SS + i) * 4 + 3];
    ink[y * N + x] = s / (255 * SS * SS);
  }
  const g = gauss(ink, 0.6);
  const res = {ink: g, outline: outline(g)};
  lookCache.set(r.key, res);
  return res;
}

function gauss(a, sigma) {
  const r = Math.max(1, Math.floor(sigma * 3)), k = [];
  let sum = 0;
  for (let i = 0; i <= 2 * r; i++) { const v = Math.exp(-((i - r) * (i - r)) / (2 * sigma * sigma)); k.push(v); sum += v; }
  for (let i = 0; i < k.length; i++) k[i] /= sum;
  const tmp = new Float32Array(N * N), out = new Float32Array(N * N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let s = 0;
    for (let j = -r; j <= r; j++) { const xx = x + j; if (xx >= 0 && xx < N) s += k[j + r] * a[y * N + xx]; }
    tmp[y * N + x] = s;
  }
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let s = 0;
    for (let j = -r; j <= r; j++) { const yy = y + j; if (yy >= 0 && yy < N) s += k[j + r] * tmp[yy * N + x]; }
    out[y * N + x] = s;
  }
  return out;
}

function outline(ink) {
  const c = new Float32Array(N * N), s = new Float32Array(N * N);
  const at = (x, y) => (x >= 0 && x < N && y >= 0 && y < N ? ink[y * N + x] : 0);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const gx = (at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)) / 4;
    const gy = (at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)) / 4;
    const m = Math.hypot(gx, gy);
    if (m > 1e-6) { c[y * N + x] = (gx * gx - gy * gy) / m; s[y * N + x] = 2 * gx * gy / m; }
  }
  const out = new Float32Array(2 * N * N);
  out.set(gauss(c, 0.7), 0);
  out.set(gauss(s, 0.7), N * N);
  return out;
}

function overlap(a, b) {
  let best = 0;
  for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
    let lo = 0, hi = 0;
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const u = a[y * N + x], xx = x + dx, yy = y + dy;
      const v = xx >= 0 && xx < N && yy >= 0 && yy < N ? b[yy * N + xx] : 0;
      lo += Math.min(u, v);
      hi += Math.max(u, v);
    }
    if (hi > 0) best = Math.max(best, lo / hi);
  }
  return best;
}

function along(a, b) {
  const nn = N * N;
  let aa = 0, bb = 0;
  for (let i = 0; i < 2 * nn; i++) { aa += a[i] * a[i]; bb += b[i] * b[i]; }
  if (aa === 0 || bb === 0) return 0;
  let best = -1;
  for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
    let ab = 0;
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const xx = x + dx, yy = y + dy;
      if (xx < 0 || xx >= N || yy < 0 || yy >= N) continue;
      const i = y * N + x, j = yy * N + xx;
      ab += a[i] * b[j] + a[nn + i] * b[nn + j];
    }
    best = Math.max(best, ab / Math.sqrt(aa * bb));
  }
  return best;
}

function lookScore(a, b) { return Math.sqrt(overlap(a.ink, b.ink) * Math.max(0, along(a.outline, b.outline))); }

/** The kinds of [k]'s family, and of the families beside it on the map, whose symbols read like [id], closest first. */
function alikeList(k, id, raw) {
  const memoKey = k.kind + '|' + (raw ? '~' : '') + id;
  if (S.alikeMemo && S.alikeMemo.key === memoKey) return S.alikeMemo.list;
  const key = raw ? '~' + id : resolve(id)?.key;
  const L = key ? look(id, raw) : null;
  const out = [];
  if (L) {
    const fam = famOf(k).id, fams = new Set([fam]);
    for (const [a, b] of NEIGHBOURS) { if (a === fam) fams.add(b); if (b === fam) fams.add(a); }
    for (const o of S.data.kinds) {
      if (o.kind === k.kind) continue;
      const of = famOf(o).id;
      if (!fams.has(of)) continue;
      const ok = resolve(o.symbol)?.key;
      if (!ok) continue;
      let score;
      if (ok === key) score = 1;
      else {
        const pk = key < ok ? key + '|' + ok : ok + '|' + key;
        score = scoreCache.get(pk);
        if (score === undefined) { const M = look(o.symbol); score = M ? lookScore(L, M) : 0; scoreCache.set(pk, score); }
      }
      if (score >= 0.5) out.push({o, score, same: ok === key, sameFam: of === fam});
    }
  }
  out.sort((a, b) => b.score - a.score);
  S.alikeMemo = {key: memoKey, list: out};
  return out;
}

/* ------------------------------------------------------------------------------------------------- candidates */

const tokCache = new Map();
function stem(w) {
  if (w.length > 4 && w.endsWith('ies')) return w.slice(0, -3) + 'y';
  if (w.length > 4 && /(ch|sh|x|ss)es$/.test(w)) return w.slice(0, -2);
  if (w.length > 3 && w.endsWith('s') && !w.endsWith('ss')) return w.slice(0, -1);
  return w;
}
function words(s) {
  return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ')
    .split(' ').filter(w => w.length >= 3 && !GENERIC.has(w)).map(stem);
}
/* A note's words that say how a drawing was chosen, not what it shows. */
const NOTE_NOISE = new Set(('owner pick symbol manager was wants better one right board set maki temaki pinhead atlas ours drawn drawing ' +
  'keep kept step rule kin goes along reads like not but than its it from same own new old redo the a an of on in to').split(' ').map(stem));
/* The boards' names ("Nature", "Street", "Rest"): a note cites them as sources, they say nothing of the thing. */
let boardNames = null;
function kindTokens(k) {
  if (!boardNames) {
    boardNames = new Set();
    for (const ls of Object.values(S.data.boards)) for (const l of ls) for (const w of words(l.split(':')[0])) boardNames.add(w);
  }
  // a note's head says what the drawing is ("a plug: electrical things to recycle", "the café's cup, as …"); the rest
  // is why, and its words (screens, leaves, the boards it cites) would only bring in drawings of other things
  const head = String(k.note || '').split(/[:;,(]| — | - /)[0];
  const note = words(head).filter(w => !NOTE_NOISE.has(w) && !boardNames.has(w) && !/^\d/.test(w));
  return new Set([...words(k.kind.replace(/\//g, ' ')), ...words(k.name), ...words(k.fullName), ...note]);
}
/** A drawing's words, each with where it came from: '' for its own name, else the board card it stands on. */
function drawingTokens(id) {
  if (tokCache.has(id)) return tokCache.get(id);
  const d = S.data.drawings[id], m = new Map();
  for (const w of words(d.set === 'board' ? id.split('.').slice(1).join(' ') : id.slice(id.indexOf('-') + 1))) m.set(w, '');
  if (d.set === 'board') {
    for (const l of S.data.boards[id] || []) for (const w of words(l.split(':').slice(1).join(' '))) if (!m.has(w)) m.set(w, l);
  }
  tokCache.set(id, m);
  return m;
}
let docFreq = null;
/** How telling a word is: "food" stands under hundreds of drawings, "burger" under a few. */
function rarity(w) {
  if (!docFreq) {
    docFreq = new Map();
    for (const id of Object.keys(S.data.drawings)) for (const t of drawingTokens(id).keys()) docFreq.set(t, (docFreq.get(t) || 0) + 1);
  }
  return Math.log(1 + Object.keys(S.data.drawings).length / (docFreq.get(w) || 1));
}
/** How well a drawing's words [m] answer a kind's [a]: its own name counts fully, a board card it stands on less. */
function matchScore(a, m) {
  let s = 0, label = null;
  for (const w of a) {
    if (m.has(w)) {
      const l = m.get(w);
      s += rarity(w) * (l ? 0.35 : 1);
      if (label === null || !l) label = l;
      continue;
    }
    if (w.length < 4) continue;
    for (const [v, l] of m) {
      if (v.length >= 4 && (v.startsWith(w) || w.startsWith(v))) { s += 0.4 * rarity(v) * (l ? 0.35 : 1); if (label === null) label = l; break; }
    }
  }
  return {s, label};
}

/** Every drawing the app can draw whose words answer [toks], the best first (ours a little ahead). */
function rankDrawings(toks) {
  const scored = [];
  for (const [id, d] of Object.entries(S.data.drawings)) {
    if (d.aliasedTo || d.preview) continue;
    const {s, label} = matchScore(toks, drawingTokens(id));
    if (s > 0) scored.push({s: s * (d.set === 'board' ? 1.15 : 1), id, label});
  }
  scored.sort((a, b) => b.s - a.s || a.id.localeCompare(b.id));
  return scored;
}
function whyNamed(x) { const d = S.data.drawings[x.id]; return d.set === 'board' ? (x.label ? 'the boards: ' + x.label : 'ours, named alike') : setLabel(d.set); }

/** What [k] could wear, the likeliest first: now, the preset's own, the drawings named like it (ours a little ahead),
    the family chip's, its kin's, and the drawings that match it less well. */
function suggestions(k) {
  const out = [], at = new Map();
  const add = (id, why, weak = false) => {
    const r = resolve(id);
    if (!r || r.d.preview) return;
    if (at.has(r.key)) { const e = at.get(r.key); if (!e.why.includes(why)) e.why.push(why); return; }
    const e = {id: r.key, why: [why], raw: false, weak};
    at.set(r.key, e);
    out.push(e);
  };
  add(k.symbol, 'now');
  const was = /\bwas ([a-z][\w.-]*\w)/i.exec(k.note || '');
  if (was) add(was[1], 'before');
  if (k.preset) add(k.preset, "the preset's own");
  for (const id of new Set([k.symbol, k.preset])) {  // the set's own drawing that ours stands in for: to compare
    const d = id && S.data.drawings[id];
    if (d && d.aliasedTo && !at.has('~' + id)) {
      const e = {id, why: [setLabel(d.set) + "'s own, behind ours"], raw: true};
      at.set('~' + id, e);
      out.push(e);
    }
  }
  const scored = rankDrawings(kindTokens(k));
  const best = scored.length ? scored[0].s : 0;
  const why = whyNamed;
  const strong = scored.filter(x => x.s >= best * 0.5).slice(0, 30);
  for (const x of strong) add(x.id, why(x));
  const f = famOf(k);
  if (f.symbol) add(f.symbol, 'the family chip');
  const parts = k.kind.split('/'), parent = parts.slice(0, -1).join('/');
  for (const o of S.data.kinds) {
    if (o.kind === k.kind || o.family !== k.family) continue;
    if (o.kind.startsWith(k.kind + '/') || (parts.length > 2 && (o.kind === parent || o.kind.startsWith(parent + '/')))) add(o.symbol, 'kin: ' + o.name);
  }
  for (const x of scored.filter(x => x.s >= best * 0.25 && !strong.includes(x)).slice(0, 24)) add(x.id, why(x), true);
  return out;
}

function searchHit(id, d, q) {
  if (id.toLowerCase().includes(q)) return true;
  if ((S.data.boards[id] || []).some(l => l.toLowerCase().includes(q))) return true;
  return (S.wearers.get(id) || []).some(o => o.name.toLowerCase().includes(q));
}

/** Every drawing of the chosen set; "all" leaves out those behind ours, unless a search asks for them. */
function everything() {
  const out = [], searching = !!S.candQuery.trim();
  for (const [id, d] of Object.entries(S.data.drawings)) {
    const set = setOf(d);
    if (S.candSet === 'all' ? set === 'behind' && !searching : set !== S.candSet) continue;
    out.push({id, why: [], raw: !!d.aliasedTo});
  }
  const rank = c => { const s = setOf(S.data.drawings[c.id]), i = SET_ORDER.indexOf(s); return i < 0 ? (s === 'behind' ? 99 : 50) : i; };
  out.sort((a, b) => rank(a) - rank(b) || a.id.localeCompare(b.id));
  return out;
}

function candidates() {
  const k = S.byKind.get(S.sel);
  let list = S.candTab === 'suggested' ? suggestions(k) : everything();
  const q = S.candQuery.trim().toLowerCase();
  if (q) list = list.filter(c => searchHit(c.id, S.data.drawings[c.id], q));
  return list;
}

/* ------------------------------------------------------------------------------------------------------ kinds */

function stateOf(k) { return k.state || 'new'; }
function isOurs(k) { const r = resolve(k.symbol); return !!r && (r.d.set === 'board' || r.d.set === 'ui'); }
function matches(k, filter = S.filter, query = S.query) {
  if (filter === 'new' && k.state) return false;
  if (filter === 'redo' && k.state !== 'redo') return false;
  if (filter === 'sets' && isOurs(k)) return false;
  if (filter === 'ours' && !isOurs(k)) return false;
  if (filter === 'mine' && !S.mine.has(k.kind)) return false;
  if (query) {
    const q = query.toLowerCase();
    if (!(k.name.toLowerCase().includes(q) || k.kind.includes(q) || k.symbol.toLowerCase().includes(q) || (k.fullName || '').toLowerCase().includes(q))) return false;
  }
  return true;
}
function visibleKinds() { return S.data.kinds.filter(k => matches(k)); }

function renderFilters() {
  $('#filters').innerHTML = FILTERS.map(([id, name]) => {
    const n = S.data.kinds.filter(k => matches(k, id, '')).length;
    return `<button role="tab" data-filter="${id}" aria-selected="${S.filter === id}">${esc(name)}<span class="n">${n}</span></button>`;
  }).join('');
}

function renderKinds() {
  const byFam = new Map();
  for (const k of visibleKinds()) { if (!byFam.has(k.family)) byFam.set(k.family, []); byFam.get(k.family).push(k); }
  let h = '';
  for (const f of S.data.families) {
    const list = byFam.get(f.name);
    if (!list) continue;
    h += `<div class="fam-h"><span class="dot" style="background:${f.colour}"></span>${esc(f.name)}<span class="n">${list.length}</span></div>`;
    for (const k of list) {
      h += `<button class="krow" data-kind="${esc(k.kind)}"${k.kind === S.sel ? ' aria-current="true"' : ''} title="${esc(k.kind)} · ${esc(k.symbol)}">` +
        `${tileSVG(k.symbol, colourOf(k), 0.65)}<span class="nm">${esc(k.name)}</span><span class="st st-${stateOf(k)}" title="${STATE_WORD[k.state] || ''}"></span></button>`;
    }
  }
  $('#kinds').innerHTML = h || '<p class="empty" style="padding:14px">No kind matches.</p>';
}

function select(kind, scroll = true) {
  if (!S.byKind.has(kind)) return;
  S.sel = kind;
  S.preview = null;
  S.previewRaw = false;
  S.candIndex = -1;
  S.note = '';
  S.noteDirty = false;
  for (const el of document.querySelectorAll('.krow[aria-current="true"]')) el.removeAttribute('aria-current');
  const row = document.querySelector(`.krow[data-kind="${CSS.escape(kind)}"]`);
  if (row) { row.setAttribute('aria-current', 'true'); if (scroll) row.scrollIntoView({block: 'nearest'}); }
  renderDetail();
  $('#detail').scrollTop = 0;
}

function step(n) {
  const vis = visibleKinds();
  if (!vis.length) return;
  const i = vis.findIndex(k => k.kind === S.sel);
  const j = i < 0 ? 0 : Math.max(0, Math.min(vis.length - 1, i + n));
  select(vis[j].kind);
}

/* ----------------------------------------------------------------------------------------------------- detail */

function renderDetail() {
  const k = S.byKind.get(S.sel);
  const el = $('#detail');
  if (!k) { el.innerHTML = '<p class="empty">Pick a kind on the left.</p>'; return; }
  const f = famOf(k), vis = visibleKinds(), i = vis.findIndex(x => x.kind === k.kind);
  el.innerHTML = `
    <div class="head">
      <span id="headTile"></span>
      <div>
        <h1>${esc(k.name)}</h1>
        <div class="sub"><span class="pill" style="background:${f.colour}">${esc(f.name)}</span><span class="mono">${esc(k.kind)}</span>
          ${k.fullName && k.fullName !== k.name ? `<span class="muted">${esc(k.fullName)}</span>` : ''}</div>
      </div>
      <div class="nav">
        <div class="seg" id="stateSeg">${Object.keys(STATE_WORD).map(s => `<button data-state="${s}" aria-pressed="${k.state === s}">${STATE_WORD[s]}</button>`).join('')}</div>
        <button class="ghost" id="prevBtn" title="Previous kind (↑)">↑</button>
        <span class="muted">${i < 0 ? '–' : i + 1} of ${vis.length}</span>
        <button class="ghost" id="nextBtn" title="Next kind (↓)">↓</button>
      </div>
    </div>
    <div class="cols">
      <div class="card" id="ctxCard"></div>
      <div><div class="card" id="alikeCard"></div><div class="card" id="usesCard"></div></div>
    </div>
    <div class="card" id="candCard" style="margin-top:16px"></div>
    <div class="actions" id="actions"></div>`;
  renderShown();
  renderCandidates();
}

/** Everything that shows the drawing in view: the current one, or the one being tried. */
function renderShown() {
  const k = S.byKind.get(S.sel);
  const id = S.preview || k.symbol, raw = !!(S.preview && S.previewRaw);
  $('#headTile').innerHTML = tileSVG(id, colourOf(k), 1.6, raw);
  renderContexts(k, id, raw);
  renderAlikes(k, id, raw);
  renderUses(k, id, raw);
  renderActions(k);
}

function neighbours(k, n) {
  const fam = S.data.kinds.filter(x => x.family === k.family), i = fam.findIndex(x => x.kind === k.kind), out = [];
  for (let d = 1; out.length < n && (i - d >= 0 || i + d < fam.length); d++) {
    if (i + d < fam.length) out.push(fam[i + d]);
    if (out.length < n && i - d >= 0) out.push(fam[i - d]);
  }
  return out;
}

function phone(k, id, raw, dark) {
  const rows = [{k, id, raw, me: true}, ...neighbours(k, 4).map(o => ({k: o, id: o.symbol}))];
  const order = [rows[1], rows[0], ...rows.slice(2)].filter(Boolean);
  const dist = ['120 m', '350 m', '600 m', '1.1 km', '1.4 km'];
  return `<div class="phone${dark ? ' dark' : ''}">` + order.map((r, i) =>
    `<div class="lrow${r.me ? ' me' : ''}">${tileSVG(r.id, colourOf(r.k), 1, r.raw)}<div><b>${esc(r.k.name)}</b><span>open until 22:00 · ${dist[i]}</span></div></div>`).join('') + '</div>';
}

function mapStrip(k, id, raw, dark) {
  const W = 340, H = 150;
  const spots = [[170, 76], [72, 44], [262, 54], [112, 114], [232, 116], [34, 102], [304, 100]];
  const near = alikeList(k, id, raw).filter(a => !a.same).slice(0, 2).map(a => a.o);
  const others = [...neighbours(k, 4), ...near].filter((o, i, a) => a.findIndex(x => x.kind === o.kind) === i);
  const pins = [{k, id, raw}, ...others.map(o => ({k: o, id: o.symbol}))].slice(0, spots.length);
  const bg = dark ? '#1c1f25' : '#ece8de', road = dark ? '#2b2f37' : '#ffffff', water = dark ? '#1b2a3d' : '#c7dbef', park = dark ? '#1f2b23' : '#d4e5cb';
  let s = `<svg class="map" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${dark ? 'dark ' : ''}map with neighbours">` +
    `<rect width="${W}" height="${H}" fill="${bg}"/><path d="M0 130 C 80 112, 140 150, 220 132 S 320 122, 340 134 L340 150 L0 150Z" fill="${water}"/>` +
    `<rect x="246" y="6" width="88" height="40" rx="8" fill="${park}"/><path d="M0 64 H340 M150 0 V150 M0 18 L124 150" stroke="${road}" stroke-width="7" fill="none"/>`;
  pins.forEach((p, i) => { const [x, y] = spots[i]; s += pinAt(p.id, colourOf(p.k), x - 15, y - 15, p.raw); });
  return s + '</svg>';
}

function renderContexts(k, id, raw) {
  const colour = colourOf(k), trying = !!S.preview;
  let h = '<h2>In the app</h2><div class="swap">' +
    `<div class="big now">${bigIcon(k.symbol)}<span>now · ${esc(label(k.symbol))}</span></div>` +
    (trying ? `<div class="arrow">→</div><div class="big">${bigIcon(id, raw)}<span>${esc(label(id, raw))}</span></div>`
      : '<div></div><div class="muted">Try a drawing below: it shows here, as a pin, a row and a chip, among its neighbours, light and dark.</div>') + '</div>';
  h += `<div class="ctx-label">Map pin at 1×, 2× and 3×, and closed (lighter)</div><div class="ctx-row">${[1, 2, 3].map(s => `<figure>${pinSVG(id, colour, s, false, raw)}</figure>`).join('')}` +
    `<figure>${pinSVG(id, colour, 2, true, raw)}<figcaption>closed</figcaption></figure></div>`;
  h += `<div class="ctx-label">A result row's tile at 1× and 2×, and as a chip</div><div class="ctx-row">${tileSVG(id, colour, 1, raw)}${tileSVG(id, colour, 2, raw)}` +
    `<div class="chips"><span class="chip">${discSVG(id, colour, 26, raw)}${esc(k.name)}<span class="count">12</span></span>` +
    `<span class="chip dark">${discSVG(id, colour, 26, raw)}${esc(k.name)}<span class="count">12</span></span></div></div>`;
  h += '<div class="ctx-label">Among its neighbours, and the kinds that read most like it</div>' +
    `<div class="two"><div>${phone(k, id, raw, false)}</div><div>${phone(k, id, raw, true)}</div></div>` +
    `<div class="two" style="margin-top:12px">${mapStrip(k, id, raw, false)}${mapStrip(k, id, raw, true)}</div>`;
  $('#ctxCard').innerHTML = h;
}

function renderAlikes(k, id, raw) {
  const list = alikeList(k, id, raw);
  const shares = list.filter(a => a.same && a.sameFam), others = list.filter(a => !(a.same && a.sameFam));
  let h = "<h2>Reads like, at a pin's size</h2>";
  if (shares.length) {
    h += `<div class="muted" style="margin-bottom:8px">Shares this drawing with ${shares.length} ${shares.length === 1 ? 'kind' : 'kinds'} of its family: ` +
      `<span class="uses">${shares.slice(0, 10).map(a => `<button class="use" data-kind="${esc(a.o.kind)}">${esc(a.o.name)}</button>`).join('')}${shares.length > 10 ? ' …' : ''}</span></div>`;
  }
  if (!others.length) h += '<p class="muted">Nothing in its family, or in the families beside it on the map, reads like it.</p>';
  else {
    h += others.slice(0, 10).map(a => `<div class="alike ${a.same ? 'same' : a.score >= ALIKE ? 'hit' : a.score >= 0.6 ? 'near' : ''}">` +
      `${pinSVG(a.o.symbol, colourOf(a.o), 1)}<button data-kind="${esc(a.o.kind)}">${esc(a.o.name)}</button>` +
      `<span class="muted">${esc(famOf(a.o).name)}${a.same ? ' · the same drawing' : ''}</span><span class="score">${a.score.toFixed(2)}</span></div>`).join('');
  }
  h += `<p class="muted" style="margin:8px 0 0">An estimate of KindsTest's measure: from ${ALIKE.toFixed(2)} two read alike. The test run has the last word.</p>`;
  $('#alikeCard').innerHTML = h;
}

function section(title, body) { return `<div class="ctx-label">${esc(title)}</div>${body}`; }

function renderUses(k, id, raw) {
  const r = raw ? {key: id, d: S.data.drawings[id]} : resolve(id);
  if (!r) { $('#usesCard').innerHTML = "<h2>Where it's used</h2><p class=\"muted\">Atlas has no drawing by that name.</p>"; return; }
  const key = r.key, d = r.d;
  const names = raw ? [id] : [key, ...Object.keys(S.data.aliases).filter(a => S.data.aliases[a] === key)];
  const kinds = raw ? S.data.kinds.filter(o => o.symbol === id) : S.wearers.get(key) || [];
  const cuis = raw ? [] : Object.entries(S.data.cuisine).filter(([, v]) => resolve(v[0])?.key === key).map(([c]) => c);
  const code = names.flatMap(n => (S.data.code[n] || []).map(w => [n, w]));
  const boards = S.data.boards[key] || [];
  let h = `<h2>Where it's used${raw ? '' : ` <button class="ghost linkish" data-sym="${esc(key)}" title="Everything that shows it, and a swap for every kind that wears it">Its own page ›</button>`}</h2>` +
    `<div class="muted" style="margin-bottom:4px">${esc(setLabel(setOf(d)))} · <span class="mono">${esc(key)}</span>` +
    `${names.length > 1 ? ` · also drawn for ${names.slice(1, 9).map(n => `<span class="mono">${esc(n)}</span>`).join(', ')}${names.length > 9 ? ' …' : ''}` : ''}</div>`;
  h += section(raw ? 'Kinds that name it (the app shows them ours)' : 'Kinds that wear it', kinds.length ? `<div class="uses">${kinds.map(o => `<button class="use" data-kind="${esc(o.kind)}"><span class="d" style="background:${colourOf(o)}"></span>${esc(o.name)}</button>`).join('')}</div>` : '<span class="muted">none</span>');
  if (cuis.length) h += section('Cuisine chips', `<div class="uses">${cuis.map(c => `<span class="use">${esc(c)}</span>`).join('')}</div>`);
  if (boards.length) h += section('On the boards', `<div class="uses">${boards.map(b => `<span class="use">${esc(b)}</span>`).join('')}</div>`);
  if (code.length) h += section('In the app\'s code', `<div class="code">${code.slice(0, 24).map(([n, w]) => esc(w) + (n !== key ? ` <span>(${esc(n)})</span>` : '')).join('<br>')}${code.length > 24 ? `<br>… and ${code.length - 24} more` : ''}</div>`);
  if (raw) h += `<p class="muted">The set's own drawing, behind ours: the app draws <span class="mono">${esc(d.aliasedTo)}</span> for this id. Here to compare.</p>`;
  if (d.extra && !d.preview && !d.imported) h += '<p class="muted">Not in the app yet: a swap to it brings it in from the source cache.</p>';
  if (d.imported) h += `<p class="muted">${d.blocked ? `From an imported set, not in the app yet: ${esc(d.blocked)}. Compare it here.` : 'From an imported set: a swap to it brings it into the app and credits the set under About &amp; licences.'}</p>`;
  if (d.coloured) h += '<p class="muted">A coloured picture: the app draws it in its own colours, not white on a pin.</p>';
  $('#usesCard').innerHTML = h;
}

function blockedWhy(id, raw) {
  if (raw) return 'The app draws ours for this id: compare only.';
  const d = resolve(id)?.d;
  if (!d) return 'No such drawing.';
  if (d.blocked) return `Not in the app yet: ${d.blocked}.`;
  if (d.preview) return 'Lucide reaches the app through the interface set: compare only.';
  return '';
}

function renderActions(k) {
  const p = S.preview;
  let what, buttons;
  if (p) {
    const blocked = blockedWhy(p, S.previewRaw);
    what = `Trying <b>${esc(label(p, S.previewRaw))}</b>${blocked ? ` · <span class="err">${esc(blocked)}</span>` : ''}`;
    buttons = `<button id="useBtn"${blocked ? ' disabled' : ''}>Use this</button><button class="ghost" id="cancelBtn">Keep the current one</button>`;
  } else {
    what = `<span class="mono">${esc(k.symbol)}</span>${k.note ? ` <span class="muted">· ${esc(k.note)}</span>` : ''}`;
    buttons = `<button class="ghost" id="keepBtn" title="O">Right as it is</button><button class="ghost" id="redoBtn" title="R">Wants a better one</button>`;
  }
  $('#actions').innerHTML = `<div class="what">${what}</div>${buttons}<label class="check"><input type="checkbox" id="advance"${S.advance ? ' checked' : ''}> then the next kind</label>` +
    `<div class="notewrap"><textarea id="note" rows="1" placeholder="Why (optional): it goes into the kind's note in kinds.tsv">${esc(S.note)}</textarea>` +
    '<span class="kbd"><kbd>↑</kbd><kbd>↓</kbd> kinds · <kbd>←</kbd><kbd>→</kbd> drawings · <kbd>Enter</kbd> use · <kbd>Esc</kbd> back · <kbd>O</kbd> right · <kbd>R</kbd> redo · <kbd>U</kbd> undo · <kbd>/</kbd> find</span></div>';
}

function renderCandidates() {
  const sets = new Map();
  let total = 0;
  for (const d of Object.values(S.data.drawings)) { const s = setOf(d); sets.set(s, (sets.get(s) || 0) + 1); if (s !== 'behind') total++; }
  const order = [...SET_ORDER, ...S.data.sets.map(x => x.prefix), 'behind'];
  for (const s of sets.keys()) if (!order.includes(s)) order.splice(order.length - 1, 0, s);
  if (S.candSet !== 'all' && !sets.get(S.candSet)) S.candSet = 'all';
  let h = '<div class="cand-bar"><div class="seg" id="candTabs">' +
    `<button data-tab="suggested" aria-pressed="${S.candTab === 'suggested'}">Suggested</button><button data-tab="all" aria-pressed="${S.candTab === 'all'}">Every drawing</button></div>` +
    `<input id="candSearch" type="search" placeholder="Search: cup, bread, temaki-, place." value="${esc(S.candQuery)}" autocomplete="off"><span class="muted" id="candCount"></span></div>`;
  if (S.candTab === 'all') {
    h += `<div class="setchips" id="setChips" style="margin-bottom:10px"><button data-set="all" aria-pressed="${S.candSet === 'all'}">All <span class="n">${total}</span></button>` +
      order.filter(s => sets.get(s)).map(s => `<button data-set="${esc(s)}" aria-pressed="${S.candSet === s}">${esc(setLabel(s))} <span class="n">${sets.get(s)}</span></button>`).join('') + '</div>';
  }
  h += '<div class="cands" id="cands"></div><button class="ghost more" id="moreBtn" hidden>Show more</button>';
  $('#candCard').innerHTML = h;
  renderCandGrid();
}

function renderCandGrid() {
  S.candList = candidates();
  S.candShown = 0;
  S.candIndex = S.candList.findIndex(c => c.id === S.preview && c.raw === S.previewRaw);
  $('#cands').innerHTML = '';
  $('#candCount').textContent = `${S.candList.length} ${S.candList.length === 1 ? 'drawing' : 'drawings'}`;
  moreCandidates();
}

function candHTML(c, k, colour, nowKey) {
  const d = S.data.drawings[c.id];
  const tag = c.raw ? '<span class="tag">behind</span>' : d.imported ? '<span class="tag imp">imported</span>' : d.preview ? '<span class="tag">compare</span>'
    : d.extra ? '<span class="tag new">new</span>' : d.coloured ? '<span class="tag">colour</span>' : '';
  const n = c.raw ? 0 : (S.wearers.get(c.id) || []).length;
  const isNow = !c.raw && c.id === nowKey;
  return `<button class="cand${isNow ? ' now' : ''}" data-id="${esc(c.id)}" data-raw="${c.raw ? 1 : ''}" aria-selected="${S.preview === c.id && S.previewRaw === c.raw}"` +
    ` title="${esc(c.id)}${n ? ' · worn by ' + n : ''}${c.why.length ? ' · ' + esc(c.why.join(', ')) : ''}">${tag}${tileSVG(c.id, colour, 1.2, c.raw)}` +
    `<span class="cid">${esc(c.id)}${n ? ' ×' + n : ''}</span>${c.why.length ? `<span class="why">${esc(c.why[0])}</span>` : ''}</button>`;
}

function moreCandidates() {
  const k = S.byKind.get(S.sel), colour = colourOf(k), nowKey = resolve(k.symbol)?.key;
  const next = S.candList.slice(S.candShown, S.candShown + 180);
  $('#cands').insertAdjacentHTML('beforeend', next.map(c => candHTML(c, k, colour, nowKey)).join(''));
  S.candShown += next.length;
  $('#moreBtn').hidden = S.candShown >= S.candList.length;
}

function setPreview(id, raw = false) {
  const k = S.byKind.get(S.sel);
  if (!k) return;
  const same = !raw && id && resolve(id)?.key === resolve(k.symbol)?.key;
  S.preview = id && !same ? id : null;
  S.previewRaw = !!(S.preview && raw);
  for (const el of document.querySelectorAll('.cand[aria-selected="true"]')) el.setAttribute('aria-selected', 'false');
  if (S.preview) {
    const el = document.querySelector(`.cand[data-id="${CSS.escape(id)}"][data-raw="${raw ? 1 : ''}"]`);
    if (el) { el.setAttribute('aria-selected', 'true'); el.scrollIntoView({block: 'nearest'}); }
  }
  renderShown();
}

/** The arrows walk the drawings: a cursor of their own, as the first one is often the current drawing (no preview). */
function moveCand(n) {
  const list = S.candList;
  if (!list.length) return;
  const i = S.candIndex < 0 ? (n > 0 ? 0 : list.length - 1) : Math.max(0, Math.min(list.length - 1, S.candIndex + n));
  while (i >= S.candShown && S.candShown < list.length) moreCandidates();
  S.candIndex = i;
  setPreview(list[i].id, list[i].raw);
  const el = document.querySelectorAll('#cands .cand')[i];
  if (el) el.scrollIntoView({block: 'nearest'});
}

/* -------------------------------------------------------------------------------------------- all symbols (list) */
/* The simple way through: every kind under its family, the drawing it wears now, and every other drawing Atlas has
   for it beside it. A click on a drawing opens its own page (where it's used, and a swap); the ✓ on it, or Enter,
   and the kind wears it at once; the message that follows has Undo. */

const SHORT = {board: 'ours', ui: 'interface', atlas: 'Atlas', temaki: 'Temaki', maki: 'Maki', pinhead: 'Pinhead'};
const ALTS_SHOWN = 14;
let fillTimer = null, findTimer = null;

function shortSet(d) { return SHORT[d.set] || d.set; }

/** What [k] can wear instead, the likeliest first, all of it drawable by the app today. */
function alternatives(k) {
  const nowKey = resolve(k.symbol)?.key;
  return suggestions(k).filter(c => !c.raw && c.id !== nowKey && !blockedWhy(c.id, false));
}

/** How many kinds wear [id] now, as a caption's tail: " ×3". */
function wornTail(id) { const n = (S.wearers.get(resolve(id)?.key) || []).length; return n ? ' ×' + n : ''; }

/** A drawing [k] could wear: a click opens its own page, the ✓ (shown on hover) puts it on [k] at once. */
function altHTML(id, colour, why, k) {
  const d = resolve(id)?.d;
  if (!d) return '';
  const n = (S.wearers.get(resolve(id).key) || []).length;
  const note = d.extra ? ' · not in the app yet: choosing it brings it in' : '';
  return `<div class="alt" data-id="${esc(id)}"><button type="button" class="alt-go" title="${esc(id)}${why ? ' · ' + esc(why) : ''}` +
    `${n ? ` · worn by ${n} ${n === 1 ? 'kind' : 'kinds'}` : ''}${note} · click: where it's used">${tileSVG(id, colour, 1.2)}` +
    `<span>${esc(shortSet(d))}${wornTail(id)}</span></button>` +
    `<button type="button" class="alt-use" title="Use it for ${esc(k.name)}" aria-label="Use it for ${esc(k.name)}">✓</button></div>`;
}

function rowHTML(k) {
  const d = resolve(k.symbol)?.d;
  return `<div class="row${S.focus === k.kind ? ' focus' : ''}" data-kind="${esc(k.kind)}">` +
    `<div class="who"><b>${esc(k.name)}</b><span>${esc(k.kind)}</span></div>` +
    `<button type="button" class="cur" title="now: ${esc(k.symbol)} · click: where it's used">${tileSVG(k.symbol, colourOf(k), 1.4)}` +
    `<span>now${d ? ' · ' + esc(shortSet(d)) : ''}${wornTail(k.symbol).replace(/^ ×1$/, '')}</span></button>` +
    '<div class="alts"></div>' +
    `<button class="ghost open" data-open="${esc(k.kind)}" title="See it as a pin, a row and a chip, on the map, light and dark">Preview ›</button></div>`;
}

function fillRow(row) {
  const k = S.byKind.get(row.dataset.kind);
  if (!k) return;
  const found = alternatives(k), open = S.expanded.has(k.kind), colour = colourOf(k);
  const firm = found.filter(c => !c.weak), weak = found.filter(c => c.weak), all = firm.concat(weak);
  const shown = open ? all : firm.slice(0, ALTS_SHOWN);
  let h = shown.map(c => altHTML(c.id, colour, c.why[0], k)).join('');
  if (!all.length) h += '<span class="muted none">Nothing else has its name.</span>';
  if (!open) h += `<button class="alt-more" data-more="1">${all.length > shown.length ? `+${all.length - shown.length} more` : 'Find another'}</button>`;
  else h += '<div class="alt-find"><input type="search" class="find-any" placeholder="Find any other drawing: cup, bread, tent…" autocomplete="off">' +
    '<span class="muted"></span></div><div class="found"></div>';
  row.querySelector('.alts').innerHTML = h;
  row.dataset.filled = '1';
}

function findAny(row, query) {
  const k = S.byKind.get(row.dataset.kind), colour = colourOf(k), nowKey = resolve(k.symbol)?.key, q = query.trim().toLowerCase();
  const out = [];
  if (q.length >= 2) {
    for (const [id, d] of Object.entries(S.data.drawings)) {
      if (d.aliasedTo || d.preview || d.blocked || id === nowKey) continue;
      if (searchHit(id, d, q)) out.push(id);
      if (out.length >= 90) break;
    }
  }
  row.querySelector('.found').innerHTML = out.map(id => altHTML(id, colour, '', k)).join('');
  row.querySelector('.alt-find .muted').textContent = q.length < 2 ? '' : out.length ? `${out.length}${out.length >= 90 ? '+' : ''} found` : 'nothing found';
}

function renderList() {
  const byFam = new Map();
  for (const k of visibleKinds()) { if (!byFam.has(k.family)) byFam.set(k.family, []); byFam.get(k.family).push(k); }
  let nav = '', h = '';
  for (const f of S.data.families) {
    const list = byFam.get(f.name);
    if (!list) continue;
    nav += `<button data-fam="${esc(f.id)}">${discSVG(f.symbol, f.colour, 22)}${esc(f.name)}<span class="n">${list.length}</span></button>`;
    h += `<div class="fam-anchor" id="fam-${esc(f.id)}"></div>` +
      `<h2 class="fam-title">${discSVG(f.symbol, f.colour, 28)}${esc(f.name)}<span class="n">${list.length}</span></h2>` + list.map(rowHTML).join('');
  }
  $('#famNav').innerHTML = nav;
  $('#list').innerHTML = h || '<p class="empty">No kind matches.</p>';
  setNavHeight();
  fillNear();
}

/** The rows in sight and a screen or so either side get their drawings; the rest wait until they come near. */
function fillNear() {
  const lv = $('#listView');
  if (lv.hidden) return;
  const top = lv.scrollTop - 700, bottom = lv.scrollTop + lv.clientHeight + 1400;
  for (const row of document.querySelectorAll('#list .row:not([data-filled])')) {
    const y = row.offsetTop;
    if (y > bottom) break;
    if (y + row.offsetHeight >= top) fillRow(row);
  }
}

function setNavHeight() { document.documentElement.style.setProperty('--navh', $('#famNav').offsetHeight + 'px'); }

/** The rows of the kinds whose lines changed, drawn again where they stand. */
function refreshRows(kinds) {
  for (const kind of kinds) {
    const row = rowOf(kind), k = S.byKind.get(kind);
    if (!row || !k) continue;
    const t = document.createElement('div');
    t.innerHTML = rowHTML(k);
    const fresh = t.firstElementChild;
    row.replaceWith(fresh);
    fillRow(fresh);
  }
}

function rowOf(kind) { return kind ? document.querySelector(`#list .row[data-kind="${CSS.escape(kind)}"]`) : null; }

function focusRow(kind, scroll = true) {
  const row = rowOf(kind);
  if (!row) return;
  for (const r of document.querySelectorAll('#list .row.focus')) r.classList.remove('focus');
  for (const a of document.querySelectorAll('#list .alt.cursor')) a.classList.remove('cursor');
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#list .alt')) document.activeElement.blur();
  S.focus = kind;
  S.altIndex = -1;
  row.classList.add('focus');
  if (!row.dataset.filled) fillRow(row);
  if (scroll) { row.scrollIntoView({block: 'nearest'}); fillNear(); row.scrollIntoView({block: 'nearest'}); }
}

function stepRow(n) {
  const rows = [...document.querySelectorAll('#list .row')];
  if (!rows.length) return;
  const i = rows.findIndex(r => r.dataset.kind === S.focus);
  focusRow(rows[i < 0 ? 0 : Math.max(0, Math.min(rows.length - 1, i + n))].dataset.kind);
}

function moveAlt(n) {
  const row = rowOf(S.focus);
  if (!row) { stepRow(0); return; }
  const alts = [...row.querySelectorAll('.alt')];
  if (!alts.length) return;
  S.altIndex = S.altIndex < 0 ? (n > 0 ? 0 : alts.length - 1) : Math.max(0, Math.min(alts.length - 1, S.altIndex + n));
  alts.forEach((a, i) => a.classList.toggle('cursor', i === S.altIndex));
  alts[S.altIndex].querySelector('.alt-go').focus({preventScroll: true});
  alts[S.altIndex].scrollIntoView({block: 'nearest'});
}

/** [kind] wears [id] from now on: its line in kinds.tsv, saying it was the owner's pick and what it wore before. */
async function choose(kind, id, thenNext) {
  const k = S.byKind.get(kind), r = resolve(id);
  if (!k || !r || blockedWhy(id, false) || r.key === resolve(k.symbol)?.key) return false;
  const symbol = r.d.set === 'board' ? r.key : id;
  const rows = [...document.querySelectorAll('#list .row')].map(x => x.dataset.kind), next = rows[rows.indexOf(kind) + 1];
  const ok = await write(k, {symbol, state: 'ok', note: `the owner's pick in the symbol manager, ${S.data.today}; was ${k.symbol}`},
    `${k.name} now wears ${symbol}`);
  if (ok && thenNext && next) focusRow(next);
  return ok;
}

function showDetail(kind) {
  if (S.view === 'list') S.listTop = $('#listView').scrollTop;
  S.view = 'detail';
  $('#listView').hidden = true;
  $('#symbolView').hidden = true;
  $('#detailView').hidden = false;
  $('#backBtn').hidden = false;
  $('#backBtn').textContent = '← All symbols';
  $('#importBtn').hidden = false;
  renderKinds();
  select(kind);
}

/** Back to the list, at [kind]'s row (the list keeps where it was scrolled to). */
function showList(kind = S.sel) {
  S.view = 'list';
  $('#detailView').hidden = true;
  $('#symbolView').hidden = true;
  $('#listView').hidden = false;
  $('#backBtn').hidden = true;
  $('#importBtn').hidden = true;
  renderList();
  $('#listView').scrollTop = S.listTop;
  fillNear();
  focusRow(kind);
}

/* --------------------------------------------------------------------------------------------- a drawing's page */
/* One drawing on its own: everything in the app that shows it (the kinds that wear it, the family chips, the cuisine
   chips, the boards, the app's code), a swap for the kind you came from, and one for every kind that wears it. */

/** Where the app shows drawing [key]: the kinds that wear it, and what else names it. */
function usesOf(key) {
  const names = [key, ...Object.keys(S.data.aliases).filter(a => S.data.aliases[a] === key)];
  return {
    names,
    kinds: S.wearers.get(key) || [],
    families: S.data.families.filter(f => f.symbol && resolve(f.symbol)?.key === key),
    cuisines: Object.entries(S.data.cuisine).filter(([, v]) => resolve(Array.isArray(v) ? v[0] : v)?.key === key).map(([c]) => c),
    boards: S.data.boards[key] || [],
    code: names.flatMap(n => (S.data.code[n] || []).map(w => [n, w])),
  };
}

/** What could take [key]'s place for the kinds that wear it: what they wore before, their presets' own, the drawings
    named like it or like them, the likeliest first. */
function drawingSuggestions(key) {
  const wearers = S.wearers.get(key) || [], out = [], at = new Set([key]);
  const add = (id, why, weak = false) => {
    const r = resolve(id);
    if (!r || at.has(r.key) || blockedWhy(r.key, false)) return;
    at.add(r.key);
    out.push({id: r.key, why: [why], weak});
  };
  for (const k of wearers) { const was = /\bwas ([a-z][\w.-]*\w)/i.exec(k.note || ''); if (was) add(was[1], `${k.name} wore it before`); }
  for (const k of wearers) if (k.preset) add(k.preset, `the preset's own: ${k.name}`);
  // its own name's words (not the board cards it stands on: "Café keeps bags" is about the bags) and its wearers'
  const toks = new Set([...drawingTokens(key)].filter(([, l]) => !l).map(([w]) => w));
  for (const k of wearers.slice(0, 8)) for (const w of kindTokens(k)) toks.add(w);
  const scored = rankDrawings(toks), best = scored.length ? scored[0].s : 0;
  for (const x of scored.filter(x => x.s >= best * 0.5).slice(0, 30)) add(x.id, whyNamed(x));
  for (const x of scored.filter(x => x.s >= best * 0.25).slice(0, 60)) add(x.id, whyNamed(x), true);
  return out;
}

function openSymbol(id, fromKind) {
  const r = resolve(id);
  if (!r) return;
  if (S.view !== 'symbol') {
    if (S.view === 'list') S.listTop = $('#listView').scrollTop;
    S.symBack = {view: S.view, kind: S.view === 'list' ? (fromKind || S.focus) : S.sel};
  }
  S.sym = {key: r.key, from: fromKind && S.byKind.has(fromKind) ? fromKind : null};
  S.symPick = null;
  S.symOff = new Set();
  S.symQuery = '';
  S.view = 'symbol';
  $('#listView').hidden = true;
  $('#detailView').hidden = true;
  $('#symbolView').hidden = false;
  $('#backBtn').hidden = false;
  $('#backBtn').textContent = S.symBack.view === 'detail' ? '← Back to the preview' : '← All symbols';
  $('#importBtn').hidden = true;
  renderSymbol();
  $('#symbolView').scrollTop = 0;
}

/** Leaves a drawing's page for where it was opened from, or for the list at [kind]. */
function leaveSymbol(kind) {
  const b = kind ? {view: 'list', kind} : S.symBack || {view: 'list', kind: S.focus};
  S.sym = null;
  S.symPick = null;
  if (b.view === 'detail' && S.byKind.has(b.kind)) showDetail(b.kind);
  else showList(b.kind);
}

function renderSymbol() {
  const el = $('#sym');
  const r = S.sym && resolve(S.sym.key);
  if (!r) { el.innerHTML = '<p class="empty">Atlas has no drawing by that name any more.</p>'; return; }
  const key = r.key, d = r.d, u = usesOf(key);
  if (S.sym.from && !S.byKind.has(S.sym.from)) S.sym.from = null;
  const from = S.sym.from && S.byKind.get(S.sym.from);
  const colour = from ? colourOf(from) : u.kinds[0] ? colourOf(u.kinds[0]) : u.families[0] ? u.families[0].colour : '#5A6478';
  const blocked = blockedWhy(key, false), fromNow = from && resolve(from.symbol)?.key === key;
  const name = from ? from.name : u.kinds[0] ? u.kinds[0].name : u.boards[0] ? u.boards[0].split(': ').pop() : key;
  const notes = [];
  if (d.extra) notes.push(d.imported ? 'Not in the app yet: the first kind to wear it brings it in from its imported set, which About & licences then credits.'
    : 'Not in the app yet: the first kind to wear it brings it in from the source cache.');
  if (d.coloured) notes.push('A coloured picture: the app draws it in its own colours, not white on a pin.');
  if (blocked) notes.push(blocked);

  let use = '';
  if (from && fromNow) use = `<div class="sym-use"><span class="muted"><b>${esc(from.name)}</b> wears it now.</span></div>`;
  else if (from) {
    use = `<div class="sym-use"><div class="swapline">${tileSVG(from.symbol, colour, 1.1)}<span class="arrow">→</span>${tileSVG(key, colour, 1.1)}</div>` +
      `<button id="symUse"${blocked ? ' disabled' : ''} title="Enter">Use it for ${esc(from.name)}</button>` +
      `<span class="muted">${esc(from.name)} wears <button class="ghost linkish" data-sym="${esc(from.symbol)}">${esc(resolve(from.symbol)?.key || from.symbol)}</button> now</span></div>`;
  }
  let h = `<section class="card sym-head"><div class="sym-big">${bigIcon(key, false, 96)}</div><div class="sym-info">` +
    `<h1>${esc(key)}</h1><div class="sub">${esc(setLabel(setOf(d)))}</div>` +
    `<div class="ctx-row">${[1, 2].map(s => `<figure>${pinSVG(key, colour, s)}<figcaption>pin ${s}×</figcaption></figure>`).join('')}` +
    `<figure>${tileSVG(key, colour, 1)}<figcaption>row</figcaption></figure>` +
    `<figure><span class="chip">${discSVG(key, colour, 26)}${esc(name)}</span><figcaption>chip</figcaption></figure></div>` +
    (notes.length ? `<p class="muted">${notes.map(esc).join(' ')}</p>` : '') + `</div>${use}</section>`;

  // the kinds that wear it, each with a tick for the swap below
  const n = u.kinds.length;
  h += `<section class="card"><h2>${n ? `Worn by ${n} ${n === 1 ? 'kind' : 'kinds'}` : 'No kind wears it'}</h2>`;
  if (n) {
    h += '<div class="wearers">' + u.kinds.map(k => `<div class="wearer${k.kind === S.sym.from ? ' from' : ''}">` +
      `<input type="checkbox" data-wear="${esc(k.kind)}" aria-label="Swap it for ${esc(k.name)}"${S.symOff.has(k.kind) ? '' : ' checked'}>` +
      `${tileSVG(k.symbol, colourOf(k), 0.8)}<div class="wn"><b>${esc(k.name)}</b> <span class="mono">${esc(k.kind)}</span>` +
      `${k.symbol !== key ? ` <span class="muted">(as ${esc(k.symbol)})</span>` : ''}<span class="note">${esc(famOf(k).name)}${k.note ? ' · ' + esc(k.note) : ''}</span></div>` +
      `<button class="ghost small" data-goto="${esc(k.kind)}">In the list ›</button></div>`).join('') + '</div>';
  } else h += '<p class="muted">Nothing in kinds.tsv names it.</p>';
  h += '</section>';

  // everything else in the app that shows it
  let also = '';
  if (u.families.length) also += section('The family chip', `<div class="uses">${u.families.map(f => `<span class="use"><span class="d" style="background:${f.colour}"></span>${esc(f.name)}</span>`).join('')}</div>`);
  if (u.cuisines.length) also += section('Cuisine chips', `<div class="uses">${u.cuisines.map(c => `<span class="use">${esc(c)}</span>`).join('')}</div>`);
  if (u.boards.length) also += section('On the boards', `<div class="uses">${u.boards.map(b => `<span class="use">${esc(b)}</span>`).join('')}</div>`);
  if (u.code.length) also += section("In the app's code", `<div class="code">${u.code.slice(0, 40).map(([nm, w]) => esc(w) + (nm !== key ? ` <span>(${esc(nm)})</span>` : '')).join('<br>')}${u.code.length > 40 ? `<br>… and ${u.code.length - 40} more` : ''}</div>`);
  if (u.names.length > 1) also += section('The app also draws it for these ids', `<div class="uses">${u.names.slice(1).map(x => `<span class="use mono">${esc(x)}</span>`).join('')}</div>`);
  h += `<section class="card"><h2>Also in the app</h2>${also ? also + '<p class="muted" style="margin:10px 0 0">These come from the app\'s own files, not from kinds.tsv: to change one, tell Claude.</p>'
    : `<p class="muted">${n ? 'Nowhere else: only the kinds above show it.' : 'Nowhere: the app does not show it today.'}</p>`}</section>`;

  // the swap for every kind that wears it
  if (n) {
    h += `<section class="card"><h2>Replace it</h2><p class="muted" id="symCount" style="margin:0 0 8px"></p>` +
      `<div class="picks" id="symPicks"></div><div class="alt-find"><input id="symFind" type="search" value="${esc(S.symQuery)}" ` +
      'placeholder="Find any other drawing: cup, bread, tent…" autocomplete="off"><span class="muted" id="symFindN"></span></div>' +
      '<div class="picks" id="symFound"></div><div class="confirm" id="symConfirm" hidden></div></section>';
  }
  el.innerHTML = h;
  if (n) {
    $('#symPicks').innerHTML = drawingSuggestions(key).slice(0, 40).map(c => pickHTML(c.id, colour, c.why[0])).join('') ||
      '<span class="muted">Nothing else is named like it: find one below.</span>';
    symFind(S.symQuery);
    renderSymCount();
    renderSymConfirm();
  }
}

/** A drawing that could take the page's drawing's place: a click picks it, the swap still waits for a yes. */
function pickHTML(id, colour, why) {
  const d = resolve(id)?.d;
  if (!d) return '';
  return `<button type="button" class="pick${S.symPick === id ? ' picked' : ''}" data-pick="${esc(id)}" title="${esc(id)}${why ? ' · ' + esc(why) : ''}">` +
    `${tileSVG(id, colour, 1.2)}<span>${esc(shortSet(d))}${wornTail(id)}</span></button>`;
}

function symTargets() { return (S.wearers.get(S.sym.key) || []).filter(k => !S.symOff.has(k.kind)); }

function renderSymCount() {
  const el = $('#symCount');
  if (!el) return;
  const t = symTargets().length, n = (S.wearers.get(S.sym.key) || []).length;
  el.textContent = t ? `Pick the drawing that takes its place for ${t === n ? (n === 1 ? 'the kind' : n === 2 ? 'both kinds' : `all ${n} kinds`) : `the ${t} ticked ${t === 1 ? 'kind' : 'kinds'}`} above. Nothing changes until you say yes.`
    : 'Tick the kinds above it should change for.';
}

function symFind(query) {
  const box = $('#symFound');
  if (!box) return;
  const q = query.trim().toLowerCase(), out = [], colour = S.sym.from ? colourOf(S.byKind.get(S.sym.from)) : (S.wearers.get(S.sym.key) || [])[0] ? colourOf(S.wearers.get(S.sym.key)[0]) : '#5A6478';
  if (q.length >= 2) {
    for (const [id, d] of Object.entries(S.data.drawings)) {
      if (d.aliasedTo || d.preview || d.blocked || id === S.sym.key) continue;
      if (searchHit(id, d, q)) out.push(id);
      if (out.length >= 90) break;
    }
  }
  box.innerHTML = out.map(id => pickHTML(id, colour, '')).join('');
  $('#symFindN').textContent = q.length < 2 ? '' : out.length ? `${out.length}${out.length >= 90 ? '+' : ''} found` : 'nothing found';
}

function renderSymConfirm() {
  const box = $('#symConfirm');
  if (!box) return;
  for (const p of document.querySelectorAll('#sym .pick')) p.classList.toggle('picked', p.dataset.pick === S.symPick);
  const r = S.symPick && resolve(S.symPick);
  if (!r) { box.hidden = true; box.innerHTML = ''; return; }
  const key = S.sym.key, targets = symTargets(), already = (S.wearers.get(r.key) || []);
  const colour = targets[0] ? colourOf(targets[0]) : '#5A6478';
  const who = targets.slice(0, 6).map(k => esc(k.name)).join(', ') + (targets.length > 6 ? ` and ${targets.length - 6} more` : '');
  box.hidden = false;
  box.innerHTML = `<div class="swapline">${tileSVG(key, colour, 1.1)}<span class="arrow">→</span>${tileSVG(r.key, colour, 1.1)}</div>` +
    `<div class="what">${targets.length ? `<b>${esc(r.key)}</b> instead of <b>${esc(key)}</b> for ${who}.` : 'Tick at least one kind above.'}` +
    (already.length ? `<br><span class="warnline">${esc(r.key)} is worn by ${already.length} ${already.length === 1 ? 'kind' : 'kinds'} already: ` +
      `${already.slice(0, 5).map(k => esc(k.name)).join(', ')}${already.length > 5 ? ' …' : ''}. They would share it.</span>` : '') + '</div>' +
    `<button id="symGo"${targets.length ? '' : ' disabled'}>Replace for ${targets.length} ${targets.length === 1 ? 'kind' : 'kinds'}</button>` +
    `<button class="ghost" id="symCancel">Cancel</button><button class="ghost linkish" data-sym="${esc(r.key)}">Its own page ›</button>`;
}

/** The page's drawing on the kind it was opened from. */
async function useFromSymbol() {
  const from = S.sym && S.sym.from && S.byKind.get(S.sym.from);
  if (!from || blockedWhy(S.sym.key, false) || resolve(from.symbol)?.key === S.sym.key) return;
  const kind = from.kind;
  const ok = await choose(kind, S.sym.key, false);
  if (ok) leaveSymbol(S.symBack && S.symBack.view === 'detail' ? undefined : kind);
}

/** The picked drawing on every ticked kind that wears the page's drawing: one write, one undo. */
async function replaceEverywhere() {
  const r = S.symPick && resolve(S.symPick);
  const targets = symTargets();
  if (!r || !targets.length || blockedWhy(r.key, false) || S.writing) return;
  const key = S.sym.key, symbol = r.d.set === 'board' ? r.key : S.symPick;
  const changes = targets.map(k => ({kind: k.kind, expect: k.line, symbol, state: 'ok',
    note: `the owner's pick in the symbol manager, ${S.data.today}; was ${k.symbol}`}));
  S.writing = true;
  S.lastWrite = Date.now();
  try {
    const res = await post('/api/kinds', {changes});
    if (res.status === 200) {
      S.symPick = null;
      await load();
      const group = res.group, n = changes.length;
      toast(`${n === 1 ? targets[0].name + ' wears' : n + ' kinds wear'} ${symbol} instead of ${key}` + (res.built ? ' · brought into symbols.json' : ''),
        'Undo', () => undoGroup(group));
      openSymbol(symbol, S.sym && S.sym.from);
    } else {
      const last = res.output ? ': ' + res.output.trim().split('\n').pop().slice(0, 160) : '';
      toast((res.message || res.error || 'That did not work') + last, null, null, true);
      if (res.error === 'changed') await load();
    }
  } catch (e) {
    toast('The server did not answer: ' + e.message, null, null, true);
  } finally {
    S.writing = false;
    S.lastWrite = Date.now();
  }
}

/* ------------------------------------------------------------------------------------------------------ writes */

function owned(text) { return `${text} (the owner, symbol manager, ${S.data.today})`; }

async function use() {
  const k = S.byKind.get(S.sel);
  if (!k || !S.preview || blockedWhy(S.preview, S.previewRaw)) return;
  const r = resolve(S.preview);
  const symbol = r.d.set === 'board' ? r.key : S.preview;
  const note = S.noteDirty && S.note.trim() ? owned(S.note.trim()) : `the owner's pick in the symbol manager, ${S.data.today}; was ${k.symbol}`;
  await write(k, {symbol, state: 'ok', note}, `${k.name} now wears ${symbol}`);
}

async function setState(state) {
  const k = S.byKind.get(S.sel);
  if (!k) return;
  let note = k.note;
  if (S.noteDirty && S.note.trim()) note = owned(S.note.trim());
  else if (state === 'redo' && k.state !== 'redo') note = `the owner wants a better one (symbol manager, ${S.data.today})${k.note ? '; ' + k.note : ''}`;
  else if (state === 'ok' && !k.note) note = `the owner: right as it is (symbol manager, ${S.data.today})`;
  if (state === k.state && note === k.note) { if (S.advance) step(1); return; }
  await write(k, {symbol: k.symbol, state, note}, `${k.name}: ${STATE_WORD[state].toLowerCase()}`);
}

async function write(k, change, done) {
  if (S.writing) return false;
  S.writing = true;
  S.lastWrite = Date.now();
  const vis = visibleKinds(), i = vis.findIndex(x => x.kind === k.kind);
  const after = vis.slice(i + 1).map(x => x.kind);
  try {
    const res = await post('/api/kind', {kind: k.kind, expect: k.line, ...change});
    if (res.status === 200) {
      S.note = '';
      S.noteDirty = false;
      S.preview = null;
      const entry = res.entry;
      await load();
      toast(done + (res.built ? ' · brought into symbols.json' : ''), 'Undo', () => undo(entry.id));
      if (S.view === 'detail' && S.advance) {
        const next = after.find(x => S.byKind.has(x) && matches(S.byKind.get(x)));
        if (next) select(next); else step(1);
      }
      return true;
    } else {
      const last = res.output ? ': ' + res.output.trim().split('\n').pop().slice(0, 160) : '';
      toast((res.message || res.error || 'That did not work') + last, null, null, true);
      if (res.output) console.warn(res.output);
      if (res.error === 'changed') await load();
    }
  } catch (e) {
    toast('The server did not answer: ' + e.message, null, null, true);
  } finally {
    S.writing = false;
    S.lastWrite = Date.now();
  }
  return false;
}

async function undo(id) {
  S.lastWrite = Date.now();
  const res = await post('/api/undo', {id});
  if (res.status === 200) { await load(); toast('Undone'); } else toast(res.message || res.error, null, null, true);
}

/** Every change of one swap made on a drawing's page, taken back together. */
async function undoGroup(group) {
  S.lastWrite = Date.now();
  const res = await post('/api/undo', {group});
  if (res.status === 200) {
    await load();
    toast(res.left && res.left.length ? `Undone, except ${res.left.length} that changed again since` : 'Undone');
  } else toast(res.message || res.error, null, null, true);
}

async function undoLast() {
  const h = await getJSON('/api/history');
  const undone = new Set(h.filter(e => e.undoes).map(e => e.undoes));
  const last = h.find(e => !e.undoes && !undone.has(e.id));
  if (!last) toast('Nothing to undo');
  else if (last.group) undoGroup(last.group);
  else undo(last.id);
}

/* ------------------------------------------------------------------------------------------- history and import */

async function showHistory() {
  const h = await getJSON('/api/history');
  const undone = new Set(h.filter(e => e.undoes).map(e => e.undoes));
  const col = (line, n) => (line || '').split('\t')[n] || '';
  $('#historyList').innerHTML = h.length ? '<div class="hist">' + h.map(e => {
    const k = S.byKind.get(e.kind), c = k ? colourOf(k) : '#5A6478';
    const change = col(e.before, 1) !== col(e.after, 1) ? `${esc(col(e.before, 1))} → ${esc(col(e.after, 1))}`
      : `${esc(STATE_WORD[col(e.before, 2)] || col(e.before, 2))} → ${esc(STATE_WORD[col(e.after, 2)] || col(e.after, 2))}`;
    return `${tileSVG(col(e.after, 1), c, 0.8)}<div><b>${esc(k ? k.name : e.kind)}</b> <span class="muted">${esc(e.at.replace('T', ' '))}${e.undoes ? ' · an undo' : ''}</span><br><span class="mono">${change}</span></div>` +
      (!e.undoes && !undone.has(e.id) ? `<button class="ghost" data-undo="${esc(e.id)}">Undo</button>` : '<span></span>');
  }).join('') + '</div>' : '<p class="muted">Nothing has changed here yet.</p>';
  $('#historyDlg').showModal();
}

let impFiles = [], impNotice = null;
async function filesFromDrop(dt) {
  const out = [];
  const walk = async (entry, depth) => {
    if (entry.isFile) {
      const f = await new Promise((res, rej) => entry.file(res, rej));
      f.atlasDepth = depth;
      out.push(f);
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      let batch;
      do {
        batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        for (const e of batch) await walk(e, depth + 1);
      } while (batch.length);
    }
  };
  const entries = [...dt.items].map(i => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
  if (entries.length) for (const e of entries) await walk(e, 0); else out.push(...dt.files);
  return out;
}

// The set's licence file, the one nearest the top of what was picked (LICENSE, LICENCE or COPYING, maybe .txt or .md):
// the notice a licence such as MIT asks to go with the drawings, which the import keeps beside the set.
function noticeOf(files) {
  const depth = f => f.atlasDepth ?? (f.webkitRelativePath || f.name).split('/').length;
  return files.filter(f => /^(licen[cs]e|copying)(\.(txt|md))?$/i.test(f.name) && f.size <= 64 * 1024)
    .sort((a, b) => depth(a) - depth(b))[0] || null;
}

async function takeFiles(list) {
  const svgs = [...list].filter(f => /\.svg$/i.test(f.name) && f.size <= 512 * 1024).slice(0, 6000);
  $('#impGo').disabled = true;
  impNotice = null;
  if (!svgs.length) { $('#impStatus').innerHTML = '<span class="err">No SVG files there.</span>'; return; }
  $('#impStatus').textContent = `Reading ${svgs.length} SVG files…`;
  const notice = noticeOf([...list]);
  impNotice = notice ? {name: notice.name, text: await notice.text()} : null;
  impFiles = await Promise.all(svgs.map(async f => ({name: f.name, text: await f.text()})));
  const res = await post('/api/import', {prefix: 'preview', files: impFiles, dry: true});
  if (res.status !== 200) { $('#impStatus').innerHTML = `<span class="err">${esc(res.error || 'They could not be read.')}</span>`; return; }
  const fails = res.failures || [];
  $('#impStatus').innerHTML = `${res.count} drawings read${fails.length ? `, <span class="err">${fails.length} could not be</span> (${esc(fails.slice(0, 4).map(f => f.name + ': ' + f.error).join('; '))}${fails.length > 4 ? ' …' : ''})` : ''}. ` +
    (impNotice ? `Its licence file (${esc(impNotice.name)}) goes with them. ` : '') +
    'Files with the same name overwrite each other: import one style of a set at a time.';
  $('#impPreview').innerHTML = Object.entries(res.drawings || {}).slice(0, 400).map(([id, d]) => {
    const m = markup(d, 'imp' + id);
    return `<svg width="34" height="34" viewBox="${m.vb}" style="color:#15171c" aria-label="${esc(id)}"><title>${esc(id)}</title>${m.html}</svg>`;
  }).join('');
  $('#impGo').disabled = !res.count;
}

async function importGo() {
  const prefix = $('#impPrefix').value.trim().toLowerCase();
  $('#impGo').disabled = true;
  const res = await post('/api/import', {prefix, name: $('#impName').value, licence: $('#impLicence').value, source: $('#impSource').value, files: impFiles, licenceText: impNotice ? impNotice.text : ''});
  if (res.status === 200) {
    $('#importDlg').close();
    await load();
    S.candTab = 'all';
    S.candSet = prefix;
    renderCandidates();
    const blocked = S.data.sets.find(x => x.prefix === prefix)?.blocked;
    toast(`Imported ${res.count} drawings as ${prefix}-…${res.licenceFile ? ', with its licence file' : ''}` +
      (blocked ? `. Not in the app yet: ${blocked}.` : ': a kind may wear them.'), null, null, !!blocked);
  } else {
    $('#impStatus').innerHTML = `<span class="err">${esc(res.error || 'The import did not work.')}</span>`;
    $('#impGo').disabled = false;
  }
}

/* -------------------------------------------------------------------------------------------------------- misc */

let toastTimer = null;
function toast(msg, action, fn, bad) {
  const el = $('#toast');
  el.innerHTML = `<span${bad ? ' style="color:#ffb4ae"' : ''}>${esc(msg)}</span>${action ? `<button type="button">${esc(action)}</button>` : ''}`;
  if (action) el.querySelector('button').onclick = () => { el.classList.remove('on'); fn(); };
  el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), action ? 8000 : 4000);
}

async function poll() {
  try {
    const v = JSON.stringify(await getJSON('/api/version'));
    $('#live').classList.remove('off');
    $('#live').textContent = 'live';
    if (S.version && v !== S.version && !S.writing) {
      const ours = Date.now() - S.lastWrite < 5000;
      const {changed, files} = await load();
      if (!ours) {
        const sel = S.byKind.get(S.view === 'list' ? S.focus : S.view === 'symbol' ? S.sym && S.sym.from : S.sel);
        const who = changed.length ? `: ${changed.slice(0, 4).join(', ')}${changed.length > 4 ? ` and ${changed.length - 4} more` : ''}` : '';
        toast(sel && changed.includes(sel.name) ? `${sel.name} changed in kinds.tsv while you looked: this is it now`
          : `Read again: ${files.join(', ') || 'the files'} changed${who}`);
      }
    }
  } catch (e) {
    $('#live').classList.add('off');
    $('#live').textContent = 'the server stopped';
  }
}

/** A filter or a search from a drawing's page: back in the view it was opened from, which then draws itself again. */
function offSymbol() {
  if (S.view !== 'symbol') return;
  const back = S.symBack && S.symBack.view === 'detail' ? 'detail' : 'list';
  S.sym = null;
  S.symPick = null;
  S.view = back;
  $('#symbolView').hidden = true;
  $(back === 'detail' ? '#detailView' : '#listView').hidden = false;
  $('#backBtn').hidden = back === 'list';
  $('#backBtn').textContent = '← All symbols';
  $('#importBtn').hidden = back === 'list';
}

function wire() {
  $('#kinds').addEventListener('click', e => { const b = e.target.closest('.krow'); if (b) select(b.dataset.kind, false); });
  $('#filters').addEventListener('click', e => {
    const b = e.target.closest('[data-filter]');
    if (!b) return;
    S.filter = b.dataset.filter;
    offSymbol();
    renderFilters();
    if (S.view === 'list') { renderList(); $('#listView').scrollTop = 0; return; }
    renderKinds();
    if (!visibleKinds().some(k => k.kind === S.sel)) step(0); else renderDetail();
  });
  let kt = null;
  const searchNow = value => {
    S.query = value.trim();
    offSymbol();
    if (S.view === 'list') { renderList(); $('#listView').scrollTop = 0; return; }
    renderKinds();
    const v = visibleKinds();
    if (v.length && !v.some(k => k.kind === S.sel)) select(v[0].kind);
  };
  $('#kindSearch').addEventListener('input', e => { clearTimeout(kt); kt = setTimeout(() => searchNow(e.target.value), 150); });
  $('#kindSearch').addEventListener('keydown', e => {
    if (e.key !== 'Enter') return;
    clearTimeout(kt);
    searchNow(e.target.value);
    if (S.view === 'list') { const first = $('#list .row'); if (first) focusRow(first.dataset.kind); }
    else { const v = visibleKinds(); if (v.length) select(v[0].kind); }
    e.target.blur();
  });

  $('#list').addEventListener('click', e => {
    const t = e.target, row = t.closest('.row');
    if (!row) return;
    const kind = row.dataset.kind, alt = t.closest('.alt');
    if (alt) {
      focusRow(kind, false);
      if (t.closest('.alt-use')) choose(kind, alt.dataset.id, false);
      else openSymbol(alt.dataset.id, kind);
      return;
    }
    if (t.closest('.cur')) { focusRow(kind, false); openSymbol(S.byKind.get(kind).symbol, kind); return; }
    if (t.closest('[data-more]')) {
      S.expanded.add(kind);
      fillRow(row);
      focusRow(kind, false);
      const input = row.querySelector('.find-any');
      if (input) input.focus();
      return;
    }
    if (t.closest('[data-open]')) { showDetail(kind); return; }
    if (!t.closest('input')) focusRow(kind, false);
  });
  $('#list').addEventListener('input', e => {
    if (!e.target.classList.contains('find-any')) return;
    const row = e.target.closest('.row'), value = e.target.value;
    clearTimeout(findTimer);
    findTimer = setTimeout(() => findAny(row, value), 150);
  });
  $('#famNav').addEventListener('click', e => {
    const b = e.target.closest('[data-fam]');
    const anchor = b && document.getElementById('fam-' + b.dataset.fam);
    if (anchor) { anchor.scrollIntoView({block: 'start'}); fillNear(); anchor.scrollIntoView({block: 'start'}); fillNear(); }
  });
  $('#listView').addEventListener('scroll', () => { clearTimeout(fillTimer); fillTimer = setTimeout(fillNear, 40); }, {passive: true});
  window.addEventListener('resize', () => { setNavHeight(); fillNear(); });
  $('#backBtn').onclick = () => (S.view === 'symbol' ? leaveSymbol() : showList());

  const sym = $('#sym');
  sym.addEventListener('click', e => {
    const t = e.target;
    const go = t.closest('[data-goto]');
    if (go) { leaveSymbol(go.dataset.goto); return; }
    const s = t.closest('[data-sym]');
    if (s) { openSymbol(s.dataset.sym, S.sym && S.sym.from); return; }
    const p = t.closest('[data-pick]');
    if (p) {
      S.symPick = S.symPick === p.dataset.pick ? null : p.dataset.pick;
      renderSymConfirm();
      if (S.symPick) $('#symConfirm').scrollIntoView({block: 'nearest'});
      return;
    }
    const id = (t.closest('button') || {}).id;
    if (id === 'symUse') useFromSymbol();
    else if (id === 'symGo') replaceEverywhere();
    else if (id === 'symCancel') { S.symPick = null; renderSymConfirm(); }
  });
  sym.addEventListener('change', e => {
    const c = e.target.closest('[data-wear]');
    if (!c) return;
    if (c.checked) S.symOff.delete(c.dataset.wear); else S.symOff.add(c.dataset.wear);
    renderSymCount();
    renderSymConfirm();
  });
  let symTimer = null;
  sym.addEventListener('input', e => {
    if (e.target.id !== 'symFind') return;
    S.symQuery = e.target.value;
    clearTimeout(symTimer);
    symTimer = setTimeout(() => symFind(S.symQuery), 150);
  });

  const detail = $('#detail');
  detail.addEventListener('click', e => {
    const t = e.target;
    const cand = t.closest('.cand');
    if (cand) {
      S.candIndex = [...document.querySelectorAll('#cands .cand')].indexOf(cand);
      setPreview(cand.dataset.id, !!cand.dataset.raw);
      return;
    }
    const symBtn = t.closest('[data-sym]');
    if (symBtn) { openSymbol(symBtn.dataset.sym, S.sel); return; }
    const kindBtn = t.closest('[data-kind]');
    if (kindBtn) { select(kindBtn.dataset.kind); return; }
    const st = t.closest('#stateSeg [data-state]');
    if (st) { setState(st.dataset.state); return; }
    const tab = t.closest('#candTabs [data-tab]');
    if (tab) { S.candTab = tab.dataset.tab; renderCandidates(); return; }
    const set = t.closest('#setChips [data-set]');
    if (set) { S.candSet = set.dataset.set; renderCandidates(); return; }
    const id = (t.closest('button') || {}).id;
    if (id === 'moreBtn') moreCandidates();
    else if (id === 'prevBtn') step(-1);
    else if (id === 'nextBtn') step(1);
    else if (id === 'useBtn') use();
    else if (id === 'cancelBtn') setPreview(null);
    else if (id === 'keepBtn') setState('ok');
    else if (id === 'redoBtn') setState('redo');
  });
  detail.addEventListener('dblclick', e => { const cand = e.target.closest('.cand'); if (cand) { setPreview(cand.dataset.id, !!cand.dataset.raw); use(); } });
  let ct = null;
  detail.addEventListener('input', e => {
    if (e.target.id === 'candSearch') { clearTimeout(ct); ct = setTimeout(() => { S.candQuery = e.target.value; renderCandGrid(); }, 140); }
    if (e.target.id === 'note') { S.note = e.target.value; S.noteDirty = true; }
  });
  detail.addEventListener('change', e => { if (e.target.id === 'advance') S.advance = e.target.checked; });

  document.addEventListener('keydown', e => {
    const t = e.target, typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
    if ($('#importDlg').open || $('#historyDlg').open) return;
    if (e.key === 'Escape') {
      if (typing) { t.blur(); return; }
      if (S.view === 'symbol') { e.preventDefault(); if (S.symPick) { S.symPick = null; renderSymConfirm(); } else leaveSymbol(); return; }
      if (S.view === 'detail') { e.preventDefault(); if (S.preview) setPreview(null); else showList(); return; }
      for (const a of document.querySelectorAll('#list .alt.cursor')) a.classList.remove('cursor');
      S.altIndex = -1;
      return;
    }
    if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
    const inAlt = t.closest ? t.closest('.alt') : null;
    if (e.key === 'Enter' && t.tagName === 'BUTTON' && !t.classList.contains('cand') && !inAlt) return;
    if (S.view === 'symbol') {
      switch (e.key) {
        case '/': e.preventDefault(); $('#kindSearch').focus(); break;
        case 'Enter': if (S.sym && S.sym.from && !S.symPick) { e.preventDefault(); useFromSymbol(); } break;
        case 'u': case 'U': undoLast(); break;
        default:
      }
      return;
    }
    if (S.view === 'list') {
      switch (e.key) {
        case '/': e.preventDefault(); $('#kindSearch').focus(); break;
        case 'ArrowDown': case 'j': e.preventDefault(); stepRow(1); break;
        case 'ArrowUp': case 'k': e.preventDefault(); stepRow(-1); break;
        case 'ArrowRight': e.preventDefault(); moveAlt(1); break;
        case 'ArrowLeft': e.preventDefault(); moveAlt(-1); break;
        case 'Enter': {
          const row = inAlt ? inAlt.closest('.row') : rowOf(S.focus);
          const a = inAlt || (row && row.querySelectorAll('.alt')[S.altIndex]);
          if (a && row) { e.preventDefault(); choose(row.dataset.kind, a.dataset.id, true); }
          break;
        }
        case 'u': case 'U': undoLast(); break;
        default:
      }
      return;
    }
    switch (e.key) {
      case '/': e.preventDefault(); $('#kindSearch').focus(); break;
      case 'ArrowDown': case 'j': e.preventDefault(); step(1); break;
      case 'ArrowUp': case 'k': e.preventDefault(); step(-1); break;
      case 'ArrowRight': e.preventDefault(); moveCand(1); break;
      case 'ArrowLeft': e.preventDefault(); moveCand(-1); break;
      case 'Enter': if (S.preview) { e.preventDefault(); use(); } break;
      case 'o': case 'O': setState('ok'); break;
      case 'r': case 'R': setState('redo'); break;
      case 'u': case 'U': undoLast(); break;
      default:
    }
  });

  $('#historyBtn').onclick = showHistory;
  $('#historyClose').onclick = () => $('#historyDlg').close();
  $('#historyList').addEventListener('click', async e => {
    const b = e.target.closest('[data-undo]');
    if (b) { await undo(b.dataset.undo); showHistory(); }
  });
  $('#importBtn').onclick = () => { $('#impStatus').textContent = ''; $('#impPreview').innerHTML = ''; $('#impGo').disabled = true; impFiles = []; impNotice = null; $('#importDlg').showModal(); };
  $('#impCancel').onclick = () => $('#importDlg').close();
  $('#impGo').onclick = importGo;
  $('#importFiles').onchange = e => takeFiles(e.target.files);
  $('#importFolder').onchange = e => takeFiles(e.target.files);
  const drop = $('#drop');
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', async e => { e.preventDefault(); drop.classList.remove('over'); takeFiles(await filesFromDrop(e.dataTransfer)); });
}

wire();
load().catch(e => { $('#list').innerHTML = `<p class="err">Could not read the symbols database: ${esc(e.message)}</p>`; console.error(e); });
setInterval(poll, 2500);
