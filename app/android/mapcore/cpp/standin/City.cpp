// The stand-in's city and its painter's order (standin/City.hpp).
#include "standin/City.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace atlas::standin {

namespace {

/// xorshift64*: a small, fast, deterministic stream (the city is the same on every device).
class Stream {
public:
    explicit Stream(std::uint64_t seed) noexcept : state_(seed == 0 ? 1 : seed) {}
    std::uint64_t next() noexcept {
        state_ ^= state_ >> 12;
        state_ ^= state_ << 25;
        state_ ^= state_ >> 27;
        return state_ * 0x2545F4914F6CDD1DULL;
    }
    /// Uniform in [0, 1).
    double unit() noexcept { return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0); }

private:
    std::uint64_t state_;
};

[[nodiscard]] constexpr std::uint32_t rgba(std::uint32_t r, std::uint32_t g, std::uint32_t b) noexcept {
    return r | (g << 8) | (b << 16) | 0xFF000000U;
}

// Walls and roofs in the renders' manner: brick, ochre, plaster and grey; tiles, slate and lead.
constexpr std::array walls{rgba(150, 82, 62),   rgba(171, 104, 72),  rgba(120, 66, 52), rgba(196, 160, 112),
                           rgba(214, 206, 190), rgba(160, 156, 150), rgba(110, 72, 58), rgba(186, 120, 86)};
constexpr std::array roofs{rgba(120, 70, 56), rgba(92, 88, 86), rgba(136, 128, 120), rgba(148, 84, 60),
                           rgba(70, 72, 76)};

} // namespace

City City::generate(std::uint32_t columns, double spacing, std::uint64_t seed) {
    City city;
    Stream random(seed);
    const double half = columns * spacing * 0.5;
    city.extent = half * 1.25;
    city.canal_z = spacing * 6.5;
    city.canal_half = spacing * 1.4;
    city.boxes.reserve(static_cast<std::size_t>(columns) * columns);
    for (std::uint32_t row = 0; row < columns; ++row) {
        for (std::uint32_t col = 0; col < columns; ++col) {
            const double cx = (col + 0.5) * spacing - half;
            const double cz = (row + 0.5) * spacing - half;
            const double w = spacing * (0.30 + 0.12 * random.unit());
            const double d = spacing * (0.30 + 0.12 * random.unit());
            // Mostly four to six floors, a few towers.
            const double u = random.unit();
            const double height = u < 0.92 ? 9.0 + 12.0 * random.unit() : 22.0 + 18.0 * random.unit();
            const double z = cz;
            const bool by_water = std::abs(z - city.canal_z) < city.canal_half + spacing;
            city.boxes.push_back({.cx = static_cast<float>(cx),
                                  .cz = static_cast<float>(z),
                                  .hx = static_cast<float>(w),
                                  .hz = static_cast<float>(d),
                                  .base = 0.0F,
                                  .height = static_cast<float>(height),
                                  .wall = walls[random.next() % walls.size()],
                                  .roof = roofs[random.next() % roofs.size()]});
            if (by_water) city.waterside.push_back(static_cast<std::uint32_t>(city.boxes.size() - 1));
        }
    }
    // Crowns along the quays and in a scatter of squares: 2,048 trees.
    const auto boxes = static_cast<std::uint32_t>(city.boxes.size());
    for (std::uint32_t i = 0; i < 2048; ++i) {
        const double along = (random.unit() * 2.0 - 1.0) * half;
        const bool quay = i < 1024;
        const double x = quay ? along : (random.unit() * 2.0 - 1.0) * half;
        const double z = quay ? city.canal_z + ((i & 1U) != 0 ? 1.0 : -1.0) * (city.canal_half + 2.0)
                              : std::floor((random.unit() * 2.0 - 1.0) * half / spacing) * spacing;
        city.trees.push_back({.x = static_cast<float>(x),
                              .z = static_cast<float>(z),
                              .radius = static_cast<float>(3.0 + 3.0 * random.unit()),
                              .height = static_cast<float>(9.0 + 7.0 * random.unit()),
                              .colour = rgba(96 + static_cast<std::uint32_t>(random.next() % 40),
                                             128 + static_cast<std::uint32_t>(random.next() % 40), 72),
                              .sprite = static_cast<std::uint32_t>(random.next() % 256),
                              .item = boxes + i,
                              .pad = 0});
    }
    return city;
}

Ranks::Ranks(const City& city) : boxes_(static_cast<std::uint32_t>(city.boxes.size())) {
    const std::uint32_t items = city.items();
    points_.reserve(static_cast<std::size_t>(items) * 3);
    for (const Box& b : city.boxes)
        points_.insert(points_.end(),
                       {static_cast<double>(b.cx), static_cast<double>(b.base) + static_cast<double>(b.height) * 0.5,
                        static_cast<double>(b.cz)});
    for (const Tree& t : city.trees)
        points_.insert(points_.end(),
                       {static_cast<double>(t.x), static_cast<double>(t.height) * 0.6, static_cast<double>(t.z)});
    keys_.resize(items);
    order_.resize(items);
    for (std::uint32_t i = 0; i < items; ++i) order_[i] = i;
    ranks_.resize(items);
    front_.resize(boxes_);
}

std::uint64_t Ranks::update(const double eye[3], const double forward[3]) {
    const std::size_t n = keys_.size();
    for (std::size_t i = 0; i < n; ++i) {
        const double* p = &points_[3 * i];
        keys_[i] = (p[0] - eye[0]) * forward[0] + (p[1] - eye[1]) * forward[1] + (p[2] - eye[2]) * forward[2];
    }
    // A stable insertion pass from the last frame's order: farther (larger key) first. A pan or a zoom moves every
    // key by the same amount and makes no swap; a turn of a fraction of a degree moves few items a few places. A
    // first frame, or a jump of the view by more than two degrees, sorts afresh instead.
    std::uint64_t swaps = 0;
    const double turned = forward[0] * last_[0] + forward[1] * last_[1] + forward[2] * last_[2];
    if (!sorted_ || turned < 0.99939) { // cos 2°
        std::ranges::stable_sort(order_, [&](std::uint32_t a, std::uint32_t b) { return keys_[a] > keys_[b]; });
        swaps = n;
        sorted_ = true;
    } else {
        for (std::size_t i = 1; i < n; ++i) {
            const std::uint32_t item = order_[i];
            const double key = keys_[item];
            std::size_t j = i;
            while (j > 0 && keys_[order_[j - 1]] < key) {
                order_[j] = order_[j - 1];
                --j;
            }
            swaps += i - j;
            order_[j] = item;
        }
    }
    last_[0] = forward[0];
    last_[1] = forward[1];
    last_[2] = forward[2];
    std::size_t front = 0;
    for (std::size_t k = 0; k < n; ++k) {
        const std::uint32_t item = order_[k];
        ranks_[item] = static_cast<std::uint32_t>(k);
        const std::uint32_t back = order_[n - 1 - k];
        if (back < boxes_) front_[front++] = back;
    }
    return swaps;
}

void within(const City& city, double x, double z, double radius, std::vector<std::uint32_t>& out) {
    out.clear();
    const double r2 = radius * radius;
    for (std::uint32_t i = 0; i < city.boxes.size(); ++i) {
        const double dx = static_cast<double>(city.boxes[i].cx) - x;
        const double dz = static_cast<double>(city.boxes[i].cz) - z;
        if (dx * dx + dz * dz <= r2) out.push_back(i);
    }
}

} // namespace atlas::standin
