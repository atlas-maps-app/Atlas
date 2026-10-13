"""NDW open data (DATEX II v3, Verkeersdata NL profile): travel-time sites with their geometry, and one snapshot's
measured travel time beside the normally expected one. No account needed: opendata.ndw.nu.
    sites = load_sites(cfg_gz, bbox)     # {id: dict(name, pts=[(lat, lon)], m)}
    meas = load_times(snapshot_gz)       # {id: dict(t=seconds, normal=seconds, at=iso)}"""
import gzip, math, xml.etree.ElementTree as ET
NS = {'roa': 'http://datex2.eu/schema/3/roadTrafficData', 'loc': 'http://datex2.eu/schema/3/locationReferencing', 'com': 'http://datex2.eu/schema/3/common'}
R = '{%s}' % NS['roa']; L = '{%s}' % NS['loc']; C = '{%s}' % NS['com']
def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))
def load_sites(path, bbox=None):
    out = {}
    for ev, el in ET.iterparse(gzip.open(path), events=('end',)):
        if el.tag != R + 'measurementSite': continue
        sid = el.get('id')
        name = el.find('.//' + R + 'measurementSiteName//' + C + 'value')
        pts = []
        for pl in el.iter(L + 'posList'):
            v = [float(x) for x in pl.text.split()]
            seg = list(zip(v[0::2], v[1::2]))
            pts += seg if not pts else seg[1:] if seg and pts[-1] == seg[0] else seg
        el.clear()
        if not pts: continue
        if bbox and not any(bbox[0] <= p[0] <= bbox[2] and bbox[1] <= p[1] <= bbox[3] for p in pts): continue
        out[sid] = dict(name=name.text if name is not None else sid, pts=pts, m=sum(hav(a, b) for a, b in zip(pts, pts[1:])))
    return out
def load_times(path):
    out = {}
    for ev, el in ET.iterparse(gzip.open(path), events=('end',)):
        if el.tag != R + 'siteMeasurements': continue
        ref = el.find(R + 'measurementSiteReference'); sid = ref.get('id') if ref is not None else None
        tt = el.find('.//' + R + 'travelTime/' + R + 'duration'); nt = el.find('.//' + R + 'normallyExpectedTravelTime/' + R + 'duration')
        at = el.find('.//' + R + 'measurementTimeDefault/' + R + 'timeValue')
        if sid and tt is not None:
            t = float(tt.text)
            out[sid] = dict(t=t if t >= 0 else None, normal=float(nt.text) if nt is not None else None, at=at.text if at is not None else None)
        el.clear()
    return out
