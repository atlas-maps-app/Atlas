#include "geometry/Trees.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "tiles/layers/Points.hpp"

#include <cmath>
#include <numbers>
#include <string>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;
using tl::TreeForm;

/// city.py's vnorm's second lane: a[1] / sqrt(vdot(a, a)), summed left to right.
[[nodiscard]] double norm_y(double x, double y, double z) noexcept {
    const double l = pymath::sqrt((x * x) + (y * y) + (z * z));
    return y / l;
}

} // namespace

TreeForm tree_form(const tl::Style& style, std::optional<std::string_view> leaf_type,
                   std::optional<std::string_view> genus, std::optional<std::string_view> species,
                   std::optional<std::string_view> taxon) {
    // if t.get('leaf_type') == 'needleleaved': return 'cone'
    if (leaf_type && *leaf_type == "needleleaved") return TreeForm::cone;
    // nm = (t.get('genus') or t.get('species') or t.get('taxon') or '').split(' ')[0].lower()
    std::string_view name;
    if (genus && !genus->empty()) {
        name = *genus;
    } else if (species && !species->empty()) {
        name = *species;
    } else if (taxon && !taxon->empty()) {
        name = *taxon;
    }
    name = name.substr(0, name.find(' '));
    std::string lower(name);
    for (char& c : lower) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a'); // the genera FORMS names are ASCII
    }
    for (const auto& [genus_name, form] : style.trees.forms) {
        if (genus_name == lower) return form;
    }
    return TreeForm::ovoid;
}

std::array<TreeClump, tree_clumps> tree_close_clumps(std::int64_t id, TreeForm form, double h, double r, double x,
                                                     double z) {
    // closeup.py's tree_close, its crown's loop: rnd = random.Random(n['id']), its clumps as drawn when none is skipped
    pymath::PyRandom rnd(id);
    const double cb = h * (form != TreeForm::weep ? 0.38 : 0.3);
    const double cy = cb + ((h - cb) * 0.5);
    const double ry = (h - cb) / 2;
    std::array<TreeClump, tree_clumps> out{};
    for (TreeClump& c : out) {
        double y = 0;
        double big = 0; // R
        for (int attempt = 0; attempt < 8; ++attempt) {
            switch (form) {
            case TreeForm::vase: {
                const double f = rnd.uniform(0.12, 1.0);
                y = cb + ((h - cb) * f);
                big = r * (0.38 + (0.62 * pymath::sin(pymath::min(std::numbers::pi / 2, f * 2.0)))) *
                      (1 - (pymath::max(0.0, f - 0.85) * 2.5));
                break;
            }
            case TreeForm::cone: {
                const double f = rnd.uniform(0.0, 0.95);
                y = (cb * 0.7) + ((h - (cb * 0.7)) * f);
                big = r * (1 - f);
                break;
            }
            case TreeForm::column: {
                const double f = rnd.uniform(-1, 1);
                y = cy + (ry * f);
                big = r * 0.55 * pymath::sqrt(pymath::max(0.0, 1 - (f * f)));
                break;
            }
            case TreeForm::dome:
            case TreeForm::broad:
            case TreeForm::weep: {
                const double f = rnd.uniform(-0.55, 1);
                y = cy + (ry * f);
                big = r * (form == TreeForm::broad ? 1.08 : 1.0) * pymath::sqrt(pymath::max(0.0, 1 - (f * f)));
                break;
            }
            case TreeForm::ovoid: {
                const double f = rnd.uniform(-1, 1);
                y = cy + (ry * f);
                big = r * pymath::sqrt(pymath::max(0.0, 1 - (f * f)));
                break;
            }
            }
            if (big > 0.2) break;
        }
        const double ang = rnd.uniform(0, 2 * std::numbers::pi);
        const double rad = big * pymath::sqrt(rnd.uniform(0.35, 1.0));
        // x, z = n['x'] + rad * cos(ang), n['z'] + rad * sin(ang), kept as their offsets from the trunk, x - n['x'] and
        // z - n['z'] (nrm's), which the frame adds to the trunk's tile-local point
        c.dx = (x + (rad * pymath::cos(ang))) - x;
        c.dz = (z + (rad * pymath::sin(ang))) - z;
        c.y = y;
        c.under = norm_y(c.dx, (y - cy) * 1.4, c.dz) < -0.45;
        c.br = r * rnd.uniform(0.27, 0.38);
    }
    return out;
}

std::array<TreeClump, tree_blobs> tree_generic_blobs(std::int64_t id, double h, double r, double x, double z) {
    // amsterdam.py's draw_tree3d without weather, nb = 9 (d < 400; 5 beyond: the first of them)
    pymath::PyRandom rnd(id);
    std::array<TreeClump, tree_blobs> out{};
    for (TreeClump& b : out) {
        const double ang = rnd.uniform(0, 2 * std::numbers::pi);
        const double rad = r * pymath::sqrt(rnd.random()) * 0.7;
        b.dx = (x + (rad * pymath::cos(ang))) - x;
        b.dz = (z + (rad * pymath::sin(ang))) - z;
        b.y = (h * 0.68) + (rnd.uniform(-0.3, 0.3) * r);
        b.br = r * rnd.uniform(0.38, 0.55);
    }
    return out;
}

TileTrees build_trees(const TreeInput& in) {
    TileTrees out;
    const tl::Layer* layer = in.tile->layer(tl::LayerId::tree);
    if (layer == nullptr) return out;
    const tl::Frame& frame = in.head->frame;
    out.trees.reserve(layer->count);
    out.items.reserve(layer->count);
    for (std::uint32_t i = 0; i < layer->count; ++i) {
        Tree t;
        const tl::GridPoint p = layer->point(tl::tree::pos, i);
        t.x = frame.x(p.lon);
        t.z = frame.z(p.lat);
        t.id = layer->integer(tl::tree::id, i);
        t.node = static_cast<std::uint32_t>(layer->integer(tl::tree::node, i));
        t.h = layer->decimal(tl::tree::h, i).value_or(0.0);
        t.r = layer->decimal(tl::tree::crown, i).value_or(0.0) / 2;
        t.gy = layer->decimal(tl::tree::gy, i).value_or(0.0);
        const std::optional<std::string_view> leaf_type = layer->string(tl::tree::tag_leaf_type, i);
        t.form = tree_form(*in.style, leaf_type, layer->string(tl::tree::tag_genus, i),
                           layer->string(tl::tree::tag_species, i), layer->string(tl::tree::tag_taxon, i));
        // autumn: leaf_cycle=evergreen and needleleaved trees stay green
        const std::optional<std::string_view> cycle = layer->string(tl::tree::tag_leaf_cycle, i);
        t.evergreen = (cycle && *cycle == "evergreen") || (leaf_type && *leaf_type == "needleleaved");
        t.clumps = tree_close_clumps(t.id, t.form, t.h, t.r, t.x, t.z);
        t.blobs = tree_generic_blobs(t.id, t.h, t.r, t.x, t.z);
        // its item: amsterdam.py's gather, c = cam.cam((n['x'], 1, n['z'])), from 2 m, the radius cut at the trunk; the
        // sphere about that point holds every circle it draws (a clump's centre within 1.08 r of the trunk and its
        // radius under 0.475 r, a lit one moved toward the sun by 0.55 of it) and its trunk from the ground
        const double across = 2 * t.r;
        const double up = std::abs(t.gy - 1) + t.h + t.r;
        out.items.push_back({.x = t.x,
                             .y = 1.0,
                             .z = t.z,
                             .offset = 0.0,
                             .cut_x = t.x,
                             .cut_z = t.z,
                             .order = scene::order_of(nodes_list, t.node),
                             .kind = in.kind,
                             .flags = scene::item_flag::radius,
                             .slots = {tree_slots, tree_slots, tree_slots, tree_slots},
                             .min_depth = 2.0F,
                             .radius = static_cast<float>(std::sqrt((across * across) + (up * up)) + 0.01),
                             .tile = 0,
                             .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(t.id))});
        out.trees.push_back(t);
    }
    return out;
}

} // namespace atlas::geometry
