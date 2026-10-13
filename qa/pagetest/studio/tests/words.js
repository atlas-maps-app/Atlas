/* G34: one list, each word on or off; on = cfg.set (in prompt order), off = the rest of cfg.pool. A word turned on and off again
   leaves every prompt byte-identical, and the write-back is the one-list form (set = the on words). The words you held (poolHold:
   fun, real things) stay held through every edit, so what a mix with From pool > 0 draws does not change (checked at From pool 5) */
await ready(); await tabTo('words');
const P = () => S().cfg().mixes.map(m => S().promptFor(m).text);
const P5 = () => S().cfg().mixes.filter(m => typeof m.own !== 'string').map(m => S().wordsFor(Object.assign({}, m, { fromPool: 5 })).join(' | '));   /* the draws at From pool 5 */
const cfg0 = JSON.parse(JSON.stringify(S().cfg())), p0 = P(), d0 = P5();
const out = { count: Q('#words-count').textContent, chips: QA('#wordlist .chip').length, on: QA('#wordlist .chip:not(.off)').map(c => c.dataset.w) };
out.onIsSet = JSON.stringify(out.on) === JSON.stringify(cfg0.set.filter(x => x.on).map(x => x.w));
const firstOff = Q('#wordlist .chip.off'), w = firstOff.dataset.w;
out.word = w;
firstOff.querySelector('button.w').click(); await sleep(400);
out.afterOn = { isOn: !Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"]').classList.contains('off'), lastInSet: S().cfg().set[S().cfg().set.length - 1].w, promptHasIt: S().promptFor(S().cfg().mixes[0]).words.includes(w) };
Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"] button.w').click(); await sleep(1100);
const p2 = P(), c2 = S().cfg();
out.afterOff = { isOff: Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"]').classList.contains('off'), promptsIdentical: JSON.stringify(p2) === JSON.stringify(p0),
  setSameOrder: JSON.stringify(c2.set) === JSON.stringify(cfg0.set.filter(x => x.on)), poolSame: JSON.stringify(c2.pool) === JSON.stringify(cfg0.pool), poolHold: c2.poolHold, poolHoldBefore: cfg0.poolHold };
/* the held words: shown as held, and each one on and off again comes back held; From pool 5 draws the same throughout */
out.heldChips = QA('#wordlist .chip.held').map(c => c.dataset.w); out.countAfter = Q('#words-count').textContent;
const hw = out.heldChips[0];
if (hw) { Q('#wordlist .chip[data-w="' + CSS.escape(hw) + '"] button.w').click(); await sleep(400);
  out.heldOn = { on: S().cfg().set.some(x => x.w === hw && x.on), inPrompt: S().promptFor(S().cfg().mixes[0]).words.includes(hw), stillInPoolHold: (S().cfg().poolHold || []).includes(hw), tip: Q('#wordlist .chip[data-w="' + CSS.escape(hw) + '"] button.w').title };
  Q('#wordlist .chip[data-w="' + CSS.escape(hw) + '"] button.w').click(); await sleep(1100);
  out.heldBack = { held: Q('#wordlist .chip[data-w="' + CSS.escape(hw) + '"]').classList.contains('held'), poolHold: S().cfg().poolHold, prompts: JSON.stringify(P()) === JSON.stringify(p0), draws5: JSON.stringify(P5()) === JSON.stringify(d0) }; }
out.draws5Same = JSON.stringify(P5()) === JSON.stringify(d0); out.draws5 = d0.map(x => x.split(' | ').slice(-5).join(' | '));
const wr = window.__writes.filter(x => x[1] === 'studio/config');
out.configWrites = wr.length;
const last = wr.length && wr[wr.length - 1][2];
out.written = last ? { set: JSON.stringify(last.set) === JSON.stringify(cfg0.set.filter(x => x.on)), poolHold: last.poolHold, goodKept: last.good === cfg0.good, notesKept: last.notes === cfg0.notes, qGoneKept: JSON.stringify(last.qGone) === JSON.stringify(cfg0.qGone) } : null;
/* drag order: drop the 3rd on word onto the 1st: it goes first, and the prompt words follow */
const ons = QA('#wordlist .chip:not(.off)'), a = ons[2].dataset.w;
ons[2].dispatchEvent(new Event('dragstart', { bubbles: true })); ons[0].dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await sleep(200);
out.drag = { first: S().cfg().set[0].w === a, promptFirstWord: S().promptFor(S().cfg().mixes[0]).words[0] };
/* add a word: on, last */
type(Q('#add-word-w'), 'unhurried'); Q('#add-word').requestSubmit(); await sleep(200);
out.added = { on: S().cfg().set.some(x => x.w === 'unhurried' && x.on), inPool: S().cfg().pool.includes('unhurried'), chip: !!Q('#wordlist .chip[data-w="unhurried"]:not(.off)') };
/* off, then remove for good */
Q('#wordlist .chip[data-w="unhurried"] button.w').click(); await sleep(400);
Q('#wordlist .chip[data-w="unhurried"] button.x').click(); await sleep(200);
out.armed = S().cfg().pool.includes('unhurried') && (Q('#wordlist .chip[data-w="unhurried"] button.x') || {}).textContent === 'Remove?';   /* one tap only arms */
Q('#wordlist .chip[data-w="unhurried"] button.x').click(); await sleep(200);
out.removed = out.armed && !S().cfg().pool.includes('unhurried') && !Q('#wordlist .chip[data-w="unhurried"]');
out.errors = window.__errors;
return JSON.stringify(out);
