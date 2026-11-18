//! atlas-transit: the planner at a prompt, over a timetable file (../../FORMAT.md).
//!
//!   atlas-transit info  <file>                       what the file holds
//!   atlas-transit check <file>                       everything `open` leaves out (FORMAT.md, "What a reader checks")
//!   atlas-transit days  <file>                       how the file's days are filled, week by week, and by whom
//!   atlas-transit stops <file> <text>                stops and stations by a part of their name
//!   atlas-transit trip  <file> <number> [--date D]   one train or bus by its number: its stops, times and ids
//!   atlas-transit plan  <file> --from P --to P [--date YYYYMMDD] [--time HH:MM] [--window MIN] [--arrive-by]
//!                       [--max-rides N] [--never W]… [--avoid W[=MIN]]… [--prefer W[=MIN]]… [--every-option]
//!                       [--no-rules] [--scan]
//!   atlas-transit fixed <file> <journeys.tsv> [--write]   the fixed journeys: checked against the scan, or written
//!   atlas-transit bench <file> [--date D] [--time HH:MM] [--pairs N] [--points] [--no-rules] [--quick]
//!                                                         times, and agreement with the scan, on random pairs
//!   atlas-transit fuzz  <file> <runs> [seed]              files a little wrong, and queries at their edges
//!
//! A place P is `lat,lon`, `id:<stop_id>`, or a name (every stop called exactly that; else the one station whose
//! name contains it). A wish W is `stop:<name>`, `station:<name>`, `area:<lat>,<lon>,<metres>`,
//! `box:<south>,<west>,<north>,<east>`, `line:<short name>`, `operator:<name>`, `mode:<rail|bus|tram|metro|ferry|…>`,
//! `kind:<Intercity|Sprinter|…>`, `trip:<number>`, `no-wheelchair`, `wheelchair-unknown`, `no-bicycles`,
//! `bicycles-unknown`; a stop, station or area with `+through` also counts for riding past. Times are the file's
//! service-day times (noon less twelve hours), read as the clock's: on the day the clock changes they are an hour
//! off the clock, as the feed's own are (FORMAT.md, "Time").

use atlas_transit::file::{LineText, OperatorText, StopText, TripText};
use atlas_transit::reference::{Reference, impossible};
use atlas_transit::{Area, How, Journey, Leg, Mode, NONE, Place, Planner, Query, Spot, Timetable, What, Wish, fuzz};
use std::fmt::Write as _;
use std::process::ExitCode;
use std::time::Instant;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("atlas-transit: {e}");
            ExitCode::from(2)
        }
    }
}

fn run(args: &[String]) -> Result<ExitCode, String> {
    let (Some(cmd), Some(path)) = (args.first(), args.get(1)) else {
        return Err("usage: atlas-transit <info|check|days|stops|trip|plan|fixed|bench|fuzz> <file> …  (the source's head has the rest)".into());
    };
    let t0 = Instant::now();
    let bytes = std::fs::read(path).map_err(|e| format!("{path}: {e}"))?;
    let read_ms = ms(t0);
    let t0 = Instant::now();
    let tt = Timetable::open(&bytes).map_err(|e| format!("{path}: {e:?}"))?;
    let open_ms = ms(t0);
    let rest = &args[2..];
    match cmd.as_str() {
        "info" => info(&tt, bytes.len(), read_ms, open_ms),
        "check" => {
            let t0 = Instant::now();
            tt.verify()?;
            println!("sound: routes sorted and never overtaking, profiles never going back, the routes at each stop the patterns' own, rules sorted ({:.0} ms)", ms(t0));
        }
        "days" => days(&tt),
        "stops" => stops(&tt, rest.first().ok_or("stops: a part of a name")?),
        "trip" => trip(&tt, rest)?,
        "plan" => plan(&tt, rest)?,
        "fixed" => return fixed(&tt, rest),
        "bench" => return bench(&tt, rest, open_ms),
        "fuzz" => {
            let runs: u64 = rest.first().and_then(|s| s.parse().ok()).ok_or("fuzz: how many runs")?;
            let mut rng = fuzz::Rng(rest.get(1).and_then(|s| s.parse().ok()).unwrap_or(0x9E37_79B9_7F4A_7C15u64) | 1);
            let (t0, mut opened, mut sum) = (Instant::now(), 0u64, 0u64);
            for _ in 0..runs / 8 {
                sum += fuzz::file(&bytes, &mut rng, 8);
            }
            for _ in 0..runs {
                let b = fuzz::mutate(&bytes, &mut rng);
                let got = fuzz::file(&b, &mut rng, 2);
                opened += (got > 0) as u64;
                sum += got;
            }
            println!("{runs} files a little wrong ({opened} still opened and were asked 2 journeys each) and {} queries on the sound file: no panic, {:.1} s ({sum})", runs / 8 * 8, ms(t0) / 1000.0);
        }
        other => return Err(format!("{other}: no such command")),
    }
    Ok(ExitCode::SUCCESS)
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1e3
}

fn option<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).map(|s| s.as_str())
}

fn options<'a>(args: &'a [String], name: &str) -> Vec<&'a str> {
    args.windows(2).filter(|w| w[0] == name).map(|w| w[1].as_str()).collect()
}

/// A clock time of a service day, with the days it lies past (or before) the query's.
fn clock(t: i32) -> String {
    let (d, s) = (t.div_euclid(86_400), t.rem_euclid(86_400));
    let mut out = format!("{:02}:{:02}", s / 3600, s / 60 % 60);
    if s % 60 != 0 {
        let _ = write!(out, ":{:02}", s % 60);
    }
    if d != 0 {
        let _ = write!(out, "{d:+}d");
    }
    out
}

fn parse_clock(s: &str) -> Result<u32, String> {
    let p: Vec<u32> = s.split(':').map(|x| x.parse().map_err(|_| format!("{s}: not a time"))).collect::<Result<_, _>>()?;
    match p[..] {
        [h, m] => Ok(h * 3600 + m * 60),
        [h, m, sec] => Ok(h * 3600 + m * 60 + sec),
        _ => Err(format!("{s}: not a time")),
    }
}

fn degrees(s: &str) -> Result<i32, String> {
    // decimal text to 1e-7 degree without a float
    let t = s.trim();
    let (neg, t) = match t.strip_prefix('-') {
        Some(r) => (true, r),
        None => (false, t),
    };
    let (whole, frac) = t.split_once('.').unwrap_or((t, ""));
    let whole: i64 = whole.parse().map_err(|_| format!("{s}: not degrees"))?;
    let mut f = 0i64;
    for i in 0..7 {
        f = f * 10 + frac.as_bytes().get(i).map_or(Ok(0), |c| if c.is_ascii_digit() { Ok((c - b'0') as i64) } else { Err(format!("{s}: not degrees")) })?;
    }
    let v = whole * 10_000_000 + f;
    i32::try_from(if neg { -v } else { v }).map_err(|_| format!("{s}: not degrees"))
}

fn day_of(tt: &Timetable, args: &[String]) -> Result<i32, String> {
    match option(args, "--date") {
        Some(d) => d.parse().ok().and_then(|d| tt.day_of(d)).ok_or(format!("{d}: not a date (YYYYMMDD)")),
        None => Ok(0),
    }
}

fn stop_label(tt: &Timetable, s: u32) -> String {
    let (name, platform) = (tt.stop_text(s, StopText::Name), tt.stop_text(s, StopText::Platform));
    if platform.is_empty() { name.to_string() } else { format!("{name} (platform {platform})") }
}

fn info(tt: &Timetable, bytes: usize, read_ms: f64, open_ms: f64) {
    println!("{bytes} bytes, read in {read_ms:.1} ms, opened and checked in {open_ms:.1} ms");
    println!("days: {} from {} to {}", tt.days(), tt.first_day(), tt.date_of(tt.days() as i32 - 1));
    println!(
        "stops {}, operators {}, lines {}, patterns {}, routes {}, profiles {}, trips {}, services {}, walks {}, transfer rules {}",
        tt.stops(),
        tt.operators(),
        tt.lines(),
        tt.patterns(),
        tt.routes(),
        tt.profiles(),
        tt.trips(),
        tt.services(),
        tt.walks(),
        tt.rules()
    );
    println!("stop times it stands for: {}", tt.stop_times());
    for (k, v) in tt.meta_lines() {
        println!("  {k} = {v}");
    }
}

/// A count with its thousands apart.
fn grouped(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// How the file's days are filled. A feed's days are not all as full: the operators hand in their timetables to
/// different days, and around a change of timetable the old and the new are both in it. By week: the trips that run
/// on its emptiest and its fullest day, the operators and lines with a trip in it, and what a file ending with that
/// week would hold (the trips with a day up to there; a trip is in the file once, however many days it runs).
fn days(tt: &Timetable) {
    let n = tt.days() as usize;
    let weeks = n.div_ceil(7);
    // per service: its trips, their stop times, its first day
    let services = tt.services() as usize;
    let (mut trips_of, mut times_of) = (vec![0u64; services], vec![0u64; services]);
    let first_of: Vec<usize> = (0..services).map(|s| (0..n).find(|d| tt.runs(s as u32, *d as i32)).unwrap_or(n)).collect();
    let weeks_of: Vec<Vec<bool>> = (0..services).map(|s| (0..weeks).map(|w| (w * 7..(w * 7 + 7).min(n)).any(|d| tt.runs(s as u32, d as i32))).collect()).collect();
    let mut line_weeks = vec![vec![false; weeks]; tt.lines() as usize];
    let mut operator_days = vec![(n, 0usize, 0u64); tt.operators() as usize]; // first day, last day, trips
    for r in 0..tt.routes() {
        let route = tt.route(r);
        let operator = tt.line_operator(route.line) as usize;
        for t in route.first_trip..route.first_trip.saturating_add(route.trips).min(tt.trips()) {
            let s = tt.trip(t).service as usize;
            if s >= services {
                continue;
            }
            trips_of[s] += 1;
            times_of[s] += route.stops as u64;
            if let Some(lw) = line_weeks.get_mut(route.line as usize) {
                for (w, runs) in weeks_of[s].iter().enumerate() {
                    lw[w] |= *runs;
                }
            }
            if let Some(o) = operator_days.get_mut(operator) {
                let last = (0..n).rev().find(|d| tt.runs(s as u32, *d as i32)).unwrap_or(0);
                *o = (o.0.min(first_of[s]), o.1.max(last), o.2 + 1);
            }
        }
    }
    let running: Vec<u64> = (0..n).map(|d| (0..services).filter(|s| tt.runs(*s as u32, d as i32)).map(|s| trips_of[s]).sum()).collect();
    let (all_trips, all_times): (u64, u64) = (trips_of.iter().sum(), times_of.iter().sum());
    println!("{} days from {} to {}: {} trips, {} stop times", n, tt.first_day(), tt.date_of(n as i32 - 1), grouped(all_trips), grouped(all_times));
    println!("week  from      days  trips a day, least to most   operators  lines   a file ending here would hold");
    for w in 0..weeks {
        let (from, to) = (w * 7, (w * 7 + 7).min(n));
        let (least, most) = (running[from..to].iter().min().copied().unwrap_or(0), running[from..to].iter().max().copied().unwrap_or(0));
        let lines = line_weeks.iter().filter(|lw| lw[w]).count();
        let operators = (0..tt.operators() as usize).filter(|o| (0..tt.lines()).any(|l| tt.line_operator(l) as usize == *o && line_weeks[l as usize][w])).count();
        let (kept_trips, kept_times): (u64, u64) = (0..services).filter(|s| first_of[*s] < to).fold((0, 0), |a, s| (a.0 + trips_of[s], a.1 + times_of[s]));
        println!(
            "{:>4}  {}  {:>4}  {:>11} to {:>11}   {:>9}  {:>5}   {:>9} trips ({:>3} %), {:>10} stop times ({:>3} %)",
            w + 1,
            tt.date_of(from as i32),
            to - from,
            grouped(least),
            grouped(most),
            operators,
            lines,
            grouped(kept_trips),
            kept_trips * 100 / all_trips.max(1),
            grouped(kept_times),
            kept_times * 100 / all_times.max(1)
        );
    }
    // the operators whose trips do not reach the file's last week, or begin after its first
    let mut short: Vec<(usize, usize, u64, usize)> =
        operator_days.iter().enumerate().filter(|(_, o)| o.2 > 0 && (o.1 + 7 < n || o.0 >= 7)).map(|(i, o)| (o.1, o.0, o.2, i)).collect();
    short.sort();
    let whole = operator_days.iter().filter(|o| o.2 > 0).count() - short.len();
    println!("operators with trips from the first week to the last: {whole}; the others, by their last day:");
    for (last, first, trips, i) in short {
        println!("  {} to {}  {:>9} trips  {}", tt.date_of(first as i32), tt.date_of(last as i32), grouped(trips), tt.operator_text(i as u32, OperatorText::Name));
    }
}

fn stops(tt: &Timetable, text: &str) {
    let want = text.to_lowercase();
    let mut shown = 0;
    for s in 0..tt.stops() {
        let name = tt.stop_text(s, StopText::Name);
        if !name.to_lowercase().contains(&want) {
            continue;
        }
        let st = tt.stop(s);
        let (lo, hi) = tt.routes_at(s);
        println!(
            "{s:>6}  {:<10} {:<8} {}  {:.5},{:.5}  routes {}  id {}{}",
            ["stop", "station", "entrance", "node", "boarding"].get(st.kind as usize).unwrap_or(&"?"),
            tt.stop_text(s, StopText::Platform),
            name,
            st.lat as f64 / 1e7,
            st.lon as f64 / 1e7,
            hi - lo,
            tt.stop_text(s, StopText::Id),
            if st.parent == NONE { String::new() } else { format!("  in {}", st.parent) }
        );
        shown += 1;
        if shown == 60 {
            println!("… and more");
            break;
        }
    }
}

/// A place from the prompt.
fn place(tt: &Timetable, text: &str) -> Result<Place, String> {
    if let Some(id) = text.strip_prefix("id:") {
        return (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).map(Place::Stop).ok_or(format!("{id}: no stop has this id"));
    }
    if let Some((a, b)) = text.split_once(',') {
        if let (Ok(lat), Ok(lon)) = (degrees(a), degrees(b)) {
            return Ok(Place::Point { lat, lon });
        }
    }
    named(tt, text).map(|stops| Place::Walks(stops.into_iter().map(|s| (s, 0)).collect()))
}

/// The stops a name stands for: every stop called exactly that, with the other stops of their stations; else those
/// of the one station whose name contains it.
fn named(tt: &Timetable, text: &str) -> Result<Vec<u32>, String> {
    let exact: Vec<u32> = (0..tt.stops()).filter(|s| tt.stop_text(*s, StopText::Name).eq_ignore_ascii_case(text)).collect();
    let mut stations: Vec<u32> = exact.iter().map(|s| tt.station_of(*s)).collect();
    if stations.is_empty() {
        let want = text.to_lowercase();
        stations = (0..tt.stops()).filter(|s| tt.stop_text(*s, StopText::Name).to_lowercase().contains(&want)).map(|s| tt.station_of(s)).collect();
        stations.sort_unstable();
        stations.dedup();
        if stations.len() > 1 {
            let names: Vec<&str> = stations.iter().take(8).map(|s| tt.stop_text(*s, StopText::Name)).collect();
            return Err(format!("{text}: {} places contain this; say one exactly: {}", stations.len(), names.join(" | ")));
        }
    }
    stations.sort_unstable();
    stations.dedup();
    let out: Vec<u32> = (0..tt.stops()).filter(|s| tt.stop(*s).kind == 0 && stations.binary_search(&tt.station_of(*s)).is_ok()).collect();
    if out.is_empty() { Err(format!("{text}: no stop is called this")) } else { Ok(out) }
}

/// A wish from the prompt; a name may stand for several things, so several wishes.
fn wishes(tt: &Timetable, text: &str, how: How, out: &mut Vec<Wish>) -> Result<(), String> {
    let (text, through) = match text.strip_suffix("+through") {
        Some(t) => (t, true),
        None => (text, false),
    };
    let (kind, value) = text.split_once(':').unwrap_or((text, ""));
    let mut push = |what: What| out.push(Wish { what, how });
    let numbers = |n: usize| -> Result<Vec<&str>, String> {
        let v: Vec<&str> = value.split(',').collect();
        if v.len() == n { Ok(v) } else { Err(format!("{text}: {n} numbers are needed")) }
    };
    match kind {
        "stop" | "station" => {
            // one wish for all of them: "prefer" is about what is none of them
            let stops = named(tt, value)?;
            let lats: Vec<(i32, i32)> = stops.iter().map(|s| (tt.stop(*s).lat, tt.stop(*s).lon)).collect();
            if let [only] = stops[..] {
                push(What::Stop { stop: only, through });
            } else {
                let mut stations: Vec<u32> = stops.iter().map(|s| tt.station_of(*s)).collect();
                stations.sort_unstable();
                stations.dedup();
                if let [one] = stations[..] {
                    push(What::Station { station: one, through });
                } else {
                    let (s, n) = (lats.iter().map(|p| p.0).min().unwrap_or(0), lats.iter().map(|p| p.0).max().unwrap_or(0));
                    let (w, e) = (lats.iter().map(|p| p.1).min().unwrap_or(0), lats.iter().map(|p| p.1).max().unwrap_or(0));
                    push(What::Area { area: Area::Box { south: s, west: w, north: n, east: e }, through });
                }
            }
        }
        "area" => {
            let v = numbers(3)?;
            push(What::Area { area: Area::Circle { lat: degrees(v[0])?, lon: degrees(v[1])?, radius_m: v[2].parse().map_err(|_| format!("{text}: metres"))? }, through });
        }
        "box" => {
            let v = numbers(4)?;
            push(What::Area { area: Area::Box { south: degrees(v[0])?, west: degrees(v[1])?, north: degrees(v[2])?, east: degrees(v[3])? }, through });
        }
        "line" => {
            let lines: Vec<u32> = (0..tt.lines()).filter(|l| tt.line_text(*l, LineText::Short).eq_ignore_ascii_case(value)).collect();
            if lines.is_empty() {
                return Err(format!("{value}: no line has this short name"));
            }
            if lines.len() > 1 && matches!(how, How::Prefer(_)) {
                return Err(format!("{value}: {} lines have this short name; prefer takes one thing", lines.len()));
            }
            lines.into_iter().for_each(|l| push(What::Line(l)));
        }
        "operator" => {
            let op = (0..tt.operators()).find(|o| tt.operator_text(*o, OperatorText::Name).eq_ignore_ascii_case(value) || tt.operator_text(*o, OperatorText::Id).eq_ignore_ascii_case(value));
            push(What::Operator(op.ok_or(format!("{value}: no operator is called this"))?));
        }
        "mode" => push(What::Mode(Mode::parse(value).ok_or(format!("{value}: no such kind of vehicle"))?)),
        "kind" => push(What::Kind(value.to_string())),
        "trip" => {
            let trips: Vec<u32> = (0..tt.trips()).filter(|t| tt.trip_text(*t, TripText::Number) == value).collect();
            if trips.is_empty() {
                return Err(format!("{value}: no trip has this number"));
            }
            if matches!(how, How::Prefer(_)) && trips.len() > 1 {
                return Err(format!("{value}: {} trips have this number over the file's days; prefer takes one thing", trips.len()));
            }
            trips.into_iter().for_each(|t| push(What::Trip(t)));
        }
        "no-wheelchair" => push(What::NoWheelchair),
        "wheelchair-unknown" => push(What::WheelchairUnknown),
        "no-bicycles" => push(What::NoBicycles),
        "bicycles-unknown" => push(What::BicyclesUnknown),
        _ => return Err(format!("{text}: not a wish (the source's head lists them)")),
    }
    Ok(())
}

fn query_of(tt: &Timetable, args: &[String]) -> Result<Query, String> {
    let from = place(tt, option(args, "--from").ok_or("plan: --from")?)?;
    let to = place(tt, option(args, "--to").ok_or("plan: --to")?)?;
    let mut q = Query::new(from, to, day_of(tt, args)?, parse_clock(option(args, "--time").unwrap_or("08:00"))?);
    q.window = option(args, "--window").map_or(Ok(0), |m| m.parse::<u32>().map(|m| m * 60).map_err(|_| "--window: minutes"))?;
    q.arrive_by = args.iter().any(|a| a == "--arrive-by");
    q.rules = !args.iter().any(|a| a == "--no-rules");
    q.weigh = !args.iter().any(|a| a == "--every-option");
    if let Some(n) = option(args, "--max-rides") {
        q.max_rides = n.parse().map_err(|_| "--max-rides: a number")?;
    }
    for w in options(args, "--never") {
        wishes(tt, w, How::Never, &mut q.wishes)?;
    }
    for (name, soft) in [("--avoid", true), ("--prefer", false)] {
        for w in options(args, name) {
            let (w, minutes) = match w.rsplit_once('=') {
                Some((w, m)) => (w, m.parse::<u32>().map_err(|_| format!("{name}: minutes after ="))?),
                None => (w, 10),
            };
            wishes(tt, w, if soft { How::Avoid(minutes * 60) } else { How::Prefer(minutes * 60) }, &mut q.wishes)?;
        }
    }
    Ok(q)
}

fn spot(tt: &Timetable, s: &Spot) -> String {
    match s {
        Spot::Origin => "the start".into(),
        Spot::Destination => "the end".into(),
        Spot::Stop(s) => stop_label(tt, *s),
    }
}

fn print_journey(tt: &Timetable, j: &Journey) {
    let d = j.arrive - j.depart;
    print!("{} → {}  {}:{:02}  {} ride{}", clock(j.depart), clock(j.arrive), d / 3600, d / 60 % 60, j.rides, if j.rides == 1 { "" } else { "s" });
    if j.walking > 0 {
        print!(", {} min on foot", j.walking.div_ceil(60));
    }
    if j.penalty > 0 {
        print!(", against the wishes: {} min", j.penalty.div_ceil(60));
    }
    println!();
    for leg in j.legs.iter() {
        match leg {
            Leg::Walk { from, to, depart, arrive } => println!("    {} walk {} min: {} → {}", clock(*depart), (arrive - depart + 59) / 60, spot(tt, from), spot(tt, to)),
            Leg::Ride { trip, route, day, board, alight, from, to, depart, arrive } => {
                let r = tt.route(*route);
                let line = r.line;
                let mut head = tt.pattern_stop_headsign(r.first_stop + board);
                if head.is_empty() {
                    head = tt.pattern_headsign(r.pattern);
                }
                let (kind, short) = (tt.trip_text(*trip, TripText::Kind), tt.line_text(line, LineText::Short));
                let name = if kind.is_empty() { format!("{} {short}", Mode::of(tt.line_type(line)).name()) } else if short.is_empty() || kind.contains(short) { kind.to_string() } else { format!("{kind} {short}") };
                println!(
                    "    {} {} → {} ({}), {}: {} → {} {}   [{} stops; no. {}; {} on {}; {}{}]",
                    clock(*depart),
                    name,
                    head,
                    tt.operator_text(tt.line_operator(line), OperatorText::Name),
                    stop_label(tt, *from),
                    clock(*depart),
                    clock(*arrive),
                    stop_label(tt, *to),
                    alight - board,
                    tt.trip_text(*trip, TripText::Number),
                    tt.trip_text(*trip, TripText::Realtime),
                    tt.date_of(*day),
                    match tt.trip(*trip).wheelchair {
                        1 => "wheelchair yes",
                        2 => "wheelchair no",
                        _ => "wheelchair not said",
                    },
                    match tt.trip(*trip).bicycles {
                        1 => ", bicycles yes",
                        2 => ", bicycles no",
                        _ => "",
                    }
                );
            }
        }
    }
}

fn plan(tt: &Timetable, args: &[String]) -> Result<(), String> {
    let q = query_of(tt, args)?;
    let t0 = Instant::now();
    let mut planner = Planner::new(*tt);
    let ready = ms(t0);
    let t0 = Instant::now();
    let a = planner.plan(&q);
    let took = ms(t0);
    let ends = planner.ends(&q);
    println!(
        "{} {} on {}{}{}; {} stops at the start, {} at the end",
        if q.arrive_by { "arrive by" } else { "leave at" },
        clock(q.time as i32),
        tt.date_of(q.day),
        if q.window > 0 { format!(" or up to {} min {}", q.window / 60, if q.arrive_by { "earlier" } else { "later" }) } else { String::new() },
        if q.wishes.is_empty() { String::new() } else { format!(", {} wishes", q.wishes.len()) },
        ends.from.len(),
        ends.to.len()
    );
    for j in a.journeys.iter() {
        print_journey(tt, j);
    }
    println!(
        "{} option{} in {took:.2} ms ({} searches, {} labels, {} steps{}); the planner was ready in {ready:.1} ms",
        a.journeys.len(),
        if a.journeys.len() == 1 { "" } else { "s" },
        a.searches,
        a.labels,
        a.steps,
        if a.cut_short { "; CUT SHORT by a cap" } else { "" }
    );
    if args.iter().any(|a| a == "--scan") {
        let t0 = Instant::now();
        let reference = Reference::new(&planner, q.day, q.time as i32 - 2 * 86_400, q.time as i32 + 2 * 86_400);
        let built = ms(t0);
        let t0 = Instant::now();
        let said = if q.arrive_by {
            reference.latest_departure(&planner, &q, q.time as i32).map(|t| format!("latest departure {}", clock(t)))
        } else {
            reference.earliest_arrival(&planner, &q, q.time as i32).map(|t| format!("earliest arrival {}", clock(t)))
        };
        println!("the scan ({} connections, built in {built:.0} ms; no cap on rides): {} in {:.2} ms", reference.connections(), said.unwrap_or("none".into()), ms(t0));
    }
    Ok(())
}

fn trip(tt: &Timetable, args: &[String]) -> Result<(), String> {
    let number = args.first().ok_or("trip: a number")?;
    let day = option(args, "--date").map(|_| day_of(tt, args)).transpose()?;
    let mut shown = 0;
    for t in 0..tt.trips() {
        if tt.trip_text(t, TripText::Number) != number.as_str() || day.is_some_and(|d| !tt.runs(tt.trip(t).service, d)) {
            continue;
        }
        let r = tt.route(tt.route_of_trip(t));
        let tr = tt.trip(t);
        let days: Vec<String> = (0..tt.days() as i32).filter(|d| tt.runs(tr.service, *d)).map(|d| (tt.date_of(d) % 10000).to_string()).collect();
        println!(
            "{} {} → {} ({}); trip {}, live id {}; wheelchair {}, bicycles {}; runs on {}",
            tt.trip_text(t, TripText::Kind),
            tt.line_text(r.line, LineText::Short),
            tt.pattern_headsign(r.pattern),
            tt.operator_text(tt.line_operator(r.line), OperatorText::Name),
            tt.trip_text(t, TripText::Id),
            tt.trip_text(t, TripText::Realtime),
            tr.wheelchair,
            tr.bicycles,
            days.join(" ")
        );
        let times = tt.trip_times(t);
        for pos in 0..r.stops {
            let ps = tt.pattern_stop(r.first_stop + pos);
            let (a, d) = tt.time(times, pos);
            println!(
                "    {} {}  {}{}{}{}",
                clock(a as i32),
                clock(d as i32),
                stop_label(tt, ps.stop),
                if ps.pickup == 1 { "  (no boarding)" } else { "" },
                if ps.drop_off == 1 { "  (no alighting)" } else { "" },
                tt.pattern_stop_distance(r.first_stop + pos).map_or(String::new(), |m| format!("  {:.1} km", m as f64 / 1000.0))
            );
        }
        shown += 1;
        if shown == 3 {
            println!("… more trips have this number; --date narrows");
            break;
        }
    }
    if shown == 0 {
        return Err(format!("{number}: no trip has this number{}", if day.is_some() { " on that day" } else { "" }));
    }
    Ok(())
}

/// A fixed journey's line: name, from, to, date, time, what is asked (`depart`, `arrive`, or `window` and minutes),
/// and what the scan answered (asked as a query is by default: holding to the feed's rules, a day on). Tabs between. A line `#feed <version>` says which feed the answers are of. On a file
/// of another feed (as the weekly workflow packs one) the kept answers are not compared: each journey is asked on
/// the same weekday inside that file's days, the planner is held to the scan and to journeys that can be made, and
/// three in four journeys must have an answer at all, or the journeys no longer fit the file and prove nothing.
fn fixed(tt: &Timetable, args: &[String]) -> Result<ExitCode, String> {
    let path = args.first().ok_or("fixed: the journeys' file")?;
    let write = args.iter().any(|a| a == "--write");
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    let mut planner = Planner::new(*tt);
    let mut reference: Option<(i32, Reference)> = None;
    let (mut out, mut wrong, mut count, mut answered) = (String::new(), 0, 0, 0);
    let mut same_feed = true;
    for line in text.lines() {
        if let Some(version) = line.strip_prefix("#feed ") {
            same_feed = write || tt.meta("feed_version") == Some(version.trim());
            if write {
                let _ = writeln!(out, "#feed {}", tt.meta("feed_version").unwrap_or(""));
                continue;
            }
        }
        if line.starts_with('#') || line.trim().is_empty() {
            out.push_str(line);
            out.push('\n');
            continue;
        }
        let f: Vec<&str> = line.split('\t').collect();
        if f.len() < 6 {
            return Err(format!("{path}: a line with fewer than six fields: {line}"));
        }
        let mut day = f[3].parse().ok().and_then(|d| tt.day_of(d)).ok_or(format!("{}: not a date", f[3]))?;
        if !same_feed {
            // the same weekday inside this file's days, a day in from either end where there is room (a journey
            // may start on the day before or end on the day after)
            let days = tt.days() as i32;
            day = day.rem_euclid(7);
            if day == 0 && days > 7 {
                day = 7;
            }
            while day + 7 < days - 1 && day < 1 {
                day += 7;
            }
        }
        let mut q = Query::new(place(tt, f[1])?, place(tt, f[2])?, day, parse_clock(f[4])?);
        q.max_rides = 16;
        let (asked, minutes) = f[5].split_once(' ').unwrap_or((f[5], "0"));
        q.arrive_by = asked == "arrive";
        q.window = if asked == "window" { minutes.parse::<u32>().map_err(|_| format!("{}: minutes", f[5]))? * 60 } else { 0 };
        if reference.as_ref().is_none_or(|r| r.0 != day) {
            reference = Some((day, Reference::new(&planner, day, -86_400, 3 * 86_400)));
        }
        let scan = &reference.as_ref().unwrap().1;
        let a = planner.plan(&q);
        let mut bad = Vec::new();
        for j in a.journeys.iter() {
            if let Some(why) = impossible(&planner, &q, j) {
                bad.push(format!("a journey that can't be made: {why}"));
            }
        }
        // (the scan is asked as the planner was: as far as the query's own horizon, with the feed's rules)
        let show = |v: &[(i32, i32)]| if v.is_empty() { "none".to_string() } else { v.iter().map(|(d, a)| format!("{}>{}", clock(*d), clock(*a))).collect::<Vec<_>>().join(" ") };
        let theirs = match asked {
            "depart" | "arrive" => {
                let (ours, theirs) = if asked == "depart" {
                    (a.journeys.iter().map(|j| j.arrive).min(), scan.earliest_arrival(&planner, &q, q.time as i32))
                } else {
                    (a.journeys.iter().map(|j| j.depart).max(), scan.latest_departure(&planner, &q, q.time as i32))
                };
                if ours != theirs {
                    bad.push(format!("the planner says {}, the scan {}", ours.map_or("none".into(), clock), theirs.map_or("none".into(), clock)));
                }
                theirs.map_or("none".into(), clock)
            }
            "window" => {
                if let Some(how) = scan.window_differs(&planner, &q, &a.journeys) {
                    bad.push(how);
                }
                show(&scan.options(&planner, &q))
            }
            other => return Err(format!("{other}: depart, arrive or window")),
        };
        count += 1;
        answered += (theirs != "none") as u32;
        let kept = f.get(6).copied().unwrap_or("");
        if !write && same_feed && kept != theirs {
            bad.push(format!("the file keeps {kept}, the scan says {theirs}"));
        }
        if a.cut_short {
            bad.push("the planner was cut short by a cap".into());
        }
        if !bad.is_empty() {
            wrong += 1;
            println!("WRONG  {}: {}", f[0], bad.join("; "));
        }
        let _ = writeln!(out, "{}\t{theirs}", f[..6].join("\t"));
    }
    if write {
        std::fs::write(path, out).map_err(|e| format!("{path}: {e}"))?;
        println!("{count} journeys written with the scan's answers; {wrong} where the planner differs");
    } else {
        println!(
            "{count} fixed journeys: {} agree with the scan{}; {answered} have an answer",
            count - wrong,
            if same_feed { " and with what the file keeps".to_string() } else { format!("; the file is of feed {}, not the kept answers', so each was asked on its weekday inside the file's days and the kept answers were not compared", tt.meta("feed_version").unwrap_or("?")) }
        );
        if answered * 4 < count * 3 {
            println!("WRONG  only {answered} of {count} journeys have an answer on this file: they no longer fit it, and prove nothing");
            wrong += 1;
        }
    }
    Ok(if wrong == 0 { ExitCode::SUCCESS } else { ExitCode::FAILURE })
}

/// The options that weighing leaves of a set, by the rule alone (query.rs's head): one is beaten by another, of the
/// set or of `beyond` (the journeys that leave after the window), that leaves no earlier, arrives no later, has no
/// more rides, and is no later with each one's cost added to its arrival. As (depart, arrive, rides, cost) with the
/// end that was asked for left out where one moment was asked; sorted. The bench holds the weighing search to it.
fn thinned(all: &[Journey], beyond: &[Journey], window: u32, back: bool) -> Vec<(i32, i32, u32, u32)> {
    let v = |j: &Journey| {
        let (leave, reach) = if back { (-j.arrive, -j.depart) } else { (j.depart, j.arrive) };
        [if window == 0 { 0 } else { -(leave as i64) }, reach as i64, j.rides as i64, reach as i64 + j.penalty as i64]
    };
    let mut out: Vec<(i32, i32, u32, u32)> = Vec::new();
    for j in all {
        let a = v(j);
        let beaten = all.iter().chain(beyond.iter()).any(|o| {
            let b = v(o);
            b != a && b.iter().zip(a.iter()).all(|(x, y)| x <= y)
        });
        if !beaten {
            out.push(option_of(j, window, back));
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

fn option_of(j: &Journey, window: u32, back: bool) -> (i32, i32, u32, u32) {
    match (window, back) {
        (0, false) => (0, j.arrive, j.rides, j.penalty),
        (0, true) => (j.depart, 0, j.rides, j.penalty),
        _ => (j.depart, j.arrive, j.rides, j.penalty),
    }
}

fn percentile(v: &mut [f64], p: f64) -> f64 {
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    if v.is_empty() { 0.0 } else { v[((v.len() - 1) as f64 * p) as usize] }
}

/// Times on random pairs of stops, and whether the planner and the scan agree on each.
fn bench(tt: &Timetable, args: &[String], open_ms: f64) -> Result<ExitCode, String> {
    let day = day_of(tt, args)?;
    let n: usize = option(args, "--pairs").map_or(Ok(300), |s| s.parse().map_err(|_| "--pairs: a number"))?;
    let at = parse_clock(option(args, "--time").unwrap_or("08:00"))?;
    let points = args.iter().any(|a| a == "--points");
    let t0 = Instant::now();
    let mut planner = Planner::new(*tt);
    let ready = ms(t0);
    let t0 = Instant::now();
    let scan = Reference::new(&planner, day, at as i32 - 86_400, at as i32 + 2 * 86_400);
    println!("opened and checked in {open_ms:.1} ms; planner ready in {ready:.1} ms; the scan's {} connections built in {:.0} ms", scan.connections(), ms(t0));
    let served: Vec<u32> = (0..tt.stops()).filter(|s| tt.stop(*s).kind == 0 && tt.routes_at(*s).0 != tt.routes_at(*s).1).collect();
    if served.is_empty() {
        return Err("no stop has a route".into());
    }
    let mut rng = fuzz::Rng(0x9E37_79B9_7F4A_7C15);
    let (mut t_one, mut t_window, mut t_back, mut t_scan) = (vec![], vec![], vec![], vec![]);
    let (mut t_wish, mut t_every): ([Vec<f64>; 3], [Vec<f64>; 3]) = Default::default();
    let (mut found, mut rides, mut options, mut wrong, mut cut, mut later) = (0, 0, 0, 0, [0u32; 2], 0);
    let (mut wish_cut, mut wish_options, mut every_options) = ([0u32; 3], [0usize; 3], [0usize; 3]);
    let (mut wish_wrong, mut compared, mut unsure) = (0, 0, 0);
    let every = !args.iter().any(|a| a == "--quick");
    let rules = !args.iter().any(|a| a == "--no-rules");
    let mut most = 0usize;
    let (mut many_rides, mut departures) = (0, 0);
    let mut t_whole: [Vec<f64>; 2] = Default::default();
    for i in 0..n {
        let (a, b) = (served[rng.below(served.len() as u64) as usize], served[rng.below(served.len() as u64) as usize]);
        let spot = |s: u32| if points { Place::Point { lat: tt.stop(s).lat, lon: tt.stop(s).lon } } else { Place::Stop(s) };
        let mut q = Query::new(spot(a), spot(b), day, at);
        q.rules = rules;
        q.max_rides = 16;
        let t0 = Instant::now();
        let one = planner.plan(&q);
        t_one.push(ms(t0));
        cut[0] += one.cut_short as u32;
        // the scan is asked as the planner was: as far as the query's own horizon, holding to the feed's rules
        let t0 = Instant::now();
        let scanned = scan.earliest_with_rides(&planner, &q, at as i32);
        t_scan.push(ms(t0));
        let theirs = scanned.map(|s| s.0);
        let ours = one.journeys.iter().map(|j| j.arrive).min();
        // a journey on foot all the way is the planner's alone: the scan knows rides only
        let on_foot = one.journeys.iter().any(|j| j.rides == 0);
        for j in one.journeys.iter() {
            if let Some(why) = impossible(&planner, &q, j) {
                wrong += 1;
                println!("WRONG  pair {i} ({a} to {b}): a journey that can't be made: {why}");
            }
        }
        if ours != theirs && !on_foot {
            if scanned.is_some_and(|s| s.1 > q.max_rides) && ours.is_none_or(|o| theirs.is_some_and(|t| o > t)) {
                many_rides += 1;
                println!("RIDES  pair {i} ({a} to {b}) leaving {}: the planner {:?}, the scan {:?} with {} rides, more than a journey may have", clock(at as i32), ours.map(clock), theirs.map(clock), scanned.map_or(0, |s| s.1));
            } else {
                wrong += 1;
                println!("WRONG  pair {i} ({a} to {b}) leaving {}: the planner {:?}, the scan {:?}", clock(at as i32), ours.map(clock), theirs.map(clock));
            }
        }
        if let Some(best) = one.journeys.iter().min_by_key(|j| j.arrive) {
            found += 1;
            rides += best.rides;
        }
        if every {
            // a patient search gives what a search as far as the horizon gives, one moment and two hours
            for minutes in [0, 120] {
                let mut whole = q.clone();
                (whole.window, whole.limits.patient) = (minutes * 60, false);
                let t0 = Instant::now();
                let all = planner.plan(&whole);
                t_whole[(minutes > 0) as usize].push(ms(t0));
                whole.limits.patient = true;
                let patient = if minutes == 0 { one.journeys.clone() } else { planner.plan(&whole).journeys };
                let option = |j: &Journey| (j.depart, j.arrive, j.rides);
                if !all.cut_short && patient.iter().map(option).collect::<Vec<_>>() != all.journeys.iter().map(option).collect::<Vec<_>>() {
                    wrong += 1;
                    println!("WRONG  pair {i} ({a} to {b}) over {minutes} min: a patient search gives {:?}, a whole one {:?}", patient.iter().map(option).collect::<Vec<_>>(), all.journeys.iter().map(option).collect::<Vec<_>>());
                }
            }
            // and each option leaves as late as any way to its arrival with no more rides: asked back from there
            for j in one.journeys.iter().filter(|j| j.rides > 0) {
                let mut back = q.clone();
                (back.arrive_by, back.time, back.max_rides) = (true, j.arrive as u32, j.rides);
                let latest = planner.plan(&back).journeys.iter().map(|k| k.depart).max();
                departures += 1;
                if latest != Some(j.depart) {
                    wrong += 1;
                    println!("WRONG  pair {i} ({a} to {b}): an option leaves {} for {}, asked back from there one can leave {:?}", clock(j.depart), clock(j.arrive), latest.map(clock));
                }
            }
        }
        // arrive by the earliest arrival: the latest departure
        if let Some(arrive) = theirs {
            let mut back = q.clone();
            back.arrive_by = true;
            back.time = arrive as u32;
            let t0 = Instant::now();
            let got = planner.plan(&back);
            t_back.push(ms(t0));
            let (ours, theirs) = (got.journeys.iter().map(|j| j.depart).max(), scan.latest_departure(&planner, &back, arrive));
            for j in got.journeys.iter() {
                if let Some(why) = impossible(&planner, &back, j) {
                    wrong += 1;
                    println!("WRONG  pair {i} ({a} to {b}) arriving by: a journey that can't be made: {why}");
                }
            }
            if ours != theirs && !on_foot {
                wrong += 1;
                println!("WRONG  pair {i} ({a} to {b}) arriving by {}: the planner {:?}, the scan {:?}", clock(arrive), ours.map(clock), theirs.map(clock));
            }
        }
        // every best journey in the two hours after
        let mut window = q.clone();
        window.window = 7200;
        let t0 = Instant::now();
        let got = planner.plan(&window);
        t_window.push(ms(t0));
        cut[1] += got.cut_short as u32;
        options += got.journeys.len();
        for j in got.journeys.iter() {
            if let Some(why) = impossible(&planner, &window, j) {
                wrong += 1;
                println!("WRONG  pair {i} ({a} to {b}) over two hours: a journey that can't be made: {why}");
            }
        }
        if let Some(how) = scan.window_differs(&planner, &window, &got.journeys).filter(|_| !on_foot) {
            wrong += 1;
            println!("WRONG  pair {i} ({a} to {b}) over two hours from {}: {how}", clock(at as i32));
        }
        // with wishes: never by rail (the scan knows closed lines), and rail avoided (the planner's alone)
        let mut never = q.clone();
        never.wishes.push(Wish { what: What::Mode(Mode::Rail), how: How::Never });
        let got = planner.plan(&never);
        let scanned = scan.earliest_with_rides(&planner, &never, at as i32);
        let (ours, theirs) = (got.journeys.iter().map(|j| j.arrive).min(), scanned.map(|s| s.0));
        if ours != theirs && !on_foot {
            // (the scan knows no cap on rides: its journey with more than a journey may have is no answer here)
            if scanned.is_some_and(|s| s.1 > never.max_rides) && ours.is_none_or(|o| theirs.is_some_and(|t| o > t)) {
                many_rides += 1;
                println!("RIDES  pair {i} ({a} to {b}) never by rail: the planner {:?}, the scan {:?} with {} rides, more than a journey may have", ours.map(clock), theirs.map(clock), scanned.map_or(0, |s| s.1));
            } else {
                wrong += 1;
                println!("WRONG  pair {i} ({a} to {b}) never by rail: the planner {:?}, the scan {:?}", ours.map(clock), theirs.map(clock));
            }
        }
        // rail avoided, and buses avoided with Intercity trains preferred (the planner's alone): timed weighing
        // the wishes, and held to what every option comes to once the same rule is put to it afterwards
        let rail = Wish { what: What::Mode(Mode::Rail), how: How::Avoid(900) };
        let mixed = [Wish { what: What::Mode(Mode::Bus), how: How::Avoid(600) }, Wish { what: What::Kind("Intercity".into()), how: How::Prefer(300) }];
        for (k, (base, wishes)) in [(&q, &[rail.clone()][..]), (&window, &[rail.clone()][..]), (&window, &mixed[..])].into_iter().enumerate() {
            let mut wished = base.clone();
            wished.wishes.extend(wishes.iter().cloned());
            let t0 = Instant::now();
            let got = planner.plan(&wished);
            t_wish[k].push(ms(t0));
            wish_cut[k] += got.cut_short as u32;
            wish_options[k] += got.journeys.len();
            most = most.max(got.journeys.len());
            for j in got.journeys.iter() {
                if let Some(why) = impossible(&planner, &wished, j) {
                    wrong += 1;
                    println!("WRONG  pair {i} ({a} to {b}) with wishes {k}: a journey that can't be made: {why}");
                }
            }
            if k == 1 {
                later += (got.journeys.iter().any(|j| j.penalty > 0) && got.journeys.iter().any(|j| j.penalty == 0 && j.rides > 0)) as u32;
            }
            if every {
                // (every option, which is always as far as the query's horizon)
                let mut all = wished.clone();
                all.weigh = false;
                all.limits.options = 1024;
                let t0 = Instant::now();
                let full = planner.plan(&all);
                t_every[k].push(ms(t0));
                every_options[k] += full.journeys.len();
                if full.cut_short || got.cut_short {
                    unsure += 1;
                } else {
                    compared += 1;
                    // what leaves after the window can beat what leaves inside it: every option from its far end
                    let mut edge = all.clone();
                    (edge.window, edge.time) = (0, all.time + all.window);
                    let beyond = if all.window > 0 { planner.plan(&edge).journeys } else { Vec::new() };
                    let weighed = thinned(&full.journeys, &beyond, wished.window, wished.arrive_by);
                    let mut ours: Vec<(i32, i32, u32, u32)> = got.journeys.iter().map(|j| option_of(j, wished.window, wished.arrive_by)).collect();
                    ours.sort_unstable();
                    if weighed != ours {
                        wish_wrong += 1;
                        let show = |v: &[(i32, i32, u32, u32)]| v.iter().map(|o| format!("{}-{} {}r {}s", clock(o.0), clock(o.1), o.2, o.3)).collect::<Vec<_>>().join(", ");
                        let only = |x: &[(i32, i32, u32, u32)], y: &[(i32, i32, u32, u32)]| x.iter().filter(|o| !y.contains(o)).copied().collect::<Vec<_>>();
                        println!("WISH   pair {i} ({a} to {b}) with wishes {k}: weighing gave [{}] that every option weighed after lacks, and lacks [{}]", show(&only(&ours, &weighed)), show(&only(&weighed, &ours)));
                    }
                }
            }
        }
    }
    let line = |name: &str, v: &mut Vec<f64>| println!("{name:<58} {:>8.2} {:>8.2} {:>8.2}", percentile(v, 0.5), percentile(v, 0.9), percentile(v, 1.0));
    println!(
        "{n} random pairs of {} on {} at {}, {}: {found} have a journey, {:.1} rides each",
        if points { "points at stops" } else { "stops" },
        tt.date_of(day),
        clock(at as i32),
        if rules { "holding to the feed's rules" } else { "without the feed's rules" },
        rides as f64 / found.max(1) as f64
    );
    println!("{:<58} {:>8} {:>8} {:>8}", "ms", "median", "90th", "worst");
    line("one departure (every best by arrival and rides)", &mut t_one);
    line("arrive by", &mut t_back);
    line(&format!("two hours of departures ({:.1} options a pair)", options as f64 / n as f64), &mut t_window);
    let names = ["one departure, rail avoided (15 min a ride)", "two hours, rail avoided", "two hours, buses avoided (10 min) and Intercity preferred (5)"];
    for k in 0..3 {
        line(&format!("{} ({:.1} options)", names[k], wish_options[k] as f64 / n as f64), &mut t_wish[k]);
    }
    if every {
        for k in 0..3 {
            line(&format!("  the same, every option ({:.1})", every_options[k] as f64 / n as f64), &mut t_every[k]);
        }
    }
    if every {
        line("  one departure as far as the horizon (not patient)", &mut t_whole[0]);
        line("  two hours as far as the horizon (not patient)", &mut t_whole[1]);
    }
    line("the scan, one earliest arrival", &mut t_scan);
    println!(
        "{} of {} comparisons differ (with the scan: {}{}); {many_rides} more where the scan's journey has more rides than a journey may have; cut short by a cap: {} single departures, {} windows",
        wrong,
        n * 4 + if every { n * 2 + departures } else { 0 },
        n * 4,
        if every { format!("; a patient search with a whole one: {}; an option's departure asked back from its arrival: {departures}", n * 2) } else { String::new() },
        cut[0],
        cut[1]
    );
    println!(
        "with wishes: cut short {} single departures, {} and {} windows (the most options one gave: {most}); for {later} pairs avoiding rail offers both a way by rail and one without",
        wish_cut[0], wish_cut[1], wish_cut[2]
    );
    if every {
        println!("{wish_wrong} of {compared} wished searches differ from every option weighed afterwards ({unsure} not compared: a cap cut one short)");
    }
    Ok(if wrong == 0 && wish_wrong == 0 { ExitCode::SUCCESS } else { ExitCode::FAILURE })
}
