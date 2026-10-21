#include "labels/Sprites.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cstring>
#include <format>
#include <limits>
#include <numeric>

namespace atlas::labels {

std::string_view describe(SpriteError error) noexcept {
    switch (error) {
    case SpriteError::size: return "an image's pixels are not its width times its height, or a label has no image";
    case SpriteError::large: return "an image is larger than 2048 px on a side";
    case SpriteError::budget: return "the labels' images exceed their 16 MB";
    case SpriteError::points: return "a label has no points, or more than 1024";
    case SpriteError::scale: return "the large images' scale is not 0 or from 1 to 4, or 0 with a large image";
    }
    return "?";
}

namespace {

/// Whether `scale` is one the large images may be drawn at: 0 (none), or from 1 to max_large_scale.
[[nodiscard]] bool large_scale_valid(float scale) noexcept {
    return scale == 0.0F || (scale >= 1.0F && scale <= LabelSet::max_large_scale);
}

/// Whether `image`'s pixels are its width x height, empty for none.
[[nodiscard]] bool sized(const SpriteImage& image) noexcept {
    return image.pixels.size() == std::uint64_t{image.width} * image.height &&
           (image.width == 0) == (image.height == 0);
}

} // namespace

std::expected<std::shared_ptr<const LabelSet>, SpriteError>
LabelSet::make(std::vector<LabelSprite> sprites, std::uint64_t revision, float least, float large_scale) {
    if (!large_scale_valid(large_scale)) return std::unexpected(SpriteError::scale);
    std::uint64_t own = 0;
    for (const LabelSprite& s : sprites) {
        if (s.own.empty() || !sized(s.own) || !sized(s.large)) return std::unexpected(SpriteError::size);
        if (std::max({s.own.width, s.own.height, s.large.width, s.large.height}) > max_sprite_side) {
            return std::unexpected(SpriteError::large);
        }
        if (large_scale == 0.0F && !s.large.empty()) return std::unexpected(SpriteError::scale);
        if (s.points.empty() || s.points.size() > max_points) return std::unexpected(SpriteError::points);
        own += s.own.pixels.size();
        if (own > max_texels) return std::unexpected(SpriteError::budget);
    }
    // The large images by priority, highest first, while the set stays within its budget; the others are let go.
    std::vector<std::uint32_t> order(sprites.size());
    std::ranges::iota(order, 0U);
    std::ranges::stable_sort(order, std::ranges::greater{}, [&](std::uint32_t i) { return sprites[i].priority; });
    std::uint64_t total = own;
    for (const std::uint32_t i : order) {
        SpriteImage& large = sprites[i].large;
        if (total + large.pixels.size() <= max_all_texels) {
            total += large.pixels.size();
        } else {
            large = SpriteImage{};
        }
    }
    std::shared_ptr<LabelSet> set(new LabelSet());
    set->revision_ = revision;
    set->least_ = least > 0.0F ? least : 0.0F;
    set->large_scale_ = large_scale;
    set->offsets_.reserve(sprites.size());
    set->texels_.reserve(static_cast<std::size_t>(total));
    const auto keep = [&](SpriteImage& image) {
        if (image.empty()) return std::uint32_t{0};
        const auto at = static_cast<std::uint32_t>(set->texels_.size());
        set->texels_.insert(set->texels_.end(), image.pixels.begin(), image.pixels.end());
        image.pixels.clear();
        image.pixels.shrink_to_fit(); // the set keeps one copy of each texel
        return at;
    };
    for (LabelSprite& s : sprites) {
        const std::uint32_t at = keep(s.own);
        set->offsets_.push_back({at, keep(s.large)});
    }
    set->sprites_ = std::move(sprites);
    return std::shared_ptr<const LabelSet>(std::move(set));
}

std::expected<LabelBlock, std::string> read_label_block(std::span<const std::byte> block) {
    std::size_t at = 0;
    const auto take = [&](void* out, std::size_t bytes) {
        if (block.size() - at < bytes) return false;
        if (bytes > 0) std::memcpy(out, block.data() + at, bytes); // an empty image's data() may be null
        at += bytes;
        return true;
    };
    std::uint32_t magic = 0;
    std::uint32_t count = 0;
    LabelBlock out;
    if (!take(&magic, 4) || magic != label_block_magic) return std::unexpected("not a block of label sprites");
    if (!take(&count, 4) || !take(&out.large_scale, 4)) return std::unexpected("the block ends before its count");
    out.sprites.reserve(std::min<std::size_t>(count, block.size() / 60));
    const auto too_large = [](const SpriteImage& image) {
        return image.width > LabelSet::max_sprite_side || image.height > LabelSet::max_sprite_side;
    };
    for (std::uint32_t i = 0; i < count; ++i) {
        LabelSprite s;
        std::uint32_t points = 0;
        if (!take(&s.id, 4) || !take(&s.priority, 4) || !take(&s.measures.half, 4) || !take(&s.measures.pad, 4) ||
            !take(&s.measures.text_height, 4) || !take(&s.measures.baseline, 4) || !take(&s.own.width, 4) ||
            !take(&s.own.height, 4) || !take(&s.own.anchor_x, 4) || !take(&s.own.anchor_y, 4) || !take(&points, 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s head", i));
        }
        if (points > LabelSet::max_points || too_large(s.own)) {
            return std::unexpected(std::format("sprite {} is larger than a sprite may be", i));
        }
        s.points.resize(points);
        for (std::array<double, 2>& p : s.points) {
            if (!take(p.data(), 16)) return std::unexpected(std::format("the block ends in sprite {}'s points", i));
        }
        s.own.pixels.resize(std::size_t{s.own.width} * s.own.height);
        if (!take(s.own.pixels.data(), s.own.pixels.size() * 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s pixels", i));
        }
        if (!take(&s.large.width, 4) || !take(&s.large.height, 4) || !take(&s.large.anchor_x, 4) ||
            !take(&s.large.anchor_y, 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s large image", i));
        }
        if (too_large(s.large)) return std::unexpected(std::format("sprite {} is larger than a sprite may be", i));
        s.large.pixels.resize(std::size_t{s.large.width} * s.large.height);
        if (!take(s.large.pixels.data(), s.large.pixels.size() * 4)) {
            return std::unexpected(std::format("the block ends in sprite {}'s large image", i));
        }
        out.sprites.push_back(std::move(s));
    }
    if (at != block.size()) return std::unexpected("the block runs on past its sprites");
    return out;
}

SpriteAt sprite_at(const SpriteImage& image, const Drawn& drawn, float image_scale) noexcept {
    // Kotlin's Float.roundToInt: Math.round of the exact value (halves up), as LabelPainter rounds its text's place.
    const auto round = [](float v) {
        const std::int64_t r = pymath::java::round(static_cast<double>(v));
        constexpr std::int64_t lo = std::numeric_limits<std::int32_t>::min();
        constexpr std::int64_t hi = std::numeric_limits<std::int32_t>::max();
        return static_cast<std::int32_t>(r < lo ? lo : (r > hi ? hi : r));
    };
    const auto w = static_cast<float>(image.width);
    const auto h = static_cast<float>(image.height);
    if (drawn.scale == 1.0F && image_scale == 1.0F) {
        return {.x = static_cast<float>(round(drawn.stand.x) - image.anchor_x),
                .y = static_cast<float>(round(drawn.baseline) - image.anchor_y),
                .width = w,
                .height = h};
    }
    const float k = drawn.scale / image_scale;
    return {.x = drawn.stand.x - (static_cast<float>(image.anchor_x) * k),
            .y = drawn.baseline - (static_cast<float>(image.anchor_y) * k),
            .width = w * k,
            .height = h * k,
            .scale = k};
}

ImageAt image_at(const LabelSprite& sprite, float large_scale, const Drawn& drawn) noexcept {
    if (drawn.scale > 1.0F && large_scale > 0.0F && !sprite.large.empty()) {
        return {.image = 1, .at = sprite_at(sprite.large, drawn, large_scale)};
    }
    return {.image = 0, .at = sprite_at(sprite.own, drawn)};
}

} // namespace atlas::labels
