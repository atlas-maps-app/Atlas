/* helpers every studio test gets (run.sh points the runner at this file through PAGETEST_PRELUDE) */
const sleep = ms => new Promise(r => setTimeout(r, ms));
const Q = (s, r) => (r || document).querySelector(s);
const QA = (s, r) => [...(r || document).querySelectorAll(s)];
const over = () => document.documentElement.scrollWidth - document.documentElement.clientWidth;
const bad = () => { const t = document.body.innerText; const m = t.match(/\bnull\b|\bundefined\b|NaN|\[object /); return m ? JSON.stringify(t.slice(Math.max(0, m.index - 80), m.index + 40)) : null; };
const S = () => window.__studio;
const takeW = () => window.__writes.splice(0);
const fmtW = w => w.map(x => x[0] + ' ' + x[1] + (x[2] ? ' ' + JSON.stringify(x[2]).slice(0, 160) : ''));
/* the studio is ready when its config and screens are in and the first cards are drawn */
async function ready() {
  for (let i = 0; i < 200; i++) { const s = S(); if (s && s.cfg() && document.querySelector('#pages .shot, #pages .page') && document.getElementById('status').className.includes('saved')) break; await sleep(50); }
  await sleep(250);
}
async function tabTo(t) { const b = document.querySelector('nav [data-tab="' + t + '"]'); if (!b) return false; b.click(); await sleep(150); return true; }
/* which elements stick out past the viewport */
function wideEls() { const vw = document.documentElement.clientWidth; return QA('body *').filter(e => { const r = e.getBoundingClientRect(); return r.width && r.right > vw + 0.5 && getComputedStyle(e).position !== 'fixed' && !e.closest('.mtabwrap, nav'); }).slice(0, 8).map(e => e.tagName + '.' + e.className + '#' + e.id + ' r=' + Math.round(e.getBoundingClientRect().right)); }
function type(el, v) { el.focus(); el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); }
function visible(el) { if (!el) return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'; }
/* a click on a point of an element, as pointer events (the star rows listen to pointerdown/up) */
function tapAt(el, fx, fy) { const r = el.getBoundingClientRect(), x = r.left + r.width * fx, y = r.top + r.height * (fy === undefined ? 0.5 : fy);
  const o = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerId: 1, button: 0, pointerType: 'mouse', isPrimary: true };
  el.dispatchEvent(new PointerEvent('pointerdown', o)); el.dispatchEvent(new PointerEvent('pointerup', o)); el.dispatchEvent(new MouseEvent('click', o)); }
