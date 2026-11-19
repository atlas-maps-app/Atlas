//! The fixed journeys across the Netherlands (tests/data/journeys-nl-9696.tsv): from and to stations and points all
//! over the country, for one departure, for arriving by, and over two hours. Each line keeps what the connection
//! scan answered on OVapi's feed of 16 November 2026 (version 9696), asked as a query is by default: a day on, and
//! holding to the feed's rules. The planner must agree with the scan, the scan with what is kept, and every journey
//! must be one that can be made.
//!
//! The whole country's file is 58 MB and not in the tree (README.md says how it is packed), so the test that asks
//! the journeys is ignored by default, and `cargo test` says so: "1 ignored". With the file at hand:
//!
//!   ATLAS_TRANSIT_FILE=<file> cargo test --test fixed -- --ignored
//!
//! Asked for like that without the file, it fails. On a file of another feed the planner is still held to the scan,
//! journey by journey. What runs everywhere is the check that the journeys' file itself is whole.
//!
//! The same file holds one thing more that no hand-written feed can: a point with more stops around it than a
//! search begins at (4,096) says that its answer was cut short, and by what.

use atlas_transit::{Answer, Place, Planner, Query, Timetable};
use std::process::Command;

const JOURNEYS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/journeys-nl-9696.tsv");

#[test]
#[ignore = "needs the whole country's file: ATLAS_TRANSIT_FILE=<file> cargo test --test fixed -- --ignored"]
fn the_fixed_journeys_agree_with_the_scan() {
    let file = std::env::var("ATLAS_TRANSIT_FILE").expect("ATLAS_TRANSIT_FILE names no timetable file of the Netherlands");
    let out = Command::new(env!("CARGO_BIN_EXE_atlas-transit")).args(["fixed", &file, JOURNEYS]).output().unwrap();
    let said = String::from_utf8_lossy(&out.stdout);
    assert!(out.status.success(), "{said}{}", String::from_utf8_lossy(&out.stderr));
    assert!(said.contains("fixed journeys:"), "{said}");
}

/// From a point in Utrecht to a point in Amsterdam: with the reach a query has by default the stops around each
/// are few and nothing is cut; with a reach of 50 km each point has more stops around it than a search begins at,
/// the nearest stay, and the answer says that it was cut short by that and by nothing else.
#[test]
#[ignore = "needs the whole country's file: ATLAS_TRANSIT_FILE=<file> cargo test --test fixed -- --ignored"]
fn a_point_with_more_stops_around_it_than_a_search_begins_at_says_so() {
    let file = std::env::var("ATLAS_TRANSIT_FILE").expect("ATLAS_TRANSIT_FILE names no timetable file of the Netherlands");
    let bytes = std::fs::read(&file).unwrap();
    let tt = Timetable::open(&bytes).unwrap();
    let mut planner = Planner::new(tt).unwrap();
    let (utrecht, amsterdam) = (Place::Point { lat: 520_894_000, lon: 51_101_000 }, Place::Point { lat: 523_791_000, lon: 49_003_000 });
    let mut q = Query::new(utrecht, amsterdam, tt.day_of(20261117).unwrap_or(1), 8 * 3600);
    let near = planner.plan(&q);
    assert!(!near.cut_short && near.cut_by == 0 && !near.journeys.is_empty(), "{} {}", near.cut_by, near.journeys.len());
    q.reach_m = 50_000;
    let wide = planner.plan(&q);
    assert!(wide.cut_short && wide.cut_by == Answer::PLACE && !wide.journeys.is_empty(), "{} {}", wide.cut_by, wide.journeys.len());
    // The nearest stops stay, so the wide reach is in no later than the narrow one; and within twice that reach
    // one can walk all the way, ten hours here, which is an option of no rides beside the train.
    let first = |a: &Answer| a.journeys.iter().map(|j| j.arrive).min().unwrap();
    assert!(first(&wide) <= first(&near), "{} {}", first(&wide), first(&near));
    assert!(wide.journeys.iter().any(|j| j.rides == 0) && near.journeys.iter().all(|j| j.rides > 0));
    println!("{file}: reach 1 km, {} options, the first in at {}; reach 50 km, {} options, the first in at {}, cut short by a place of too many stops", near.journeys.len(), first(&near), wide.journeys.len(), first(&wide));
}

/// The journeys' file is whole: its feed's line, and for each journey a name of its own, two places, a date, a
/// time, what is asked, and an answer. Of each kind of question there are enough, and most have an answer.
#[test]
fn the_fixed_journeys_file_is_whole() {
    let text = std::fs::read_to_string(JOURNEYS).unwrap();
    assert_eq!(text.lines().filter(|l| l.starts_with("#feed ")).count(), 1);
    let (mut names, mut kinds, mut answered) = (Vec::new(), [0; 3], 0);
    for line in text.lines().filter(|l| !l.starts_with('#') && !l.trim().is_empty()) {
        let f: Vec<&str> = line.split('\t').collect();
        assert_eq!(f.len(), 7, "{line}");
        assert!(!names.contains(&f[0]), "two journeys are called {}", f[0]);
        names.push(f[0]);
        assert!(!f[1].is_empty() && !f[2].is_empty() && f[1] != f[2], "{line}");
        assert!(f[3].len() == 8 && f[3].parse::<u32>().is_ok_and(|d| (20260101..20300101).contains(&d)), "{line}");
        let clock: Vec<u32> = f[4].split(':').map(|n| n.parse().unwrap()).collect();
        assert!(clock.len() == 2 && clock[0] < 48 && clock[1] < 60, "{line}");
        let kind = match f[5].split_once(' ') {
            None if f[5] == "depart" => 0,
            None if f[5] == "arrive" => 1,
            Some(("window", minutes)) if minutes.parse::<u32>().is_ok_and(|m| m > 0 && m <= 240) => 2,
            _ => panic!("{line}"),
        };
        kinds[kind] += 1;
        answered += (f[6] != "none") as u32;
        // an answer is a time (with its seconds where it has them, and `+1d` on the day after), or pairs of
        // leaving>arriving
        let time = |t: &str| {
            let t = t.strip_suffix('d').and_then(|t| t.rsplit_once(['+', '-'])).filter(|(_, days)| days.parse::<u32>().is_ok()).map_or(t, |(t, _)| t);
            (2..=3).contains(&t.split(':').count()) && t.split(':').all(|n| n.len() == 2 && n.parse::<u32>().is_ok())
        };
        assert!(f[6] == "none" || f[6].split(' ').all(|p| p.split('>').count() == if kind == 2 { 2 } else { 1 } && p.split('>').all(time)), "{line}");
    }
    assert!(kinds.iter().all(|k| *k >= 8) && answered * 4 >= names.len() as u32 * 3, "{kinds:?} {answered} of {}", names.len());
}
