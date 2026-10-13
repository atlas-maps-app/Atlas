"""Freezes an interactive .dc.html board into static HTML for Quick Look: <sc-if> kept or dropped by the given values,
{{holes}} filled, scripts removed. Then lays boards side by side on one page.
python3 freeze.py <outdir> <board.dc.html>:key=val,key=val ... (values: true/false/text)"""
import re, sys, os, json
def freeze(html, vals):
    def iff(m):
        k = m.group(1); v = vals.get(k)
        if v is None: v = m.group(2).strip() == 'true'
        return m.group(3) if v in (True, 'true') else ''
    prev = None
    while prev != html:
        prev = html
        html = re.sub(r'<sc-if value="\{\{([\w.]+)\}\}" hint-placeholder-val="\{\{ ?(\w+) ?\}\}">((?:(?!<sc-if).)*?)</sc-if>', iff, html, flags=re.S)
    html = re.sub(r'\{\{([\w.]+)\}\}', lambda m: str(vals.get(m.group(1), '')), html)
    html = re.sub(r'<script.*?</script>', '', html, flags=re.S)
    blob = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'cache', 'blob') + '/'
    def local(m):
        for ext in ('.svg', '.png', '.jpg', '.webp'):
            if os.path.exists(blob + m.group(1) + ext): return 'file://' + blob + m.group(1) + ext
        return m.group(0)
    html = re.sub(r'/_blob/([0-9a-f]{32})', local, html)
    return html.replace('<x-dc>', '').replace('</x-dc>', '').replace('<helmet>', '').replace('</helmet>', '')
if __name__ == '__main__':
    out = sys.argv[1]; os.makedirs(out, exist_ok=True); frames = []
    for i, spec in enumerate(sys.argv[2:]):
        path, _, kv = spec.partition(':')
        if kv.startswith('@'): vals = json.load(open(kv[1:]))          # a state's values from a JSON file (board generators write them)
        else:
            vals = dict(x.split('=', 1) for x in kv.split(',') if x)
            vals = {k: (v == 'true' if v in ('true', 'false') else v) for k, v in vals.items()}
        name = 'f%d_%s.html' % (i, os.path.basename(path).split('.')[0])
        open(os.path.join(out, name), 'w').write(freeze(open(path).read(), vals))
        frames.append(name)
    n = len(frames); sc = float(os.environ.get('SCALE') or (1.0 if n <= 2 else 0.78 if n <= 3 else 0.55)); per = max(1, int(1016 // (412 * sc + 8)))
    cells = ''.join('<iframe src="%s" style="width: 412px; height: 915px; border: 0; transform: scale(%s); transform-origin: 0 0; position: absolute; left: %dpx; top: %dpx"></iframe>' % (
        f, sc, 8 + (i % per) * (412 * sc + 8), 8 + (i // per) * (915 * sc + 8)) for i, f in enumerate(frames))
    open(os.path.join(out, 'sheet.html'), 'w').write('<!doctype html><html><body style="margin:0;background:#888;width:1024px;height:1024px;position:relative">%s</body></html>' % cells)
    print(os.path.join(out, 'sheet.html'))
