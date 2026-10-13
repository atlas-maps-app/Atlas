"""A small GTFS-realtime reader with no dependencies: decodes the protobuf wire format against the fields Atlas uses
(alerts, trip updates, vehicle positions). Field numbers from gtfs-realtime.proto."""
import struct
def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]; i += 1; r |= (c & 0x7F) << s; s += 7
        if c < 0x80: return r, i
def fields(b):
    i, n = 0, len(b)
    while i < n:
        k, i = _varint(b, i); f, w = k >> 3, k & 7
        if w == 0: v, i = _varint(b, i)
        elif w == 1: v = b[i:i + 8]; i += 8
        elif w == 2:
            L, i = _varint(b, i); v = b[i:i + L]; i += L
        elif w == 5: v = b[i:i + 4]; i += 4
        else: raise ValueError('wire type %d' % w)
        yield f, w, v
def _s(v): return v.decode('utf-8', 'replace')
def _sint(v): return v - (1 << 64) if v >= 1 << 63 else v
def _f32(v): return struct.unpack('<f', v)[0]
def trip_desc(b):
    d = {}
    for f, w, v in fields(b):
        if f == 1: d['trip_id'] = _s(v)
        elif f == 2: d['start_time'] = _s(v)
        elif f == 3: d['start_date'] = _s(v)
        elif f == 4: d['rel'] = v
        elif f == 5: d['route_id'] = _s(v)
        elif f == 6: d['dir'] = v
    return d
def ste(b):
    d = {}
    for f, w, v in fields(b):
        if f == 1: d['delay'] = _sint(v) if v > 2**31 else v
        elif f == 2: d['time'] = v
    if d.get('delay', 0) >= 2**31: d['delay'] -= 2**32
    return d
def stu(b):
    d = {}
    for f, w, v in fields(b):
        if f == 1: d['seq'] = v
        elif f == 4: d['stop_id'] = _s(v)
        elif f == 2: d['arr'] = ste(v)
        elif f == 3: d['dep'] = ste(v)
        elif f == 5: d['rel'] = v
    return d
def tstr(b):
    out = {}
    for f, w, v in fields(b):
        if f == 1:
            t = {}
            for g, _, x in fields(v): t[g] = _s(x)
            out[t.get(2, '')] = t.get(1, '')
    return out
def alert(b):
    d = {'periods': [], 'entities': []}
    for f, w, v in fields(b):
        if f == 1:
            p = {}
            for g, _, x in fields(v): p['start' if g == 1 else 'end'] = x
            d['periods'].append(p)
        elif f == 5:
            e = {}
            for g, _, x in fields(v):
                if g == 1: e['agency'] = _s(x)
                elif g == 2: e['route'] = _s(x)
                elif g == 3: e['route_type'] = x
                elif g == 4: e['trip'] = trip_desc(x)
                elif g == 5: e['stop'] = _s(x)
            d['entities'].append(e)
        elif f == 6: d['cause'] = v
        elif f == 7: d['effect'] = v
        elif f == 8: d['url'] = tstr(v)
        elif f == 10: d['header'] = tstr(v)
        elif f == 11: d['text'] = tstr(v)
    return d
def trip_update(b):
    d = {'stu': []}
    for f, w, v in fields(b):
        if f == 1: d['trip'] = trip_desc(v)
        elif f == 2: d['stu'].append(stu(v))
        elif f == 4: d['ts'] = v
        elif f == 5: d['delay'] = v
    return d
def vehicle(b):
    d = {}
    for f, w, v in fields(b):
        if f == 1: d['trip'] = trip_desc(v)
        elif f == 2:
            for g, _, x in fields(v):
                if g == 1: d['lat'] = _f32(x)
                elif g == 2: d['lon'] = _f32(x)
                elif g == 3: d['bearing'] = _f32(x)
                elif g == 5: d['speed'] = _f32(x)
        elif f == 3: d['seq'] = v
        elif f == 7: d['stop_id'] = _s(v)
        elif f == 4: d['status'] = v
        elif f == 5: d['ts'] = v
        elif f == 8:
            for g, _, x in fields(v):
                if g == 1: d['vid'] = _s(x)
                elif g == 2: d['vlabel'] = _s(x)
    return d
def feed(path):
    b = open(path, 'rb').read(); head = {}; ents = []
    for f, w, v in fields(b):
        if f == 1:
            for g, _, x in fields(v):
                if g == 3: head['ts'] = x
        elif f == 2:
            e = {}
            for g, _, x in fields(v):
                if g == 1: e['id'] = _s(x)
                elif g == 3: e['tu'] = trip_update(x)
                elif g == 4: e['vp'] = vehicle(x)
                elif g == 5: e['alert'] = alert(x)
            ents.append(e)
    return head, ents
