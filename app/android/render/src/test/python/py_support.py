#!/usr/bin/env python3
"""Oracle for the port's Python-exact support (app.atlas.render.py): random, formatting, math and hash.

Run with PYTHONHASHSEED=0. Prints one JSON document on stdout. Floats travel as their IEEE bits (signed int64),
so both sides see exactly the same values.

    py_support.py info | random | fmt | math | hash
"""
import json, math, random, struct, sys


def bits(x):
    return struct.unpack('<q', struct.pack('<d', float(x)))[0]


def info():
    return {'minor': sys.version_info.minor, 'major': sys.version_info.major,
            'hash': sys.hash_info.algorithm, 'version': sys.version.split()[0]}


# ---------------------------------------------------------------- random
SEEDS = [0, 1, 2, 3, 5, 7, 9, 11, 17, 42, -1, -7, 255, 256, 65535, 2 ** 31 - 1, 2 ** 31, 2 ** 32 - 1, 2 ** 32,
         2 ** 32 + 1, 2 ** 63 - 1, 2 ** 64 + 3, 12345678901234567890123, 4294967295 * 3, 1234567]


def rnd_ops(r):
    """The fixed script the Kotlin test replays: returns the outputs."""
    out = []
    out.append([bits(r.random()) for _ in range(8)])
    out.append([r.getrandbits(k) for k in (1, 2, 5, 8, 16, 31, 32)])
    out.append([str(r.getrandbits(k)) for k in (33, 48, 63, 64, 65, 100, 200)])
    out.append([r.randrange(1000) for _ in range(10)])
    out.append([r.randrange(5, 17) for _ in range(5)] + [r.randrange(-10, 50, 7) for _ in range(5)]
               + [r.randrange(50, -10, -3) for _ in range(5)])
    out.append([r.randint(1, 3) for _ in range(10)] + [r.randint(-5, 5) for _ in range(5)])
    out.append([r.randrange(2 ** 40) for _ in range(3)])
    seq = [14, 16, 18, 20, 22, 25, 28, 32]
    out.append([r.choice(seq) for _ in range(10)])
    out.append([r.choice(range(-1050, -70, 70)) for _ in range(5)])
    x = list(range(20)); r.shuffle(x); out.append(x)
    x = list(range(1)); r.shuffle(x); out.append(x)
    x = list(range(2)); r.shuffle(x); out.append(x)
    out.append(r.sample(range(30), 10))
    out.append(r.sample(range(1000), 10))
    out.append(r.sample(range(10), 10))
    out.append([bits(r.uniform(-4, 4)) for _ in range(5)] + [bits(r.uniform(3.2, 5.2)) for _ in range(5)])
    out.append([bits(r.gauss(0, 1)) for _ in range(5)] + [bits(r.gauss(10, 2.5)) for _ in range(4)])
    out.append([bits(r.normalvariate(0, 1)) for _ in range(5)])
    out.append([bits(r.triangular(0, 10)) for _ in range(3)] + [bits(r.triangular(0, 10, 7)) for _ in range(3)])
    out.append([bits(r.expovariate(1.5)) for _ in range(3)])
    out.append(r.choices(['a', 'b', 'c', 'd'], k=6))
    out.append(r.choices(['a', 'b', 'c', 'd'], weights=[1, 0.5, 3, 2], k=6))
    r.seed(99)
    out.append([bits(r.random()) for _ in range(3)])
    r.seed('again')
    out.append([bits(r.random()) for _ in range(3)])
    # a long run crosses the 624-word refill many times
    acc = 0
    for _ in range(5000):
        acc = (acc * 31 + r.getrandbits(32)) % (2 ** 61 - 1)
    out.append(str(acc))
    return out


def rnd():
    cases = []
    for s in SEEDS:
        cases.append({'kind': 'int', 'seed': str(s), 'out': rnd_ops(random.Random(s))})
    for s in ['', 'a', 'atlas', 'héllo wörld', '日本語', '🗺 map', 'x' * 100]:
        cases.append({'kind': 'str', 'seed': s, 'out': rnd_ops(random.Random(s))})
    for s in [b'', b'\x00\xff', b'bytes seed']:
        cases.append({'kind': 'bytes', 'seed': list(s), 'out': rnd_ops(random.Random(s))})
    for s in [1.5, -2.25, 0.1, 1e300, 3.0, -0.0]:
        cases.append({'kind': 'float', 'seed': bits(s), 'out': rnd_ops(random.Random(s))})
    return cases


# ---------------------------------------------------------------- formatting
def sample_floats():
    r = random.Random(2026)
    xs = [0.0, -0.0, 0.5, 1.5, 2.5, -0.5, -1.5, 0.125, 0.375, 2.675, 1.005, 1.115, 0.045, 1.25, -1.25, 0.05, 0.15,
          0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1e-5, 1e-4, 0.0001, 0.001, 123.456, 1e15, 1e16, 1e17, 1e22,
          1e23, 1e300, 1.7976931348623157e308, 5e-324, 2.2250738585072014e-308, 1 / 3, 2 / 3, 0.1, 0.2, 0.3,
          9.995, 99.95, 999.5, 9999.5, 0.0005, 0.00005, -0.04, -0.004, -0.0004, 4.35, 4.45, 5.55, 8.325, 2 ** 52,
          2 ** 53, 2 ** 53 + 2, 2 ** 60, 2 ** -20, 2 ** -1074, 2 ** 1023, 123456789.125, 0.1 + 0.2, 100.0, 412.0,
          915.0, -915.5, 1234.5, 12345.5, 1e-7, 1.5e-7, 9.999999e-5, 0.99999995, 9.9999995, 999999.5, 0.0999999]
    for k in range(-60, 61):
        xs.append(k / 2); xs.append(k / 4); xs.append(k / 8); xs.append(k / 10 + 0.05); xs.append(k / 100 + 0.005)
        xs.append(k + 0.5); xs.append(k * 0.1)
    for _ in range(1500):
        m = r.uniform(-1, 1)
        e = r.choice([0, 0, 0, 1, 2, 3, -1, -2, -3, 5, -5, 10, -10, 20, -20, 100, -100, 300, -300])
        xs.append(m * 10.0 ** e)
    for _ in range(800):
        xs.append(round(r.uniform(-1000, 1000), r.randint(0, 4)) + r.choice([0, 5e-5, 5e-4, 5e-3, 5e-2]))
    for _ in range(400):
        xs.append(struct.unpack('<d', struct.pack('<Q', r.getrandbits(64)))[0])
    return [float(x) for x in xs if x == x and abs(x) != float('inf')]


PCT = ['%.0f', '%.1f', '%.2f', '%.3f', '%.4f', '%.5f', '%.6f', '%.10f', '%.17f', '%f', '%s', '%r', '%g', '%.1g',
       '%.3g', '%.10g', '%.17g', '%e', '%.0e', '%.2e', '%.15e', '%E', '%G', '%5.1f', '%-8.2f|', '%+.1f', '% .1f',
       '%010.3f', '%#.0f', '%#g', '%+g', '%08.2e']
PCT_INT = ['%d', '%02d', '%04d', '%5d', '%-5d|', '%+d', '%x', '%02x', '%02X', '%#x', '%o', '%s', '%r', '%.1f',
           '%c']
FMT = ['.1f', '.2f', '.0f', '>8.2f', '<8.1f', '^9.3e', ',.2f', '_.1f', 'g', '', '.3', '.12', '+.0%', '10', '010.2f',
       'e', '.2g', 'n', '%', '=+10.1f', 'z.1f']
FMT_INT = ['', 'd', '05d', '>6', '<6', '^7', ',', '_', 'x', 'X', '#x', 'b', 'o', '+d', '.2f', 'e', '%', 'g', 'c']
FMT_STR = ['', 's', '32s', '>10', '^9', '<6', '.3', '*^11']


def fmt():
    xs = sample_floats()
    rows = []
    for x in xs:
        row = {'x': bits(x), 'pct': [], 'fmt': [], 'round': [], 'str': str(x), 'repr': repr(x)}
        for f in PCT:
            try:
                row['pct'].append(f % x)
            except (OverflowError, ValueError):
                row['pct'].append(None)
        for f in FMT:
            try:
                row['fmt'].append(format(x, f))
            except (OverflowError, ValueError):
                row['fmt'].append(None)
        for n in (0, 1, 2, 3, 4, 5, 8, -1, -2):
            try:
                row['round'].append(bits(round(x, n)))
            except OverflowError:
                row['round'].append(None)
        try:
            row['round0'] = str(round(x))
        except (OverflowError, ValueError):
            row['round0'] = None
        try:
            row['int'] = str(int(x))
        except (OverflowError, ValueError):
            row['int'] = None
        rows.append(row)
    ints = [0, 1, -1, 5, 9, 10, 15, 16, 99, 255, 256, 1000, -1000, 123456789, -987654321, 2 ** 31, 2 ** 62, 65, 97,
            8364, 12345678901234567890]
    irows = []
    for n in ints:
        irow = {'n': str(n), 'pct': [], 'fmt': []}
        for f in PCT_INT:
            try:
                irow['pct'].append(f % n)
            except (OverflowError, ValueError, TypeError):
                irow['pct'].append(None)
        for f in FMT_INT:
            try:
                irow['fmt'].append(format(n, f))
            except (OverflowError, ValueError):
                irow['fmt'].append(None)
        irows.append(irow)
    strs = ['', 'a', 'Westerkerk', "it's", 'say "hi"', 'tab\there', 'ü', None, True, False]
    srows = []
    for s in strs:
        srows.append({'s': s, 'pct': ['%s' % (s,), '%r' % (s,), '%10s' % (s,), '%-10s|' % (s,), '%.3s' % (s,)],
                      'fmt': [format(s, f) for f in FMT_STR] if isinstance(s, str) else []})
    multi = [
        ['<rect width="%d" height="%d" fill="%s"/>', [412, 915, '#D3CEC4'],
         '<rect width="%d" height="%d" fill="%s"/>' % (412, 915, '#D3CEC4')],
        ['%.1f %.1f', [bits(1.25), bits(-0.05)], None],
        ['100%% %s%%', ['x'], '100%% %s%%' % ('x',)],
    ]
    multi[1][2] = '%.1f %.1f' % (1.25, -0.05)
    return {'pct': PCT, 'fmt': FMT, 'pct_int': PCT_INT, 'fmt_int': FMT_INT, 'fmt_str': FMT_STR,
            'floats': rows, 'ints': irows, 'strs': srows, 'multi': multi}


# ---------------------------------------------------------------- math
def mth():
    r = random.Random(77)
    vals = [0.0, -0.0, 1.0, -1.0, 0.5, 3.0, -3.0, 7.5, -7.5, 1e-300, 1e300, 360.0, -360.0, 0.1, 2.0, 1e16, 12.5, 100.0]
    for _ in range(600):
        vals.append(r.uniform(-1000, 1000))
        vals.append(r.uniform(-1, 1) * 10 ** r.randint(-8, 8))
    pairs = [(a, b) for a in vals[:18] for b in vals[:18]]
    for _ in range(3000):
        pairs.append((r.choice(vals), r.choice(vals)))
    out = {'pairs': [], 'unary': [], 'sums': [], 'hyp3': []}
    for a, b in pairs:
        row = [bits(a), bits(b)]
        for f in (lambda: a % b, lambda: a // b, lambda: math.fmod(a, b), lambda: math.hypot(a, b),
                  lambda: math.atan2(a, b), lambda: math.copysign(a, b), lambda: float(math.isclose(a, b)),
                  lambda: math.isclose(a, b, rel_tol=1e-3, abs_tol=0.5) * 1.0, lambda: max(a, b), lambda: min(a, b),
                  lambda: abs(a) ** b if abs(b) < 50 else 0.0):
            try:
                row.append(bits(f()))
            except (ZeroDivisionError, ValueError, OverflowError):
                row.append(None)
        out['pairs'].append(row)
    for a in vals:
        row = [bits(a)]
        for f in (math.sin, math.cos, math.tan, math.atan, math.exp, math.log, math.sqrt, math.radians, math.degrees,
                  math.floor, math.ceil, lambda v: v ** 2, lambda v: v ** 0.8, lambda v: v ** 0.9,
                  lambda v: math.log(v, 4), math.asin, math.acos, math.log10, math.log2):
            try:
                v = f(a)
                row.append(None if isinstance(v, complex) else bits(v))
            except (ValueError, OverflowError):
                row.append(None)
        out['unary'].append(row)
    for _ in range(400):
        n = r.randint(0, 40)
        xs = [r.uniform(-1, 1) * 10 ** r.randint(-6, 12) for _ in range(n)]
        out['sums'].append([[bits(x) for x in xs], bits(sum(xs))])
    out['sums'].append([[bits(x) for x in (1e16, 1.0, -1e16)], bits(sum([1e16, 1.0, -1e16]))])
    out['sums'].append([[bits(x) for x in (0.1,) * 10], bits(sum([0.1] * 10))])
    out['sums'].append([[bits(-0.0)], bits(sum([-0.0]))])
    for _ in range(300):
        p = [r.uniform(-1, 1) * 10 ** r.randint(-3, 6) for _ in range(3)]
        out['hyp3'].append([[bits(x) for x in p], bits(math.hypot(*p))])
    out['divmod_int'] = [[a, b, a % b, a // b] for a in (-7, -1, 0, 1, 7, 13, -13) for b in (-3, 2, 5, -5)]
    return out


# ---------------------------------------------------------------- hash
def hsh():
    r = random.Random(5)
    vals = []
    for s in ['', 'a', 'b', 'm', 't', 'c', 'w', 'r', 'n', 'rb', 'wt', 'br', 'roof', 'bk', 'marlow-pod', 'lume',
              'abcdefg', 'abcdefgh', 'abcdefghi', 'héllo', '日本', '🗺', 'x' * 33, 'Ünïcödé text with ß']:
        vals.append({'t': 's', 'v': s, 'h': hash(s)})
    for n in [0, 1, -1, -2, 2, 7, 2 ** 61 - 2, 2 ** 61 - 1, 2 ** 61, 2 ** 62, -(2 ** 61), 10 ** 30, -(10 ** 30), 123456]:
        vals.append({'t': 'i', 'v': str(n), 'h': hash(n)})
    for x in [0.0, -0.0, 1.0, -1.0, 0.5, 1.5, -2.25, 1e300, 1e-300, 5e-324, 3.14159, 2 ** 61 + 0.0, 1e16, 412.5,
              float('inf'), float('-inf')] + [r.uniform(-1e4, 1e4) for _ in range(200)]:
        vals.append({'t': 'f', 'v': bits(x), 'h': hash(x)})
    tuples = []
    tuples.append([['s', 'b'], ['i', 3], ['i', -7]])
    for i in range(-3, 22, 5):
        for j in range(-70, 18, 13):
            tuples.append([['s', 'b'], ['i', i], ['i', j]])
            tuples.append([['s', 'm'], ['i', -i], ['i', j], ['i', round(i * 37.3)], ['i', round(j * 11.7)]])
    tuples.append([])
    tuples.append([['s', 'br']])
    tuples.append([['f', bits(1.5)], ['f', bits(-2.75)]])
    tuples.append([['s', 'roof'], ['f', bits(123.456)], ['f', bits(-7.0)]])
    tuples.append([['s', 'c'], ['t', [['s', 'b'], ['i', 4], ['i', -12], ['i', 503]]]])
    tuples.append([['s', 'rb'], ['t', [['s', 'n'], ['s', 'lume']]], ['f', bits(240.7312)]])

    def build(spec):
        t, v = spec
        if t == 's': return v
        if t == 'i': return v
        if t == 'f': return struct.unpack('<d', struct.pack('<q', v))[0]
        if t == 't': return tuple(build(x) for x in v)
    trows = [{'items': tp, 'h': hash(tuple(build(x) for x in tp)),
              'seed32': hash(tuple(build(x) for x in tp)) & 0xffffffff} for tp in tuples]
    return {'values': vals, 'tuples': trows}


if __name__ == '__main__':
    mode = sys.argv[1]
    fn = {'info': info, 'random': rnd, 'fmt': fmt, 'math': mth, 'hash': hsh}[mode]
    json.dump(fn(), sys.stdout, ensure_ascii=False)
