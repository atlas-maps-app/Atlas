/* G35: only the parts some mix sends are shown, the rest behind + part; B6: Good screens is the Good tab's share titles, read only */
await ready(); await tabTo('prompt'); await sleep(200);
const vis = () => QA('#prompt details.part').filter(d => !d.hidden).map(d => d.id.replace('part-', ''));
const out = { shown: vis(), more: QA('#partmore button').map(b => b.textContent) };
out.goodLine = Q('#good-line').textContent; out.goodTextarea = !!Q('#good');
out.goodExpected = S().good().share.filter(q => !q.hold).map(q => q.title.trim()).join('. ');
Q('#partmore [data-hk="pm:+"]').click(); await sleep(150);
out.moreOpen = QA('#partmore button').map(b => b.textContent);
Q('#partmore [data-hk="pm:app"]').click(); await sleep(200);
out.afterApp = { shown: vis(), open: Q('#part-app').open, focus: document.activeElement && document.activeElement.closest('#part-app') ? 'in app' : String(document.activeElement && document.activeElement.tagName) };
/* the editor under a prompt for Good screens: read only, with a way to the Good tab */
const seg = Q('#mix-B .out [data-part="good"]'); out.seg = seg && seg.textContent;
if (seg) { seg.click(); await sleep(200); const ied = Q('#mix-B .ied'); out.ied = ied ? { text: ied.textContent.slice(0, 160), fields: ied.querySelectorAll('textarea, input').length } : null;
  const go = ied && ied.querySelector('[data-hk^="iegood"]'); if (go) { go.click(); await sleep(200); out.wentTo = QA('nav button').find(b => b.getAttribute('aria-current') === 'true').dataset.tab } }
/* a share title changed in the Good tab: the prompt follows */
const g = S().good(); const ta = QA('#goodbox textarea.gtext.title')[1]; out.title1 = ta && ta.value;
if (ta) { type(ta, ta.value + ', always'); await sleep(250); }
await tabTo('prompt'); await sleep(200);
out.goodAfterEdit = Q('#good-line').textContent.slice(0, 120);
out.promptB = S().promptFor(S().cfg().mixes[0]).segments.filter(s => s.part === 'good').map(s => s.text).join('').slice(0, 120);
out.errors = window.__errors;
return JSON.stringify(out);
