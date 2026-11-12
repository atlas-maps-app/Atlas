// The world's names (scene/WorldNames.hpp).
#include "scene/WorldNames.hpp"

#include "base/pymath/PyMath.hpp"
#include "scene/WorldTiles.hpp"

#include <algorithm>
#include <cmath>

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

} // namespace

std::optional<NameKind> name_kind(std::string_view place) noexcept {
    if (place == "city") return NameKind::city;
    if (place == "town") return NameKind::town;
    if (place == "village") return NameKind::village;
    if (place == "suburb") return NameKind::suburb;
    if (place == "country") return NameKind::country;
    return std::nullopt;
}

std::optional<NameStyle> name_style(NameKind kind, double distance) noexcept {
    if (!(distance <= names_none_beyond)) return std::nullopt;
    if (distance > names_globe_from) return NameStyle::globe;
    switch (kind) {
    case NameKind::country:
    case NameKind::city: return NameStyle::city;
    case NameKind::town: return NameStyle::town;
    case NameKind::village:
    case NameKind::suburb: return NameStyle::village;
    }
    return NameStyle::city;
}

std::int32_t name_priority(const WorldName& name) noexcept {
    // a class a kind (a capital a class of its own over the cities), 2^24 apart; within it by its people
    constexpr std::int32_t step = 1 << 24;
    std::int32_t rank = 0;
    switch (name.kind) {
    case NameKind::country: rank = 0; break;
    case NameKind::city: rank = name.capital ? 1 : 2; break;
    case NameKind::town: rank = 3; break;
    case NameKind::village: rank = 4; break;
    case NameKind::suburb: rank = 5; break;
    }
    const double people = std::clamp(name.people, 0.0, static_cast<double>(step - 1));
    return -1 - (rank * step) - (step - 1 - static_cast<std::int32_t>(people));
}

void WorldNames::reset() noexcept {
    ids_.clear();
    candidates_.clear();
    picked_.clear();
    next_.clear();
    picked_at_ = -1e9;
    rested_ = false;
}

WorldNames::Id& WorldNames::id_of(const WorldName& name, NameStyle style, double seconds) {
    const auto [first, last] = ids_.equal_range(name.text);
    for (auto at = first; at != last; ++at) {
        Id& id = at->second;
        if (id.style == style && near(name.kind, id.lat, id.lon, name.lat, name.lon)) {
            id.seen = seconds;
            return id;
        }
    }
    Id made{.id = name_id_bit | next_id_, .style = style, .lat = name.lat, .lon = name.lon, .seen = seconds};
    next_id_ = next_id_ + 1 < name_id_bit ? next_id_ + 1 : 1;
    made.text = name.text;
    return ids_.emplace(name.text, std::move(made))->second;
}

void WorldNames::pick(const WorldFrame* world, std::span<const WorldName> extra, double distance, const InView& in_view,
                      double seconds) {
    ++picks_;
    candidates_.clear();
    const auto take = [&](const WorldName& name) {
        if (name.text.empty()) return;
        const std::optional<NameStyle> style = name_style(name.kind, distance);
        if (!style || !in_view(name.lat, name.lon)) return;
        candidates_.push_back({.name = &name, .style = *style, .priority = name_priority(name)});
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
    for (Candidate& c : candidates_) c.id = id_of(*c.name, c.style, seconds).id;

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
        const Id& id = id_of(*c.name, c.style, seconds);
        next_.push_back({.id = c.id,
                         .style = c.style,
                         .priority = c.priority,
                         .lat = c.name->lat,
                         .lon = c.name->lon,
                         .text = id.text,
                         .lit = c.name->lit,
                         .people = c.name->people});
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
                                               bool changed) {
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
        pick(world, extra, distance, in_view, seconds);
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
