"""Rasterises engine SVGs with WebKit (qlmanage) at exactly 2x: two 1024 x 1024 thumbnails of a 1 css px = 1 device px page, stitched into 824 x 1830 (or the SVG's size x 2)."""
import sys, os, re, subprocess, zlib, struct, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from png import read_png
OUT = os.environ.get('SVG_REF_OUT', '/Users/erika/Documents/Claudea/cache/svg-ref')
def write_png(path, w, h, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))
def size_of(svg):
    head = open(svg, 'rb').read(400).decode('utf8', 'replace')
    w = float(re.search(r'width="([\d.]+)"', head).group(1)); h = float(re.search(r'height="([\d.]+)"', head).group(1))
    return w, h
def ref(svg, name, scale=2):
    w, h = size_of(svg); W, H = int(round(w * scale)), int(round(h * scale))
    tmp = tempfile.mkdtemp(dir=OUT + '/tmp')
    tops = list(range(0, H, 1024))
    parts = []
    for i, top in enumerate(tops):
        html = os.path.join(tmp, 'p%d.html' % i)
        open(html, 'w').write('<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;width:1024px;height:1024px;overflow:hidden;background:#fff}img{position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx}</style></head><body><img src="file://%s"></body></html>' % (-top, W, H, os.path.abspath(svg)))
        subprocess.run(['qlmanage', '-t', '-s', '1024', '-o', tmp, html], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        parts.append(read_png(html + '.png'))
    rows = []
    for y in range(H):
        i = y // 1024; pw, ph, b, px = parts[i]; yy = y - tops[i]
        line = bytearray()
        for x in range(W):
            o = (yy * pw + x) * b; line += px[o:o + 3]
        rows.append(line)
    write_png(os.path.join(OUT, name + '.png'), W, H, rows)
    subprocess.run(['rm', '-rf', tmp])
if __name__ == '__main__':
    os.makedirs(OUT + '/tmp', exist_ok=True)
    for a in sys.argv[1:]:
        name = os.path.basename(a)[:-4]
        ref(a, name); print('ok', name, flush=True)
