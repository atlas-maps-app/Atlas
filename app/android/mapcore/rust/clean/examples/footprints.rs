//! Cleans a file of footprints and writes what is drawn as Overpass's JSON, for the fault detectors to read
//! (~/atlas/home/fleet/architect/fault-detectors): a tool for looking, not part of the core.
//!
//!   cargo run --release --features std --example footprints -- <in.fp> <out.json> [lat]
//!
//! The file holds, per footprint, `F <part 0|1> <min> <top> <id> <rings>` and each ring as `<n> <lat> <lon> ...` in
//! grid units. It prints the counts and the time the cleaning took.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    reason = "a tool run by hand: a bad file or argument stops it, saying what was wrong"
)]

use atlas_clean::clean::{Footprint, Params, clean};
use atlas_clean::geom::P;
use std::io::Write;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let text = std::fs::read_to_string(&args[1]).expect("the footprints");
    let lat: f64 = args.get(3).map_or(52.372, |s| s.parse().expect("a latitude"));
    let mut lines = text.lines();
    let mut fps = Vec::new();
    let mut ids = Vec::new();
    while let Some(l) = lines.next() {
        let w: Vec<&str> = l.split_whitespace().collect();
        let rings: usize = w[5].parse().unwrap();
        let mut rs = Vec::new();
        for _ in 0..rings {
            let v: Vec<i64> = lines.next().unwrap().split_whitespace().map(|s| s.parse().unwrap()).collect();
            rs.push((0..v[0] as usize).map(|i| P::new(v[2 + 2 * i], v[1 + 2 * i])).collect());
        }
        fps.push(Footprint { rings: rs, min: w[2].parse().unwrap(), top: w[3].parse().unwrap(), part: w[1] == "1" });
        ids.push(w[4].to_string());
    }
    let kx = 111320.0 * (lat.to_radians()).cos();
    let t = std::time::Instant::now();
    let c = clean(&fps, Params::new(kx / 1e7, 110540.0 / 1e7));
    let ms = t.elapsed().as_secs_f64() * 1e3;
    println!("{} footprints in {:.0} ms: {:?}", fps.len(), ms, c.counts);
    let mut worst: Vec<(f64, usize)> =
        (0..fps.len()).filter(|&k| c.uncovered[k] > 5.0).map(|k| (c.uncovered[k], k)).collect();
    worst.sort_by(|a, b| b.0.total_cmp(&a.0));
    for (a, k) in worst.iter().take(25) {
        let area: f64 = fps[*k].rings.iter().map(|r| atlas_clean::geom::area2(r) as f64).sum::<f64>().abs()
            * 0.5
            * (kx / 1e7)
            * (110540.0 / 1e7);
        println!(
            "uncovered {:7.1} m2 of {:8.1}  {} drawn {} ring {:?}",
            a,
            area,
            ids[*k],
            c.drawn[*k],
            c.ring[*k].as_ref().map(Vec::len)
        );
    }
    for k in (0..fps.len()).filter(|&k| c.uncovered[k] < 0.0) {
        println!(
            "unmeasured {} drawn {} rings in {} ring out {:?}",
            ids[k],
            c.drawn[k],
            fps[k].rings.len(),
            c.ring[k].as_ref().map(Vec::len)
        );
    }
    if let Ok(show) = std::env::var("ATLAS_CLEAN_SHOW") {
        for id in show.split(',') {
            for k in (0..fps.len()).filter(|&k| ids[k] == id) {
                let r = c.ring[k].as_ref();
                let a2 = r.map(|r| atlas_clean::geom::area2(r));
                let seams = r.map(|r| atlas_clean::bridge::seams(r).iter().filter(|&&s| s).count());
                println!(
                    "{id}: drawn {} part {} rings in {} ring out {:?} area2 {:?} seams {:?}",
                    c.drawn[k],
                    fps[k].part,
                    fps[k].rings.len(),
                    r.map(Vec::len),
                    a2,
                    seams
                );
                if let Some(r) = r {
                    println!("   {:?}", r);
                }
            }
        }
    }
    let mut out = std::io::BufWriter::new(std::fs::File::create(&args[2]).unwrap());
    write!(out, "{{\"version\":0.6,\"elements\":[").unwrap();
    let mut first = true;
    for k in 0..fps.len() {
        if !c.drawn[k] && fps[k].part {
            continue;
        }
        let ring: Vec<P> = match &c.ring[k] {
            Some(r) => r.clone(),
            None => fps[k].rings[0].clone(),
        };
        let id: i64 = ids[k][1..].parse().unwrap();
        let key = if fps[k].part { "building:part" } else { "building" };
        write!(
            out,
            "{}{{\"type\":\"way\",\"id\":{},\"tags\":{{\"{}\":\"yes\",\"height\":\"{}\",\"min_height\":\"{}\",\"was\":\"{}\",\"atlas:drawn\":\"{}\"}},\"geometry\":[",
            if first { "" } else { "," },
            if ids[k].starts_with('r') { 9_000_000_000_000 + id } else { id },
            key,
            fps[k].top,
            c.min[k],
            ids[k],
            if c.drawn[k] { "yes" } else { "no" }
        )
        .unwrap();
        first = false;
        for (i, p) in ring.iter().chain(std::iter::once(&ring[0])).enumerate() {
            write!(
                out,
                "{}{{\"lat\":{},\"lon\":{}}}",
                if i == 0 { "" } else { "," },
                p.y as f64 / 1e7,
                p.x as f64 / 1e7
            )
            .unwrap();
        }
        write!(out, "]}}").unwrap();
    }
    writeln!(out, "]}}").unwrap();
}
