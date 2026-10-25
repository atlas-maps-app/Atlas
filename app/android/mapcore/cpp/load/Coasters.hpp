// A theme park's coaster tracks (ENGINE.md step 4, "Trees and things"; render/coasters.py's load, R/Coasters.kt), as
// regions.py loads them for a region with a park (R's `park`). OSM maps a track's line, its layer and which pieces are
// the lift hill, the station and the brake run, not how high it runs, so the heights are the renders' guesses: each
// piece at a share of its ride's top height by its layer (the ride's published profile where there is one, else 12 m),
// the lift hill climbing between its neighbours to 95 % of its ride's top, the station and the brake run at 1.2 m, a
// piece of layer 0 just off the ground, and the pieces meeting where they share a node (the mean of their heights),
// each ramping to its ends over up to 18 m. A piece in a tunnel, below ground or inside a building is left out; a
// monorail (railway=monorail) is a single beam at 3.5 m. Every value is the port's double, by its own arithmetic.
#pragma once

#include "load/Scene.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Sections.hpp"

#include <expected>
#include <functional>
#include <map>
#include <string>
#include <vector>

namespace atlas::load {

/// What regions.py reads of a park's rides' published profiles (its `hts` and `kinds`): each ride's top height, where
/// its profile has one (rideProfile.stats.heightM), and the rides that are wooden coasters (rideProfile.types), by
/// name.
struct RideProfiles {
    std::map<std::string, double, std::less<>> heights;
    std::map<std::string, bool, std::less<>> wooden;
};

/// The profiles in a park's folder of them (data/park/<name>/pf), its JSON files in their names' order as the port
/// reads them; or why one cannot be read.
[[nodiscard]] std::expected<RideProfiles, std::string> read_ride_profiles(const std::string& folder);

/// coasters.load(raw, XZ, S['buildings'] + S['parts'], hts, kinds): the document's coaster tracks in `frame`, in its
/// order; or the element whose position is off the Earth, or whose layer int(float(v)) cannot take.
[[nodiscard]] std::expected<std::vector<CoasterTrack>, LoadError>
load_coasters(const osm::Document& doc, const tiles::Frame& frame, const Scene& scene, const RideProfiles& rides);

} // namespace atlas::load
