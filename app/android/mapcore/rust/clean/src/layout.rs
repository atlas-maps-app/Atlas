//! The interface's layout, written for its C side (tests only): each `#[repr(C)]` struct's size and alignment and
//! each field's offset, by name, as the library is built for a 64-bit target, the only kind the core is built for.
//! `load/CleanLayout.h` holds them, checked in, and `load/Clean.cpp` holds `load/CleanAbi.h`'s structs to them, so the
//! numbers come from this side alone. The test fails while that file is not what it would write, and prints the
//! text it would be; `ATLAS_CLEAN_LAYOUT=write cargo test --features std layout` writes it.

use crate::{AtlasCleanCounts, AtlasCleanFootprint, AtlasCleanInput, AtlasCleanOutput, AtlasCleanPoint};
use core::mem::{align_of, offset_of, size_of};
use std::string::String;
use std::vec;
use std::vec::Vec;

/// The file, from the crate's folder.
const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../cpp/load/CleanLayout.h");

/// A struct as C names it: its size and alignment, and its fields' offsets in their order.
struct Layout {
    name: &'static str,
    size: usize,
    align: usize,
    fields: Vec<(&'static str, usize)>,
}

/// The layout of struct `$t`, which C names `$name`, with every one of its fields: the pattern has no `..`, so a field
/// the struct gains and this list lacks does not compile.
macro_rules! layout {
    ($name:literal, $t:ident { $($f:ident),+ $(,)? } $(,)?) => {{
        let _every_field = |s: &$t| {
            let $t { $($f: _),+ } = s;
        };
        Layout {
            name: $name,
            size: size_of::<$t>(),
            align: align_of::<$t>(),
            fields: vec![$((stringify!($f), offset_of!($t, $f))),+],
        }
    }};
}

fn layouts() -> Vec<Layout> {
    vec![
        layout!("atlas_clean_point", AtlasCleanPoint { lat, lon }),
        layout!("atlas_clean_footprint", AtlasCleanFootprint { first_ring, rings, min, top, part, pad }),
        layout!(
            "atlas_clean_input",
            AtlasCleanInput {
                points,
                point_count,
                ring_sizes,
                ring_count,
                footprints,
                footprint_count,
                metres_per_lon,
                metres_per_lat,
            }
        ),
        layout!(
            "atlas_clean_counts",
            AtlasCleanCounts {
                overlapping_pairs,
                uncovered_outlines,
                courtyards,
                covered,
                hidden,
                clipped,
                raised,
                vanished,
                seamed,
                unsettled,
                unmeasured,
                uncovered_m2,
                sliver_m2,
                dropped_m2,
                pairs_before,
                pad,
            }
        ),
        layout!("atlas_clean_output", AtlasCleanOutput { state, min, ring_first, points, counts, owner }),
    ]
}

/// The file's text: two lists, each a macro whose argument is called on each row.
fn text(layouts: &[Layout]) -> String {
    let mut out = String::from(
        "/* The layout of the cleaning's interface as the library gives it (rust/clean/src/lib.rs's #[repr(C)] structs, on
 * a 64-bit target, the only kind the core is built for): each struct's size and alignment, and each field's offset,
 * in bytes. Written by the library's test (rust/clean/src/layout.rs; in rust/clean, `ATLAS_CLEAN_LAYOUT=write cargo
 * test --features std layout`), which fails while this file is not what it would write: not to be edited by hand.
 * load/Clean.cpp holds load/CleanAbi.h's structs to it. */
#ifndef ATLAS_LOAD_CLEAN_LAYOUT_H
#define ATLAS_LOAD_CLEAN_LAYOUT_H

/* clang-format off */

/* S(struct, size, alignment), for each struct */
#define ATLAS_CLEAN_STRUCTS(S)",
    );
    for l in layouts {
        out.push_str(&std::format!(" \\\n    S({}, {}, {})", l.name, l.size, l.align));
    }
    out.push_str("\n\n/* F(struct, field, offset), for each field in its order */\n#define ATLAS_CLEAN_FIELDS(F)");
    for l in layouts {
        for (field, offset) in &l.fields {
            out.push_str(&std::format!(" \\\n    F({}, {field}, {offset})", l.name));
        }
    }
    out.push_str("\n\n/* clang-format on */\n\n#endif\n");
    out
}

#[test]
fn the_c_side_s_layout_file_is_the_layout_the_structs_have() {
    assert_eq!(size_of::<usize>(), 8, "the file is a 64-bit target's");
    let want = text(&layouts());
    if std::env::var_os("ATLAS_CLEAN_LAYOUT").is_some_and(|v| v == "write") {
        std::fs::write(PATH, &want).expect("load/CleanLayout.h written");
        std::println!("wrote {PATH}");
        return;
    }
    let have = std::fs::read_to_string(PATH).unwrap_or_default();
    assert!(
        have == want,
        "load/CleanLayout.h is not the layout the interface's structs have: it would be\n{want}\n(in rust/clean, \
         ATLAS_CLEAN_LAYOUT=write cargo test --features std layout writes it)"
    );
}
