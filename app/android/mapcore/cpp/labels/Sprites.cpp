#include "labels/Sprites.hpp"

#include "base/pymath/PyMath.hpp"

#include <cstring>
#include <format>
#include <limits>

namespace atlas::labels {

std::string_view describe(SpriteError error) noexcept {
    switch (error) {
    case SpriteError::size: return "a sprite's pixels are not its width times its height, or it is empty";
    case SpriteError::large: return "a sprite is larger than 2048 px on a side";
    case SpriteError::budget: return "the sprites' texels exceed the labels' 16 MB";
    case SpriteError::points: return "a label has no points, or more than 1024";
    }
    return "?";
}

std::expected<std::shared_ptr<const LabelSet>, SpriteError> LabelSet::make(std::vector<LabelSprite> sprites,
                                                                           std::uint64_t revision, float least) {
    std::uint64_t total = 0;
    for (const LabelSprite& s : sprites) {
        if (s.width == 0 || s.height == 0 || s.pixels.size() != std::uint64_t{s.width} * s.height) {
            return std::unexpected(SpriteError::size);
        }
        if (s.width > max_sprite_side || s.height > max_sprite_side) return std::unexpected(SpriteError::large);
        if (s.points.empty() || s.points.size() > max_points) return std::unexpected(SpriteError::points);
        total += s.pixels.size();
        if (total > max_texels) return std::unexpected(SpriteError::budget);
    }
    std::shared_ptr<LabelSet> set(new LabelSet());
    set->revision_ = revision;
    set->least_ = least > 0.0F ? least : 0.0F;
    set->offsets_.reserve(sprites.size());
    set->texels_.reserve(static_cast<std::size_t>(total));
    for (LabelSprite& s : sprites) {
        set->offsets_.push_back(static_cast<std::uint32_t>(set->texels_.size()));
        set->texels_.insert(set->texels_.end(), s.pixels.begin(), s.pixels.end());
        s.pixels.clear();
        s.pixels.shrink_to_fit(); // the set keeps one copy of each texel
    }
    set->sprites_ = std::move(sprites);
    return std::shared_ptr<const LabelSet>(std::move(set));
}

std::expected<std::vector<LabelSprite>, std::string> read_label_block(std::span<const std::byte> block) {
    std::size_t at = 0;
    const auto take = [&](void* out, std::size_t bytes) {
        if (block.size() - at < bytes) return false;
        std::memcpy(out, block.data() + at, bytes);
        at += bytes;
        return true;
    };
    std::uint32_t magic = 0;
    std::uint32_t count = 0;
    if (!take(&magic, 4) || magic != label_block_magic) return std::unexpected("not a block of label sprites");
    if (!take(&count, 4)) return std::unexpected("the block ends before its count");
    std::vector<LabelSprite> out;
    out.reserve(std::min<std::size_t>(count, block.size() / 44));
    for (std::uint32_t i = 0; i < count; ++i) {
        LabelSprite s;
        std::uint32_t points = 0;
        if (!take(&s.id, 4) || !take(&s.priority, 4) || !take(&s.measures.half, 4) || !take(&s.measures.pad, 4) ||
            !take(&s.measures.text_height, 4) || !take(&s.measures.baseline, 4) || !take(&s.width, 4) ||
            !take(&s.height, 4) || !take(&s.anchor_x, 4) || !take(&s.anchor_y, 4) || !take(&points, 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s head", i));
        }
        if (points > LabelSet::max_points || s.width > LabelSet::max_sprite_side ||
            s.height > LabelSet::max_sprite_side) {
            return std::unexpected(std::format("sprite {} is larger than a sprite may be", i));
        }
        s.points.resize(points);
        for (std::array<double, 2>& p : s.points) {
            if (!take(p.data(), 16)) return std::unexpected(std::format("the block ends in sprite {}'s points", i));
        }
        s.pixels.resize(std::size_t{s.width} * s.height);
        if (!take(s.pixels.data(), s.pixels.size() * 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s pixels", i));
        }
        out.push_back(std::move(s));
    }
    if (at != block.size()) return std::unexpected("the block runs on past its sprites");
    return out;
}

SpriteAt sprite_at(const LabelSprite& sprite, const Drawn& drawn) noexcept {
    // Kotlin's Float.roundToInt: Math.round of the exact value (halves up), as LabelPainter rounds its text's place.
    const auto round = [](float v) {
        const std::int64_t r = pymath::java::round(static_cast<double>(v));
        constexpr std::int64_t lo = std::numeric_limits<std::int32_t>::min();
        constexpr std::int64_t hi = std::numeric_limits<std::int32_t>::max();
        return static_cast<std::int32_t>(r < lo ? lo : (r > hi ? hi : r));
    };
    const auto w = static_cast<float>(sprite.width);
    const auto h = static_cast<float>(sprite.height);
    if (drawn.scale == 1.0F) {
        return {.x = static_cast<float>(round(drawn.stand.x) - sprite.anchor_x),
                .y = static_cast<float>(round(drawn.baseline) - sprite.anchor_y),
                .width = w,
                .height = h};
    }
    const float k = drawn.scale;
    return {.x = drawn.stand.x - (static_cast<float>(sprite.anchor_x) * k),
            .y = drawn.baseline - (static_cast<float>(sprite.anchor_y) * k),
            .width = w * k,
            .height = h * k,
            .scale = k};
}

} // namespace atlas::labels
