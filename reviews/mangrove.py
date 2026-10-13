"""Mangrove: the open reviews Atlas reads and writes (mangrove.reviews, by the Open Reviews Association).

Atlas's default for reviews. Reviews are signed by their writer's own key, tied to a place by its
coordinates and name, and published under CC BY 4.0 for any app to use; MapComplete and CoMaps
read and write the same pool.

Reading needs nothing. Writing signs with a reviewer key kept on this computer
(~/.config/atlas/mangrove/key.pem, made on first use; the same key is the same reviewer), using the
openssl that comes with macOS, and is sent only by submit(): a review is public as soon as it is sent.
"""
import base64
import json
import os
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.mangrove.reviews'
UNCERTAINTY = 30            # metres: how far from the mapped point a review still counts as this place
MAX_OPINION = 500           # characters, Mangrove's limit
USER_AGENT = 'Atlas (Mangrove reviews)'
LICENSE = 'CC-BY-4.0'


class MangroveError(Exception):
    """Mangrove could not be reached, refused a review, or a review is not valid."""


# ---- places as review subjects

def _encode(text):
    """Like JavaScript's encodeURIComponent, which Mangrove's own client uses for the name."""
    return urllib.parse.quote(text, safe="-_.!~*'()")


def _coord(x):
    """Up to 7 decimals with no trailing zeros, as JavaScript writes a number."""
    return ('%.7f' % x).rstrip('0').rstrip('.')


def subject(name, lat, lon, uncertainty=UNCERTAINTY):
    """The place as Mangrove names it: geo:<lat>,<lon>?q=<name>&u=<metres>, as MapComplete writes it."""
    return 'geo:%s,%s?q=%s&u=%d' % (_coord(lat), _coord(lon), _encode(' '.join(name.split())), uncertainty)


def stars(rating):
    """Mangrove's 0–100 as 1–5 stars."""
    return None if rating is None else round(1 + rating / 25, 1)


def rating(stars_):
    """1–5 stars as Mangrove's 0–100."""
    if not 1 <= stars_ <= 5:
        raise MangroveError('Stars go from 1 to 5.')
    return round((stars_ - 1) * 25)


# ---- reading

def _get(path, query=None, opener=None):
    url = API + path + ('?' + urllib.parse.urlencode(query) if query else '')
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Accept': 'application/json'})
    try:
        with (opener or urllib.request.urlopen)(req, timeout=20) as r:
            body = r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        raise MangroveError('Mangrove answered %s: %s' % (e.code, e.read().decode('utf-8', 'replace')[:200]))
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MangroveError('Mangrove could not be reached: %s' % e)
    return json.loads(body) if body else None


def _review(r):
    p = r.get('payload') or {}
    meta = p.get('metadata') or {}
    return {
        'stars': stars(p.get('rating')), 'rating': p.get('rating'), 'text': p.get('opinion'),
        'author': meta.get('nickname') or meta.get('preferred_username') or 'A Mangrove reviewer',
        'when': time.strftime('%Y-%m-%d', time.gmtime(p['iat'])) if p.get('iat') else None,
        'osm_id': meta.get('osm_id'), 'how': meta.get('atlas_how'),
        'images': [i.get('src') for i in p.get('images') or [] if i.get('src')],
        'license': meta.get('license') or LICENSE, 'app': meta.get('client_id'),
        'signature': r.get('signature'), 'credit': 'Mangrove',
    }


def reviews_for(name, lat, lon, uncertainty=UNCERTAINTY, limit=50, opener=None):
    """Reviews of the place named `name` within `uncertainty` metres of lat/lon, newest first.

    Mangrove matches them itself: within the radius, and with the same name ignoring case,
    punctuation and accents. Every review carries its author, licence and source for the credit line.
    """
    got = _get('/reviews', {'sub': subject(name, lat, lon, uncertainty), 'limit': limit}, opener) or {}
    out = [_review(r) for r in got.get('reviews') or []]
    return sorted(out, key=lambda r: r['when'] or '', reverse=True)


def summary(name, lat, lon, uncertainty=UNCERTAINTY, opener=None):
    """Mangrove's own totals for the place: how many reviews, how many with text, and its 0–100 quality."""
    s = _get('/subject/' + urllib.parse.quote(subject(name, lat, lon, uncertainty), safe=''), None, opener) or {}
    return {'count': s.get('count', 0), 'with_text': s.get('opinion_count', 0), 'positive': s.get('positive_count', 0),
            'stars': stars(s.get('quality')), 'quality': s.get('quality')}


# ---- the reviewer's key

def key_dir():
    return os.environ.get('ATLAS_MANGROVE_HOME') or os.path.expanduser('~/.config/atlas/mangrove')


def _openssl(args, data=None):
    run = subprocess.run(['openssl'] + args, input=data, capture_output=True)
    if run.returncode != 0:
        raise MangroveError('openssl failed: %s' % run.stderr.decode('utf-8', 'replace').strip()[:200])
    return run.stdout


def key_path(create=True):
    """The reviewer key, made on first use and readable only by you."""
    path = os.path.join(key_dir(), 'key.pem')
    if not os.path.exists(path):
        if not create:
            raise MangroveError('No reviewer key yet at %s.' % path)
        os.makedirs(key_dir(), exist_ok=True)
        os.chmod(key_dir(), 0o700)
        pem = _openssl(['ecparam', '-name', 'prime256v1', '-genkey', '-noout'])
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as f:
            f.write(pem)
    return path


def _b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode('ascii')


def public_key(path=None):
    """(PEM on one line, the way Mangrove's `kid` carries it; the public key as a JWK string)."""
    der = _openssl(['ec', '-in', path or key_path(), '-pubout', '-outform', 'DER'])
    pem = '-----BEGIN PUBLIC KEY-----%s-----END PUBLIC KEY-----' % base64.b64encode(der).decode('ascii')
    point = der[-65:]  # 0x04, then x and y, 32 bytes each
    jwk = {'crv': 'P-256', 'ext': True, 'key_ops': ['verify'], 'kty': 'EC',
           'x': _b64url(point[1:33]), 'y': _b64url(point[33:])}
    return pem, json.dumps(jwk, separators=(',', ':'))


# ---- ES256 signatures: openssl writes DER, a JWT carries r and s side by side

def der_to_raw(der):
    def integer(i):
        if der[i] != 0x02:
            raise MangroveError('Not an ECDSA signature.')
        n = der[i + 1]
        return der[i + 2:i + 2 + n].lstrip(b'\0').rjust(32, b'\0'), i + 2 + n
    if der[0] != 0x30:
        raise MangroveError('Not an ECDSA signature.')
    r, i = integer(2)
    s, _ = integer(i)
    return r + s


def raw_to_der(raw):
    def integer(b):
        b = b.lstrip(b'\0') or b'\0'
        if b[0] & 0x80:
            b = b'\0' + b
        return b'\x02' + bytes([len(b)]) + b
    body = integer(raw[:32]) + integer(raw[32:])
    return b'\x30' + bytes([len(body)]) + body


# ---- writing

def review(name, lat, lon, stars_, opinion=None, osm_id=None, how=None, nickname=None,
           personal=True, uncertainty=UNCERTAINTY, key=None):
    """A signed review, ready for submit(), as (jwt, payload). Nothing is sent.

    how: for food and drink, 'eaten_there', 'takeaway' or 'delivered' (Atlas's own question).
    """
    if opinion and len(opinion) > MAX_OPINION:
        raise MangroveError('A Mangrove review holds at most %d characters; this one has %d.' % (MAX_OPINION, len(opinion)))
    if how not in (None, 'eaten_there', 'takeaway', 'delivered'):
        raise MangroveError('how is eaten_there, takeaway or delivered.')
    meta = {'license': LICENSE, 'is_personal_experience': bool(personal) or None}
    if os.environ.get('ATLAS_CLIENT_ID'):
        meta['client_id'] = os.environ['ATLAS_CLIENT_ID']
    if nickname:
        meta['nickname'] = nickname
    if osm_id:
        meta['osm_id'] = osm_id
    if how:
        meta['atlas_how'] = how
    payload = {'sub': subject(name, lat, lon, uncertainty), 'rating': rating(stars_)}
    if opinion and opinion.strip():
        payload['opinion'] = opinion.strip()
    payload['iat'] = int(time.time())
    payload['metadata'] = {k: v for k, v in meta.items() if v is not None}
    path = key or key_path()
    pem, jwk = public_key(path)
    header = {'alg': 'ES256', 'typ': 'JWT', 'kid': pem, 'jwk': jwk}
    signing_input = '%s.%s' % (_b64url(json.dumps(header, separators=(',', ':')).encode()),
                               _b64url(json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()))
    with tempfile.NamedTemporaryFile() as f:
        f.write(signing_input.encode())
        f.flush()
        der = _openssl(['dgst', '-sha256', '-sign', path, f.name])
    return signing_input + '.' + _b64url(der_to_raw(der)), payload


def verify(jwt):
    """True when the review's signature matches the key it names. Works for anyone's review."""
    head, body, sig = jwt.split('.')
    pad = lambda s: s + '=' * (-len(s) % 4)
    kid = json.loads(base64.urlsafe_b64decode(pad(head)))['kid']
    b64 = kid.replace('-----BEGIN PUBLIC KEY-----', '').replace('-----END PUBLIC KEY-----', '').strip()
    pem = '-----BEGIN PUBLIC KEY-----\n' + '\n'.join(b64[i:i + 64] for i in range(0, len(b64), 64)) + '\n-----END PUBLIC KEY-----\n'
    with tempfile.TemporaryDirectory() as d:
        for name, content in (('pub.pem', pem.encode()), ('sig', raw_to_der(base64.urlsafe_b64decode(pad(sig)))),
                              ('data', ('%s.%s' % (head, body)).encode())):
            with open(os.path.join(d, name), 'wb') as f:
                f.write(content)
        run = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', os.path.join(d, 'pub.pem'),
                              '-signature', os.path.join(d, 'sig'), os.path.join(d, 'data')], capture_output=True)
    return run.returncode == 0


def submit(jwt, opener=None):
    """Publish a signed review. It is public, under CC BY 4.0, as soon as Mangrove accepts it."""
    req = urllib.request.Request(API + '/submit/' + jwt, method='PUT', headers={'User-Agent': USER_AGENT})
    try:
        with (opener or urllib.request.urlopen)(req, timeout=20) as r:
            return r.status in (200, 201, 204)
    except urllib.error.HTTPError as e:
        raise MangroveError('Mangrove refused the review (%s): %s' % (e.code, e.read().decode('utf-8', 'replace')[:300]))
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MangroveError('Mangrove could not be reached: %s' % e)
