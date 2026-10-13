#!/usr/bin/env python3
import sys, json, os, time, math, random
sys.path.insert(0, os.path.dirname(__file__))
from city import *

OUT = os.path.join(os.path.dirname(__file__), '..', 'cache', 'render')
os.makedirs(OUT, exist_ok=True)

BLUE = '#2E5BFF'
W, H = 412, 915
REG = (-3600, 2200, -6500, 1500)

CHART = dict(DAY)
CHART.update(name='chart', chart=True,
             sky_top='#B9CFE6', sky_hor='#EEF1EE', fogc='#EEF0EC', fog_max=0.7,
             street='#F1E2B2', pad='#ECD9A2', park='#D7DB9E', plaza='#E6D7A6', esp='#D9DDA2',
             water='#E4F2F9', shallow2='#C6E4F3', shallow='#A7D3EC', contour='#5B8FB5',
             tree='#AFBF78', tree_hi='#C7D494', tree_lo='#94A864',
             pal=['#EDD9A0', '#E8D195', '#F0DEAA', '#E4CB8C', '#EAD49A'], glass_from_pal=True,
             shadow='#6B5A2E', shadow_op=0.12, shade_tint='#C9B27A', warm='#FFF0C8', detail=False,
             hall='#E4CB8C', lume='#E4CB8C')

GRAY = dict(DAY)
GRAY.update(name='gray', sky_top='#DCE3EA', sky_hor='#F2F2F0', fogc='#F0F0EE',
            street='#E2E1DD', pad='#EEEDEA', park='#DCE3D5', plaza='#E8E8E2', esp='#DDE4D6',
            water='#D3DEE8', tree='#C7D2C0', tree_hi='#D8E0D2', tree_lo='#B3C0AB',
            pal=['#F5F4F1', '#F1F0EC', '#EEEDE9', '#F3F2EE'], glass='#E6E9ED', glass2='#E1E5EA',
            shadow='#5A6275', shadow_op=0.1, warm='#FFF6EC', warm_mix=0.1, shade_tint='#C9CCD6', detail=False,
            hall='#EEEDE9', lume='#F3F2EE')


def route_layers_day(main=True):
    if main:
        return [(15, '#FFFFFF', 1), (9, BLUE, 1), (2.2, '#8FB0FF', 0.9)]
    return [(12, '#FFFFFF', 1), (7, '#9AA6C4', 1)]


# ---------- query geometry: cafés within 200 m of a bike dock AND within 140 m of the park ----------
DOCKS = [(330, -490), (440, -700), (220, -350), (660, -770), (660, -260), (330, -840)]
def park_buffer(d, n=10):
    x0, x1, z0, z1 = PARK
    pts = []
    for (cx, cz, a0) in ((x1, z0, -90), (x1, z1, 0), (x0, z1, 90), (x0, z0, 180)):
        for k in range(n+1):
            a = math.radians(a0+90*k/n)
            pts.append((cx+d*math.cos(a), cz+d*math.sin(a)))
    return pts

def clip_convex(subj, clip):
    def inside(p, a, b): return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]) >= 0
    def inter(p, q, a, b):
        x1, y1, x2, y2 = p[0], p[1], q[0], q[1]; x3, y3, x4, y4 = a[0], a[1], b[0], b[1]
        den = (x1-x2)*(y3-y4)-(y1-y2)*(x3-x4)
        t = ((x1-x3)*(y3-y4)-(y1-y3)*(x3-x4))/den
        return (x1+t*(x2-x1), y1+t*(y2-y1))
    ar = sum(clip[i][0]*clip[(i+1) % len(clip)][1]-clip[(i+1) % len(clip)][0]*clip[i][1] for i in range(len(clip)))
    if ar < 0: clip = clip[::-1]
    out = subj
    for i in range(len(clip)):
        a, b = clip[i], clip[(i+1) % len(clip)]
        inp = out; out = []
        if not inp: break
        s = inp[-1]
        for e in inp:
            if inside(e, a, b):
                if not inside(s, a, b): out.append(inter(s, e, a, b))
                out.append(e)
            elif inside(s, a, b): out.append(inter(s, e, a, b))
            s = e
    return out

def dist_rect(x, z, r):
    dx = max(r[0]-x, 0, x-r[1]); dz = max(r[2]-z, 0, z-r[3]); return math.hypot(dx, dz)

PB = park_buffer(140)
QRINGS = [(x, z, 200, BLUE, 0.0, '#5C7CFF', '3 5') for (x, z) in DOCKS]
QRINGS.append((0, 0, PB, '#1E9E5A', 0.06, '#1E9E5A', '3 5'))
for (x, z) in DOCKS:
    circ = [(x+200*math.cos(a), z+200*math.sin(a)) for a in [k*2*math.pi/72 for k in range(72)]]
    inter = clip_convex(circ, PB)
    if len(inter) >= 3: QRINGS.append((0, 0, inter, '#2E5BFF', 0.16, '#2E5BFF', None))

rq = random.Random(5)
CAFES = []
for k in range(60):
    if rq.random() < 0.5:
        x = rq.choice([110, 220, 330, 440, 550, 660, 770])+rq.choice([-13, 13]); z = rq.uniform(-1050, -80)
    else:
        z = rq.choice(range(-1050, -70, 70))+rq.choice([-10, 10]); x = rq.uniform(60, 800)
    if dist_rect(x, z, PARK) < 4: continue
    m = dist_rect(x, z, PARK) <= 140 and any(math.hypot(x-dx, z-dz) <= 200 for (dx, dz) in DOCKS)
    CAFES.append((x, z, m))
QANCH = {}
mi = ni = 0
for (x, z, m) in CAFES:
    if m: QANCH['m%d' % mi] = (x, 0, z); mi += 1
    else: QANCH['n%d' % ni] = (x, 0, z); ni += 1
for i, (x, z) in enumerate(DOCKS): QANCH['dock%d' % i] = (x, 0, z)

EAT = {
    'r_lume': (250, 0, -835), 'r_ramen': (342, 0, -760), 'r_taco': (208, 0, -700), 'r_pizza': (300, 0, -913),
    'r_sushi': (318, 0, -640), 'r_bakery': (160, 0, -847), 'r_thai': (232, 0, -980), 'r_burger': (400, 0, -835),
    'r_wine': (428, 0, -600), 'r_cafe': (270, 0, -703), 'r_veg': (122, 0, -760), 'r_closed': (380, 0, -983),
}

CHARGERS = {'c0': (550, 0, -205), 'c1': (648, 0, -420), 'c2': (452, 0, -40), 'c3': (758, 0, -140),
            'c4': (500, 0, -287), 'c5': (620, 0, -63), 'c6': (342, 0, -330)}

EXPLORED = [
    [(440, 30), (440, -840)], [(440, -840), (110, -840)], [(330, -140), (330, -700)], [(440, -140), (220, -140)],
    [(220, -140), (220, -560)], [(220, -560), (0, -560)], [(0, -560), (0, -1050)], [(440, -350), (770, -350)],
    [(550, -350), (550, -560)], [(440, -560), (660, -560)], [(660, -560), (660, -980)], [(440, 30), (660, 30)],
    [(110, -840), (110, -1050)], [(0, -1050), (110, -1050)],
]
PASS = {'v_lume': (250, 0, -835), 'v_hall': (56, 15, -1015), 'v_park': (550, 0, -455), 'v_fern': (415, 11, -114),
        'v_bridge': (-40, 40, -1610), 'v_mystery': (720, 0, -1050), 'v_mystery2': (170, 0, -230), 'home': (440, 0, 30)}

BOAT = {'boat': (-330, 0, -950), 'r1': (-180, 0, -1080), 'r2': (-185, 0, -1320), 'g1': (-560, 0, -1100),
        'g2': (-560, 0, -1340), 'bridge': (-390, 40, -1610), 'marina': (-150, 0, -1900), 'r3': (-190, 0, -1520),
        'g3': (-570, 0, -1540)}

rs = random.Random(9)
SOUND = []
for k in range(90):
    z = rs.uniform(-2300, -900)
    e, w_ = shore_e(z), shore_w(z)
    x = rs.uniform(w_+15, e-15)
    edge = min(e-x, x-w_)
    dep = int(8 + min(edge, 220)/220*38 + rs.uniform(-3, 3))
    SOUND.append((x, z, str(dep)))
CHAN = [((-352, -960), (-395, -1560), 6, 5, 1.8, '#C2185B', 0.85), ((-395, -1560), (-420, -2400), 6, 5, 1.8, '#C2185B', 0.85)]

VIEWS = {
    'explore': dict(W=W, H=H, theme=DAY, target=(330, 0, -700), yaw=-36, pitch=17, dist=1300, fov=42, cy=434, region=REG),
    'eat': dict(W=W, H=H, theme=DAY, target=(270, 0, -800), yaw=-22, pitch=46, dist=760, fov=42, cy=300, region=REG,
                anchors=EAT),
    'place': dict(W=W, H=470, theme=DAY, target=(250, 6, -818), yaw=-150, pitch=33, dist=170, fov=40, cy=250,
                  highlight='lume', roof_details=420, region=(-900, 1400, -2400, 400)),
    'query': dict(W=W, H=H, theme=DAY, target=(420, 0, -540), yaw=-8, pitch=56, dist=1750, fov=40, cy=380, region=REG,
                  rings=QRINGS, anchors=QANCH, rings_top=True),
    'route': dict(W=W, H=H, theme=DAY, target=(345, 0, -405), yaw=-14, pitch=58, dist=2350, fov=38, cy=222, region=REG,
                  routes=[dict(pts=ROUTE_ALT, layers=[(22, '#FFFFFF', 1), (13, '#8E9BBD', 1)], r=20),
                          dict(pts=ROUTE_MAIN, layers=[(28, '#FFFFFF', 1), (17, BLUE, 1), (4, '#9DB8FF', 0.9)], r=22, xray=0.35)]),
    'nav': dict(W=W, H=H, theme=NIGHT, target=(446, 0, -775), yaw=-3, pitch=27, dist=150, fov=58, cy=470,
                region=(-4200, 2200, -7000, 900),
                anchors={'car': (446, 0, -690), 'turn': (446, 0, -846), 'dest': (250, 0, -846), 'hazard': (446, 0, -1000)},
                routes=[dict(pts=[(446, -560), (446, -846), (250, -846)], r=16,
                             layers=[(16, '#2E5BFF', 0.18), (10, '#3B72FF', 0.35), (6.5, '#4F86FF', 1), (1.3, '#CFE0FF', 0.9)],
                             xray=0.25)],
                markings=[((440, -400), (440, -2000), 3, 7, 0.28, '#E8C77A', 0.55),
                          ((451, -400), (451, -2000), 2.5, 6, 0.18, '#FFFFFF', 0.3),
                          ((429, -400), (429, -2000), 2.5, 6, 0.18, '#FFFFFF', 0.3)],
                crosswalks=[((430, -836.5), 'x', 13), ((430, -776.5), 'x', 13), ((430, -706.5), 'x', 13)]),
    'boat': dict(W=W, H=H, theme=CHART, target=(-385, 0, -1330), yaw=-6, pitch=15, dist=520, fov=52, cy=520, region=REG,
                 anchors=BOAT, markings=CHAN, soundings=SOUND),
    'charge': dict(W=W, H=H, theme=DAY, target=(560, 0, -250), yaw=24, pitch=50, dist=820, fov=42, cy=330, region=REG,
                   anchors=CHARGERS),
    'passport': dict(W=W, H=H, theme=GRAY, target=(380, 0, -520), yaw=-16, pitch=60, dist=2100, fov=40, cy=420, region=REG,
                     anchors=PASS,
                     routes=[dict(pts=seg, layers=[(26, '#FF6B3D', 0.16), (11, '#FF6B3D', 1), (3, '#FFD2B8', 0.9)], r=4, xray=0.45) for seg in EXPLORED]),
}


def main(names):
    t0 = time.time()
    anchors = {}
    af = os.path.join(OUT, 'anchors.json')
    if os.path.exists(af): anchors = json.load(open(af))
    cache = {}
    for n in names:
        cfg = VIEWS[n]
        reg = cfg['region']
        if reg not in cache: cache[reg] = build_city(reg)
        svg, anc, cam = render(cfg, cache[reg], n)
        open(os.path.join(OUT, n + '.svg'), 'w').write(svg)
        anchors[n] = anc
        print(n, '%.1f KB' % (len(svg)/1024), 'horizon %.0f' % cam.horizon_y(), '%.1fs' % (time.time()-t0))
    json.dump(anchors, open(af, 'w'), indent=1)


if __name__ == '__main__':
    main(sys.argv[1:] or list(VIEWS))
