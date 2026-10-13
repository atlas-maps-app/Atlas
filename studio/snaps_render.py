"""Renders boards whose content is drawn by their own script, from DOM snapshots taken in a browser after the script ran.
Quick Look runs no JavaScript, so thumbs.py alone shows such boards with blanks where values go (or only their first layer).
1. Serve <snapdir> (srv.py: the boards, support.js, their /_blob images) and save each board's DOM to <snapdir>/snaps/<Board>.html.
2. python3 snaps_render.py <snapdir> [Board ...]  ->  cache/pictures/full/<Board>.jpg (824 wide), the old one kept in <snapdir>/old/.
3. python3 thumbs.py makes img/ and big/ from the new full images (studio/full_hashes.json records which board each picture shows)."""
import os, re, sys, shutil, subprocess, time, concurrent.futures as cf
SN = sys.argv[1]; A = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); F = A + '/cache/pictures/full'; BLOB = A + '/cache/blob/'
names = sys.argv[2:] or sorted(f[:-5] for f in os.listdir(SN + '/snaps') if f.endswith('.html'))
os.makedirs(SN + '/old', exist_ok=True)
def local(m):
    for ext in ('.svg', '.png', '.jpg', '.webp'):
        if os.path.exists(BLOB + m.group(1) + ext): return 'file://' + BLOB + m.group(1) + ext
    return m.group(0)
def one(b):
    out = SN + '/out_' + b; shutil.rmtree(out, ignore_errors=True); os.makedirs(out)
    h = open(SN + '/snaps/' + b + '.html').read()
    h = re.sub(r'(?:https?://(?:localhost|127\.0\.0\.1):\d+)?/_blob/([0-9a-f]{32})', local, h)
    h = re.sub(r'<script.*?</script>', '', h, flags=re.S).replace('<head>', '<head><style>html,body{margin:0}</style>', 1)
    open(out + '/f0.html', 'w').write(h)
    open(out + '/sheet.html', 'w').write('<!doctype html><html><body style="margin:0;background:#888;width:1024px;height:1024px;position:relative"><iframe src="f0.html" style="width: 412px; height: 915px; border: 0; position: absolute; left: 8px; top: 8px"></iframe></body></html>')
    subprocess.run(['qlmanage', '-t', '-s', '2048', '-o', out, out + '/sheet.html'], capture_output=True, timeout=120)
    png = out + '/sheet.html.png'
    for _ in range(20):
        if os.path.exists(png): break
        time.sleep(1)
    if not os.path.exists(png): return b, False
    subprocess.run(['sips', '--cropOffset', '16', '16', '-c', '1830', '824', png, '--out', out + '/crop.png'], capture_output=True)
    if os.path.exists(F + '/' + b + '.jpg') and not os.path.exists(SN + '/old/' + b + '.jpg'): shutil.copy(F + '/' + b + '.jpg', SN + '/old/' + b + '.jpg')
    subprocess.run(['sips', '-s', 'format', 'jpeg', '-s', 'formatOptions', '80', out + '/crop.png', '--out', F + '/' + b + '.jpg'], capture_output=True)
    return b, True
with cf.ThreadPoolExecutor(4) as ex:
    res = list(ex.map(one, names))
print(sum(ok for _, ok in res), 'rendered;', [b for b, ok in res if not ok], 'failed')
# the picture is now of the board as it is: thumbs.py reads studio/full_hashes.json to know a picture is up to date
import json, hashlib
HS = A + '/studio/full_hashes.json'; seen = json.load(open(HS)) if os.path.exists(HS) else {}
for b, ok in res:
    src = next((d + '/' + b + '.dc.html' for d in (A + '/canvas/project', A + '/engine/project') if os.path.exists(d + '/' + b + '.dc.html')), None)
    if ok and src: seen[b] = hashlib.sha1(open(src, 'rb').read()).hexdigest()
json.dump(dict(sorted(seen.items())), open(HS, 'w'), indent=0)
