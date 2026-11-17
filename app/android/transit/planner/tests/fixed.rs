//! The fixed journeys across the Netherlands (tests/data/journeys-nl-9696.tsv): from and to stations and points all
//! over the country, for one departure, for arriving by, and over two hours. Each line keeps what the connection
//! scan answered on OVapi's feed of 16 November 2026 (version 9696); the planner must agree with the scan, the scan
//! with what is kept, and every journey must be one that can be made.
//!
//! The whole country's file is 58 MB and not in the tree: the test runs when ATLAS_TRANSIT_FILE names it (README.md
//! says how it is packed), and says so when it does not. On a file of another feed the planner is still held to the
//! scan, journey by journey.

use std::process::Command;

#[test]
fn the_fixed_journeys_agree_with_the_scan() {
    let Ok(file) = std::env::var("ATLAS_TRANSIT_FILE") else {
        eprintln!("not run: ATLAS_TRANSIT_FILE names no timetable file of the Netherlands");
        return;
    };
    let journeys = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/journeys-nl-9696.tsv");
    let out = Command::new(env!("CARGO_BIN_EXE_atlas-transit")).args(["fixed", &file, journeys]).output().unwrap();
    let said = String::from_utf8_lossy(&out.stdout);
    assert!(out.status.success(), "{said}{}", String::from_utf8_lossy(&out.stderr));
    assert!(said.contains("fixed journeys:"), "{said}");
}
