// A render pack's head and style as the packer writes them (load/PackHead.hpp).
#include "load/PackHead.hpp"

#include "load/Amsterdam.hpp"
#include "load/Countries.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "tiles/Json.hpp"
#include "tiles/Schema.hpp"
#include "tiles/SectionWriter.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <format>
#include <optional>
#include <span>
#include <utility>

namespace atlas::load {

namespace {

namespace tl = atlas::tiles;

/// regions.py's GENERALISE (metres: footprint detail below this is not visible at the zoom shown), in its order.
constexpr std::array<std::pair<std::string_view, double>, 14> generalised = {{{"grachten", 0.8},
                                                                              {"grachten_home", 0.7},
                                                                              {"grachten_eat", 0.6},
                                                                              {"paris", 0.9},
                                                                              {"schiphol", 0.6},
                                                                              {"port", 0.6},
                                                                              {"dunes", 0.5},
                                                                              {"paris_close", 0.3},
                                                                              {"schiphol_close", 0.4},
                                                                              {"port_close", 0.4},
                                                                              {"paris_walk", 0.0},
                                                                              {"nottinghill_close", 0.15},
                                                                              {"sangimignano_close", 0.2},
                                                                              {"bergen_close", 0.2}}};

constexpr std::array<tl::HeadBand, 5> bands = {{{.tolerance = 0, .up_to = 250.0},
                                                {.tolerance = 1, .up_to = 400.0},
                                                {.tolerance = 3, .up_to = 700.0},
                                                {.tolerance = 6, .up_to = 2000.0},
                                                {.tolerance = 8, .up_to = std::nullopt}}};

/// The living map's one band: it is always loaded whole.
constexpr std::array<tl::HeadBand, 1> whole = {{{.tolerance = 0, .up_to = std::nullopt}}};

[[nodiscard]] std::optional<double> generalised_at(std::string_view view) noexcept {
    for (const auto& [name, tolerance] : generalised) {
        if (name == view) return tolerance;
    }
    return std::nullopt;
}

/// `s` with every `part` taken out, left to right (Python's and Kotlin's replace(part, '')).
[[nodiscard]] std::string without(std::string_view s, std::string_view part) {
    std::string out;
    out.reserve(s.size());
    std::size_t at = 0;
    while (at < s.size()) {
        if (s.substr(at).starts_with(part)) {
            at += part.size();
        } else {
            out += s[at++];
        }
    }
    return out;
}

/// The living map's camera (amsterdam.py's main, ViewLadder.livingView), read once; null if the literal does not read
/// (pack_head_test holds that it does).
[[nodiscard]] const std::optional<tl::JsonValue>& living_camera() {
    static const std::optional<tl::JsonValue> camera = []() -> std::optional<tl::JsonValue> {
        constexpr std::string_view json = R"({"W": 412, "H": 915, "target": [-10, 0, -30], "yaw": 52, "pitch": 38, )"
                                          R"("dist": 430, "fov": 36, "cy": 440, "radius": 380})";
        auto read = tl::read_json(std::as_bytes(std::span(json.data(), json.size())));
        if (!read) return std::nullopt;
        return std::move(*read);
    }();
    return camera;
}

/// Kotlin's Double.compareTo between distances: -0.0 before 0.0 (JSON has no NaN).
[[nodiscard]] bool nearer(double a, double b) noexcept {
    if (a != b) return a < b;
    return std::signbit(a) && !std::signbit(b);
}

/// A view's number (Knot's num()), or nothing.
[[nodiscard]] std::optional<double> number_of(const tl::Value& camera, std::string_view key) {
    const std::optional<tl::Value> v = camera.find(key);
    return v ? v->number() : std::nullopt;
}

/// Whether a view's camera holds what ViewLadder's knots read: a target of three numbers, a yaw, a pitch and a
/// distance.
[[nodiscard]] bool framed(const tl::Value& camera) {
    if (camera.type() != tl::ValueType::map) return false;
    const std::optional<tl::Value> target = camera.find("target");
    if (!target || target->type() != tl::ValueType::list || target->size() != 3) return false;
    for (std::size_t k = 0; k < 3; ++k) {
        if (!target->child(k).number()) return false;
    }
    return number_of(camera, "yaw") && number_of(camera, "pitch") && number_of(camera, "dist");
}

} // namespace

double generalise(std::string_view region, std::string_view view) {
    if (const auto own = generalised_at(view)) return *own;
    if (const auto plain = generalised_at(without(without(view, "_detail"), "_kit"))) return *plain;
    if (const auto whole_region = generalised_at(region)) return *whole_region;
    return 0.0;
}

std::span<const tl::HeadBand> region_bands() noexcept { return bands; }

std::expected<std::vector<PackView>, std::string> pack_views(const PackFacts& facts) {
    std::vector<PackView> views;
    if (facts.profile == tl::Profile::living) {
        const auto& camera = living_camera();
        if (!camera) return std::unexpected(std::string("the living map's camera does not read"));
        views.push_back({.name = std::string(facts.region), .camera = camera->root(), .tolerance = 0, .dist = 430});
        return views;
    }
    const std::optional<tl::Value> all = facts.r.find("views");
    if (!all || all->type() != tl::ValueType::map || all->size() == 0) {
        return std::unexpected(std::format("region {} has no views", facts.region));
    }
    for (std::size_t i = 0; i < all->size(); ++i) {
        const std::string_view key = all->key(i);
        const tl::Value camera = all->child(i);
        std::string name = key.empty() ? std::string(facts.region) : std::format("{}_{}", facts.region, key);
        const std::optional<double> dist = number_of(camera, "dist");
        if (!framed(camera) || !dist) {
            return std::unexpected(std::format("{}'s view {} has no target of three numbers, or no yaw, pitch or "
                                               "distance",
                                               facts.region, name));
        }
        const double tolerance = generalise(facts.region, name);
        const int level = tl::keep::level_of(tolerance);
        if (level < 0) {
            return std::unexpected(std::format("{}'s view {} loads at {} m, which is not on the keep ladder",
                                               facts.region, name, tolerance));
        }
        views.push_back(
            {.name = std::move(name), .camera = camera, .tolerance = static_cast<std::uint8_t>(level), .dist = *dist});
    }
    std::ranges::stable_sort(views, [](const PackView& a, const PackView& b) { return nearer(a.dist, b.dist); });
    return views;
}

std::vector<std::uint8_t> variant_ladder(std::span<const PackView> views) {
    std::vector<std::uint8_t> ladder;
    for (const PackView& v : views) ladder.push_back(v.tolerance);
    for (const tl::HeadBand& b : bands) ladder.push_back(b.tolerance);
    std::ranges::sort(ladder);
    const auto [first, last] = std::ranges::unique(ladder);
    ladder.erase(first, last);
    std::erase(ladder, std::uint8_t{0});
    return ladder;
}

std::expected<PackSections, std::string> pack_sections(const PackFacts& facts, std::span<const PackView> views) {
    const bool living = facts.profile == tl::Profile::living;
    const std::string version = style_version();
    if (version.empty()) return std::unexpected(std::string("the engine's style file does not read"));
    std::vector<tl::HeadViewOut> out;
    out.reserve(views.size());
    for (const PackView& v : views) out.push_back({.name = v.name, .camera = v.camera, .tolerance = v.tolerance});
    auto head = tl::encode_head({.profile = facts.profile,
                                 .levels = facts.levels,
                                 .frame = facts.frame,
                                 .region = facts.region,
                                 .engine_version = facts.engine_version,
                                 .style_version = version,
                                 .source = facts.source,
                                 .views = out,
                                 .bands = living ? std::span<const tl::HeadBand>(whole) : region_bands()});
    if (!head) return std::unexpected("the head does not encode: " + tl::describe(head.error()));
    std::optional<std::vector<std::byte>> style;
    if (living) {
        style = style_section({.palette = amsterdam_palette(), .state = {}});
    } else {
        // regions.py's configure: TZ_H from R's time zone (Amsterdam's where it names none); S['tradition'] is R's
        const std::optional<tl::Value> tz = facts.r.find("tz");
        const std::string_view zone = tz && !tz->is_null() ? tz->string().value_or("") : "Europe/Amsterdam";
        const std::optional<std::int32_t> tz_h = zone_hours(zone);
        if (!tz_h)
            return std::unexpected(std::format("the core has no UTC offset for {}'s time zone {}", facts.region, zone));
        const std::optional<tl::Value> named = facts.r.find("tradition");
        const std::optional<std::string_view> tradition =
            named && !named->is_null() ? named->string() : std::optional<std::string_view>();
        const std::string view = std::format("{}_whole", facts.region);
        const SceneScalars scalars{.region = facts.region, .view = view, .tradition = tradition, .r = facts.r};
        style = style_section({.palette = region_palette(facts.region),
                               .state = region_state(facts.region, *tz_h),
                               .scene = &scalars,
                               .tradition = facts.tradition});
    }
    if (!style) return std::unexpected(std::string("the engine's style file does not read"));
    return PackSections{.head = std::move(*head), .style = std::move(*style)};
}

} // namespace atlas::load
