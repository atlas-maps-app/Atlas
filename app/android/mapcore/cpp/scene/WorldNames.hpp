// The world's names (ENGINE.md step 5, "Names"; its step 9): the places and the waters a world tile names, and the
// pick of them a frame shows.
//
//   - Stage T keeps a tile's places beside its geometry, in the tile's own order (the packer's rank): each with its
//     kind, its people, whether it is a capital, its place and its `name`, the name in its own script. After them its
//     waters (an ocean, a sea, a bay, a strait, a lake, a reservoir: each at its name's point with its area) and its
//     rivers (each at the middle of its longest piece in the tile, with its length there), in the packer's ranks.
//   - A water is named as its size on the screen deserves, in one rank with the places (water_priority): an ocean or
//     a sea with the countries; a lake, a reservoir, a bay or a strait by its area on the screen, with the cities
//     from 32,000 dp², with the towns from 8,000 dp², else with the villages; a river after the towns. A water of a
//     known area under 2,000 dp² on the screen (the packer's own least for a name's level) and a river shorter than
//     96 dp on it have no name, so no place is lost to a pond or a brook. A river, which every tile it crosses names,
//     is named once within water_apart_dp on the screen: where it was named before while that row is picked, else
//     at its row nearest the camera's target. A water that two rows name (OpenStreetMap's node of a bay and its
//     area) is named once within as much too: where it was named before, else at its row of the higher rank.
//   - The pick takes the names of the tiles the frame draws, each from the tile that draws its ground (a place is
//     named once, as a city is lit once), that stand in view on the bent ground and not behind the horizon, and
//     takes the first `most` by rank: a country, a capital, then cities, towns, villages and suburbs, each kind by
//     its people. A name picked stays picked while it stands in view among the first `keep` by rank, so a view's
//     names do not come and go as the camera moves (a frame has from `most` to `keep` of them); one that leaves is
//     handed on for a fade's length as leaving, and placement fades it out where it stands. Names are picked again at
//     most ten times a second while the camera moves, and once at rest (again there when a tile that names places
//     comes).
//   - A water whose point does not show, or lies within its name's height of the frame's edge, is named where its
//     water shows on the frame (its stand: scene/WaterStand.hpp), under the same rank. A stand is a place of the
//     ground: it holds while it shows and is water, and the name goes back to the water's own point once that lies
//     two heights clear of the edge. A name at a stand has an id of its own, so it fades between the two.
//   - A name stands at its place in the tile that draws its ground, as that tile's light of it does; a lit place's
//     name stands clear of its light at night (scene::name_rise, scene/WorldLook.hpp).
//   - A name's look follows the camera's distance: a city's, a town's or a village's (a suburb's too) up to the
//     globe's 6,000 km, the Globe board's beyond, none beyond 20,000 km, where the board has none. Its id holds for
//     the map's life, so its spot and its fades follow it; a name in another look is another id.
//
// The pick is pure: what stands in view is the caller's to say (the labels pass projects by scene::name_point).
#pragma once

#include "scene/WorldBend.hpp"
#include "view/Camera.hpp"

#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::scene {

struct WorldFrame; // scene/WorldTiles.hpp

/// A place's kind as a world tile names it, by rank; then a water's (the packer's kinds) and a river.
enum class NameKind : std::uint8_t {
    country,
    city,
    town,
    village,
    suburb,
    ocean,
    sea,
    bay,
    strait,
    lake,
    reservoir,
    river
};

/// The kind of a place=* value, or nothing for one the world does not name.
[[nodiscard]] std::optional<NameKind> name_kind(std::string_view place) noexcept;
/// The kind of a water's row (the label layer's source 2: ocean, sea, bay, strait, lake, reservoir), or nothing.
[[nodiscard]] std::optional<NameKind> water_kind(std::string_view kind) noexcept;
/// Whether `kind` is a water's or a river's.
[[nodiscard]] constexpr bool is_water(NameKind kind) noexcept { return kind >= NameKind::ocean; }

/// A place a world tile names.
struct WorldName {
    NameKind kind = NameKind::city;
    bool capital = false;  ///< capital=yes: a country's
    std::uint8_t cell = 0; ///< its cell of the tile (row · 4 + column, rows from the north)
    double people = 0;     ///< its population as the packer ranks by it (0: none given)
    double lat = 0;        ///< degrees
    double lon = 0;
    std::string text; ///< its `name`
    /// Its tile lights it at night (scene::world_lights of the tile's level: a city, a town from L10), where the
    /// world's style has the ladder.
    bool lit = false;
    /// A water's area, m² (0: a node's, which says none), or a river's length in its tile, m.
    double extent = 0;
};

/// A name's look, the app's to draw (MapCore.NameWant's looks, in this order): a place's three, the globe's, and a
/// water's (the Main board's one water label, the IJ's).
enum class NameStyle : std::uint8_t { city, town, village, globe, water };

/// A look's text size, dp, as the app draws it (LabelPainter's: the Main board's place style 17, a town 15, a village
/// 13, the Globe board's 12, the board's water 21): the readable floor over it is the least a name of the look is
/// drawn at.
[[nodiscard]] constexpr double name_size_dp(NameStyle style) noexcept {
    switch (style) {
    case NameStyle::city: return 17.0;
    case NameStyle::town: return 15.0;
    case NameStyle::village: return 13.0;
    case NameStyle::globe: return 12.0;
    case NameStyle::water: return 21.0;
    }
    return 17.0;
}

/// The camera distance from which names take the Globe board's look, and the one beyond which none is shown, metres.
inline constexpr double names_globe_from = 6'000'000.0;
inline constexpr double names_none_beyond = 20'000'000.0;

/// The look of a name of `kind` from a camera `distance` metres from its target, or nothing beyond the last: a
/// water's is the board's water look up to the globe's 6,000 km and none beyond, where the Globe board names no water.
[[nodiscard]] std::optional<NameStyle> name_style(NameKind kind, double distance) noexcept;

/// A place's priority among the labels: under every label of the boards' (theirs are 0 or more), a country first,
/// then a capital, then by kind (the rivers' class between the towns and the villages), each kind by its people.
[[nodiscard]] std::int32_t name_priority(const WorldName& name) noexcept;

/// The least area on the screen a water of a known area is named from, dp² (the packer's own: nameLevel's 2,000 dp²
/// at a level's start, about 45 by 45 dp), the areas from which it ranks with the towns and with the cities, the
/// least length on the screen a river is named from, dp (the packer's name span, twice its NAME_HALF), and how far
/// apart on the screen two rows of one name are both named, dp (a river's rows; a water's node and its area).
inline constexpr double water_least_dp2 = 2'000.0;
inline constexpr double water_town_dp2 = 8'000.0;
inline constexpr double water_city_dp2 = 32'000.0;
inline constexpr double river_least_dp = 96.0;
inline constexpr double water_apart_dp = 400.0;

/// A water's or a river's priority among the labels where a dp of the screen is `metres_a_dp` metres at the camera's
/// target (0 or less: no scale known, every water as large as can be), in one rank with the places' (the file's
/// head); nothing for one too small on the screen to name. Within its class the larger first.
[[nodiscard]] std::optional<std::int32_t> water_priority(const WorldName& name, double metres_a_dp) noexcept;

/// What the pick knows of the frame's camera beside its distance: the metres a dp of the screen is at its target (the
/// distance over the camera's focal length, dp; 0: unknown), and its target, degrees.
struct NameScale {
    double metres_a_dp = 0;
    double lat = 0;
    double lon = 0;
};

/// Where a name stands, degrees: a water's own point, or its stand where that does not show (scene/WaterStand.hpp).
struct NamePlace {
    double lat = 0;
    double lon = 0;
    bool stand = false;
};

/// The first bit of a name's id: the boards' labels' ids, the app's, stay under it.
inline constexpr std::uint32_t name_id_bit = 0x8000'0000U;

/// A name a frame shows.
struct PickedName {
    std::uint32_t id = 0;
    NameStyle style = NameStyle::city;
    std::int32_t priority = 0;
    /// Its place in the tile that draws its ground this pick (where that tile's light of it is drawn; the globe's
    /// place of a city and the base's lie apart), degrees.
    double lat = 0;
    double lon = 0;
    /// No longer picked: it fades out where it stands (labels::Candidate::hidden) until the fade's length has gone.
    bool leaving = false;
    std::string_view text; ///< the pick's own (it holds until the name's id is let go)
    /// Its tile lights it at night, and its people (the light's size): its name stands clear of the light
    /// (scene::name_rise).
    bool lit = false;
    double people = 0;
    /// A water's name at its stand, not its own point; and the row's own point (a place's: where it stands).
    bool stand = false;
    double own_lat = 0;
    double own_lon = 0;
};

/// The pick of a map's names.
class WorldNames {
public:
    /// The rank a name is picked within, the rank a picked name stays picked within (the names a frame has at most),
    /// the least time between two picks while the camera moves and how long a name that left is handed on, seconds.
    static constexpr std::size_t most = 64;
    static constexpr std::size_t keep = 96;
    static constexpr double pick_seconds = 0.1;
    static constexpr double leave_seconds = 0.25;
    /// The ids kept at most; beyond, those not picked for a minute are let go.
    static constexpr std::size_t most_ids = 16'384;

    /// Whether a place stands in view (the labels pass: on the frame and before the horizon).
    using InView = std::function<bool(double lat, double lon)>;
    /// Where a water's name stands this pick (not a river's): its own point, a stand, or nothing where neither
    /// shows; `held` is the stand the last pick gave it, if it stood at one (the labels pass: scene::WaterStands).
    using Stand = std::function<std::optional<NamePlace>(const WorldName& water, const NamePlace* held)>;

    /// The frame's names at `seconds`: picked again where a pick is due (the file's head; `rest`: the camera rests,
    /// `changed`: it is not where the last frame saw it), else the last pick's. `world` null, or a camera beyond the
    /// last distance: none (those shown leave). `extra` are names no tile holds (a test's), picked as a tile's are.
    /// `scale` ranks the waters by their size on the screen and says where the camera looks. `stand` says where a
    /// water's name stands (none given: at its own point where that is in view, as a place's).
    [[nodiscard]] std::span<const PickedName> update(const WorldFrame* world, std::span<const WorldName> extra,
                                                     double distance, const InView& in_view, double seconds, bool rest,
                                                     bool changed, const NameScale& scale = {},
                                                     const Stand& stand = {});

    /// How many picks there were (for tests and logs).
    [[nodiscard]] std::uint64_t picks() const noexcept { return picks_; }
    /// Forgets the pick (a new world).
    void reset() noexcept;

private:
    struct Id {
        std::uint32_t id = 0;
        NameStyle style = NameStyle::city;
        double lat = 0;
        double lon = 0;
        double seen = 0; ///< when it was last picked
        std::string text;
    };
    struct Candidate {
        const WorldName* name = nullptr;
        NameStyle style = NameStyle::city;
        std::int32_t priority = 0;
        std::uint32_t id = 0;
        NamePlace place; ///< where it stands: its own point, or a water's stand
    };

    void pick(const WorldFrame* world, std::span<const WorldName> extra, double distance, const InView& in_view,
              double seconds, const NameScale& scale, const Stand& stand);
    /// Of the candidates that are waters' and rivers' rows, those named this pick: one of a name within
    /// water_apart_dp on the screen (the file's head); the others are dropped.
    void pick_waters(const NameScale& scale);
    /// The id of `name` in `style` standing at `place` (a name at another place is another name).
    [[nodiscard]] Id& id_of(const WorldName& name, NameStyle style, const NamePlace& place, double seconds);

    std::unordered_multimap<std::string, Id> ids_; // by text
    std::uint32_t next_id_ = 1;
    std::vector<Candidate> candidates_;
    std::vector<PickedName> picked_;
    std::vector<PickedName> next_;
    std::vector<std::pair<std::uint32_t, double>> left_; // the ids leaving, and when each left
    double picked_at_ = -1e9;
    bool rested_ = false;     // the last frame saw the camera at rest
    std::uint64_t drawn_ = 0; // what the last pick's frame drew of the names' tiles (a hash)
    std::uint64_t picks_ = 0;
};

/// Where a place of the world stands on `view`'s device pixels: on the bent ground as the world pass draws it
/// (scene::world_point: flat up to 18 km, dropped, on the sphere from 600 km), or nothing where it stands nearer than
/// the camera's near plane or behind the horizon (where the bent ground faces away from the eye, by `least_facing`,
/// the cosine between the ground's normal and the ray to the eye a name is shown from).
[[nodiscard]] std::optional<view::Vec2> name_point(const WorldBend& bend, const view::GroundFrame& frame,
                                                   const view::View& view, double lat, double lon,
                                                   double least_facing = 0.0) noexcept;

} // namespace atlas::scene
