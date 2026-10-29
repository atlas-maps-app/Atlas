// Painter ranks (frame/Ranks.hpp).
#include "frame/Ranks.hpp"

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <limits>

namespace atlas::frame {

namespace {

/// The order the renders' stable sort by -depth gives: the deeper first, the earlier gathered on equal depths.
struct Before {
    const double* depth;
    const std::uint64_t* order;
    [[nodiscard]] bool operator()(std::uint32_t a, std::uint32_t b) const noexcept {
        return depth[a] > depth[b] || (depth[a] == depth[b] && order[a] < order[b]);
    }
};

} // namespace

void Ranks::reset() noexcept {
    order_.clear();
    listed_.clear();
}

void Ranks::update(const scene::ItemTable& items, const view::Camera& camera, const Selectors& selectors,
                   const RankOptions& options) {
    const std::size_t rows = items.rows();
    depth_.resize(rows);
    first_.assign(rows, none);
    band_.assign(rows, 0);
    table_.assign(rows, shaders::RANK_NONE);
    drawn_.assign(rows, 0);
    listed_.resize(rows, 0);
    near_ = 0;

    const view::Vec3& c = camera.eye();
    const view::Vec3& f = camera.forward();
    const view::Vec3& r = camera.right();
    const view::Vec3& u = camera.up();
    const double focal = camera.focal();
    const double cx = camera.cx();
    const double cy = camera.cy();
    const double w = camera.width();
    const double h = camera.height();
    const double near = camera.near();
    // the frame's four sides widened by the margin, each a plane through the eye: px >= -margin is
    // focal * kx + (cx + margin) * kz >= 0, and so on; their normals' lengths in pymath's exact sqrt
    const double left = cx + options.margin;
    const double right = w + options.margin - cx;
    const double top = cy + options.margin;
    const double bottom = h + options.margin - cy;
    const double left_n = pymath::sqrt((focal * focal) + (left * left));
    const double right_n = pymath::sqrt((focal * focal) + (right * right));
    const double top_n = pymath::sqrt((focal * focal) + (top * top));
    const double bottom_n = pymath::sqrt((focal * focal) + (bottom * bottom));
    const auto x = items.x();
    const auto y = items.y();
    const auto z = items.z();
    const auto offset = items.offset();
    const auto alive = items.alive_rows();
    const auto min_depth = items.min_depth();
    const auto max_depth = items.max_depth();
    const auto radius = items.radius();
    const auto flags = items.flags();
    const auto cut_x = items.cut_x();
    const auto cut_z = items.cut_z();
    const auto kind = items.kind();
    for (std::size_t i = 0; i < rows; ++i) {
        if (alive[i] == 0 || (flags[i] & scene::item_flag::masked) != 0) continue;
        // its cut point beyond the most at which it may be drawn (a door's node), so a frame ranks only what it may
        // draw; first, as most of a city's doors are, and with no depth (no pass reads the depth of a row it does not
        // rank)
        if (max_depth[i] < std::numeric_limits<float>::infinity() &&
            ((cut_x[i] - c.x) * f.x) - (c.y * f.y) + ((cut_z[i] - c.z) * f.z) > static_cast<double>(max_depth[i])) {
            continue;
        }
        // gatherItems: cc = cam.cam(p); dropped nearer than its minimum depth; sorted by cc[2] + offset
        const double dx = x[i] - c.x;
        const double dy = y[i] - c.y;
        const double dz = z[i] - c.z;
        const double kz = dx * f.x + dy * f.y + dz * f.z;
        depth_[i] = kz + offset[i];
        if (kz < static_cast<double>(min_depth[i])) continue;
        // a kind the frame leaves out (its parts left out of the map) takes no slot
        if (selectors.left_out(kind[i])) continue;
        if (options.radius_cut && (flags[i] & scene::item_flag::radius) != 0 &&
            view::java_hypot(cut_x[i] - options.cut_x, cut_z[i] - options.cut_z) > options.radius) {
            continue;
        }
        // The frame sees it: its sphere against the frame and the margin (it may reach in from beyond the edges).
        const auto rad = static_cast<double>(radius[i]);
        if (kz + rad < near) continue;
        const double kx = dx * r.x + dy * r.y + dz * r.z;
        const double ky = dx * u.x + dy * u.y + dz * u.z;
        if (kz > rad) {
            const double s = focal / kz;
            const double px = cx + kx * s;
            const double py = cy - ky * s;
            const double reach = rad * s + options.margin;
            if (px + reach < 0 || px - reach > w || py + reach < 0 || py - reach > h) continue;
        } else if ((focal * kx) + (left * kz) < -rad * left_n || (right * kz) - (focal * kx) < -rad * right_n ||
                   (top * kz) - (focal * ky) < -rad * top_n || (focal * ky) + (bottom * kz) < -rad * bottom_n) {
            // a sphere across the eye's plane, which projects to no circle: against the frame's four sides instead,
            // planes through the eye widened by the margin, so one wholly beside, above or below the frame (behind the
            // camera, across the city) takes no rank
            continue;
        }
        drawn_[i] = 1;
        if (kz - rad < near) {
            drawn_[i] = 2;
            ++near_;
        }
    }

    // Last frame's order, less what is not drawn now, then what is newly drawn; then a stable insertion pass.
    next_.clear();
    for (const std::uint32_t row : order_) {
        if (row < rows && drawn_[row] != 0) {
            next_.push_back(row);
        } else if (row < rows) {
            listed_[row] = 0;
        }
    }
    for (std::uint32_t row = 0; row < rows; ++row) {
        if (drawn_[row] != 0 && listed_[row] == 0) {
            next_.push_back(row);
            listed_[row] = 1;
        }
    }
    const Before before{.depth = depth_.data(), .order = items.order().data()};
    moves_ = 0;
    sorted_fully_ = false;
    const std::uint64_t budget = 8 * static_cast<std::uint64_t>(next_.size()) + 1024;
    for (std::size_t i = 1; i < next_.size(); ++i) {
        const std::uint32_t row = next_[i];
        std::size_t j = i;
        while (j > 0 && before(row, next_[j - 1])) {
            next_[j] = next_[j - 1];
            --j;
            ++moves_;
        }
        next_[j] = row;
        if (moves_ > budget) {
            std::ranges::sort(next_, before); // the key is total: the stable sort's order
            sorted_fully_ = true;
            break;
        }
    }
    order_.swap(next_);

    // The slots: the ground's, then each item's in painter's order, as many as its detail band at its depth needs.
    std::uint64_t at = options.ground_slots;
    const auto building = items.building();
    for (const std::uint32_t row : order_) {
        const std::size_t band = selectors.band(kind[row], depth_[row]);
        // the last slot is never a first one, so no entry is RANK_NONE
        const auto first = static_cast<std::uint32_t>(std::min<std::uint64_t>(at, shaders::PAINTER_SLOTS - 2));
        first_[row] = first;
        band_[row] = static_cast<std::uint8_t>(band);
        std::uint32_t detail = selectors.detail(kind[row], depth_[row]);
        if (options.asked != 0 && building[row] == options.asked) {
            // the building asked about: plain (its near cells alone), hatched before its answer
            detail = (detail & 1U) | (options.hatched ? (shaders::RANK_ASKED >> shaders::RANK_DETAIL_SHIFT) : 0U);
        }
        table_[row] = shaders::rank_entry(first, detail);
        at += items.slots(row, band);
    }
    slots_ = static_cast<std::uint32_t>(std::min<std::uint64_t>(at, shaders::PAINTER_SLOTS));
}

} // namespace atlas::frame
