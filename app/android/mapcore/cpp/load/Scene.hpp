// The scene a loader makes of OpenStreetMap's data (ENGINE.md step 3, stage P): the port's `S`, typed. Each record is a
// dict of amsterdam.py's load (and guess_tags, the places and `hidden` after it), field for field: a field Python
// leaves None is an empty optional, a tag's value is the document's own string (an empty one is not None), and a field
// the dict may not have at all says so. The records view the document they were loaded from (their tags, the tags'
// strings), so the document must outlive the scene.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "osm/Overpass.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::load {

using geometry::V2;

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

private:
    std::array<Guess, 8> keys_{};
    std::uint8_t count_ = 0;
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

/// A ground area (`S['areas']`): a park, a square, a pedestrian area...
struct Area {
    std::vector<V2> poly;
    std::string_view kind;
    Tags tags;
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

/// The scene, list for list as `S`.
struct Scene {
    std::vector<Building> buildings;
    std::vector<Building> parts;
    std::vector<std::vector<V2>> water;
    std::vector<Road> roads;
    std::vector<std::vector<V2>> rails;
    std::vector<Area> areas;
    std::vector<Tree> trees;
    std::vector<Node> nodes;
    std::vector<std::uint32_t> pois; ///< the places, as indices of the nodes
    std::vector<Label> labels;
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
