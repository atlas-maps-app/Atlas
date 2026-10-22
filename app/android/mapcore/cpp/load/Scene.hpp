// The scene a loader makes of OpenStreetMap's data (ENGINE.md step 3, stage P): the port's `S`, typed. Each record is a
// dict of amsterdam.py's load (and guess_tags, the places and `hidden` after it), field for field: a field Python
// leaves None is an empty optional, a tag's value is the document's own string (an empty one is not None), and a field
// the dict may not have at all says so. The records view the document they were loaded from (their tags, the tags'
// strings), so the document must outlive the scene; what a building tradition gave them views the tradition, which the
// scene holds.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Values.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::load {

using geometry::V2;

class Tradition;

/// An element's tags as the loaders read them: a dict, looked up by key (a document holds each key once).
class Tags {
public:
    Tags() = default;
    explicit Tags(std::span<const osm::Tag> tags) noexcept : tags_(tags) {}

    /// `t.get(key)`.
    [[nodiscard]] std::optional<std::string_view> get(std::string_view key) const noexcept {
        for (const osm::Tag& t : tags_) {
            if (t.key == key) return t.value;
        }
        return std::nullopt;
    }
    /// `key in t`.
    [[nodiscard]] bool has(std::string_view key) const noexcept { return get(key).has_value(); }
    /// `t.get(key) == value`.
    [[nodiscard]] bool is(std::string_view key, std::string_view value) const noexcept {
        const auto v = get(key);
        return v.has_value() && *v == value;
    }
    /// `bool(t.get(key))`: there, and not empty.
    [[nodiscard]] bool truthy(std::string_view key) const noexcept {
        const auto v = get(key);
        return v.has_value() && !v->empty();
    }
    [[nodiscard]] std::span<const osm::Tag> all() const noexcept { return tags_; }

private:
    std::span<const osm::Tag> tags_;
};

/// Python's truth of an optional string: there, and not empty.
[[nodiscard]] constexpr bool truthy(const std::optional<std::string_view>& s) noexcept {
    return s.has_value() && !s->empty();
}

/// Where a building's height came from (`src`): its height tag, its levels, or neither (then guessed).
enum class HeightSource : std::uint8_t { none, height, levels };

/// A tag guess_tags records as guessed.
enum class Guess : std::uint8_t {
    height,
    building_levels,
    roof_shape,
    roof_height,
    building_colour,
    building_material,
    roof_colour,
    roof_direction,
    roof_material,
    roof_orientation,
};

/// The tag's key: `building:levels` for Guess::building_levels.
[[nodiscard]] std::string_view key_of(Guess guess) noexcept;

/// What guess_tags guessed for one building, in the order its dict took the keys.
class Guessed {
public:
    /// `g[key] = True`: a key already there keeps its place.
    void add(Guess guess) noexcept {
        if (!has(guess)) keys_[count_++] = guess;
    }
    [[nodiscard]] bool has(Guess guess) const noexcept {
        for (std::uint8_t i = 0; i < count_; ++i) {
            if (keys_[i] == guess) return true;
        }
        return false;
    }
    [[nodiscard]] std::span<const Guess> keys() const noexcept { return std::span(keys_).first(count_); }
    /// `g.pop(key, None)`: the keys after it move up.
    void remove(Guess guess) noexcept {
        std::uint8_t k = 0;
        for (std::uint8_t i = 0; i < count_; ++i) {
            if (keys_[i] != guess) keys_[k++] = keys_[i];
        }
        count_ = k;
    }

private:
    std::array<Guess, 10> keys_{};
    std::uint8_t count_ = 0;
};

/// Which of generic.py's paths gave a building its look: none; Amsterdam's homes, which keep their guessed brick and
/// plaster (`keep_homes`: a kind, a roof material and why); the generic look by kind and age; a tradition's.
enum class LookPath : std::uint8_t { none, home, generic, tradition };

/// A tradition's details for one building (generic.py's `trad`): the tradition's windows, timber boards, base band and
/// chimneys where they apply (null where not: values of the tradition's file), a shutter colour, the eaves' overhang,
/// a cornice, a hoist beam, crenellations.
struct TradDetails {
    tiles::Value win;
    std::optional<std::string_view> shutter;
    double eaves = 0.0;
    tiles::Value boards;
    tiles::Value band;
    bool cornice = false;
    bool hoist = false;
    tiles::Value chimney;
    bool crenel = false;
};

/// A box in the frame's metres, as `(min(xs), max(xs), min(zs), max(zs))`.
struct Box {
    double x0 = 0.0;
    double x1 = 0.0;
    double z0 = 0.0;
    double z1 = 0.0;
};

/// A building or a building part (amsterdam.py's `rec`): its outline, what its tags say, and what guess_tags adds.
struct Building {
    std::vector<V2> poly; ///< the outline, its closing point dropped, turned so its signed area is positive
    std::int64_t id = 0;
    Tags tags;
    double h = 0.0;   ///< metres: height, or from building:levels and roof:levels, or guessed
    double min = 0.0; ///< min_height, or 0
    HeightSource src = HeightSource::none;
    std::optional<std::int64_t> year;         ///< the four digits start_date begins with
    std::optional<std::string_view> roof;     ///< roof:shape, or the shape guessed for a building
    std::optional<double> roof_h;             ///< roof:height, or the height guessed for the shape
    std::optional<std::string_view> orient;   ///< roof:orientation, or `along` for a guessed gable
    std::optional<std::string_view> mat;      ///< building:material (None once a colour is guessed)
    std::optional<std::string_view> rmat;     ///< roof:material
    std::optional<std::string_view> col;      ///< building:colour, or the colour guessed
    std::optional<std::string_view> rcol;     ///< roof:colour, or the colour guessed
    std::optional<std::string_view> mat_draw; ///< the material drawn; not in the dict where none was set
    std::int32_t levels = 1;                  ///< storeys, mapped or from the height (the port's Int)
    Guessed guessed;
    Box bb;                            ///< `_bb`
    std::vector<std::uint32_t> places; ///< the places in it, as indices of the scene's nodes; no `places` when empty
    bool hidden = false;               ///< an outline its parts stand for (`hidden`, which only a building has)
    // regions.py's load adds:
    bool relation = false;                 ///< mapped as a multipolygon relation (`otype` 'relation'), else a way
    std::optional<std::int64_t> parent_id; ///< a part: the id of the outline it stands in
    bool in_church = false;                ///< a part standing in a church (`in_church`, only where true)
    // generic.py's looks (load/Generic.hpp): `look` says which of its paths set them, and so which keys the dict has
    // (`kind`, `rmat_draw` and `look_why` on every path; `col_draw`, `rcol_draw` and `no_win` on a full look; `trad` on
    // a tradition's)
    LookPath look = LookPath::none;
    std::optional<std::string_view> kind;      ///< the generic look's kind (home, office, ...)
    std::optional<std::string_view> rmat_draw; ///< the roof material drawn
    std::optional<std::string> col_draw;       ///< the wall colour drawn (#RRGGBB)
    std::optional<std::string> rcol_draw;      ///< the roof colour drawn
    bool no_win = false;                       ///< no window rows (halls, tanks, glass walls)
    std::optional<std::string> look_why;       ///< why the look is what it is; None where nothing was guessed
    std::optional<std::string_view> lv_why;    ///< why its levels are guessed as they are (`lv_why`), where they are
    std::optional<std::string_view> roof_why;  ///< `mansard` where a tradition's rule gave it one
    TradDetails trad;                          ///< a tradition's details (LookPath::tradition)
    /// The walls it shares with a neighbour (`party`: a tradition's chimneys stand on them), each from a corner to the
    /// next; only where the tradition's chimneys are party ones, and the building is not hidden.
    std::optional<std::vector<std::array<V2, 2>>> party;
};

/// A road (`S['roads']`).
struct Road {
    std::vector<V2> pts;
    std::int64_t id = 0;
    Tags tags;
    double w = 0.0; ///< metres: width, or the class's
    std::string_view hw;
    bool bridge = false;
    double layer = 0.0;
    bool wguess = false; ///< no width mapped
};

/// The OpenStreetMap element a record of the ground comes from, and its ring's place among the element's rings (a
/// relation's outer rings, a way's one): what orders the ground across the windows the phone builds (ENGINE.md step 3:
/// Overpass lists an answer by type, then id). The renders' dicts lack it, so the parity dumps leave it out.
struct Origin {
    osm::Kind kind = osm::Kind::way;
    std::int64_t id = 0;
    std::uint32_t part = 0;
};

/// A ground area (`S['areas']`): a park, a square, a pedestrian area...
struct Area {
    std::vector<V2> poly;
    std::string_view kind;
    Tags tags;
    Origin from;
};

/// A name along a way (`S['labels']`): a canal's or a street's.
struct Label {
    std::vector<V2> pts;
    std::string_view name;
    std::string_view kind; ///< `canal` or `street`
};

/// How a place looks (poi_style.py's `style`): its category and colour, glyph, kind label, brand and sign colours.
struct PlaceStyle {
    std::string_view cat;
    std::string_view col;
    std::string_view glyph;
    std::string kind;
    std::optional<std::string_view> brand;
    std::optional<std::string_view> bg;
    std::optional<std::string_view> fg;
};

/// A node (`S['nodes']`): its position, tags, the style of a place, and the building a place stands in.
struct Node {
    double x = 0.0;
    double z = 0.0;
    std::int64_t id = 0;
    Tags tags;
    std::optional<PlaceStyle> style;       ///< `style`: only a place has one
    std::optional<std::uint32_t> building; ///< `_b`: the building a place stands in, an index of the scene's buildings
};

/// A tree's guessed tags (`guessed`): height, diameter_crown and leaf_type where the tree lacks them, in that order.
struct TreeGuessed {
    bool height = false;
    bool diameter_crown = false;
    bool leaf_type = false;
};

/// A tree (`S['trees']`): a node, and what guess_tags gives it.
struct Tree {
    std::uint32_t node = 0; ///< its index among the scene's nodes, which hold it too
    double h = 0.0;
    double crown = 0.0;
    TreeGuessed guessed;
};

/// Land cover (`S['land']`, regions.py's): its ring, its kind (regions.py's LAND), tags and id.
struct Land {
    std::vector<V2> poly;
    std::string_view kind;
    Tags tags;
    std::int64_t id = 0;
    Origin from;
};

/// A runway, taxiway, taxilane, stand or stopway (`S['aero']`).
struct Aero {
    std::vector<V2> pts;
    std::string_view kind;
    Tags tags;
    std::int64_t id = 0;
    Origin from;
};

/// A motorway, trunk or primary road and their links (`S['big']`), above the ground.
struct BigRoad {
    std::vector<V2> pts;
    Tags tags;
    std::string_view hw;
    double layer = 0.0;
    std::int64_t id = 0;
    Origin from;
};

/// A railway, or a crane's rail (`S['rail']`), above the ground.
struct Railway {
    std::vector<V2> pts;
    Tags tags;
    bool crane = false;
    Origin from;
};

/// A power line (`S['power']`).
struct PowerLine {
    std::vector<V2> pts;
    Tags tags;
    Origin from;
};

/// A breakwater, groyne, pier or quay (`S['marine']`).
struct Marine {
    std::vector<V2> pts;
    std::string_view kind;
    Tags tags;
    bool closed = false; ///< its way's first and last points the same
    Origin from;
};

/// A gantry or a goods conveyor (`S['gantries']`, `S['conveyors']`).
struct Gantry {
    std::vector<V2> pts;
    Tags tags;
    std::int64_t id = 0;
    std::optional<double> h;
};

/// A crane, a mast, a tower with a height or a wind turbine: where it stands, and what it is (`S['cranes']`,
/// `S['masts']`, `S['mtowers']`, `S['turbines']`).
struct Item {
    double x = 0.0;
    double z = 0.0;
    Tags tags;
    std::int64_t id = 0;
    std::optional<double> h;
    std::optional<double> rotor;         ///< a turbine's rotor:diameter
    std::optional<std::vector<V2>> poly; ///< a crane mapped as a way: its points (x, z their mean)
};

/// A power tower or pole (`S['towers']`, by id): where it stands and its height.
struct Tower {
    std::int64_t id = 0;
    V2 at;
    std::optional<double> h;
};

/// A national park's (or the dunes' protected area's) ring (`S['park']`).
struct ParkEdge {
    std::vector<V2> ring;
    Tags tags;
    Origin from;
};

/// The scene, list for list as `S`.
struct Scene {
    std::vector<Building> buildings;
    std::vector<Building> parts;
    std::vector<std::vector<V2>> water;
    std::vector<Road> roads;
    std::vector<std::vector<V2>> rails;
    std::vector<Origin> rails_from; ///< each tram rail's way (Origin)
    std::vector<Area> areas;
    std::vector<Tree> trees;
    std::vector<Node> nodes;
    std::vector<std::uint32_t> pois; ///< the places, as indices of the nodes
    std::vector<Label> labels;
    // regions.py's load adds:
    std::vector<Land> land; ///< sorted by LAND's draw order, then by area, largest first
    std::vector<Aero> aero;
    std::vector<BigRoad> big;
    std::vector<Railway> rail;
    std::vector<PowerLine> power;
    std::vector<Marine> marine;
    std::vector<Item> cranes;
    std::vector<Item> masts;
    std::vector<Gantry> gantries;
    std::vector<Gantry> conveyors;
    std::vector<Item> turbines;
    std::vector<Item> mtowers;
    std::vector<Tower> towers; ///< in the order their ids came
    std::vector<ParkEdge> park;
    std::vector<std::vector<V2>> water_holes; ///< islands: the inner rings of the water's relations
    std::vector<bool> natural_shore;          ///< per ring of `water`: a natural shore (no quay wall)
    /// The building tradition the looks came from (`S['tradition']`, read), or null. The buildings' tradition values
    /// and strings view it.
    std::shared_ptr<const Tradition> tradition;
};

/// Why a document cannot be loaded, and the element that showed it.
struct LoadError {
    enum class Fault : std::uint8_t {
        off_earth, ///< a position that is not one (a latitude past ±90°, a longitude past ±180°, not a number)
    };
    Fault fault = Fault::off_earth;
    std::int64_t element = 0; ///< its id
};

/// The fault's name, for logs and tests.
[[nodiscard]] std::string_view name_of(LoadError::Fault fault) noexcept;

} // namespace atlas::load
