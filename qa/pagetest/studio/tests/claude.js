/* Claude's ratings (studio/claude) and Looks · Use (studio/dims): behind each card's ⋯, the large view, the Claude row, the views */
await ready(); await tabTo('screens'); await sleep(200);
const out = {};
const card = n => Q('#pages .shot[data-screen="' + n + '"]');
out.faceMain = QA('button, a, .rate, .seg', card('Main')).map(e => e.className || e.tagName);
out.foot = Q('.claudefoot') && Q('.claudefoot').innerText;
card('Main').querySelector('.more-btn').click(); await sleep(200);
const cm = card('Main').querySelector('.cardmore');
out.cmMain = cm && { who: QA('.seg.who button', cm).map(b => b.textContent + ':' + b.getAttribute('aria-pressed')), tier: !!cm.querySelector('.tier-sel'), dims: !!cm.querySelector('.dims'), text: cm.innerText.replace(/\n+/g, ' | ').slice(0, 300) };
/* switch to Claude's */
cm.querySelector('[data-who^="c:"]').click(); await sleep(200);
const cm2 = card('Main').querySelector('.cardmore');
out.cmMainClaude = cm2 && cm2.innerText.replace(/\n+/g, ' | ').slice(0, 400);
out.faceStarsWhenClaude = card('Main').querySelector('.starrow .rate') && card('Main').querySelector('.starrow .rate').getAttribute('aria-valuetext');
/* keep Claude's stars */
const keepStars = QA('.crow', cm2).find(r => /Stars/.test(r.textContent)); if (keepStars) { keepStars.querySelector('.keepc').click(); await sleep(1000); }
out.afterKeepStars = { mine: S().starMap().Main, writes: fmtW(takeW()) };
/* Keep mine on Search (only well/badly from Claude: no switch should show) */
card('Search').querySelector('.more-btn').click(); await sleep(200);
out.searchWho = QA('.seg.who', card('Search')).length;
/* the category: Claude's stars beside yours */
Q('#pages .page[data-page="map_home"] .page-h .more-btn').click(); await sleep(200);
out.cat = Q('#cm-map_home') && Q('#cm-map_home').innerText.replace(/\n+/g, ' | ');
/* Show Claude's */
const sc = Q('#show-claude'); out.showClaude = !!sc;
if (sc) { sc.click(); await sleep(300); out.afterShow = card('LivingLooks') && card('LivingLooks').innerText.replace(/\n+/g, ' | ').slice(0, 200); out.livingLooksFull = card('LivingLooks').classList.contains('full'); }
/* the Looks vs use view */
const v = Q('#view'); v.value = 'lookuse'; v.dispatchEvent(new Event('change')); await sleep(300);
out.lookuse = QA('#pages .page-h .t').map(t => t.textContent + ':' + (t.parentNode.querySelector('.count') || {}).textContent); out.pts = QA('#pages .plot.big .pt').length;
v.value = 'rank'; v.dispatchEvent(new Event('change')); await sleep(300);
out.rank = { tiers: QA('#pages .tierbox').length, rows: QA('#pages .rrow').length, firstRow: (Q('#pages .rrow') || {}).innerText };
v.value = 'pages'; v.dispatchEvent(new Event('change')); await sleep(300);
/* the large view of Main with Claude's */
card('Main').querySelector('.img').click(); await sleep(400);
out.focus = Q('#focus').innerText.replace(/\n+/g, ' | ').slice(0, 400);
out.bad = bad(); out.errors = window.__errors;
return JSON.stringify(out);
