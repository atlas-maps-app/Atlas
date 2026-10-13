/* words, more: Alt+arrows, editing a word (double-click; a held word stays held), adding a word that is there already, dropping an on word on an off word */
await ready(); await tabTo('words');
const out = {};
const P = () => S().promptFor(S().cfg().mixes[0]).words.join('|');
const btn = w => Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"] button.w');
const on0 = QA('#wordlist .chip:not(.off)').map(c => c.dataset.w);
/* Alt+→ on the first on word: it swaps with the second */
btn(on0[0]).focus(); btn(on0[0]).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', altKey: true, bubbles: true })); await sleep(250);
out.altRight = { set: S().cfg().set.slice(0, 3).map(x => x.w), focus: document.activeElement && document.activeElement.dataset.hk };
/* edit a word: double-click, type, Enter */
btn('rich').dispatchEvent(new MouseEvent('dblclick', { bubbles: true, detail: 2 })); await sleep(150);
const inp = Q('#wordlist input[data-field="wedit"]'); out.editing = !!inp;
if (inp) { inp.value = 'richer'; inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await sleep(250); }
out.afterEdit = { inSet: S().cfg().set.some(x => x.w === 'richer'), inPool: S().cfg().pool.includes('richer'), oldGone: !S().cfg().pool.includes('rich'), chip: !!btn('richer'), focus: document.activeElement && document.activeElement.dataset.hk };
/* a held word renamed stays held, and back */
const ren = async (from, to) => { btn(from).dispatchEvent(new MouseEvent('dblclick', { bubbles: true, detail: 2 })); await sleep(150); const i = Q('#wordlist input[data-field="wedit"]'); if (!i) return false; i.value = to; i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await sleep(250); return true; };
await ren('fun', 'funny');
out.heldRename = { poolHold: (S().cfg().poolHold || []).slice(), held: !!Q('#wordlist .chip.held[data-w="funny"]'), inPool: S().cfg().pool.includes('funny') };
await ren('funny', 'fun'); out.heldRename.back = (S().cfg().poolHold || []).slice();
/* renamed to another word's name (any case): no rename, that word gets the focus; nothing moves over (no two-in-one, no hold or on carried) */
const cfgJ = () => JSON.stringify(Object.assign({}, S().cfg(), { updatedAt: null })), cfgR = cfgJ();   /* updatedAt aside: the save of the renames before stamps it */
await ren('fun', 'Calm'); await sleep(100);
out.renameOnto = { same: cfgJ() === cfgR, focus: document.activeElement && document.activeElement.dataset.hk, chips: QA('#wordlist .chip').filter(c => /^(calm|fun)$/i.test(c.dataset.w)).map(c => c.dataset.w + (c.classList.contains('held') ? ' held' : '')) };
await ren('love', 'calm'); await sleep(100); out.renameOnto.onSame = cfgJ() === cfgR;
/* add a word that is already there, in other case */
type(Q('#add-word-w'), 'CALM'); Q('#add-word').requestSubmit(); await sleep(250);
out.addExisting = { calmOn: S().cfg().set.some(x => x.w.toLowerCase() === 'calm'), count: S().cfg().pool.filter(w => w.toLowerCase() === 'calm').length, chips: QA('#wordlist .chip').filter(c => c.dataset.w.toLowerCase() === 'calm').length };
/* add an on word again: does it move? */
const before = S().cfg().set.map(x => x.w).join('|');
type(Q('#add-word-w'), 'love'); Q('#add-word').requestSubmit(); await sleep(250);
out.addOnAgain = { moved: S().cfg().set.map(x => x.w).join('|') !== before, last: S().cfg().set[S().cfg().set.length - 1].w };
/* drop an on word onto an off word: it goes off, before it */
const offW = Q('#wordlist .chip.off').dataset.w, onW = 'minimal';
Q('#wordlist .chip[data-w="minimal"]').dispatchEvent(new Event('dragstart', { bubbles: true })); Q('#wordlist .chip[data-w="' + CSS.escape(offW) + '"]').dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await sleep(250);
out.dropOnOff = { off: Q('#wordlist .chip[data-w="minimal"]').classList.contains('off'), pool: S().cfg().pool.indexOf('minimal') + 1 === S().cfg().pool.indexOf(offW) || S().cfg().pool.indexOf('minimal') < S().cfg().pool.indexOf(offW) };
const wr = window.__writes.filter(x => x[1] === 'studio/config'); out.configWrites = wr.length; out.lastPoolHold = wr.length && wr[wr.length - 1][2].poolHold;
out.errors = window.__errors; out.over = over();
return JSON.stringify(out);
