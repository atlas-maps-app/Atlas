// Frames as files and numbers on hosts (platform/host/Offscreen.hpp): a hash, a PPM to look at, a comparison.
#include "platform/host/Offscreen.hpp"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <memory>

namespace atlas::platform {

std::uint64_t hash_of(const Pixels& pixels) noexcept {
    std::uint64_t h = 0xcbf29ce484222325ULL;
    for (const std::uint8_t b : pixels.rgba) {
        h ^= b;
        h *= 0x100000001b3ULL;
    }
    return h;
}

bool write_ppm(const Pixels& pixels, const std::string& path) {
    const std::unique_ptr<std::FILE, int (*)(std::FILE*)> file(std::fopen(path.c_str(), "wb"), &std::fclose);
    if (!file) return false;
    if (std::fprintf(file.get(), "P6\n%u %u\n255\n", pixels.width, pixels.height) < 0) return false;
    std::vector<std::uint8_t> rgb;
    rgb.reserve(static_cast<std::size_t>(pixels.width) * pixels.height * 3);
    for (std::size_t i = 0; i + 3 < pixels.rgba.size(); i += 4)
        rgb.insert(rgb.end(), {pixels.rgba[i], pixels.rgba[i + 1], pixels.rgba[i + 2]});
    return std::fwrite(rgb.data(), 1, rgb.size(), file.get()) == rgb.size();
}

Difference compare(const Pixels& a, const Pixels& b, int levels) {
    Difference d;
    if (a.width != b.width || a.height != b.height || a.rgba.size() != b.rgba.size())
        return {.share = 1.0, .largest = 255};
    std::size_t over = 0;
    for (std::size_t i = 0; i < a.rgba.size(); i += 4) {
        int worst = 0;
        for (std::size_t c = 0; c < 4; ++c) worst = std::max(worst, std::abs(int{a.rgba[i + c]} - int{b.rgba[i + c]}));
        d.largest = std::max(d.largest, worst);
        if (worst > levels) ++over;
    }
    d.share = a.rgba.empty() ? 0.0 : static_cast<double>(over) / (static_cast<double>(a.rgba.size()) / 4.0);
    return d;
}

} // namespace atlas::platform
