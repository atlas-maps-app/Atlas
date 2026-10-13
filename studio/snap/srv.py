"""Serves this folder; /_blob/<id> from <id>.svg|png|jpg here; POST /save?name=X stores the body as snaps/X.html."""
import http.server, os, re, urllib.parse
D = os.path.dirname(os.path.abspath(__file__))
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=D, **k)
    def do_GET(self):
        m = re.match(r'^/_blob/([0-9a-f]{32})', self.path)
        if m:
            for ext, ct in (('.svg', 'image/svg+xml'), ('.png', 'image/png'), ('.jpg', 'image/jpeg')):
                f = os.path.join(D, m.group(1) + ext)
                if os.path.exists(f):
                    b = open(f, 'rb').read(); self.send_response(200); self.send_header('Content-Type', ct); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b); return
            self.send_error(404); return
        return super().do_GET()
    def do_POST(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        name = re.sub(r'[^A-Za-z0-9_-]', '', q.get('name', ['snap'])[0])
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        open(os.path.join(D, 'snaps', name + '.html'), 'wb').write(body)
        self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(('127.0.0.1', 8778), H).serve_forever()
