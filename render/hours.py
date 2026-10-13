"""opening_hours as a week: what the door plaque shows. Weekly rules only; dated rules count only on their own date (see real.open_at)."""
import re
from real import DAYS, RULE, MONTHS, _days, open_at

DATED = re.compile(r'(?:\d{4}\s+)?(?:%s)\s+\d' % '|'.join(MONTHS))

def _mins(s):
    s = s.strip(); h, m = s.split(':')
    return int(h) * 60 + int(m)

def week(oh):
    """{day: [(start, end), ...]} in minutes, end past 1440 when a range runs past midnight; [] = closed. None if not understood."""
    if not oh: return None
    s = oh.strip()
    if s == '24/7': return {d: [(0, 1440)] for d in DAYS}
    kept = [re.sub(r',?\bPH\b', '', p.strip()) for p in s.split(';') if p.strip() and not DATED.match(p.strip())]
    out = {d: [] for d in DAYS}; seen = False
    for m in RULE.finditer('; '.join(kept)):
        seen = True
        sel, times = m.group(1), m.group(2)
        days = _days(sel) if sel else set(DAYS)
        rngs = []
        if times not in ('off', 'closed'):
            for r in re.split(r'\s*,\s*', times):
                a, b = r.split('-'); ta, tb = _mins(a), _mins(b)
                if tb <= ta: tb += 1440
                rngs.append((ta, tb))
        for d in days: out[d] = rngs                # a later rule replaces an earlier one for its days
    return out if seen else None

def hm(t):
    t %= 1440
    return '%02d:%02d' % (t // 60, t % 60)

def at(oh, wk, day, hhmm, date):
    """(state, words) at a moment: state 1 open, 0 closed, -1 not understood."""
    st = open_at(oh, day, hhmm, date=date)
    t = hhmm[0] * 60 + hhmm[1]
    if st is None or wk is None: return -1, 'hours mapped, not read here'
    i = DAYS.index(day)
    if st:
        for (a, b) in wk[day]:
            if a <= t < b: return 1, ('open all day' if (a, b) == (0, 1440) else 'until ' + hm(b))
        prev = DAYS[(i - 1) % 7]                    # open past midnight from the day before
        for (a, b) in wk[prev]:
            if b > 1440 and t < b - 1440: return 1, 'until ' + hm(b)
        return 1, 'open'
    for (a, b) in sorted(wk[day]):
        if a > t: return 0, 'opens ' + hm(a)
    for k in range(1, 8):
        d = DAYS[(i + k) % 7]
        if wk[d]: return 0, 'opens %s %s' % ('tomorrow' if k == 1 else d, hm(min(a for a, _ in wk[d])))
    return 0, 'closed'

def plaque(oh, moments):
    """moments: {key: (day, (h, m), date)} -> compact data for the board."""
    wk = week(oh)
    return dict(raw=oh, wk=[[list(r) for r in wk[d]] for d in DAYS] if wk else None,
                m={k: list(at(oh, wk, d, t, dt)) + [DAYS.index(d), t[0] * 60 + t[1]] for k, (d, t, dt) in moments.items()})

def stickers(t):
    """What a shop door says beside its hours, only where it is mapped: access, payment, food, Wi-Fi, terrace, takeaway, booking."""
    out = []
    wc = t.get('wheelchair')
    if wc in ('yes', 'designated'): out.append(['Wheelchair: yes', 'ok'])
    elif wc == 'limited': out.append(['Wheelchair: limited', 'mid'])
    elif wc == 'no': out.append(['Wheelchair: no', 'no'])
    if t.get('payment:cash') == 'no': out.append(['No cash', 'info'])
    elif any(t.get('payment:' + k) == 'yes' for k in ('credit_cards', 'debit_cards', 'cards', 'mastercard', 'visa', 'maestro', 'contactless')): out.append(['Cards', 'info'])
    v, vg = t.get('diet:vegan'), t.get('diet:vegetarian')
    if v == 'only': out.append(['Vegan only', 'info'])
    elif v == 'yes': out.append(['Vegan options', 'info'])
    elif vg == 'only': out.append(['Vegetarian only', 'info'])
    elif vg == 'yes': out.append(['Vegetarian options', 'info'])
    if t.get('internet_access') in ('wlan', 'yes'): out.append(['Wi-Fi' + (' free' if t.get('internet_access:fee') == 'no' else ''), 'info'])
    if t.get('outdoor_seating') == 'yes': out.append(['Terrace', 'info'])
    if t.get('takeaway') == 'only': out.append(['Takeaway only', 'info'])
    elif t.get('takeaway') == 'yes': out.append(['Takeaway', 'info'])
    if t.get('reservation') == 'required': out.append(['Booking required', 'info'])
    return out[:4]
