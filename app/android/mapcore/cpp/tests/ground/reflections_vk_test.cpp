// The water's reflections on the host's Vulkan (passes/Reflections.cpp): at the Westerkerk home by day with the sun set
// (no shadow on the water), the ground drawn alone with its reflections and without them. Where the frame without them
// shows the bare water (wgrad under its tint: nothing later on the ground covers it), a pixel whose samples the twin's
// reflections cover alike is what their group composites there over the water, under the tint: the latest face's colour
// at the group's opacity over wgrad's, a window row's at its opacity over the face under it; and a pixel they leave
// bare is the bare water. The twin's faces are its own polygons (a wall's grown by its .3 stroke's half width) and
// circles, at the standard 4x sample positions, the latest painter slot winning.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

/// A twin reflection in device px: its polygons (a window rows' path has several) and half stroke, or its circle; its
/// colour, its slot, whether it is a window row (drawn at its opacity).
struct Face {
    std::vector<std::vector<std::array<double, 2>>> polys;
    double hw = 0;
    bool circle = false;
    double cx = 0;
    double cy = 0;
    double r = 0;
    std::uint32_t colour = 0;
    std::uint32_t slot = 0;
    bool sheer = false;
};

/// Whether x lies in the convex polygon or within hw of it, and how far it lies from that shape's boundary.
std::pair<bool, double> polygon_at(const std::vector<std::array<double, 2>>& p, double hw, double x, double y) {
    double twice = 0;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % p.size()];
        twice += (a[0] * b[1]) - (b[0] * a[1]);
    }
    const double sgn = twice >= 0 ? 1.0 : -1.0;
    bool inside = true;
    double nearest = 1e30;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % p.size()];
        const double ex = b[0] - a[0];
        const double ey = b[1] - a[1];
        const double qx = x - a[0];
        const double qy = y - a[1];
        if (sgn * ((ex * qy) - (ey * qx)) < 0) inside = false;
        const double l2 = (ex * ex) + (ey * ey);
        const double t = l2 > 0 ? std::clamp(((qx * ex) + (qy * ey)) / l2, 0.0, 1.0) : 0.0;
        nearest = std::min(nearest, pymath::hypot(qx - (ex * t), qy - (ey * t)));
    }
    const bool covered = inside || (hw > 0 && nearest <= hw);
    return {covered, inside && hw == 0 ? nearest : std::abs(nearest - hw)};
}

/// Whether a face covers (x, y), and how far (x, y) lies from its boundary.
std::pair<bool, double> face_at(const Face& f, double x, double y) {
    if (f.circle) {
        const double d = pymath::hypot(x - f.cx, y - f.cy);
        return {d <= f.r, std::abs(d - f.r)};
    }
    bool covered = false;
    double from = 1e30;
    for (const auto& p : f.polys) {
        const auto [c, d] = polygon_at(p, f.hw, x, y);
        covered = covered || c;
        from = std::min(from, d);
    }
    return {covered, from};
}

/// What a sample shows of the reflections: the latest face (its index + 1; 0: none), the latest opaque one, and how
/// many window rows lie over that.
struct Top {
    std::uint32_t top = 0;
    std::uint32_t opaque = 0;
    std::uint32_t sheers = 0;
    friend bool operator==(const Top&, const Top&) = default;
};

std::array<double, 3> channels(std::uint32_t argb) {
    return {static_cast<double>((argb >> 16U) & 0xFFU), static_cast<double>((argb >> 8U) & 0xFFU),
            static_cast<double>(argb & 0xFFU)};
}

std::array<double, 3> channels(const style::Rgb8& c) {
    return {static_cast<double>(c.r), static_cast<double>(c.g), static_cast<double>(c.b)};
}

} // namespace

TEST_CASE("ground: the reflections composite as their group over the water, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the reflections are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock day = test::sunny();
    day.sun_vector = {0.0, -1.0, 0.0};
    day.sun_elevation = -5.0;
    std::vector<Face> faces;
    double group = 0;
    double windows = 0;
    double wgrad_y1 = 0;
    double wgrad_y2 = 0;
    style::ThemeBlock th{};
    const auto a = test::draw_ground(
        *session, camera, day, dir, "amsterdam", [&](passes::Ground& g, const frame::FrameState& state) {
            twin::Stream s;
            g.emit(state, s, passes::Ground::phase_opaque);
            const twin::Decoded d = twin::decode(s.bytes());
            REQUIRE(d.error.empty());
            const passes::GroundFrame& f = g.decided();
            REQUIRE(!f.reflections.empty());
            const tiles::ReflectionsStyle& rs = f.tiles[f.reflections.front().tile].ground->reflection_style;
            group = rs.opacity[0];
            windows = rs.windows_opacity;
            wgrad_y1 = f.wgrad_y1;
            wgrad_y2 = f.wgrad_y2;
            th = state.theme;
            const auto density = static_cast<double>(state.block.camera.frame.z);
            const std::uint32_t first = g.slot(geometry::group::reflection, 0, 0);
            const auto count = static_cast<std::uint32_t>(f.reflections.size());
            for (const twin::Element& e : d.document.elements) {
                if (e.layer != twin::Layer::ground) continue;
                if (e.slot < first || e.slot >= first + count) continue;
                Face face;
                face.slot = e.slot;
                face.colour = e.style.fill.argb;
                if (e.kind == twin::Op::circle) {
                    face.circle = true;
                    face.cx = static_cast<double>(e.points[0].x) * density;
                    face.cy = static_cast<double>(e.points[0].y) * density;
                    face.r = static_cast<double>(e.points[1].x) * density;
                    faces.push_back(face);
                    continue;
                }
                if (e.kind != twin::Op::path) continue;
                face.sheer = e.node.opacity < 1.0F;
                face.hw = e.style.stroke.kind == twin::Paint::Kind::none
                              ? 0.0
                              : static_cast<double>(e.style.stroke_width) * density / 2;
                std::size_t start = 0;
                for (const std::uint32_t end : e.subpath_ends) {
                    std::vector<std::array<double, 2>> p;
                    p.reserve(end - start);
                    for (std::size_t k = start; k < end; ++k) {
                        p.push_back({static_cast<double>(e.points[k].x) * density,
                                     static_cast<double>(e.points[k].y) * density});
                    }
                    face.polys.push_back(std::move(p));
                    start = end;
                }
                faces.push_back(std::move(face));
            }
        });
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its reflections");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const auto b = test::draw_ground(*session, camera, day, dir, "amsterdam", {}, 40,
                                     [](passes::Ground& g) { g.draw_reflections(false); });
    ATLAS_REQUIRE(b.has_value(), "the home was not drawn without its reflections");
    REQUIRE(b->decided.reflections.empty());
    REQUIRE(faces.size() > 200);

    std::ranges::stable_sort(faces, {}, &Face::slot);
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    const auto density = static_cast<double>(camera.density);
    // each sample's reflections, and whether it lies within a print step (0.1 CSS px) of a face's boundary, where the
    // vertex stage's float corners may print a step off the twin's doubles
    const double step = 0.1 * density;
    std::vector<std::array<Top, 4>> tops(std::size_t{w} * h);
    std::vector<std::uint8_t> near_edge(std::size_t{w} * h, 0);
    for (std::uint32_t fi = 0; fi < faces.size(); ++fi) {
        const Face& f = faces[fi];
        double x0 = f.cx - f.r;
        double x1 = f.cx + f.r;
        double y0 = f.cy - f.r;
        double y1 = f.cy + f.r;
        if (!f.circle) {
            x0 = y0 = 1e30;
            x1 = y1 = -1e30;
            for (const auto& p : f.polys) {
                for (const auto& q : p) {
                    x0 = std::min(x0, q[0]);
                    x1 = std::max(x1, q[0]);
                    y0 = std::min(y0, q[1]);
                    y1 = std::max(y1, q[1]);
                }
            }
        }
        const double grow = f.hw + 1;
        for (auto y = static_cast<std::int64_t>(std::max(0.0, std::floor(y0 - grow)));
             y <= static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1 + grow))); ++y) {
            for (auto x = static_cast<std::int64_t>(std::max(0.0, std::floor(x0 - grow)));
                 x <= static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1 + grow))); ++x) {
                const std::size_t i = (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x);
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    const auto [covered, from] =
                        face_at(f, static_cast<double>(x) + samples[k][0], static_cast<double>(y) + samples[k][1]);
                    if (from < step) near_edge[i] = 1;
                    if (!covered) continue;
                    Top& t = tops[i][k];
                    if (t.top == fi + 1) continue; // a path's faces cover a sample once
                    t.top = fi + 1;
                    if (f.sheer) {
                        ++t.sheers;
                    } else {
                        t.opaque = fi + 1;
                        t.sheers = 0;
                    }
                }
            }
        }
    }
    const std::array<double, 3> top = channels(th.water_top);
    const std::array<double, 3> bottom = channels(th.water_bottom);
    const std::array<double, 3> water = channels(th.water);
    constexpr double tint = 0.12;
    std::size_t bare = 0;
    std::size_t whole = 0;
    std::size_t sheer = 0;
    std::size_t wrong = 0;
    std::size_t boundary = 0;
    std::size_t stray = 0;
    std::string first;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        // wgrad at the pixel's centre, under the tint: the bare water
        const std::size_t row = i / w;
        const double css_y = (static_cast<double>(row) + 0.5) / density;
        const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                              : (css_y < wgrad_y1 ? 0.0 : 1.0);
        std::array<double, 3> wg{};
        std::array<double, 3> plain{};
        for (std::size_t c = 0; c < 3; ++c) {
            wg[c] = top[c] + ((bottom[c] - top[c]) * t);
            plain[c] = (wg[c] * (1 - tint)) + (water[c] * tint);
        }
        const std::uint8_t* pb = &b->pixels.rgba[i * 4];
        const std::uint8_t* pa = &a->pixels.rgba[i * 4];
        bool is_bare = true;
        for (std::size_t c = 0; c < 3; ++c) is_bare = is_bare && std::abs(static_cast<double>(pb[c]) - plain[c]) <= 2;
        if (!is_bare) continue;
        ++bare;
        const std::array<Top, 4>& s = tops[i];
        if (s[1] != s[0] || s[2] != s[0] || s[3] != s[0]) continue;
        std::array<double, 3> want = plain;
        if (s[0].top == 0) {
            // none of its samples: the bare water
            bool same = true;
            for (std::size_t c = 0; c < 3; ++c) same = same && pa[c] == pb[c];
            if (!same && near_edge[i] == 0) ++stray;
            continue;
        }
        if (s[0].sheers > 1) continue; // rows over rows: not composited here
        ++whole;
        // the group's layer at the sample, composited at the group's opacity over wgrad, under the tint
        std::array<double, 3> layer{};
        double alpha = 1;
        const Face& f = faces[s[0].top - 1];
        const std::array<double, 3> fc = channels(f.colour);
        if (!f.sheer) {
            layer = fc;
        } else {
            ++sheer;
            if (s[0].opaque != 0) {
                const std::array<double, 3> under = channels(faces[s[0].opaque - 1].colour);
                for (std::size_t c = 0; c < 3; ++c) layer[c] = (fc[c] * windows) + (under[c] * (1 - windows));
            } else {
                layer = fc;
                alpha = windows;
            }
        }
        for (std::size_t c = 0; c < 3; ++c) {
            const double comp = (layer[c] * group * alpha) + (wg[c] * (1 - (group * alpha)));
            want[c] = (comp * (1 - tint)) + (water[c] * tint);
        }
        double worst = 0;
        for (std::size_t c = 0; c < 3; ++c) worst = std::max(worst, std::abs(static_cast<double>(pa[c]) - want[c]));
        if (worst <= 2) continue;
        if (near_edge[i] != 0) {
            ++boundary;
            continue;
        }
        if (wrong == 0) {
            first = std::format("pixel {} {}: {} {} {}, the twin's {:.1f} {:.1f} {:.1f} (face #{:06x}{})", i % w, i / w,
                                int(pa[0]), int(pa[1]), int(pa[2]), want[0], want[1], want[2], f.colour & 0xFFFFFFU,
                                f.sheer ? ", a window row" : "");
        }
        ++wrong;
    }
    MESSAGE("home by day, the sun set: " << faces.size() << " reflections' faces and circles in the twin; " << bare
                                         << " pixels of bare water without them, " << whole
                                         << " of them wholly the reflections' (" << sheer
                                         << " under a window row): " << boundary
                                         << " another colour by a sample within a print step of a boundary, " << wrong
                                         << " otherwise; " << stray << " left bare but not the water's");
    CHECK(whole > 2000);
    CHECK(sheer > 100);
    CHECK_MESSAGE(wrong == 0, first);
    CHECK(boundary <= whole / 1000);
    CHECK(stray == 0);
}

#endif
