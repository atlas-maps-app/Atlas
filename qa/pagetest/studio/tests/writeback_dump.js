/* a Words edit on the new page, the config it writes: off words on and off again, every held word on and off again (it comes back held),
   the last on word off and on again (it goes back last), an off word edited to itself, a held word renamed and back, a held word dragged
   before the first off word, a pair on hold and back. None of these changes what any mix draws, From pool included */
await ready(); await tabTo('words');
const chipEl = w => Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"]');
const chip = w => Q('#wordlist .chip[data-w="' + CSS.escape(w) + '"] button.w');
const twice = async w => { chip(w).click(); await sleep(350); chip(w).click(); await sleep(350); };
const rename = async (from, to) => { chip(from).dispatchEvent(new MouseEvent('dblclick', { bubbles: true, detail: 2 })); await sleep(150); const i = Q('#wordlist input[data-field="wedit"]'); if (!i) return; i.value = to; i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await sleep(300); };
const offs = QA('#wordlist .chip.off:not(.held)').map(c => c.dataset.w), held = QA('#wordlist .chip.held').map(c => c.dataset.w);
const ons = QA('#wordlist .chip:not(.off)').map(c => c.dataset.w);
const done = [];
for (const w of [offs[0], offs[5]]) if (w) { await twice(w); done.push('on-off ' + w); }
for (const w of held) { await twice(w); done.push('held on-off ' + w); }
const last = ons[ons.length - 1]; chip(last).click(); await sleep(350); chip(last).click(); await sleep(350); done.push('off-on ' + last);
if (offs[1]) { await rename(offs[1], offs[1]); done.push('edit to itself ' + offs[1]); }
if (held[0]) { await rename(held[0], held[0] + ' (x)'); await rename(held[0] + ' (x)', held[0]); done.push('held rename and back ' + held[0]); }
if (held[0] && offs[0]) { chipEl(held[0]).dispatchEvent(new Event('dragstart', { bubbles: true })); chipEl(offs[0]).dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await sleep(350); done.push('held dragged before ' + offs[0]); }
const ph = Q('#pairs .pair button.hold'); if (ph) { ph.click(); await sleep(300); Q('#pairs .pair button.hold').click(); await sleep(300); done.push('pair hold and back'); }
await sleep(1200);
const heldAfter = QA('#wordlist .chip.held').map(c => c.dataset.w);
const wr = window.__writes.filter(x => x[1] === 'studio/config');
return JSON.stringify({ n: wr.length, done, held, heldAfter, cfg: wr.length ? wr[wr.length - 1][2] : null, errors: window.__errors });
