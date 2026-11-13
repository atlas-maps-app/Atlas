// The world's names (scene/WorldNames.hpp).
#include "scene/WorldNames.hpp"

#include "base/pymath/PyMath.hpp"
#include "scene/WorldTiles.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::scene {

namespace {

/// How near two places of one name are one place, degrees: a city's within half a degree (Natural Earth's cities
/// against OpenStreetMap's nodes), another kind's where a tile and its coarser tile hold the same node (two villages
/// of one name a few kilometres apart are two places).
[[nodiscard]] constexpr double same_place(NameKind kind) noexcept { return kind == NameKind::city ? 0.5 : 0.001; }
/// How long an id not picked is kept once the ids are more than most_ids, seconds.
constexpr double id_seconds = 60.0;

[[nodiscard]] bool near(NameKind kind, double lat0, double lon0, double lat1, double lon1) noexcept {
    double dlon = std::abs(lon0 - lon1);
    if (dlon > 180.0) dlon = 360.0 - dlon;
    return std::abs(lat0 - lat1) <= same_place(kind) && dlon <= same_place(kind);
}

/// The classes of the one rank, each 2^24 apart: a kind's (a capital a class of its own over the cities, the rivers'
/// between the towns and the villages).
constexpr std::int32_t class_step = 1 << 24;
constexpr std::int32_t class_country = 0;
constexpr std::int32_t class_capital = 1;
constexpr std::int32_t class_city = 2;
constexpr std::int32_t class_town = 3;
constexpr std::int32_t class_river = 4;
constexpr std::int32_t class_village = 5;
constexpr std::int32_t class_suburb = 6;

/// The priority of a name of class `rank` whose worth within it is `value` (0 to 2^24 - 1, the more the higher).
[[nodiscard]] std::int32_t priority_of(std::int32_t rank, double value) noexcept {
    const double within = std::clamp(value, 0.0, static_cast<double>(class_step - 1));
    return -1 - (rank * class_step) - (class_step - 1 - static_cast<std::int32_t>(within));
}

/// A water's worth within its class: its size on a log scale (an ocean's 1.6e14 m² is 47 doublings, under 2^24 at
/// 2^18 a doubling), so the larger is first and no size is past the class.
[[nodiscard]] double size_worth(double extent) noexcept {
    return extent > 1.0 ? pymath::log(extent) / pymath::log(2.0) * static_cast<double>(1 << 18) : 0.0;
}

/// The metres between two places near each other (the rivers' rows of a frame).
[[nodiscard]] double metres_between(double lat0, double lon0, double lat1, double lon1) noexcept {
    double dlon = std::abs(lon0 - lon1);
    if (dlon > 180.0) dlon = 360.0 - dlon;
    const double x = dlon * 111'320.0 * pymath::cos(pymath::radians((lat0 + lat1) / 2.0));
    const double y = (lat0 - lat1) * 110'540.0;
    return pymath::sqrt((x * x) + (y * y));
}

} // namespace

std::optional<NameKind> name_kind(std::string_view place) noexcept {
    if (place == "city") return NameKind::city;
    if (place == "town") return NameKind::town;
    if (place == "village") return NameKind::village;
    if (place == "suburb") return NameKind::suburb;
    if (place == "country") return NameKind::country;
    return std::nullopt;
}

std::optional<NameKind> water_kind(std::string_view kind) noexcept {
    if (kind == "lake") return NameKind::lake;
    if (kind == "reservoir") return NameKind::reservoir;
    if (kind == "bay") return NameKind::bay;
    if (kind == "strait") return NameKind::strait;
    if (kind == "sea") return NameKind::sea;
    if (kind == "ocean") return NameKind::ocean;
    return std::nullopt;
}

std::optional<NameStyle> name_style(NameKind kind, double distance) noexcept {
    if (!(distance <= names_none_beyond)) return std::nullopt;
    if (is_water(kind)) {
        // the Globe board names no water
        if (distance > names_globe_from) return std::nullopt;
        return NameStyle::water;
    }
    if (distance > names_globe_from) return NameStyle::globe;
    switch (kind) {
    case NameKind::town: return NameStyle::town;
    case NameKind::village:
    case NameKind::suburb: return NameStyle::village;
    default: return NameStyle::city;
    }
}

std::int32_t name_priority(const WorldName& name) noexcept {
    // within its class a place by its people
    std::int32_t rank = class_city;
    switch (name.kind) {
    case NameKind::country: rank = class_country; break;
    case NameKind::city: rank = name.capital ? class_capital : class_city; break;
    case NameKind::town: rank = class_town; break;
    case NameKind::village: rank = class_village; break;
    case NameKind::suburb: rank = class_suburb; break;
    default: break;
    }
    return priority_of(rank, name.people);
}

std::optional<std::int32_t> water_priority(const WorldName& name, double metres_a_dp) noexcept {
    const bool scaled = metres_a_dp > 0.0;
    if (name.kind == NameKind::river) {
        if (scaled && name.extent / metres_a_dp < river_least_dp) return std::nullopt;
        return priority_of(class_river, size_worth(name.extent));
    }
    // its area on the screen, dp²: a node's, which says none, is taken as a name's least
    const bool known = name.extent > 0.0;
    const double on_screen =
        !known ? water_least_dp2
               : (scaled ? name.extent / (metres_a_dp * metres_a_dp) : std::numeric_limits<double>::infinity());
    if (on_screen < water_least_dp2) return std::nullopt;
    if (name.kind == NameKind::ocean || name.kind == NameKind::sea) {
        return priority_of(class_country, size_worth(name.extent));
    }
    const std::int32_t rank =
        on_screen >= water_city_dp2 ? class_city : (on_screen >= water_town_dp2 ? class_town : class_village);
    return priority_of(rank, size_worth(name.extent));
}

void WorldNames::reset() noexcept {
    ids_.clear();
    candidates_.clear();
    picked_.clear();
    next_.clear();
    picked_at_ = -1e9;
    rested_ = false;
}

WorldNames::Id& WorldNames::id_of(const WorldName& name, NameStyle style, const NamePlace& place, double seconds) {
    const auto [first, last] = ids_.equal_range(name.text);
    for (auto at = first; at != last; ++at) {
        Id& id = at->second;
        if (id.style == style && near(name.kind, id.lat, id.lon, place.lat, place.lon)) {
            id.seen = seconds;
            return id;
        }
    }
    Id made{.id = name_id_bit | next_id_, .style = style, .lat = place.lat, .lon = place.lon, .seen = seconds};
    next_id_ = next_id_ + 1 < name_id_bit ? next_id_ + 1 : 1;
    made.text = name.text;
    return ids_.emplace(name.text, std::move(made))->second;
}

void WorldNames::pick_waters(const NameScale& scale) {
    struct Row {
        std::size_t at = 0;
        bool river = false;
        bool shown = false;
        double from = 0;
        std::int32_t priority = 0;
    };
    std::vector<Row> rows;
    for (std::size_t i = 0; i < candidates_.size(); ++i) {
        const WorldName& n = *candidates_[i].name;
        if (!is_water(n.kind)) continue;
        // named there before, and still picked
        const bool shown = std::ranges::any_of(picked_, [&n](const PickedName& p) {
            return !p.leaving && p.text == n.text && near(n.kind, p.own_lat, p.own_lon, n.lat, n.lon);
        });
        rows.push_back({.at = i,
                        .river = n.kind == NameKind::river,
                        .shown = shown,
                        .from = metres_between(n.lat, n.lon, scale.lat, scale.lon),
                        .priority = candidates_[i].priority});
    }
    if (rows.empty()) return;
    // Where it was named first; then a river's row nearest the camera's target, another water's row of the highest
    // rank (stable: a tie in the tiles' order).
    std::ranges::stable_sort(rows, [](const Row& a, const Row& b) {
        if (a.shown != b.shown) return a.shown;
        if (a.river != b.river) return a.river;
        return a.river ? a.from < b.from : a.priority > b.priority;
    });
    const double apart =
        scale.metres_a_dp > 0.0 ? water_apart_dp * scale.metres_a_dp : std::numeric_limits<double>::infinity();
    std::vector<std::uint8_t> dropped(candidates_.size(), 0);
    std::vector<std::size_t> kept;
    for (const Row& row : rows) {
        const WorldName& n = *candidates_[row.at].name;
        const bool twice = std::ranges::any_of(kept, [&](std::size_t k) {
            const WorldName& o = *candidates_[k].name;
            return (o.kind == NameKind::river) == row.river && o.text == n.text &&
                   metres_between(o.lat, o.lon, n.lat, n.lon) < apart;
        });
        if (twice) {
            dropped[row.at] = 1;
        } else {
            kept.push_back(row.at);
        }
    }
    std::size_t to = 0;
    for (std::size_t i = 0; i < candidates_.size(); ++i) {
        if (dropped[i] == 0) candidates_[to++] = candidates_[i];
    }
    candidates_.resize(to);
}

void WorldNames::pick(const WorldFrame* world, std::span<const WorldName> extra, double distance, const InView& in_view,
                      double seconds, const NameScale& scale, const Stand& stand) {
    ++picks_;
    candidates_.clear();
    const auto take = [&](const WorldName& name) {
        if (name.text.empty()) return;
        const std::optional<NameStyle> style = name_style(name.kind, distance);
        if (!style) return;
        // a water by its size on the screen, or not at all
        const std::optional<std::int32_t> priority =
            is_water(name.kind) ? water_priority(name, scale.metres_a_dp) : name_priority(name);
        if (!priority) return;
        NamePlace place{.lat = name.lat, .lon = name.lon};
        if (stand && is_water(name.kind) && name.kind != NameKind::river) {
            // where its water shows: the stand the last pick gave it is the caller's to keep or let go
            const auto before = std::ranges::find_if(picked_, [&name](const PickedName& p) {
                return !p.leaving && p.stand && p.text == name.text &&
                       near(name.kind, p.own_lat, p.own_lon, name.lat, name.lon);
            });
            const NamePlace held = before != picked_.end()
                                       ? NamePlace{.lat = before->lat, .lon = before->lon, .stand = true}
                                       : NamePlace{};
            const std::optional<NamePlace> at = stand(name, before != picked_.end() ? &held : nullptr);
            if (!at) return;
            place = *at;
        } else if (!in_view(name.lat, name.lon)) {
            return;
        }
        candidates_.push_back({.name = &name, .style = *style, .priority = *priority, .place = place});
    };
    if (world != nullptr) {
        for (const WorldDraw& draw : world->draws) {
            if (draw.tile == nullptr || draw.light_cells == 0) continue;
            for (const WorldName& name : draw.tile->names) {
                if (((draw.light_cells >> name.cell) & 1U) != 0) take(name);
            }
        }
    }
    for (const WorldName& name : extra) take(name);
    pick_waters(scale);
    // by rank (stable: a tie in the tiles' order), each place once
    std::ranges::stable_sort(candidates_,
                             [](const Candidate& a, const Candidate& b) { return a.priority > b.priority; });
    // (only the first `keep` places can be picked: the rest is let go)
    std::size_t kept = 0;
    for (const Candidate c : candidates_) {
        if (kept >= keep) break;
        const bool twice = std::ranges::any_of(std::span(candidates_).first(kept), [&c](const Candidate& before) {
            return before.style == c.style && before.name->text == c.name->text &&
                   near(c.name->kind, before.name->lat, before.name->lon, c.name->lat, c.name->lon);
        });
        if (!twice) candidates_[kept++] = c;
    }
    candidates_.resize(kept);
    for (Candidate& c : candidates_) c.id = id_of(*c.name, c.style, c.place, seconds).id;

    // The first `most` by rank, and the names picked before that still stand among the first `keep`.
    std::vector<std::uint8_t> chosen(candidates_.size(), 0);
    for (std::size_t i = 0; i < candidates_.size() && i < keep; ++i) {
        chosen[i] =
            i < most || std::ranges::any_of(
                            picked_, [&](const PickedName& p) { return !p.leaving && p.id == candidates_[i].id; })
                ? 1
                : 0;
    }
    next_.clear();
    for (std::size_t i = 0; i < candidates_.size(); ++i) {
        if (chosen[i] == 0) continue;
        const Candidate& c = candidates_[i];
        const Id& id = id_of(*c.name, c.style, c.place, seconds);
        next_.push_back({.id = c.id,
                         .style = c.style,
                         .priority = c.priority,
                         .lat = c.place.lat,
                         .lon = c.place.lon,
                         .text = id.text,
                         .lit = c.name->lit,
                         .people = c.name->people,
                         .stand = c.place.stand,
                         .own_lat = c.name->lat,
                         .own_lon = c.name->lon});
    }
    // Those no longer picked leave: handed on, leaving, for a fade's length from when they left.
    for (const PickedName& p : picked_) {
        if (std::ranges::any_of(next_, [&p](const PickedName& n) { return n.id == p.id; })) continue;
        auto at = std::ranges::find(left_, p.id, &std::pair<std::uint32_t, double>::first);
        if (at == left_.end()) at = left_.insert(left_.end(), {p.id, seconds});
        if (seconds - at->second >= leave_seconds) continue;
        PickedName gone = p;
        gone.leaving = true;
        next_.push_back(gone);
    }
    std::erase_if(left_, [&](const auto& l) {
        return std::ranges::none_of(next_, [&l](const PickedName& n) { return n.leaving && n.id == l.first; });
    });
    // The ids: beyond most_ids, those not picked for a minute are let go (never one this pick hands on).
    if (ids_.size() > most_ids) {
        std::erase_if(ids_, [&](const auto& e) {
            return seconds - e.second.seen > id_seconds &&
                   std::ranges::none_of(next_, [&e](const PickedName& n) { return n.id == e.second.id; });
        });
    }
    picked_.swap(next_);
    picked_at_ = seconds;
}

std::span<const PickedName> WorldNames::update(const WorldFrame* world, std::span<const WorldName> extra,
                                               double distance, const InView& in_view, double seconds, bool rest,
                                               bool changed, const NameScale& scale, const Stand& stand) {
    // What the frame draws of the world's names: its tiles, each with the cells it names, and the test's names.
    std::uint64_t drawn = 0xCBF2'9CE4'8422'2325ULL;
    const auto mix = [&drawn](std::uint64_t v) { drawn = (drawn ^ v) * 0x0000'0100'0000'01B3ULL; };
    if (world != nullptr) {
        for (const WorldDraw& draw : world->draws) {
            if (draw.tile == nullptr || draw.light_cells == 0 || draw.tile->names.empty()) continue;
            mix(draw.tile->key.packed());
            mix(draw.light_cells);
        }
    }
    mix(extra.size());
    mix(reinterpret_cast<std::uintptr_t>(extra.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    // While the camera moves, at most every pick_seconds. At rest once where it rests, again when the tiles that name
    // places are others (a tile came), and while names leave until their fades have ended.
    const bool due = seconds - picked_at_ >= pick_seconds;
    const bool leaving = std::ranges::any_of(picked_, &PickedName::leaving);
    bool now = false;
    if (rest) {
        now = !rested_ || changed || drawn != drawn_ || (leaving && due);
        rested_ = true;
    } else {
        rested_ = false;
        now = due;
    }
    if (now) {
        pick(world, extra, distance, in_view, seconds, scale, stand);
        drawn_ = drawn;
    }
    return picked_;
}

std::optional<view::Vec2> name_point(const WorldBend& bend, const view::GroundFrame& frame, const view::View& view,
                                     double lat, double lon, double least_facing) noexcept {
    const double shift = wrap_shift(bend, lon);
    const view::Vec3 p = world_point(bend, frame, lat, lon, shift);
    if (bend.drop > 0.0) {
        // the bent ground's normal at the place, as world.slang's: the dropped ground's gradient, blended to the
        // sphere's by w₂
        const double dx = frame.x(lat, lon + shift) - bend.target.x;
        const double dz = frame.z(lat, lon + shift) - bend.target.z;
        view::Vec3 n{2.0 * bend.drop * dx, 1.0, 2.0 * bend.drop * dz};
        if (bend.sphere > 0.0) {
            const view::Vec3 o = sphere_offset(bend, lat, lon);
            const view::Vec3 s{o.x / view::earth_radius, (o.y + view::earth_radius) / view::earth_radius,
                               o.z / view::earth_radius};
            const double w = std::min(bend.sphere, 1.0);
            n = {n.x + ((s.x - n.x) * w), n.y + ((s.y - n.y) * w), n.z + ((s.z - n.z) * w)};
        }
        const view::Vec3 to_eye{bend.eye.x - p.x, bend.eye.y - p.y, bend.eye.z - p.z};
        const double facing = (n.x * to_eye.x) + (n.y * to_eye.y) + (n.z * to_eye.z);
        const double lengths = pymath::sqrt((n.x * n.x) + (n.y * n.y) + (n.z * n.z)) *
                               pymath::sqrt((to_eye.x * to_eye.x) + (to_eye.y * to_eye.y) + (to_eye.z * to_eye.z));
        if (!(facing > least_facing * lengths)) return std::nullopt;
    }
    const std::optional<view::Vec2> s = view.camera().proj(p);
    if (!s) return std::nullopt;
    return view.device_point(*s);
}

} // namespace atlas::scene
