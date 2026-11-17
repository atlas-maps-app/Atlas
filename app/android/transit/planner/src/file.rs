//! The timetable file, read in place (../../FORMAT.md). [`Timetable::open`] checks everything a search steers by;
//! after it, every accessor still gives a harmless value for a number out of range (0, `NONE`, an empty string), so no
//! path from a file's bytes ends in a panic.

use crate::NONE;

/// Why a file was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OpenError {
    /// Not a timetable file, or cut short before its section table.
    NotATimetable,
    /// A version this reader does not read.
    Version(u32),
    /// A section is missing, there twice, outside the file, or not as long as its records ask.
    Section([u8; 4]),
    /// A number in a section points outside what it names, or passes a bound. The second is the record.
    Record([u8; 4], u32),
    /// `META` lacks `first_day` or `days`, or they are out of range.
    Days,
}

/// The version this reader reads.
pub const VERSION: u32 = 1;
/// Times in the file (a first departure, a time along a profile, a walk) are below this: 2^20 s, twelve days.
pub const MAX_TIME: u32 = 1 << 20;
/// A pattern has at most this many stops.
pub const MAX_PATTERN: u32 = 65_535;
/// A file covers at most this many days.
pub const MAX_DAYS: u32 = 400;

#[derive(Clone, Copy, Default)]
struct Sec {
    off: usize,
    len: usize,
    n: u32,
}

const TAGS: [(&[u8; 4], usize); 22] = [
    (b"META", 0),
    (b"STOP", 12),
    (b"AGCY", 6),
    (b"LINE", 10),
    (b"PATN", 5),
    (b"PSTP", 1),
    (b"PHSG", 1),
    (b"PFAR", 1),
    (b"PDST", 1),
    (b"PROF", 2),
    (b"PTIM", 2),
    (b"ROUT", 3),
    (b"TRIP", 4),
    (b"TRIX", 5),
    (b"SERV", 0),
    (b"SVID", 1),
    (b"XFRI", 1),
    (b"XFER", 2),
    (b"SRTI", 1),
    (b"SRTE", 2),
    (b"TRUL", 8),
    (b"STRS", 0),
];
const META: usize = 0;
const STOP: usize = 1;
const AGCY: usize = 2;
const LINE: usize = 3;
const PATN: usize = 4;
const PSTP: usize = 5;
const PHSG: usize = 6;
const PFAR: usize = 7;
const PDST: usize = 8;
const PROF: usize = 9;
const PTIM: usize = 10;
const ROUT: usize = 11;
const TRIP: usize = 12;
const TRIX: usize = 13;
const SERV: usize = 14;
const SVID: usize = 15;
const XFRI: usize = 16;
const XFER: usize = 17;
const SRTI: usize = 18;
const SRTE: usize = 19;
const TRUL: usize = 20;
const STRS: usize = 21;

/// One country's timetable, over the file's bytes.
#[derive(Clone, Copy)]
pub struct Timetable<'a> {
    b: &'a [u8],
    sec: [Sec; 22],
    first_day: u32,
    days: u32,
    words: u32,
    change_s: u32,
}

/// A stop's fixed fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Stop {
    /// Latitude and longitude in 1e-7 degree.
    pub lat: i32,
    pub lon: i32,
    /// The station it is part of, or `NONE`.
    pub parent: u32,
    /// GTFS `location_type`: 0 a stop or platform, 1 a station, 2 an entrance, 3 a node, 4 a boarding area.
    pub kind: u8,
    /// GTFS `wheelchair_boarding`: 0 not said, 1 possible, 2 not possible.
    pub wheelchair: u8,
    /// Whose place `lat` and `lon` are: 0 the stop's own, 1 its station's (the feed gives the stop none), 2 nobody's
    /// (0, 0: the stop is nowhere, and nothing that measures may use it).
    pub place: u8,
}

/// Which of a stop's strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StopText {
    Id = 4,
    Code = 5,
    Name = 6,
    Platform = 7,
    Zone = 8,
    Ifopt = 9,
    Timezone = 10,
    Description = 11,
}

/// Which of a line's strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LineText {
    Id = 1,
    Short = 2,
    Long = 3,
    Description = 4,
    Url = 8,
}

/// Which of an operator's strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OperatorText {
    Id = 0,
    Name = 1,
    Url = 2,
    Timezone = 3,
    Phone = 4,
    Language = 5,
}

/// Which of a trip's strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TripText {
    /// GTFS `trip_id`.
    Id = 0,
    /// The id live data names the trip by (OVapi's `realtime_trip_id`).
    Realtime = 1,
    /// `trip_short_name`: a train's number.
    Number = 2,
    /// `trip_long_name`: its kind, "Intercity".
    Kind = 3,
    Block = 4,
}

/// A stop of a pattern.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PatternStop {
    pub stop: u32,
    /// GTFS `pickup_type`: 0 as usual, 1 none, 2 phone the operator, 3 ask the driver.
    pub pickup: u8,
    /// GTFS `drop_off_type`, likewise.
    pub drop_off: u8,
    /// The time here is approximate (`timepoint` 0).
    pub approximate: bool,
}

/// A route: the trips of one pattern of which none overtakes another, sorted by first departure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Route {
    pub pattern: u32,
    pub first_trip: u32,
    pub trips: u32,
    pub line: u32,
    /// The pattern's first record among the pattern stops, and how many stops it has.
    pub first_stop: u32,
    pub stops: u32,
}

/// A trip's fixed fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Trip {
    pub first_departure: u32,
    pub profile: u32,
    pub service: u32,
    /// GTFS `wheelchair_accessible` and `bikes_allowed`: 0 not said, 1 yes, 2 no.
    pub wheelchair: u8,
    pub bicycles: u8,
}

/// A transfer rule of the feed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rule {
    pub from_stop: u32,
    pub to_stop: u32,
    pub from_line: u32,
    pub to_line: u32,
    pub from_trip: u32,
    pub to_trip: u32,
    /// GTFS `transfer_type`: 0 recommended, 1 the second vehicle waits, 2 needs `min_time`, 3 not possible,
    /// 4 stay seated, 5 must alight.
    pub kind: u32,
    pub min_time: u32,
}

#[inline]
fn rd(b: &[u8], o: usize) -> u32 {
    match b.get(o..o.wrapping_add(4)) {
        Some(s) => u32::from_le_bytes([s[0], s[1], s[2], s[3]]),
        None => 0,
    }
}

impl<'a> Timetable<'a> {
    /// Opens a file's bytes, checking what FORMAT.md lists under "What a reader checks".
    pub fn open(b: &'a [u8]) -> Result<Timetable<'a>, OpenError> {
        if b.len() < 16 || &b[..8] != b"ATLASTT\0" {
            return Err(OpenError::NotATimetable);
        }
        let version = rd(b, 8);
        if version != VERSION {
            return Err(OpenError::Version(version));
        }
        let count = rd(b, 12) as usize;
        if count > 4096 || b.len() < 16 + count * 16 {
            return Err(OpenError::NotATimetable);
        }
        let mut sec = [Sec::default(); 22];
        let mut seen = [false; 22];
        for i in 0..count {
            let o = 16 + i * 16;
            let tag = [b[o], b[o + 1], b[o + 2], b[o + 3]];
            let Some(k) = TAGS.iter().position(|t| *t.0 == tag) else { continue };
            let (off, len, n) = (rd(b, o + 4) as usize, rd(b, o + 8) as usize, rd(b, o + 12));
            if seen[k] || off.checked_add(len).is_none_or(|end| end > b.len()) {
                return Err(OpenError::Section(tag));
            }
            let words = TAGS[k].1;
            if words != 0 && (n as usize).checked_mul(words * 4) != Some(len) {
                return Err(OpenError::Section(tag));
            }
            seen[k] = true;
            sec[k] = Sec { off, len, n };
        }
        if let Some(k) = seen.iter().position(|s| !*s) {
            return Err(OpenError::Section(*TAGS[k].0));
        }
        let mut t = Timetable { b, sec, first_day: 0, days: 0, words: 0, change_s: 60 };
        t.first_day = t.meta("first_day").and_then(parse_u32).ok_or(OpenError::Days)?;
        t.days = t.meta("days").and_then(parse_u32).ok_or(OpenError::Days)?;
        if t.days == 0 || t.days > MAX_DAYS || !valid_date(t.first_day) {
            return Err(OpenError::Days);
        }
        t.words = t.days.div_ceil(32);
        t.change_s = t.meta("change_same_stop_s").and_then(parse_u32).unwrap_or(60).min(MAX_TIME - 1);
        t.check()?;
        Ok(t)
    }

    fn check(&self) -> Result<(), OpenError> {
        let bad = |k: usize, i: u32| Err(OpenError::Record(*TAGS[k].0, i));
        let (stops, lines, patterns, profiles, routes, trips) =
            (self.stops(), self.lines(), self.patterns(), self.profiles(), self.routes(), self.trips());
        let (pstops, ptimes, services, operators) = (self.sec[PSTP].n, self.sec[PTIM].n, self.services(), self.operators());
        if stops >= 1 << 27 {
            return bad(STOP, stops);
        }
        if self.sec[SERV].len != services as usize * self.words as usize * 4 {
            return Err(OpenError::Section(*TAGS[SERV].0));
        }
        for k in [PHSG, PFAR, PDST] {
            if self.sec[k].n != pstops {
                return Err(OpenError::Section(*TAGS[k].0));
            }
        }
        if self.sec[TRIX].n != trips {
            return Err(OpenError::Section(*TAGS[TRIX].0));
        }
        if self.sec[SVID].n != services {
            return Err(OpenError::Section(*TAGS[SVID].0));
        }
        for i in 0..stops {
            let p = self.w(STOP, i, 12, 2);
            if p != NONE && p >= stops {
                return bad(STOP, i);
            }
        }
        for i in 0..lines {
            if self.w(LINE, i, 10, 0) >= operators {
                return bad(LINE, i);
            }
        }
        for i in 0..patterns {
            let (line, first, n) = (self.w(PATN, i, 5, 0), self.w(PATN, i, 5, 3), self.w(PATN, i, 5, 4));
            if line >= lines || !(2..=MAX_PATTERN).contains(&n) || first as u64 + n as u64 > pstops as u64 {
                return bad(PATN, i);
            }
        }
        for i in 0..pstops {
            if self.w(PSTP, i, 1, 0) & 0x07FF_FFFF >= stops {
                return bad(PSTP, i);
            }
        }
        for i in 0..profiles {
            let (first, n) = (self.w(PROF, i, 2, 0), self.w(PROF, i, 2, 1));
            if first as u64 + n as u64 > ptimes as u64 {
                return bad(PROF, i);
            }
        }
        for i in 0..ptimes {
            if self.w(PTIM, i, 2, 0) >= MAX_TIME || self.w(PTIM, i, 2, 1) >= MAX_TIME {
                return bad(PTIM, i);
            }
        }
        let mut next = 0u32;
        for i in 0..routes {
            let (pattern, first, n) = (self.w(ROUT, i, 3, 0), self.w(ROUT, i, 3, 1), self.w(ROUT, i, 3, 2));
            if pattern >= patterns || first != next || first as u64 + n as u64 > trips as u64 {
                return bad(ROUT, i);
            }
            next = first + n;
            let stops_here = self.w(PATN, pattern, 5, 4);
            for t in first..next {
                let (dep, profile, service) = (self.w(TRIP, t, 4, 0), self.w(TRIP, t, 4, 1), self.w(TRIP, t, 4, 2));
                if dep >= MAX_TIME || profile >= profiles || service >= services || self.w(PROF, profile, 2, 1) != stops_here {
                    return bad(TRIP, t);
                }
            }
        }
        if next != trips {
            return bad(ROUT, routes);
        }
        self.check_lists(XFRI, XFER, stops)?;
        self.check_lists(SRTI, SRTE, stops)?;
        for i in 0..self.sec[XFER].n {
            if self.w(XFER, i, 2, 0) >= stops || self.w(XFER, i, 2, 1) >= MAX_TIME {
                return bad(XFER, i);
            }
        }
        for i in 0..self.sec[SRTE].n {
            let (route, pos) = (self.w(SRTE, i, 2, 0), self.w(SRTE, i, 2, 1));
            if route >= routes || pos >= self.w(PATN, self.w(ROUT, route, 3, 0), 5, 4) {
                return bad(SRTE, i);
            }
        }
        for i in 0..self.rules() {
            let r = self.rule(i);
            let in_or_none = |v: u32, n: u32| v == NONE || v < n;
            if r.from_stop >= stops
                || r.to_stop >= stops
                || !in_or_none(r.from_line, lines)
                || !in_or_none(r.to_line, lines)
                || !in_or_none(r.from_trip, trips)
                || !in_or_none(r.to_trip, trips)
            {
                return bad(TRUL, i);
            }
        }
        Ok(())
    }

    /// A list by stop: one entry a stop and one more, rising, ending at the count of what it lists.
    fn check_lists(&self, index: usize, list: usize, stops: u32) -> Result<(), OpenError> {
        if self.sec[index].n as u64 != stops as u64 + 1 {
            return Err(OpenError::Section(*TAGS[index].0));
        }
        let mut prev = 0u32;
        for i in 0..=stops {
            let v = self.w(index, i, 1, 0);
            if v < prev {
                return Err(OpenError::Record(*TAGS[index].0, i));
            }
            prev = v;
        }
        if prev != self.sec[list].n {
            return Err(OpenError::Record(*TAGS[index].0, stops));
        }
        Ok(())
    }

    /// Field `f` of record `i` of a section whose records are `words` long; 0 when outside the section.
    #[inline]
    fn w(&self, k: usize, i: u32, words: usize, f: usize) -> u32 {
        let s = &self.sec[k];
        let o = (i as usize).wrapping_mul(words).wrapping_add(f).wrapping_mul(4);
        if o >= s.len {
            return 0;
        }
        rd(self.b, s.off + o)
    }

    /// The string at a reference; empty when the reference or the bytes are not sound.
    pub fn text(&self, at: u32) -> &'a str {
        let s = &self.sec[STRS];
        let blob = self.b.get(s.off..s.off + s.len).unwrap_or(&[]);
        let mut o = at as usize;
        let (mut len, mut shift) = (0usize, 0u32);
        loop {
            let Some(&c) = blob.get(o) else { return "" };
            o += 1;
            len |= ((c & 0x7F) as usize) << shift;
            if c & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift > 28 {
                return "";
            }
        }
        match o.checked_add(len).and_then(|end| blob.get(o..end)) {
            Some(bytes) => core::str::from_utf8(bytes).unwrap_or(""),
            None => "",
        }
    }

    /// A value of `META`, by its key.
    pub fn meta(&self, key: &str) -> Option<&'a str> {
        self.meta_lines().find(|(k, _)| *k == key).map(|(_, v)| v)
    }

    /// `META`'s keys and values, in the file's order.
    pub fn meta_lines(&self) -> impl Iterator<Item = (&'a str, &'a str)> {
        let s = &self.sec[META];
        let text = core::str::from_utf8(self.b.get(s.off..s.off + s.len).unwrap_or(&[])).unwrap_or("");
        text.lines().filter_map(|l| l.split_once('='))
    }

    /// The first service day, as YYYYMMDD, and how many days the file covers.
    pub fn first_day(&self) -> u32 {
        self.first_day
    }
    pub fn days(&self) -> u32 {
        self.days
    }
    /// The day number of a date (YYYYMMDD) counted from the first day; it may lie outside the file's days.
    pub fn day_of(&self, yyyymmdd: u32) -> Option<i32> {
        if !valid_date(yyyymmdd) {
            return None;
        }
        Some((day_number(yyyymmdd) - day_number(self.first_day)) as i32)
    }
    /// The date (YYYYMMDD) of a day number.
    pub fn date_of(&self, day: i32) -> u32 {
        date_of_number(day_number(self.first_day) + day as i64)
    }
    /// What a change at one stop costs, in seconds.
    pub fn change_seconds(&self) -> u32 {
        self.change_s
    }

    pub fn stops(&self) -> u32 {
        self.sec[STOP].n
    }
    pub fn operators(&self) -> u32 {
        self.sec[AGCY].n
    }
    pub fn lines(&self) -> u32 {
        self.sec[LINE].n
    }
    pub fn patterns(&self) -> u32 {
        self.sec[PATN].n
    }
    pub fn profiles(&self) -> u32 {
        self.sec[PROF].n
    }
    pub fn routes(&self) -> u32 {
        self.sec[ROUT].n
    }
    pub fn trips(&self) -> u32 {
        self.sec[TRIP].n
    }
    pub fn services(&self) -> u32 {
        if self.words == 0 { 0 } else { (self.sec[SERV].len / (self.words as usize * 4)) as u32 }
    }
    pub fn walks(&self) -> u32 {
        self.sec[XFER].n
    }
    /// How many pattern stops there are: every pattern's stops, one after another.
    pub fn pattern_stops(&self) -> u32 {
        self.sec[PSTP].n
    }
    pub fn rules(&self) -> u32 {
        self.sec[TRUL].n
    }
    /// How many stop times the file stands for: every trip's stops.
    pub fn stop_times(&self) -> u64 {
        (0..self.routes()).map(|r| self.route(r)).map(|r| r.trips as u64 * r.stops as u64).sum()
    }

    pub fn stop(&self, i: u32) -> Stop {
        let flags = self.w(STOP, i, 12, 3);
        Stop {
            lat: self.w(STOP, i, 12, 0) as i32,
            lon: self.w(STOP, i, 12, 1) as i32,
            parent: if i < self.stops() { self.w(STOP, i, 12, 2) } else { NONE },
            kind: (flags & 7) as u8,
            wheelchair: (flags >> 3 & 3) as u8,
            place: (flags >> 5 & 3) as u8,
        }
    }
    pub fn stop_text(&self, i: u32, which: StopText) -> &'a str {
        self.text(self.w(STOP, i, 12, which as usize))
    }
    /// The station a stop belongs to, or the stop itself when it has none.
    pub fn station_of(&self, i: u32) -> u32 {
        let p = self.stop(i).parent;
        if p == NONE { i } else { p }
    }

    pub fn operator_text(&self, i: u32, which: OperatorText) -> &'a str {
        self.text(self.w(AGCY, i, 6, which as usize))
    }

    pub fn line_operator(&self, i: u32) -> u32 {
        self.w(LINE, i, 10, 0)
    }
    /// GTFS `route_type`, extended types too.
    pub fn line_type(&self, i: u32) -> u32 {
        self.w(LINE, i, 10, 5)
    }
    /// A line's colour and its text's colour, as 0xRRGGBB, where the feed gives them.
    pub fn line_colours(&self, i: u32) -> (Option<u32>, Option<u32>) {
        let c = |v: u32| if v == NONE { None } else { Some(v & 0xFF_FFFF) };
        (c(self.w(LINE, i, 10, 6)), c(self.w(LINE, i, 10, 7)))
    }
    pub fn line_text(&self, i: u32, which: LineText) -> &'a str {
        self.text(self.w(LINE, i, 10, which as usize))
    }

    pub fn pattern_line(&self, p: u32) -> u32 {
        self.w(PATN, p, 5, 0)
    }
    pub fn pattern_headsign(&self, p: u32) -> &'a str {
        self.text(self.w(PATN, p, 5, 1))
    }
    /// 0, 1 or `NONE`.
    pub fn pattern_direction(&self, p: u32) -> u32 {
        self.w(PATN, p, 5, 2)
    }
    /// The stop at a place among the pattern stops (a route's `first_stop` plus the place along it).
    #[inline]
    pub fn pattern_stop(&self, at: u32) -> PatternStop {
        let v = self.w(PSTP, at, 1, 0);
        PatternStop { stop: v & 0x07FF_FFFF, pickup: (v >> 27 & 3) as u8, drop_off: (v >> 29 & 3) as u8, approximate: v >> 31 == 1 }
    }
    /// The headsign shown from a pattern stop on; empty where the pattern's own holds.
    pub fn pattern_stop_headsign(&self, at: u32) -> &'a str {
        self.text(self.w(PHSG, at, 1, 0))
    }
    /// Fare units travelled up to a pattern stop.
    pub fn pattern_stop_fare_units(&self, at: u32) -> Option<u32> {
        Some(self.w(PFAR, at, 1, 0)).filter(|v| *v != NONE && at < self.sec[PFAR].n)
    }
    /// Distance travelled up to a pattern stop, in the feed's unit.
    pub fn pattern_stop_distance(&self, at: u32) -> Option<u32> {
        Some(self.w(PDST, at, 1, 0)).filter(|v| *v != NONE && at < self.sec[PDST].n)
    }

    #[inline]
    pub fn route(&self, r: u32) -> Route {
        let pattern = self.w(ROUT, r, 3, 0);
        Route {
            pattern,
            first_trip: self.w(ROUT, r, 3, 1),
            trips: self.w(ROUT, r, 3, 2),
            line: self.w(PATN, pattern, 5, 0),
            first_stop: self.w(PATN, pattern, 5, 3),
            stops: self.w(PATN, pattern, 5, 4),
        }
    }
    /// The route a trip is in.
    pub fn route_of_trip(&self, trip: u32) -> u32 {
        let (mut lo, mut hi) = (0u32, self.routes());
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.w(ROUT, mid, 3, 1) <= trip { lo = mid + 1 } else { hi = mid }
        }
        lo.saturating_sub(1)
    }

    #[inline]
    pub fn trip(&self, t: u32) -> Trip {
        let flags = self.w(TRIP, t, 4, 3);
        Trip {
            first_departure: self.w(TRIP, t, 4, 0).min(MAX_TIME - 1),
            profile: self.w(TRIP, t, 4, 1),
            service: self.w(TRIP, t, 4, 2),
            wheelchair: (flags & 3) as u8,
            bicycles: (flags >> 2 & 3) as u8,
        }
    }
    pub fn trip_text(&self, t: u32, which: TripText) -> &'a str {
        self.text(self.w(TRIX, t, 5, which as usize))
    }
    /// Where a trip's times lie: its first departure and its profile's first record. With [`Timetable::time`], the
    /// two give every time of the trip.
    #[inline]
    pub fn trip_times(&self, t: u32) -> (u32, u32) {
        (self.w(TRIP, t, 4, 0).min(MAX_TIME - 1), self.w(PROF, self.w(TRIP, t, 4, 1), 2, 0))
    }
    /// Arrival and departure at a place along a trip, in seconds of its service day, from [`Timetable::trip_times`].
    #[inline]
    pub fn time(&self, times: (u32, u32), pos: u32) -> (u32, u32) {
        let at = times.1.wrapping_add(pos);
        (times.0 + self.w(PTIM, at, 2, 0).min(MAX_TIME - 1), times.0 + self.w(PTIM, at, 2, 1).min(MAX_TIME - 1))
    }
    /// Whether a service runs on a day counted from the first day.
    #[inline]
    pub fn runs(&self, service: u32, day: i32) -> bool {
        if day < 0 || day as u32 >= self.days || service >= self.services() {
            return false;
        }
        let o = service as usize * self.words as usize + (day as usize >> 5);
        rd(self.b, self.sec[SERV].off + o * 4) >> (day as u32 & 31) & 1 == 1
    }
    pub fn service_id(&self, service: u32) -> &'a str {
        self.text(self.w(SVID, service, 1, 0))
    }

    /// The walks from a stop: the records `from..to` of [`Timetable::walk`].
    #[inline]
    pub fn walks_from(&self, stop: u32) -> (u32, u32) {
        if stop >= self.stops() {
            return (0, 0);
        }
        let (a, b) = (self.w(XFRI, stop, 1, 0), self.w(XFRI, stop + 1, 1, 0));
        (a, b.max(a).min(self.sec[XFER].n))
    }
    /// A walk: the stop it leads to and its seconds.
    #[inline]
    pub fn walk(&self, i: u32) -> (u32, u32) {
        (self.w(XFER, i, 2, 0), self.w(XFER, i, 2, 1).min(MAX_TIME - 1))
    }
    /// The routes at a stop: the records `from..to` of [`Timetable::route_at`].
    #[inline]
    pub fn routes_at(&self, stop: u32) -> (u32, u32) {
        if stop >= self.stops() {
            return (0, 0);
        }
        let (a, b) = (self.w(SRTI, stop, 1, 0), self.w(SRTI, stop + 1, 1, 0));
        (a, b.max(a).min(self.sec[SRTE].n))
    }
    /// A route at a stop, and the stop's place along it.
    #[inline]
    pub fn route_at(&self, i: u32) -> (u32, u32) {
        (self.w(SRTE, i, 2, 0), self.w(SRTE, i, 2, 1))
    }

    pub fn rule(&self, i: u32) -> Rule {
        let f = |k: usize| self.w(TRUL, i, 8, k);
        Rule { from_stop: f(0), to_stop: f(1), from_line: f(2), to_line: f(3), from_trip: f(4), to_trip: f(5), kind: f(6), min_time: f(7) }
    }

    /// What `open` leaves out because it costs a pass over every stop time (FORMAT.md): a route's trips are sorted
    /// and never overtake; a profile's times never go back; the routes listed at each stop are the patterns' own; the
    /// rules are sorted. The first thing found wrong, in words.
    #[cfg(feature = "std")]
    pub fn verify(&self) -> Result<(), alloc::string::String> {
        use alloc::format;
        for p in 0..self.profiles() {
            let (first, n) = (self.w(PROF, p, 2, 0), self.w(PROF, p, 2, 1));
            let mut prev = 0;
            for i in first..first + n {
                let (a, d) = (self.w(PTIM, i, 2, 0), self.w(PTIM, i, 2, 1));
                if a < prev || d < a {
                    return Err(format!("profile {p}: its times go back at stop {}", i - first));
                }
                prev = d;
            }
        }
        let mut listed = alloc::vec![0u32; self.stops() as usize];
        for r in 0..self.routes() {
            let route = self.route(r);
            for t in route.first_trip + 1..route.first_trip + route.trips {
                let (a, b) = (self.trip_times(t - 1), self.trip_times(t));
                for pos in 0..route.stops {
                    let (x, y) = (self.time(a, pos), self.time(b, pos));
                    if y.0 < x.0 || y.1 < x.1 {
                        return Err(format!("route {r}: trip {t} overtakes the one before it at stop {pos}"));
                    }
                }
            }
            for pos in 0..route.stops {
                let stop = self.pattern_stop(route.first_stop + pos).stop;
                let (from, to) = self.routes_at(stop);
                if !(from..to).any(|i| self.route_at(i) == (r, pos)) {
                    return Err(format!("route {r}: not listed at its stop {pos}"));
                }
                listed[stop as usize] += 1;
            }
        }
        for s in 0..self.stops() {
            let (from, to) = self.routes_at(s);
            if to - from != listed[s as usize] {
                return Err(format!("stop {s}: lists routes that do not call there"));
            }
            for i in from..to {
                let (r, pos) = self.route_at(i);
                let route = self.route(r);
                if self.pattern_stop(route.first_stop + pos).stop != s {
                    return Err(format!("stop {s}: lists route {r} at a place that is another stop"));
                }
            }
        }
        for i in 1..self.rules() {
            let (a, b) = (self.rule(i - 1), self.rule(i));
            if (a.from_stop, a.to_stop) > (b.from_stop, b.to_stop) {
                return Err(format!("transfer rule {i}: not sorted by its stops"));
            }
        }
        Ok(())
    }
}

fn parse_u32(s: &str) -> Option<u32> {
    if s.is_empty() || s.len() > 9 || !s.bytes().all(|c| c.is_ascii_digit()) {
        return None;
    }
    s.bytes().try_fold(0u32, |n, c| n.checked_mul(10)?.checked_add((c - b'0') as u32))
}

fn valid_date(d: u32) -> bool {
    let (y, m, day) = (d / 10000, d / 100 % 100, d % 100);
    (1900..=2200).contains(&y) && (1..=12).contains(&m) && (1..=31).contains(&day)
}

/// Days since 1 March of year 0, for a date as YYYYMMDD (the civil calendar, by Howard Hinnant's counting).
pub(crate) fn day_number(d: u32) -> i64 {
    let (mut y, m, day) = ((d / 10000) as i64, (d / 100 % 100) as i64, (d % 100) as i64);
    if m <= 2 {
        y -= 1;
    }
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    era * 146_097 + yoe * 365 + yoe / 4 - yoe / 100 + doy
}

pub(crate) fn date_of_number(n: i64) -> u32 {
    let era = n.div_euclid(146_097);
    let doe = n - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y.clamp(0, 9999) * 10000 + m * 100 + day) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_count_both_ways() {
        assert_eq!(day_number(20261117) - day_number(20261116), 1);
        assert_eq!(day_number(20270301) - day_number(20270228), 1);
        assert_eq!(day_number(20280301) - day_number(20280228), 2);
        for d in [19991231u32, 20000229, 20261116, 20261231, 20270101, 20280229] {
            assert_eq!(date_of_number(day_number(d)), d);
        }
    }

    #[test]
    fn refuses_what_is_no_timetable() {
        assert_eq!(Timetable::open(b"").err(), Some(OpenError::NotATimetable));
        assert_eq!(Timetable::open(b"ATLASTT\0\x02\0\0\0\0\0\0\0").err(), Some(OpenError::Version(2)));
        assert_eq!(Timetable::open(b"ATLASTT\0\x01\0\0\0\0\0\0\0").err(), Some(OpenError::Section(*b"META")));
    }
}
