// The stand-in's city (plan-vector K1): 50,176 lit boxes on a 13 m grid, 501,760 triangles in all, with a canal across
// it, tree crowns along its quays and in its squares, and the painter's order of every item per frame, kept as the
// engine will keep it (N3): the reference points projected onto the view direction, the previous frame's order fixed
// by a stable insertion pass (pans and zooms leave it as it is; turns and tilts move few items), ranks written from it.
#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::standin {

/// A box as the shaders read it (standin.slang's Box, 32 bytes).
struct Box {
    float cx, cz; // centre, metres east and south of the city's centre
    float hx, hz; // half sizes
    float base;   // the ground's height under it
    float height;
    std::uint32_t wall; // RGBA8
    std::uint32_t roof;
};
static_assert(sizeof(Box) == 32);

/// A tree as the shaders read it (standin.slang's Tree, 32 bytes).
struct Tree {
    float x, z;
    float radius;
    float height;
    std::uint32_t colour;
    std::uint32_t sprite; // its cell of the crown atlas
    std::uint32_t item;   // its slot in the ranks (after every box)
    std::uint32_t pad;
};
static_assert(sizeof(Tree) == 32);

/// The city: its boxes and trees, and the canal.
struct City {
    std::vector<Box> boxes;
    std::vector<Tree> trees;
    std::vector<std::uint32_t> waterside; // the boxes beside the canal, mirrored in its water
    double extent = 0;                    // half the ground's side, metres
    double canal_z = 0;                   // the canal's centre line
    double canal_half = 0;                // its half width

    /// The city of `columns` × `columns` cells of `spacing` metres (224 and 13 by default: 501,760 box triangles).
    [[nodiscard]] static City generate(std::uint32_t columns = 224, double spacing = 13.0,
                                       std::uint64_t seed = 0x9e3779b97f4a7c15ULL);

    [[nodiscard]] std::uint32_t items() const noexcept {
        return static_cast<std::uint32_t>(boxes.size() + trees.size());
    }
    [[nodiscard]] std::uint64_t box_triangles() const noexcept { return boxes.size() * 10; }
};

/// The painter's order of the city's items for a view.
class Ranks {
public:
    explicit Ranks(const City& city);

    /// Orders the items for an eye at `eye` looking along `forward` (unit): farther first, by each item's reference
    /// point (its centre at half its height), stably, starting from the last order. Returns how many swaps it made.
    std::uint64_t update(const double eye[3], const double forward[3]);

    /// Per item, its place in the painter's order (0 the farthest).
    [[nodiscard]] std::span<const std::uint32_t> ranks() const noexcept { return ranks_; }
    /// The boxes front to back (the order opaque objects are drawn in, so early depth tests reject the most).
    [[nodiscard]] std::span<const std::uint32_t> front_to_back() const noexcept { return front_; }

private:
    std::vector<double> points_; // x, y, z per item
    std::vector<double> keys_;
    std::vector<std::uint32_t> order_; // items, farthest first
    std::vector<std::uint32_t> ranks_;
    std::vector<std::uint32_t> front_;
    std::uint32_t boxes_ = 0;
    double last_[3] = {0, 0, 0}; // the view direction of the last update
    bool sorted_ = false;
};

/// The boxes within `radius` metres of (x, z) (the renders' radius cut, D9): the shadows' casters.
void within(const City& city, double x, double z, double radius, std::vector<std::uint32_t>& out);

} // namespace atlas::standin
