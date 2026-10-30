// Ground text and canal names (plan-vector D13, K13): the port's printing; ground_text's map and its refusals; the
// renders' canal rule (the first way of each name in source order within the radius, a degenerate map passed over to
// the next way, nothing at night or from low); a placed name kept on its way while moving, fading on a change; the
// readability flip with its hysteresis; runway designators; a run laid out as glyph instances. With ATLAS_TILES_DIR:
// the bundled packs' candidates at their boards' views, and a pan across the canal ring on which no name changes its
// way more than once in 5 s. The port's own SVG holds the rule at both home views in :mapcorecheck's TextParityTest.
#include "base/pymath/PyMath.hpp"
#include "passes/GroundText.hpp"
#include "tests/base/require.hpp"
#include "tests/text/text_support.hpp"
#include "tests/tiles/support.hpp"
#include "text/CanalNames.hpp"
#include "text/GroundText.hpp"
#include "tiles/Chunks.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace {

using namespace atlas;
using text::CanalCandidate;
using text::CanalView;

/// A camera over the region's origin: looking north from the south at `pitch`, yaw `yaw`, 400 m away, on a frame of
/// 412 × 915 CSS px.
view::Camera camera(double yaw = 0.0, double pitch = 50.0, view::Vec3 target = {0, 0, 0}, double dist = 400.0) {
    return view::Camera({.target = target, .yaw = yaw, .pitch = pitch, .dist = dist, .width = 412, .height = 915});
}

CanalCandidate candidate(std::uint32_t index, std::string name, double x, double z, double dx, double dz,
                         std::uint32_t count = 5) {
    return {.index = index,
            .name = std::move(name),
            .mid_x = x,
            .mid_z = z,
            .prev_x = x - dx,
            .prev_z = z - dz,
            .count = count,
            .ground_key = 100 + index};
}

CanalView view_of(const view::Camera& cam, bool rest = true, double seconds = 0.0) {
    return {.camera = &cam,
            .target_x = cam.spec().target.x,
            .target_z = cam.spec().target.z,
            .radius = 600,
            .pitch = cam.spec().pitch,
            .night = false,
            .weather = true,
            .water_level = -1.5,
            // the port's look of the canal names (a pack's comes from its style)
            .look = {.fill = 0xEAF2FA, .opacity = 0.85, .weight = 700, .size = 3.2, .spacing = 0.3},
            .rest = rest,
            .seconds = seconds};
}

/// Every canal candidate of a bundled pack's finest level, by source order (the pack-level stage T's reading).
std::vector<CanalCandidate> pack_candidates(const tiles::Pack& pack) {
    auto canals = text::pack_canals(pack);
    return canals ? std::move(canals->candidates) : std::vector<CanalCandidate>{};
}

/// A pack view's camera as regions.py's cfg_for frames it: 412 × 915 CSS px.
view::CameraSpec spec_of(const tiles::Value& camera) {
    view::CameraSpec s{.width = 412, .height = 915};
    if (const auto target = camera.find("target"); target && target->size() == 3) {
        s.target = {target->child(0).number().value_or(0), target->child(1).number().value_or(0),
                    target->child(2).number().value_or(0)};
    }
    auto number = [&](std::string_view key) -> std::optional<double> {
        if (const auto v = camera.find(key)) return v->number();
        return std::nullopt;
    };
    s.yaw = number("yaw").value_or(0);
    s.pitch = number("pitch").value_or(90);
    s.dist = number("dist").value_or(400);
    s.fov = number("fov").value_or(view::field_of_view);
    s.cx = number("cx");
    s.cy = number("cy");
    return s;
}

} // namespace

TEST_CASE("printed values are Python's: correctly rounded, ties to even on the binary value, the sign of zero kept") {
    CHECK(text::printed(0.125, 2) == "0.12");
    CHECK(text::printed(0.375, 2) == "0.38");
    CHECK(text::printed(2.5, 0) == "2");
    CHECK(text::printed(-0.0001, 3) == "-0.000");
    CHECK(text::printed(-0.0, 1) == "-0.0");
    CHECK(text::printed(1234.56789, 1) == "1234.6");
    CHECK(text::printed(0.1 + 0.2, 3) == "0.300");
    CHECK(text::printed_float(3.2, 2) == 3.2F);
    CHECK(text::printed_float(-1.23456, 3) == -1.235F);
}

TEST_CASE("ground_text maps the plane at its anchor and refuses what the port refuses") {
    const view::Camera cam = camera();
    const auto run =
        text::ground_text(cam, "SINGEL", {0, -1.45, 0}, {1, 0, 0}, {0, 0, 1}, 3.2, 0xEAF2FA, 0.85, 700, 0.3);
    ATLAS_REQUIRE(run.has_value());
    const auto s0 = cam.proj({0, -1.45, 0});
    const auto sx = cam.proj({1, -1.45, 0});
    const auto sy = cam.proj({0, -1.45, 1});
    ATLAS_REQUIRE(s0.has_value() && sx.has_value() && sy.has_value());
    CHECK(run->matrix == std::array{sx->x - s0->x, sx->y - s0->y, sy->x - s0->x, sy->y - s0->y, s0->x, s0->y});
    CHECK(run->svg().starts_with("<text transform=\"matrix("));
    CHECK(run->svg().contains("font-family=\"Geist, Helvetica, sans-serif\" font-size=\"3.20\" font-weight=\"700\" "
                              "letter-spacing=\"0.30\" text-anchor=\"middle\" fill=\"#EAF2FA\" opacity=\"0.85\">SINGEL"
                              "</text>"));
    // Behind the eye, or a map that nearly degenerates (the plane seen edge on), draws nothing.
    CHECK(!text::ground_text(cam, "X", {0, 0, 1000}, {1, 0, 0}, {0, 0, 1}, 3.2, 0, 1).has_value());
    const view::Camera level = camera(0.0, 0.0001);
    CHECK(!text::ground_text(level, "X", {0, 0, 0}, {1, 0, 0}, {0, 0, 1e-6}, 3.2, 0, 1).has_value());
    // Turned by 180° about the anchor: u and v reversed, the anchor kept.
    const auto turned = run->turned();
    CHECK(turned.matrix[0] == -run->matrix[0]);
    CHECK(turned.matrix[3] == -run->matrix[3]);
    CHECK(turned.matrix[4] == run->matrix[4]);
}

TEST_CASE("the canal rule: each name's first way in source order within the radius, the next where a map degenerates") {
    const view::Camera cam = camera();
    std::vector<CanalCandidate> candidates{
        candidate(0, "Singel", 2000, 0, 1, 0),         // beyond the radius: passed over
        candidate(1, "Singel", 50, -40, 1, 0),         // the first within it: drawn
        candidate(2, "Singel", -60, -20, 1, 0),        // a later way of a drawn name: passed over
        candidate(3, "Herengracht", 0, -10, 0, 0, 1),  // fewer than 2 vertices: passed over
        candidate(4, "Herengracht", 30, -80, 0, -1),   // drawn, its baseline north
        candidate(5, "Keizersgracht", -30, 20, -1, 0), // drawn
    };
    auto rule = text::canal_rule(candidates, view_of(cam));
    ATLAS_REQUIRE(rule.size() == 3);
    CHECK(rule[0].candidate == 1);
    CHECK(rule[0].run.text == "SINGEL");
    CHECK(rule[1].candidate == 4);
    CHECK(rule[2].candidate == 5);
    // Water level + 0.05 m with weather, 0.1 m without.
    const auto on_water = cam.proj({50, -1.45, -40});
    const auto above = cam.proj({50, 0.1, -40});
    ATLAS_REQUIRE(on_water.has_value() && above.has_value());
    CHECK(rule[0].run.matrix[4] == on_water->x);
    CanalView dry = view_of(cam);
    dry.weather = false;
    CHECK(text::canal_rule(candidates, dry)[0].run.matrix[4] == above->x);
    // Not at night, not from the engine's pitch of 20° or less.
    CanalView night = view_of(cam);
    night.night = true;
    CHECK(text::canal_rule(candidates, night).empty());
    const view::Camera low = camera(0.0, 20.0);
    CHECK(text::canal_rule(candidates, view_of(low)).empty());
}

TEST_CASE("over a pack without views a live frame's names take the canal ring's radius: a far first way gives way") {
    // The windows' bare ladder sets no radius, so the rule took each name's first way anywhere in the frame cell (here
    // 2 km east, off the screen) and drew none by the view; a live frame takes the canal ring's radius at its distance.
    const view::Camera cam = camera();
    const std::vector<CanalCandidate> candidates{candidate(0, "Prinsengracht", 2000, 0, 1, 0),
                                                 candidate(1, "Prinsengracht", 50, -40, 1, 0)};
    CanalView engine = view_of(cam);
    engine.radius = passes::canal_names_radius(std::nullopt, 400, true); // engine mode: none, as the renders draw it
    CHECK(engine.radius == 1e9);
    const auto far = text::canal_rule(candidates, engine);
    ATLAS_REQUIRE(far.size() == 1);
    CHECK(far[0].candidate == 0);
    CanalView live = view_of(cam);
    live.radius = passes::canal_names_radius(std::nullopt, 400, false);
    CHECK(live.radius == 350); // the canal ring's 1,400 m at 1,600, scaled
    const auto near = text::canal_rule(candidates, live);
    ATLAS_REQUIRE(near.size() == 1);
    CHECK(near[0].candidate == 1);
    // A pack with views keeps its own radius in either mode; a radius of 0 is none.
    CHECK(passes::canal_names_radius(1400.0, 1600, false) == 1400);
    CHECK(passes::canal_names_radius(1400.0, 1600, true) == 1400);
    CHECK(passes::canal_names_radius(0.0, 400, false) == 350);
    CHECK(passes::canal_names_radius(0.0, 400, true) == 1e9);
}

TEST_CASE("the canal ring's own ladder gives the live names' radius at every distance between and beyond its views") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canal ring's views are not checked here");
        return;
    }
    auto pack = test::open_pack((std::filesystem::path(dir) / "grachten.atlas").string());
    ATLAS_REQUIRE(pack.has_value(), tiles::describe(pack.error()));
    const auto ladder = view::Ladder::of(*pack->head());
    ATLAS_REQUIRE(ladder.has_value());
    std::size_t checked = 0;
    // nearer than the nearest view, about each view, between them and beyond the farthest
    for (const double dist : {120.0,  200.0,  333.3,  400.0,  760.0,  1100.0, 1599.9, 1600.0, 1600.1, 1777.0, 1950.0,
                              2299.9, 2300.0, 2300.1, 2512.5, 2699.9, 2700.0, 2700.1, 3500.0, 5000.0, 8000.0}) {
        // by the Westerkerk, turned off every view, so that none snaps
        const view::LiveView v{.latitude = 52.3749,
                               .longitude = 4.8839,
                               .distance = dist,
                               .bearing = 31,
                               .pitch = 47,
                               .width_dp = 412,
                               .height_dp = 915,
                               .cy_dp = 440};
        const view::Placed placed = ladder->place(v);
        ATLAS_REQUIRE(!placed.view().has_value());
        CHECK(placed.number("radius") == view::live_names_radius(dist));
        ++checked;
    }
    MESSAGE("the canal ring's radius matched at " << checked << " distances");
}

TEST_CASE("moving, a name keeps its way while the rule could draw it there, else takes the nearest well inside the "
          "radius; a change fades over 250 ms; at rest the rule") {
    std::vector<CanalCandidate> candidates{candidate(0, "Singel", 0, 0, 1, 0), candidate(1, "Singel", 60, 0, 1, 0)};
    text::CanalNames names;
    names.set_candidates(candidates);
    // The first frame at rest: the rule's way at once.
    const view::Camera start = camera(0.0, 50.0, {0, 0, 0});
    auto placed = names.update(view_of(start, true, 0.0));
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].candidate == 0);
    CHECK(placed[0].alpha == 1.0F);
    // Moving so that the first way's middle is still within the radius and the screen: it stays.
    const view::Camera east = camera(0.0, 50.0, {40, 0, 0});
    CanalView moving = view_of(east, false, 0.5);
    moving.radius = 40.5; // the first way, 40 m from the target, still within
    placed = names.update(moving);
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].candidate == 0);
    // The radius leaves it behind: the rule takes the second way, the first fading out, the second in.
    moving.radius = 25;
    moving.seconds = 1.0;
    placed = names.update(moving);
    ATLAS_REQUIRE(placed.size() == 2);
    CHECK(names.changes() == 1);
    CHECK(names.fading());
    const auto incoming = std::ranges::find(placed, 1U, &text::PlacedName::candidate);
    const auto outgoing = std::ranges::find(placed, 0U, &text::PlacedName::candidate);
    ATLAS_REQUIRE(incoming != placed.end() && outgoing != placed.end());
    CHECK(incoming->alpha == 0.0F);
    CHECK(!incoming->leaving);
    CHECK(outgoing->leaving);
    CHECK(outgoing->alpha == 1.0F);
    moving.seconds = 1.125;
    placed = names.update(moving);
    CHECK(std::ranges::find(placed, 1U, &text::PlacedName::candidate)->alpha == doctest::Approx(0.5));
    moving.seconds = 1.3;
    placed = names.update(moving);
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].candidate == 1);
    CHECK(placed[0].alpha == 1.0F);
    CHECK(!names.fading());
}

TEST_CASE("moving, the screen plays no part, and a way at the radius's edge is not taken") {
    // Singel's first way in source order lies 200 m east, off the frame; its second on the frame, 10 m north. At rest
    // the rule draws the first; as the camera starts moving the name stays there.
    const view::Camera cam = camera(0.0, 50.0, {0, 0, 0}, 150.0);
    text::CanalNames names;
    names.set_candidates({candidate(0, "Singel", 200, 0, 1, 0), candidate(1, "Singel", 0, -10, 1, 0)});
    CanalView rest = view_of(cam, true, 0.0);
    rest.radius = 250.0;
    auto placed = names.update(rest);
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].candidate == 0);
    CHECK(placed[0].rule.matrix[4] > cam.width()); // off the frame
    CanalView moving = view_of(cam, false, 0.1);
    moving.radius = 250.0;
    placed = names.update(moving);
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].candidate == 0);
    CHECK(names.changes() == 0);
    // A name coming while moving takes only a way within nine tenths of the radius: Amstel's, 95 m out, comes at a
    // radius of 106 m but not at 100 m, where the rule draws it at rest.
    text::CanalNames edge;
    edge.set_candidates({candidate(0, "Amstel", 0, -95, 1, 0)});
    CanalView near = view_of(cam, false, 0.0);
    near.radius = 100.0;
    CHECK(edge.update(near).empty());
    near.radius = 106.0;
    near.seconds = 0.1;
    CHECK(edge.update(near).size() == 1);
    text::CanalNames still;
    still.set_candidates({candidate(0, "Amstel", 0, -95, 1, 0)});
    CanalView at_rest = view_of(cam, true, 0.0);
    at_rest.radius = 100.0;
    CHECK(still.update(at_rest).size() == 1);
}

TEST_CASE("a name whose baseline points past 100° from screen right is turned, and back below 80°") {
    // A way running west: its baseline points left on screen, upside down.
    std::vector<CanalCandidate> west{candidate(0, "Singel", 0, 0, -1, 0)};
    text::CanalNames names;
    names.set_candidates(west);
    auto placed = names.update(view_of(camera(), true, 0.0));
    ATLAS_REQUIRE(placed.size() == 1);
    CHECK(placed[0].turned);
    CHECK(placed[0].drawn.matrix[0] == -placed[0].rule.matrix[0]);
    // Turning the camera's yaw sweeps the baseline's angle: it turns back only once below 80°, and over again past
    // 100°.
    bool was = true;
    int flips = 0;
    for (int step = 0; step <= 360; step += 2) {
        const view::Camera cam = camera(static_cast<double>(step));
        placed = names.update(view_of(cam, false, static_cast<double>(step)));
        if (placed.empty()) continue;
        const auto& m = placed[0].rule.matrix;
        const double cosine = m[0] / pymath::hypot(m[0], m[1]);
        if (placed[0].turned != was) {
            ++flips;
            CHECK(placed[0].turned == (cosine < text::turn_cos));
            if (!placed[0].turned) CHECK(cosine > text::unturn_cos);
        }
        // never upside down by more than 100°, never turned within 80° of screen right
        if (placed[0].turned) CHECK(cosine < text::unturn_cos + 1e-12);
        if (!placed[0].turned) CHECK(cosine > text::turn_cos - 1e-12);
        was = placed[0].turned;
    }
    CHECK(flips == 2);
}

TEST_CASE("runway designators: the ref's name for each end, 60 m in, 18 m tall in Geist 800") {
    const view::Camera cam = camera(0.0, 60.0, {0, 0, 0}, 1500.0);
    constexpr std::uint32_t day = 0xF4F3EE; // the marking colour by day, as the style gives it
    // A runway from the south (z 1000) to the north (z -1000): from its south end the heading is 0°, so 36; from the
    // north end 180°, so 18.
    const text::Runway runway{.points = {{0, 1000}, {0, 0}, {0, -1000}}, .ref = "18C/36C"};
    const auto runs = text::runway_designators(cam, runway, day);
    ATLAS_REQUIRE(runs.size() == 2);
    CHECK(runs[0].text == "36C");
    CHECK(runs[1].text == "18C");
    CHECK(runs[0].weight == 800);
    CHECK(runs[0].size == 18);
    CHECK(runs[0].fill == 0xF4F3EEU);
    const auto south_end = cam.proj({0, 0.09, 940});
    ATLAS_REQUIRE(south_end.has_value());
    CHECK(runs[0].matrix[4] == south_end->x);
    CHECK(text::runway_designators(cam, runway, 0x4A4F5CU)[0].fill == 0x4A4F5CU);
    // A ref without a slash names nothing; a name whose number is off the heading by 30° or more is not taken.
    CHECK(text::runway_designators(cam, {.points = runway.points, .ref = "36C"}, day).empty());
    CHECK(text::runway_designators(cam, {.points = runway.points, .ref = " 09 / 27 "}, day).empty());
    const auto padded = text::runway_designators(cam, {.points = runway.points, .ref = " 18 / 36 "}, day);
    ATLAS_REQUIRE(padded.size() == 2);
    CHECK(padded[0].text == "36");
    // The number is read from the BMP's decimal digits, as the port's Character.isDigit reads them: Arabic-Indic
    // ٣٦ is 36, fullwidth １８ is 18; a digit past the BMP (mathematical bold 𝟑) ends the number before it starts.
    const auto eastern = text::runway_designators(cam, {.points = runway.points, .ref = "١٨/٣٦L"}, day);
    ATLAS_REQUIRE(eastern.size() == 2);
    CHECK(eastern[0].text == "٣٦L");
    CHECK(eastern[1].text == "١٨");
    const auto wide = text::runway_designators(cam, {.points = runway.points, .ref = "１８/x"}, day);
    ATLAS_REQUIRE(wide.size() == 1);
    CHECK(wide[0].text == "１８");
    CHECK(text::runway_designators(cam, {.points = runway.points, .ref = "\U0001D7D1\U0001D7D4/x"}, day).empty());
}

TEST_CASE("a run laid out: each glyph under the snapped matrix at its anchored x, in device px") {
    const text::Fonts* fonts = test::bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const view::Camera cam = camera();
    const auto run =
        text::ground_text(cam, "SINGEL", {0, -1.45, 0}, {1, 0, 0}, {0, 0, 1}, 3.2, 0xEAF2FA, 0.85, 700, 0.3);
    ATLAS_REQUIRE(run.has_value());
    const auto style = run->shape_style(text::Rounding::floor);
    ATLAS_REQUIRE(style.has_value());
    const auto shaped = text::shape(*fonts, *style, run->text);
    ATLAS_REQUIRE(shaped.has_value());
    text::slug::Atlas atlas;
    const auto instance = fonts->instance(style->font);
    ATLAS_REQUIRE(instance.has_value());
    std::vector<text::slug::Glyph> glyphs;
    glyphs.reserve(shaped->glyphs.size());
    for (const std::uint32_t g : shaped->glyphs) {
        const auto placed = atlas.add(text::outline(**instance, g), (*instance)->units_per_em());
        ATLAS_REQUIRE(placed.has_value());
        glyphs.push_back(*placed);
    }
    std::vector<const text::slug::Glyph*> places;
    places.reserve(glyphs.size());
    for (const auto& g : glyphs) places.push_back(&g);
    std::vector<text::GlyphInstance> out;
    const auto snapped = run->snapped_matrix();
    std::array<double, 6> m{};
    std::ranges::transform(snapped, m.begin(), [](float v) { return static_cast<double>(v); });
    text::lay_out(*run, m, *shaped, places, 2.625, out);
    ATLAS_REQUIRE(out.size() == 6);
    // Glyph 0's em origin: the anchored x on the baseline, through the matrix, times the density.
    const auto x0 = static_cast<double>(shaped->anchored_x(0, text::Anchor::middle));
    CHECK(out[0].row0[2] == static_cast<float>(2.625 * ((m[0] * x0) + m[4])));
    CHECK(out[0].row1[2] == static_cast<float>(2.625 * ((m[1] * x0) + m[5])));
    CHECK(out[0].colour[3] == 0.85F);
    CHECK(out[0].colour[0] == doctest::Approx(0xEA / 255.0 * 0.85));
}

TEST_CASE("the bundled packs' canal names at their boards' views, and a pan with no name changing way twice in 5 s") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    for (const auto& [pack_name, view_name] : {std::pair{"amsterdam", "amsterdam"}, {"grachten", "grachten_home"}}) {
        auto pack = test::open_pack((std::filesystem::path(dir) / std::format("{}.atlas", pack_name)).string());
        ATLAS_REQUIRE(pack.has_value(), tiles::describe(pack.error()));
        std::string names;
        for (const auto& hv : pack->head()->views()) names += std::string(hv.name) + " ";
        const auto v = pack->head()->view(view_name);
        ATLAS_REQUIRE(v.has_value(), pack_name << " has no view " << view_name << "; its views: " << names);
        const auto candidates = pack_candidates(*pack);
        const view::Camera cam(spec_of(v->camera));
        CanalView canal = view_of(cam);
        const auto radius = v->camera.find("radius");
        canal.radius = radius ? radius->number().value_or(1e9) : 1e9;
        const auto rule = text::canal_rule(candidates, canal);
        std::size_t turned = 0;
        for (const auto& r : rule) turned += text::beyond(r.run.matrix, text::turn_cos) ? 1U : 0U;
        MESSAGE(std::string(pack_name) << " at " << std::string(view_name) << ": " << candidates.size()
                                       << " candidates, " << rule.size() << " names drawn, " << turned
                                       << " upside down");
        CHECK(!rule.empty());
    }
    // VK bench's pan over the Main board's view, from rest to rest: the target 600 m along the view and back,
    // sinusoidally, over 30 s at 60 frames a second (VkBench.cameraAt, Path.PAN), the view's radius cut moving with it.
    // Each name's changes of way in any 5 s while the camera moves; then the stop, where the names settle onto the
    // rule's ways (the pan stops dead at full speed, 126 m/s). A change is seen when its old or new anchor is on the
    // frame.
    auto pack = test::open_pack((std::filesystem::path(dir) / "grachten.atlas").string());
    ATLAS_REQUIRE(pack.has_value());
    const auto v = pack->head()->view("grachten_home");
    ATLAS_REQUIRE(v.has_value());
    const view::CameraSpec board = spec_of(v->camera);
    const auto radius_value = v->camera.find("radius");
    const double radius = radius_value ? radius_value->number().value_or(1e9) : 1e9;
    const double yaw = pymath::radians(board.yaw);
    const double ahead_x = pymath::sin(yaw);
    const double ahead_z = -pymath::cos(yaw);
    text::CanalNames names;
    names.set_candidates(pack_candidates(*pack));
    std::map<std::string, std::vector<double>> moving; // name: when it changed way while the camera moved
    std::map<std::string, double> settled;             // name: its last change of way before the stop
    std::map<std::string, std::uint32_t> last;
    std::map<std::string, bool> last_seen;
    std::size_t seen = 0;
    std::size_t at_rest = 0;
    constexpr int frames = 30 * 60;
    for (int f = 0; f <= frames; ++f) {
        const double t = f / 60.0;
        const double along = 600.0 * pymath::sin(2 * pymath::pi * t / 30.0);
        view::CameraSpec s = board;
        s.target.x += along * ahead_x;
        s.target.z += along * ahead_z;
        const view::Camera cam(s);
        CanalView canal = view_of(cam, f == 0 || f == frames, t);
        canal.radius = radius;
        for (const auto& p : names.update(canal)) {
            if (p.leaving) continue;
            const std::string& name = names.candidates()[p.candidate].name;
            const bool on = p.rule.matrix[4] >= 0 && p.rule.matrix[4] <= board.width && p.rule.matrix[5] >= 0 &&
                            p.rule.matrix[5] <= board.height;
            const auto it = last.find(name);
            if (it != last.end() && it->second != p.candidate) {
                seen += on || last_seen[name] ? 1U : 0U;
                if (f == frames) {
                    ++at_rest;
                    const auto before = moving.find(name);
                    settled[name] = before == moving.end() ? -1e9 : before->second.back();
                } else {
                    moving[name].push_back(t);
                }
            }
            last[name] = p.candidate;
            last_seen[name] = on;
        }
    }
    std::size_t worst = 0;
    std::size_t changed = 0;
    for (const auto& [name, times] : moving) {
        changed += times.size();
        for (std::size_t i = 0; i < times.size(); ++i) {
            std::size_t within = 0;
            for (std::size_t j = i; j < times.size() && times[j] - times[i] < 5.0; ++j) ++within;
            worst = std::max(worst, within);
        }
    }
    std::size_t soon = 0;
    for (const auto& [name, when] : settled) soon += 30.0 - when < 5.0 ? 1U : 0U;
    MESSAGE("the pan (radius " << radius << " m): " << last.size() << " names drawn; while moving " << changed
                               << " changes of way by " << moving.size() << " names, at most " << worst
                               << " of one name in 5 s; at the stop " << at_rest
                               << " names settle onto the rule's way, " << soon
                               << " of them within 5 s of a change while moving; " << seen
                               << " changes seen on the frame");
    CHECK(worst <= 1);
}
