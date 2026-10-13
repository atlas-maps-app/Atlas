const sleep = ms => new Promise(r => setTimeout(r, ms));
const Q = (s, r) => (r || document).querySelector(s);
const QA = (s, r) => [...(r || document).querySelectorAll(s)];
const over = () => document.documentElement.scrollWidth - document.documentElement.clientWidth;
const bad = () => { const t = document.body.innerText; const m = t.match(/\bnull\b|\bundefined\b|NaN/); return m ? JSON.stringify(t.slice(Math.max(0, m.index - 80), m.index + 40)) : null; };
async function tabTo(t) { document.querySelector('#bar .tab[data-tab="' + t + '"]').click(); await sleep(80); }
/* the Plan is ready when every collection it reads is in (allLoaded) and its numbers are drawn */
async function ready() { for (let i = 0; i < 200; i++) { if (state.loaded && (typeof allLoaded === 'function' ? allLoaded() && state.loaded.has('media') : state.loaded.size >= 10) && !document.getElementById('strip').hidden) break; await sleep(50); } await sleep(200); }
const stripText = () => QA('#strip .stat').map(b => b.querySelector('.n').textContent + ' ' + b.querySelector('.w').textContent + ' [' + b.title + ']');
async function openAll(root) {
  for (let pass = 0; pass < 6; pass++) {
    const closed = QA('button.fh[aria-expanded="false"], button.rt[aria-expanded="false"]', root || document.getElementById('t-' + state.tab));
    if (!closed.length) break;
    closed.forEach(b => b.click());
    await sleep(30);
  }
}
/* which elements stick out past the viewport */
function wideEls() { const vw = document.documentElement.clientWidth; return QA('body *').filter(e => { const r = e.getBoundingClientRect(); return r.width && r.right > vw + 0.5 && getComputedStyle(e).position !== 'fixed'; }).slice(0, 8).map(e => e.tagName + '.' + e.className + '#' + e.id + ' r=' + Math.round(e.getBoundingClientRect().right)); }
async function openId(id) { const b = document.getElementById('h-' + id); if (b && b.getAttribute('aria-expanded') !== 'true') { b.click(); await sleep(40); } return !!b; }
