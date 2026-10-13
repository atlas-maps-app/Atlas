/* Traditions: its header carries the five Atlas links, Traditions as plain text; the page still draws */
for (let i = 0; i < 80 && !document.querySelector('#summary *'); i++) await sleep(50); await sleep(500);
const out = { links: [...document.querySelectorAll('.top .links a, .top .links .here')].map(a => a.textContent + (a.href ? ' ' + a.href : '')), over: document.documentElement.scrollWidth - document.documentElement.clientWidth, errors: window.__errors, summary: !!document.querySelector('#summary *') };
return JSON.stringify(out);
