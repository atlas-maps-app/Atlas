#include "sun/BitTiles.hpp"

#include "base/pymath/PyMath.hpp"

#include <limits>
#include <utility>

namespace atlas::sun {

double fade_weight(double swapped, double t) noexcept {
    const double w = (t - swapped) / fade_seconds;
    return pymath::max(0.0, pymath::min(1.0, w));
}

void BitTiles::publish(tiles::TileKey key, TileBits bits, double t) {
    std::vector<std::pair<tiles::TileKey, TileBits>> one;
    one.emplace_back(key, std::move(bits));
    publish(std::move(one), t);
}

void BitTiles::publish(std::vector<std::pair<tiles::TileKey, TileBits>> run, double t) {
    if (run.empty()) return;
    std::function<void()> published;
    {
        const std::lock_guard lock(mutex_);
        published = on_publish_;
        for (auto& [key, bits] : run) {
            auto fresh = std::make_shared<const TileBits>(std::move(bits));
            Entry& e = tiles_[key.packed()];
            if (!e.now) {
                e.before = fresh;
                e.now = std::move(fresh);
                e.swapped = -std::numeric_limits<double>::infinity(); // a tile's first bits show at once
            } else {
                e.before = std::move(e.now);
                e.now = std::move(fresh);
                e.swapped = t;
            }
        }
    }
    if (published) published();
}

void BitTiles::on_publish(std::function<void()> callback) {
    const std::lock_guard lock(mutex_);
    on_publish_ = std::move(callback);
}

std::optional<TileView> BitTiles::view(tiles::TileKey key, double t) const {
    const std::lock_guard lock(mutex_);
    const auto it = tiles_.find(key.packed());
    if (it == tiles_.end() || !it->second.now) return std::nullopt;
    const Entry& e = it->second;
    const double w = fade_weight(e.swapped, t);
    return TileView{.now = e.now, .before = w >= 1.0 ? e.now : e.before, .weight = w};
}

void BitTiles::forget(tiles::TileKey key) {
    const std::lock_guard lock(mutex_);
    tiles_.erase(key.packed());
}

std::size_t BitTiles::size() const {
    const std::lock_guard lock(mutex_);
    return tiles_.size();
}

std::optional<double> BitTiles::texels(tiles::TileKey key, double t, std::vector<std::uint32_t>& out) const {
    const std::optional<TileView> v = view(key, t);
    if (!v) return std::nullopt;
    const TileBits& now = *v->now;
    const TileBits& before = *v->before;
    const std::uint32_t texels = (now.count + 31) / 32;
    for (std::uint32_t i = 0; i < texels; ++i) {
        const auto word = [i](const TileBits& b) -> std::uint32_t {
            const std::size_t w = i / 2;
            if (w >= b.words.size()) return 0;
            return static_cast<std::uint32_t>(b.words[w] >> (32U * (i % 2)));
        };
        out.push_back(word(now));
        out.push_back(before.count == now.count ? word(before) : word(now));
    }
    return v->weight;
}

} // namespace atlas::sun
