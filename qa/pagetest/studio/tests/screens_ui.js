/* G33 toolbar, G38 headings and cards, B5 the Engine group, B7 gold dots, the views */
await ready(); await tabTo('screens'); await sleep(200);
const tools = Q('#screens .tools');
const out = { toolbar: QA(':scope > input, :scope > select, :scope > .slide3, :scope > .legend', tools).map(e => e.id || e.className), views: QA('#view option').map(o => o.textContent), show: QA('#rated button').map(b => b.textContent), legend: Q('.legend').textContent, legendTitle: Q('.legend').title };
out.gone = ['only', 'f-tab', 'f-stars', 'f-tier', 'f-also', 's-cat', 's-in', 'cards-full', 'tools-more'].filter(id => document.getElementById(id));
const heads = QA('#pages .page[data-page] > .page-h');
out.head0 = QA('button, a, .count, .t', heads[0]).map(e => (e.className || e.tagName) + ':' + e.textContent.trim().slice(0, 24));
out.engine = QA('#pages .page[data-page^="eng_"]').map(p => p.dataset.page + ':' + p.querySelectorAll('.shot').length);
out.engineLink = (Q('#pages .page[data-page="eng_regions"] .shot a.open') || {}).href;
out.mainLink = (Q('#pages .page[data-page="map_home"] .shot a.open') || {}).href;
out.folders = QA('#pages .page.folder').map(p => p.dataset.page);
out.livingGone = !Q('[data-screen="Living"]') && !Q('[data-screen="LivingKit"]');
const card = Q('#pages .shot[data-screen="Search"]');
out.cardFace = QA('button, a, .rate', card).map(e => e.className || e.tagName);
/* category ⋯ */
heads[0].querySelector('.more-btn').click(); await sleep(200);
const cm = Q('#cm-map_home'); out.catmore = cm ? { stars: !!cm.querySelector('.cat-rate .rate'), all: (cm.querySelector('[data-all]') || {}).textContent, ask: (cm.querySelector('[data-ask]') || {}).textContent } : null;
out.claudeFoot = Q('.claudefoot') && Q('.claudefoot').textContent;
/* the views */
for (const v of ['stars', 'rank', 'lookuse', 'pages']) { const s = Q('#view'); s.value = v; s.dispatchEvent(new Event('change')); await sleep(250); out['view_' + v] = { groups: QA('#pages .page, #pages .tierbox').length, cards: QA('#pages .shot').length, bad: bad(), first: (Q('#pages .page-h .t, #pages .tier-name') || {}).textContent || (Q('#pages .tier-name') || {}).value }; }
/* Find */
type(Q('#find'), 'paris'); await sleep(250); out.find = { n: QA('#pages .shot').length, names: QA('#pages .shot').map(e => e.dataset.screen), count: Q('#find-count').textContent };
out.rankCount = Q('#rank-count').textContent + ' | ' + Q('#rank-count').title;
out.errors = window.__errors;
return JSON.stringify(out);
