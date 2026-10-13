/* every mix's prompt as the page builds it, with its parts: the input of prompt_proof.py */
await ready();
const c = S().cfg();
return JSON.stringify({ mixes: c.mixes.map(m => { const p = S().promptFor(m); return { name: m.name, own: typeof m.own === 'string', text: p.text, segments: p.segments, words: p.words }; }), errors: window.__errors, warns: window.__warns });
