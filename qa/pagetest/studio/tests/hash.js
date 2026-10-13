/* #screen=<Board> opens its large view (screen.html: MarketHall, engine.html: LivingLooks, screen404.html: a name not on the list) */
await ready(); await sleep(400);
const f = Q('#focus'), out = { hash: location.hash, tab: QA('nav button').find(b => b.getAttribute('aria-current') === 'true').dataset.tab, open: !f.hidden };
if (!f.hidden) {
  out.name = f.querySelector('.fname') && f.querySelector('.fname').textContent;
  out.link = (f.querySelector('a.open') || {}).href;
  out.linkText = (f.querySelector('a.open') || {}).textContent;
  out.improve = (f.querySelector('textarea.imp') || {}).value;
  out.stars = (f.querySelector('.rate') || {}).getAttribute && f.querySelector('.rate').getAttribute('aria-valuetext');
  out.img = !!f.querySelector('img.focus-img');
  out.wellBadly = f.querySelectorAll('textarea.well, textarea.badly').length;
  f.querySelector('.focus-x').click(); await sleep(300);
  out.closed = f.hidden; out.hashAfter = location.hash;
}
out.find = Q('#find').value; out.found = Q('#find-count').textContent;
out.errors = window.__errors;
return JSON.stringify(out);
