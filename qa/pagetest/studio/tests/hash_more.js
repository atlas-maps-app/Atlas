/* #screen= after load (hashchange), an encoded name, a folder screen, a folded category, Escape closes, Show › No stars on */
await ready(); await sleep(300);
const out = {};
const f = Q('#focus');
location.hash = '#screen=' + encodeURIComponent('SanGimignano'); await sleep(500);
out.a = { open: !f.hidden, name: (f.querySelector('.fname') || {}).textContent, tab: QA('nav button').find(b => b.getAttribute('aria-current') === 'true').dataset.tab, link: (f.querySelector('a.open') || {}).href };
document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); f.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await sleep(300);
out.afterEsc = { open: !f.hidden, hash: location.hash };
if (!f.hidden) { f.querySelector('.focus-x').click(); await sleep(300); out.afterX = { open: !f.hidden, hash: location.hash }; }
/* a folder screen while its category is folded and Show › No stars is on */
const fold = Q('#pages .page[data-page="folder_map"] .fold'); if (fold) { fold.click(); await sleep(200); }
Q('#rated [data-v="unrated"]').click(); await sleep(200);
const fm = (window.__SCREENS.pages.find(p => p.id === 'folder_map') || { screens: [] }).screens[0].name; out.fm = fm;
location.hash = '#screen=' + fm; await sleep(500);
out.b = { open: !f.hidden, name: (f.querySelector('.fname') || {}).textContent, move: QA('#focus .move-btn').map(b => b.textContent) };
f.querySelector('.focus-x').click(); await sleep(300); out.bClosed = { open: !f.hidden, hash: location.hash, active: document.activeElement && (document.activeElement.className + '|' + document.activeElement.tagName) };
Q('#rated [data-v="all"]').click(); await sleep(200);
/* the same hash twice: after closing, setting it again opens again */
location.hash = '#screen=Main'; await sleep(400); out.c1 = !f.hidden; f.querySelector('.focus-x').click(); await sleep(300);
location.hash = '#screen=Main'; await sleep(400); out.c2 = !f.hidden;
/* arrows inside the large view go to the next screen; closing clears the hash */
f.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await sleep(300);
out.next = (f.querySelector('.fname') || {}).textContent; out.hashNext = location.hash;
f.querySelector('.focus-x').click(); await sleep(300); out.hashEnd = location.hash;
/* a #tab hash */
location.hash = '#inbox'; await sleep(300); out.tabHash = QA('nav button').find(b => b.getAttribute('aria-current') === 'true').dataset.tab;
out.errors = window.__errors;
return JSON.stringify(out);
