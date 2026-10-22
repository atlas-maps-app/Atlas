// A building tradition (ENGINE.md step 3, stage P): render/traditions/<id>.json, the walls, roofs, windows and details
// a building culture typically has, by kind and era, typed as generic.py reads them (`tradition(tid)`, then trad_look,
// party_walls and apply). Every value it gives a building is a real OSM value, guessed and marked; what is mapped
// always wins (load/Generic.hpp applies it).
//
// The file is read strictly: a value generic.py reads that is missing, or of a type Python could not use alike, refuses
// the tradition, named by its path (`walls.home.before:1830[2]`), where Python would raise only for the building that
// reaches it. An entry that may differ by age keeps its `before:N` and `after:N` branches in the file's order. The
// details generic.py hands on whole (the windows, boards, base band and chimneys) stay values of the file, which the
// tradition owns: what views them must not outlive it.
#pragma once

#include "tiles/Json.hpp"
#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::load {

/// One of a wall's options: its material and colour, and its weight in the pick.
struct WallOption {
    std::string_view material;
    std::string_view colour;
    double weight = 0.0;
};

/// One of a roof's shapes, and its weight in the pick.
struct ShapeOption {
    std::string_view shape;
    double weight = 0.0;
};

/// A roof: the shapes picked from, its material and colours, its pitch, and whether a narrow terrace's ridge runs along
/// the street (`terrace_across`).
struct RoofEntry {
    std::vector<ShapeOption> shapes;
    std::string_view material;
    std::vector<std::string_view> colours; ///< never empty
    std::optional<double> pitch;
    bool terrace_across = false;
};

/// An entry that may differ by age (generic.py's `era`): the first branch whose year fits, else `default`; a plain
/// entry is its own default. A branch or default may be null (Python's None): the table's next key is then tried.
template <class T>
struct Era {
    struct Branch {
        bool before = false; ///< `before:N` (else `after:N`)
        std::int64_t year = 0;
        std::optional<T> value;
    };
    std::vector<Branch> branches;
    std::optional<T> fallback;

    /// era(entry, yr).
    [[nodiscard]] const std::optional<T>& at(std::optional<std::int64_t> yr) const noexcept {
        const bool known = yr.has_value() && *yr != 0;
        for (const Branch& b : branches) {
            if (known && (b.before ? *yr < b.year : *yr >= b.year)) return b.value;
        }
        return fallback;
    }
};

/// A table of entries by building type or kind (`walls`, `roof`), in the file's order.
template <class T>
using EraTable = std::vector<std::pair<std::string_view, Era<T>>>;

/// A rule's `when` (generic.py's `fits`): each test present must hold.
struct RuleWhen {
    std::optional<std::vector<std::string_view>> kinds;
    std::optional<std::vector<std::string_view>> buildings;
    std::optional<std::vector<std::string_view>> materials;
    bool year = false; ///< `year` is there
    double year_from = -9999;
    double year_to = 9999;
    bool year_unknown = false;
    std::optional<std::pair<double, double>> levels;
    std::optional<std::pair<double, double>> area;
    std::optional<double> narrow;
    std::optional<double> min_h;
};

/// A rule's roof: the shape it sets, and what it overrides of the tradition's roof.
struct RuleRoof {
    std::string_view shape;
    std::optional<std::pair<double, double>> roof_h_range;
    std::optional<std::string_view> orient; ///< only where truthy
    std::optional<double> roof_h;
    std::optional<double> pitch;
    std::optional<std::string_view> material;
    std::optional<std::vector<std::string_view>> colours;
};

struct Rule {
    RuleWhen when;
    RuleRoof roof;
};

/// A detail of old buildings only (`cornice`, `hoist`): always (true), never (false or none), or before a year.
struct OldDetail {
    bool always = false;
    std::optional<double> before;

    /// generic.py's `before(v)`.
    [[nodiscard]] bool holds(std::optional<std::int64_t> yr) const noexcept {
        if (before) return yr.has_value() && *yr != 0 && static_cast<double>(*yr) < *before;
        return always;
    }
};

/// The shutters: the kinds that have them, the share of those buildings, the colours.
struct Shutters {
    std::vector<std::string_view> kinds;
    double share = 0.0;
    std::vector<std::string_view> colours; ///< never empty
};

/// Why a tradition cannot be read: the JSON's fault, or the path of the value generic.py could not use and why.
struct TraditionError {
    std::optional<tiles::JsonError> json;
    std::string at;
    std::string_view what;
};

/// A tradition, typed.
class Tradition {
public:
    std::string_view name;
    std::string_view says;
    std::vector<std::pair<std::string_view, std::string_view>> says_by;
    double floor_h = 0.0;
    double modern_from = 1950;
    std::vector<std::pair<std::string_view, std::int64_t>> levels; ///< has `default`
    std::vector<std::pair<std::string_view, double>> heights;
    EraTable<std::vector<WallOption>> walls;
    EraTable<RoofEntry> roof; ///< has `default`, whose era never gives None
    std::vector<Rule> rules;
    /// The windows (`kinds`, `materials` read; the whole handed on), or null.
    tiles::Value windows;
    std::vector<std::string_view> window_kinds;
    std::optional<std::vector<std::string_view>> window_materials;
    std::optional<Shutters> shutters;
    /// The timber boards and the base band (`materials` read), the chimneys (`share`, `kind` read), or null.
    tiles::Value boards;
    std::vector<std::string_view> board_materials;
    tiles::Value base_band;
    std::vector<std::string_view> band_materials;
    tiles::Value chimneys;
    double chimney_share = 0.0;
    bool party_chimneys = false; ///< chimneys.kind == "party": party_walls runs
    double eaves = 0.0;
    OldDetail cornice;
    OldDetail hoist;
    bool towers_crenellated = false;

    /// A table's entry by key, or null.
    template <class T>
    [[nodiscard]] static const Era<T>* entry(const EraTable<T>& table, std::string_view key) noexcept {
        for (const auto& [k, v] : table) {
            if (k == key) return &v;
        }
        return nullptr;
    }

    /// The file's value tree, which `windows`, `boards`, `base_band`, `chimneys` and every string view.
    [[nodiscard]] tiles::Value root() const noexcept { return json_.root(); }

private:
    friend struct TraditionReader;
    friend std::expected<std::shared_ptr<const Tradition>, TraditionError> read_tradition(std::span<const std::byte>);
    tiles::JsonValue json_;
};

/// The tradition a file's bytes hold, or why it cannot be read.
[[nodiscard]] std::expected<std::shared_ptr<const Tradition>, TraditionError>
read_tradition(std::span<const std::byte> json);

} // namespace atlas::load
