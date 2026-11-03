// The ground's pick (passes/GroundPick.cpp; Colour the map's taps on the ground): the slot a drawn colour goes with,
// as the palette's table moves it; and on the host's Vulkan, at the Westerkerk home in the app's map over the bundled
// render packs (ATLAS_TILES_DIR), the slot a tap names is the one whose colour the frame drew there. Each slot of the
// ground is repainted in turn (in the palette, and in the theme where mapview's PaletteTheme carries it), and at the
// samples inside one element in every frame the slot the tap names changes the pixel, and the slot that changes it
// most is the one the tap names (or changes it as much).
#if !defined(__ANDROID__)

#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Ground.hpp"
#include "scene/World.hpp"
#include "style/CameraState.hpp"
#include "style/Palette.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Style.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

namespace slot = style::palette_slot;

/// The largest of the channels' differences between pixel `i` of `a` and pixel `j` of `b`.
[[nodiscard]] int gap(const gpu::Pixels& a, std::size_t i, const gpu::Pixels& b, std::size_t j) {
    int d = 0;
    for (std::size_t c = 0; c < 3; ++c) {
        d = std::max(d, std::abs(int{a.rgba.at((i * 4) + c)} - int{b.rgba.at((j * 4) + c)}));
    }
    return d;
}

/// Whether the 3 × 3 pixels about (x, y) are all within 6 levels of it: inside one element, off its edges.
[[nodiscard]] bool uniform(const gpu::Pixels& p, std::uint32_t x, std::uint32_t y) {
    const std::size_t at = (std::size_t{y} * p.width) + x;
    for (std::uint32_t dy = 0; dy < 3; ++dy) {
        for (std::uint32_t dx = 0; dx < 3; ++dx) {
            if (gap(p, at, p, (std::size_t{y + dy - 1} * p.width) + (x + dx - 1)) > 6) return false;
        }
    }
    return true;
}

/// A slot the test repaints, and the theme's colour PaletteTheme moves with it (none: the palette's slot alone).
struct Repaint {
    std::size_t slot = 0;
    style::Rgb8 style::ThemeBlock::* theme = nullptr;
};

} // namespace

TEST_CASE("ground pick: a drawn colour names the slot whose move the palette's table makes of it") {
    namespace v = test::values;
    using geometry::ThemeColour;
    auto tables = test::style_tables();
    const auto set = [&tables](std::string_view name, v::ValueBytes value) {
        for (auto& [table, bytes] : tables) {
            if (table == name) {
                bytes = std::move(value);
                return;
            }
        }
    };
    set("land", v::map({{"meadow", v::list({v::text("#B8CC94"), v::integer(2)})},
                        {"residential", v::list({v::text("#DDD6C8"), v::integer(1)})},
                        {"sand", v::list({v::text("#E8DCB0"), v::integer(3)})}}));
    // a parking area's colour and a concrete surface's the same
    set("area", v::map({{"park", v::text("#A9C38A")}, {"parking", v::text("#C4C0B8")}}));
    set("surf", v::map({{"concrete", v::text("#C4C0B8")}, {"asphalt", v::text("#9B9A99")}}));
    auto read = tiles::read_style(test::style_bytes(tables));
    ATLAS_REQUIRE(read.has_value());
    auto typed = tiles::Style::of(*read);
    ATLAS_REQUIRE(typed.has_value());
    const tiles::Style& s = *typed;
    const auto literal = [](tiles::StyleRgb c) {
        return geometry::Paint{.day = geometry::rgba(c), .night = geometry::darken(geometry::rgba(c), 0.2)};
    };
    const auto theme = [](ThemeColour c) {
        return geometry::Paint{.day = geometry::theme_colour(c), .night = geometry::theme_colour(c)};
    };
    // the theme's colours as PaletteTheme moves them: the bare ground the paper's (the lawns' with grass on bare
    // ground), a street without a mapped surface the street's, the flat water the water's; at night the night's
    for (const tiles::Style* style : {&s, static_cast<const tiles::Style*>(nullptr)}) {
        CHECK(passes::ground_slot_of(theme(ThemeColour::ground), style, nullptr, false, false) == slot::paper);
        CHECK(passes::ground_slot_of(theme(ThemeColour::ground), style, nullptr, false, true) == slot::lawn);
        CHECK(passes::ground_slot_of(theme(ThemeColour::ground), style, nullptr, true, false) == slot::night_ground);
        CHECK(passes::ground_slot_of(theme(ThemeColour::ground), style, nullptr, true, true) == slot::night_ground);
        CHECK(passes::ground_slot_of(theme(ThemeColour::road), style, nullptr, false, false) == slot::street);
        CHECK(passes::ground_slot_of(theme(ThemeColour::road), style, nullptr, true, false) == slot::street);
        CHECK(passes::ground_slot_of(theme(ThemeColour::water), style, nullptr, false, false) == slot::water);
        CHECK(passes::ground_slot_of(theme(ThemeColour::water), style, nullptr, true, false) == slot::night_water);
    }
    // the style's, each with the slot it goes with (geometry::ground_slots), by day and at night alike: a meadow and
    // a park the lawns', the built land the paper's, the other areas the squares', the mapped asphalt and the big
    // roads' the asphalt's; sand and a colour the style lacks none
    for (const bool night : {false, true}) {
        CAPTURE(night);
        CHECK(passes::ground_slot_of(literal(0xB8CC94U), &s, nullptr, night, false) == slot::lawn);
        CHECK(passes::ground_slot_of(literal(0xDDD6C8U), &s, nullptr, night, false) == slot::paper);
        CHECK(passes::ground_slot_of(literal(0xA9C38AU), &s, nullptr, night, true) == slot::lawn);
        CHECK(passes::ground_slot_of(literal(s.area_other), &s, nullptr, night, false) == slot::squares);
        CHECK(passes::ground_slot_of(literal(0x9B9A99U), &s, nullptr, night, false) == slot::asphalt);
        CHECK(passes::ground_slot_of(literal(s.asphalt.day), &s, nullptr, night, false) == slot::asphalt);
        CHECK(passes::ground_slot_of(literal(0xE8DCB0U), &s, nullptr, night, false) == 0);
        CHECK(passes::ground_slot_of(literal(0x123456U), &s, nullptr, night, false) == 0);
    }
    CHECK(passes::ground_slot_of(literal(0xB8CC94U), nullptr, nullptr, false, false) == 0);
    // two slots of one colour: the first (the areas' before the surfaces'), unless the palette moves only a later one,
    // as ground_palette's table moves the colour by the first slot that changes it; a slot set to its own colour
    // changes nothing
    const style::PaletteBlock today{};
    style::PaletteBlock concrete{};
    concrete.colours.at(slot::concrete_paving) = 0x01405060U;
    style::PaletteBlock parking{};
    parking.colours.at(slot::parking) = 0x01604050U;
    style::PaletteBlock both = concrete;
    both.colours.at(slot::parking) = 0x01604050U;
    style::PaletteBlock own{};
    own.colours.at(slot::concrete_paving) = 0x01C4C0B8U;
    const geometry::Paint shared = literal(0xC4C0B8U);
    CHECK(passes::ground_slot_of(shared, &s, nullptr, false, false) == slot::parking);
    CHECK(passes::ground_slot_of(shared, &s, &today, false, false) == slot::parking);
    CHECK(passes::ground_slot_of(shared, &s, &concrete, false, false) == slot::concrete_paving);
    CHECK(passes::ground_slot_of(shared, &s, &concrete, true, false) == slot::concrete_paving);
    CHECK(passes::ground_slot_of(shared, &s, &parking, false, false) == slot::parking);
    CHECK(passes::ground_slot_of(shared, &s, &both, false, false) == slot::parking);
    CHECK(passes::ground_slot_of(shared, &s, &own, false, false) == slot::parking);
    // the big roads' asphalt moves with the asphalt only where the style maps an asphalt of its own (ground_palette
    // leaves it otherwise): without one, no slot paints it
    tiles::Style bare = s;
    std::erase_if(bare.surfaces, [](const auto& p) { return p.first == "asphalt"; });
    style::PaletteBlock asphalt{};
    asphalt.colours.at(slot::asphalt) = 0x01303030U;
    CHECK(passes::ground_slot_of(literal(s.asphalt.day), &bare, nullptr, false, false) == 0);
    CHECK(passes::ground_slot_of(literal(s.asphalt.day), &bare, &asphalt, false, false) == 0);
    CHECK(passes::ground_slot_of(literal(s.asphalt.day), &s, &asphalt, false, false) == slot::asphalt);
}

TEST_CASE("ground pick: a tap at the Westerkerk home names the slot whose colour the app's map drew there") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ground's pick is not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value());
    // the ground's own passes, as the ground's tests draw them (tests/ground/ground_draw.hpp), in the app's map
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* ground = test::ground_of(passes);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0, .grounded = true});
    ATLAS_REQUIRE(builder.has_value());
    double now = 0;
    scene::WorldOptions world_options{.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }};
    auto world = scene::World::create(device, (*builder)->passes(), world_options);
    ATLAS_REQUIRE(world.has_value());
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (!std::filesystem::exists(path)) continue;
        ATLAS_REQUIRE(
            (*world)->store().add({.id = name, .kind = tiles::PackKind::bundled, .path = path.string()}).has_value());
    }
    (*world)->set_active("amsterdam");
    // the home in the app's map, without what lies on the fills (their lines, the setts' courses, the names, the
    // shadows, the water's and the night's light, the composite's weather) and without the canopy, which this frame
    // draws black at .55 where the app's draws it in the user's woods (Ground::canopy_filtered)
    namespace part = style::camera_part;
    style::CameraState camera = test::home_camera();
    camera.flags &= ~style::camera_flag::engine_mode;
    camera.parts = part::ground_lines | part::setts | part::ground_text | part::building_shadows | part::park_shadows |
                   part::cloud_shadows | part::water_light | part::lit_streets | part::wet_cores | part::lamp_pools |
                   part::lamp_halos | part::lamp_streaks | part::rain | part::golden_wash | part::canopy |
                   part::frosted;
    style::ThemeBlock theme = test::sunny();
    style::PaletteBlock palette{};
    const auto inputs = [&] {
        (*builder)->set_inputs({.camera = camera,
                                .theme = theme,
                                .seconds = 0,
                                .mode = frame::Mode::structural,
                                .scene = world->get(),
                                .palette = &palette});
    };
    inputs();
    const vk::Extent2D extent{.width = camera.width_px, .height = camera.height_px};
    auto output = gpu::Offscreen::create(device, extent, 2);
    ATLAS_REQUIRE(output.has_value());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    int drawn = 0;
    const auto draw = [&](int frames) -> std::optional<gpu::Pixels> {
        for (int k = 0; k < frames; ++k) {
            now = static_cast<double>(drawn++) * 0.05;
            auto report = (*renderer)->draw(0);
            if (!report) {
                MESSAGE("draw: " << report.error().text());
                return std::nullopt;
            }
            (*world)->residency().wait_idle();
        }
        (*renderer)->drain();
        auto packed = (*output)->read_packed((*output)->latest());
        if (!packed) return std::nullopt;
        return gpu::to_rgba8(*packed);
    };
    // every tile the cover wants built and faded in
    const std::optional<gpu::Pixels> today = draw(40);
    ATLAS_REQUIRE(today.has_value(), "the home was not drawn");
    const std::uint32_t w = today->width;
    const std::uint32_t h = today->height;

    // the samples: a grid of 12 px, a tap at each pixel's centre, and what the tap names with today's palette
    struct Sample {
        std::uint32_t x = 0;
        std::uint32_t y = 0;
        std::optional<scene::PickedGround> picked;
        bool counted = true;            ///< inside one element in every frame
        std::vector<int> change;        ///< by each repaint, its largest channel's
        std::vector<std::size_t> named; ///< the slots a tap names here, each under its own repaint
    };
    std::vector<Sample> samples;
    samples.reserve(std::size_t{w / 12 + 1} * (h / 12 + 1));
    const auto started = std::chrono::steady_clock::now();
    for (std::uint32_t y = 6; y + 1 < h; y += 12) {
        for (std::uint32_t x = 6; x + 1 < w; x += 12) {
            Sample s{.x = x, .y = y};
            s.picked = ground->pick((*builder)->state(), x + 0.5, y + 0.5);
            s.counted = uniform(*today, x, y);
            if (s.picked) s.named.push_back(s.picked->slot);
            samples.push_back(std::move(s));
        }
    }
    const double pick_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count() /
        static_cast<double>(samples.size());

    // each slot of the ground repainted in turn, magenta (far from every colour of the map): in the palette, and in
    // the theme where PaletteTheme moves the theme's colour by it (the live day's holds each such slot's colour of
    // today, so it takes the user's exactly); each drawn at rest, as the user repaints a slot, so what the frame keeps
    // at rest takes it too (the quays' faces)
    const std::array<Repaint, 19> repaints{{
        {.slot = slot::paper, .theme = &style::ThemeBlock::ground},
        {.slot = slot::lawn},
        {.slot = slot::woods},
        {.slot = slot::squares},
        {.slot = slot::parking},
        {.slot = slot::playground},
        {.slot = slot::memorial},
        {.slot = slot::bridge_deck},
        {.slot = slot::water, .theme = &style::ThemeBlock::water},
        {.slot = slot::water_sky, .theme = &style::ThemeBlock::water_top},
        {.slot = slot::water_deep, .theme = &style::ThemeBlock::water_bottom},
        {.slot = slot::quay_walls},
        {.slot = slot::street, .theme = &style::ThemeBlock::road},
        {.slot = slot::asphalt},
        {.slot = slot::paving_stones},
        {.slot = slot::setts},
        {.slot = slot::concrete_paving},
        {.slot = slot::brick_paving},
        {.slot = slot::boardwalk},
    }};
    constexpr int changed = 24; // levels: a repaint changed the pixel
    const style::ThemeBlock sunny = theme;
    for (const Repaint& r : repaints) {
        CAPTURE(r.slot);
        palette = {};
        palette.colours.at(r.slot) = 0x01FF00FFU;
        theme = sunny;
        if (r.theme != nullptr) theme.*r.theme = {.r = 0xFF, .g = 0x00, .b = 0xFF, .a = 255};
        inputs();
        const std::optional<gpu::Pixels> b = draw(1);
        ATLAS_REQUIRE(b.has_value());
        for (Sample& s : samples) {
            const std::size_t at = (std::size_t{s.y} * w) + s.x;
            const int d = gap(*today, at, *b, at);
            s.change.push_back(d);
            s.counted = s.counted && uniform(*b, s.x, s.y);
            // a slot sharing its colour with another the tap named: named under its own repaint
            if (d > changed && s.picked && s.picked->slot != r.slot) {
                const std::optional<scene::PickedGround> p = ground->pick((*builder)->state(), s.x + 0.5, s.y + 0.5);
                if (p && p->slot == r.slot) s.named.push_back(r.slot);
            }
        }
    }
    // today's again, pixel for pixel
    palette = {};
    theme = sunny;
    inputs();
    const std::optional<gpu::Pixels> again = draw(1);
    ATLAS_REQUIRE(again.has_value());
    CHECK(*again == *today);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);

    // each counted sample: the slot the tap names changes the pixel (none where the tap names a slot no repaint
    // reaches, or nothing), and the repaint that changes it most is of a slot the tap names, or the tap's changes it
    // as much (the water's gradient about its middle row)
    std::map<std::size_t, int> named_by;
    std::map<std::size_t, int> agree_by;
    int counted = 0;
    int agree = 0;
    std::vector<std::string> told;
    gpu::Pixels marked = *today;
    for (const Sample& s : samples) {
        if (!s.counted) continue;
        const auto most = std::ranges::max_element(s.change);
        const auto k = static_cast<std::size_t>(most - s.change.begin());
        const auto of = [&](std::size_t code) -> std::optional<int> {
            for (std::size_t i = 0; i < repaints.size(); ++i) {
                if (repaints.at(i).slot == code) return s.change.at(i);
            }
            return std::nullopt;
        };
        const std::optional<int> mine = s.picked ? of(s.picked->slot) : std::nullopt;
        if (!s.picked && *most <= changed) continue; // the sky
        ++counted;
        if (s.picked) ++named_by[s.picked->slot];
        const bool reaches = mine ? *mine > changed : *most <= changed;
        const bool largest = *most <= changed || std::ranges::find(s.named, repaints.at(k).slot) != s.named.end() ||
                             (mine && *mine + changed >= *most);
        if (reaches && largest) {
            ++agree;
            if (s.picked) ++agree_by[s.picked->slot];
            continue;
        }
        const std::size_t at = ((std::size_t{s.y} * w) + s.x) * 4;
        marked.rgba.at(at) = 255;
        marked.rgba.at(at + 1) = 0;
        marked.rgba.at(at + 2) = 0;
        if (told.size() < 12) {
            told.push_back(std::format("({}, {}) named {} ({}), changed most by {} ({})", s.x, s.y,
                                       s.picked ? static_cast<int>(s.picked->slot) : -1, mine.value_or(-1),
                                       repaints.at(k).slot, *most));
        }
    }
    const auto scratch = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground_pick";
    test::write_ppm(*today, scratch / "home.ppm");
    test::write_ppm(marked, scratch / "home-disagree.ppm");
    std::string names;
    for (const auto& [code, n] : named_by) names += std::format(" {}:{}", code, n);
    MESSAGE(std::format("{} of {} samples agree; a tap's pick {:.3f} ms; named:{}", agree, counted, pick_ms, names));
    for (const std::string& t : told) MESSAGE(t);
    CHECK(counted > 5000);
    CHECK(agree >= counted * 98 / 100);
    // and each slot the taps name, of its own samples (a slot whose faces kept their colours at rest would agree at
    // none)
    for (const auto& [code, n] : named_by) {
        CAPTURE(code);
        CHECK(agree_by[code] * 100 >= n * 98);
    }
    // the home's streets, bare ground, paving, big roads, water (both ends of its gradient) and quays among them
    for (const std::size_t code :
         {slot::street, slot::paper, slot::paving_stones, slot::asphalt, slot::water_sky, slot::water_deep}) {
        CAPTURE(code);
        CHECK(named_by[code] > 50);
    }
    CHECK(named_by[slot::quay_walls] > 0);
    renderer->reset();
}

#endif
