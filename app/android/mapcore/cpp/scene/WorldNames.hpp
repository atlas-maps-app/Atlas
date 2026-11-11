// The world's names (ENGINE.md step 5, "Names"; its step 9): the places a world tile names, and the pick of them a
// frame shows.
//
//   - Stage T keeps a tile's places beside its geometry, in the tile's own order (the packer's rank): each with its
//     kind, its people, whether it is a capital, its place and its `name`, the name in its own script.
//   - The pick takes the names of the tiles the frame draws, each from the tile that draws its ground (a place is
//     named once, as a city is lit once), that stand in view on the bent ground and not behind the horizon, and
//     takes the first `most` by rank: a country, a capital, then cities, towns, villages and suburbs, each kind by
//     its people. A name picked stays picked while it stands in view among the first `keep` by rank, so a view's
//     names do not come and go as the camera moves (a frame has from `most` to `keep` of them); one that leaves is
//     handed on for a fade's length as leaving, and placement fades it out where it stands. Names are picked again at
//     most ten times a second while the camera moves, and once at rest (again there when a tile that names places
//     comes).
//   - A name's look follows the camera's distance: a city's, a town's or a village's (a suburb's too) up to the
//     globe's 6,000 km, the Globe board's beyond, none beyond 20,000 km, where the board has none. Its id holds for
//     the map's life, so its place and its fades follow it; a name in another look is another id.
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

/// A place's kind as a world tile names it, by rank.
enum class NameKind : std::uint8_t { country, city, town, village, suburb };

/// The kind of a place=* value, or nothing for one the world does not name.
[[nodiscard]] std::optional<NameKind> name_kind(std::string_view place) noexcept;

/// A place a world tile names.
struct WorldName {
    NameKind kind = NameKind::city;
    bool capital = false;  ///< capital=yes: a country's
    std::uint8_t cell = 0; ///< its cell of the tile (row · 4 + column, rows from the north)
    double people = 0;     ///< its population as the packer ranks by it (0: none given)
    double lat = 0;        ///< degrees
    double lon = 0;
    std::string text; ///< its `name`
};

/// A name's look, the app's to draw (Kotlin's LabelKind for a name, in this order).
enum class NameStyle : std::uint8_t { city, town, village, globe };

/// A look's text size, dp, as the app draws it (LabelPainter's: the Main board's place style 17, a town 15, a village
/// 13, the Globe board's 12): the readable floor over it is the least a name of the look is drawn at.
[[nodiscard]] constexpr double name_size_dp(NameStyle style) noexcept {
    switch (style) {
    case NameStyle::city: return 17.0;
    case NameStyle::town: return 15.0;
    case NameStyle::village: return 13.0;
    case NameStyle::globe: return 12.0;
    }
    return 17.0;
}

/// The camera distance from which names take the Globe board's look, and the one beyond which none is shown, metres.
inline constexpr double names_globe_from = 6'000'000.0;
inline constexpr double names_none_beyond = 20'000'000.0;

/// The look of a name of `kind` from a camera `distance` metres from its target, or nothing beyond the last.
[[nodiscard]] std::optional<NameStyle> name_style(NameKind kind, double distance) noexcept;

/// A name's priority among the labels: under every label of the boards' (theirs are 0 or more), a country first,
/// then a capital, then by kind, each kind by its people.
[[nodiscard]] std::int32_t name_priority(const WorldName& name) noexcept;

/// The first bit of a name's id: the boards' labels' ids, the app's, stay under it.
inline constexpr std::uint32_t name_id_bit = 0x8000'0000U;

/// A name a frame shows.
struct PickedName {
    std::uint32_t id = 0;
    NameStyle style = NameStyle::city;
    std::int32_t priority = 0;
    double lat = 0;
    double lon = 0;
    /// No longer picked: it fades out where it stands (labels::Candidate::hidden) until the fade's length has gone.
    bool leaving = false;
    std::string_view text; ///< the pick's own (it holds until the name's id is let go)
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

    /// The frame's names at `seconds`: picked again where a pick is due (the file's head; `rest`: the camera rests,
    /// `changed`: it is not where the last frame saw it), else the last pick's. `world` null, or a camera beyond the
    /// last distance: none (those shown leave). `extra` are names no tile holds (a test's), picked as a tile's are.
    [[nodiscard]] std::span<const PickedName> update(const WorldFrame* world, std::span<const WorldName> extra,
                                                     double distance, const InView& in_view, double seconds, bool rest,
                                                     bool changed);

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
    };

    void pick(const WorldFrame* world, std::span<const WorldName> extra, double distance, const InView& in_view,
              double seconds);
    [[nodiscard]] Id& id_of(const WorldName& name, NameStyle style, double seconds);

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
