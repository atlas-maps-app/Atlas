// The world's names' sprites (labels/Names.hpp).
#include "labels/Names.hpp"

#include <algorithm>

namespace atlas::labels {

namespace {

[[nodiscard]] bool sized(const SpriteImage& image) noexcept {
    return image.pixels.size() == std::uint64_t{image.width} * image.height &&
           (image.width == 0) == (image.height == 0);
}

[[nodiscard]] std::uint64_t texels_of(const LabelSprite& s) noexcept {
    return s.own.pixels.size() + s.large.pixels.size();
}

} // namespace

const NameSprite* NameStore::find(std::uint32_t id, double seconds) noexcept {
    const auto at = sprites_.find(id);
    if (at == sprites_.end()) return nullptr;
    at->second.shown = seconds;
    return &at->second;
}

std::vector<NameWant> NameStore::ask(double seconds) {
    std::vector<NameWant> out;
    for (const NameWant& w : wanted_) {
        if (out.size() >= most_asked) break;
        if (sprites_.contains(w.id)) continue;
        const auto at = asked_.find(w.id);
        if (at != asked_.end() && seconds - at->second < ask_again_seconds) continue;
        asked_[w.id] = seconds;
        out.push_back(w);
    }
    // the asked-for of long ago are forgotten (a name no longer wanted)
    std::erase_if(asked_, [seconds](const auto& a) { return seconds - a.second >= 4.0 * ask_again_seconds; });
    return out;
}

std::expected<void, SpriteError> NameStore::give(std::vector<LabelSprite> sprites, float large_scale, float floor_dp,
                                                 double seconds) {
    if (large_scale != 0.0F && (large_scale < 1.0F || large_scale > LabelSet::max_large_scale)) {
        return std::unexpected(SpriteError::scale);
    }
    for (const LabelSprite& s : sprites) {
        if (!sized(s.own) || !sized(s.large)) return std::unexpected(SpriteError::size);
        if (std::max({s.own.width, s.own.height, s.large.width, s.large.height}) > LabelSet::max_sprite_side) {
            return std::unexpected(SpriteError::large);
        }
        if ((large_scale == 0.0F || s.own.empty()) && !s.large.empty()) return std::unexpected(SpriteError::scale);
    }
    if (large_scale != large_scale_) {
        // another density's sprites: those of before are not this map's any more
        sprites_.clear();
        texels_ = 0;
        skipped_ = 0;
        large_scale_ = large_scale;
    }
    floor_dp_ = floor_dp > 0.0F ? floor_dp : 0.0F;
    for (LabelSprite& s : sprites) {
        const std::uint32_t id = s.id;
        if (const auto old = sprites_.find(id); old != sprites_.end()) {
            texels_ -= texels_of(old->second.sprite);
            if (old->second.skipped) --skipped_;
            sprites_.erase(old);
        }
        NameSprite kept{.serial = ++serial_, .skipped = s.own.empty(), .shown = seconds};
        texels_ += texels_of(s);
        if (kept.skipped) ++skipped_;
        kept.sprite = std::move(s);
        sprites_.emplace(id, std::move(kept));
        asked_.erase(id);
    }
    // within the budget: the least lately shown go (never one handed or shown at this very time)
    while (texels_ > max_texels) {
        auto oldest = sprites_.end();
        for (auto at = sprites_.begin(); at != sprites_.end(); ++at) {
            if (at->second.skipped || at->second.shown >= seconds) continue;
            if (oldest == sprites_.end() || at->second.shown < oldest->second.shown) oldest = at;
        }
        if (oldest == sprites_.end()) break;
        texels_ -= texels_of(oldest->second.sprite);
        sprites_.erase(oldest);
    }
    return {};
}

void NameStore::reset() noexcept {
    sprites_.clear();
    asked_.clear();
    wanted_.clear();
    texels_ = 0;
    skipped_ = 0;
    large_scale_ = 0.0F;
    floor_dp_ = 0.0F;
}

} // namespace atlas::labels
