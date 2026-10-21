// Frames read back as files and numbers (gpu/Offscreen.hpp): 8 bits a channel from the image's packing, a
// hash, a PPM to look at, a comparison.
#include "gpu/Offscreen.hpp"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>

namespace atlas::gpu {

std::uint32_t words_per_pixel(vk::Format format) noexcept {
    switch (format) {
    case vk::Format::eR8G8B8A8Unorm:
    case vk::Format::eB8G8R8A8Unorm:
    case vk::Format::eA2B10G10R10UnormPack32:
    case vk::Format::eA2R10G10B10UnormPack32: return 1;
    case vk::Format::eR16G16B16A16Unorm: return 2;
    default: return 0;
    }
}

Pixels to_rgba8(const Packed& packed) {
    Pixels out{.width = packed.width, .height = packed.height, .rgba = {}};
    const std::size_t n = packed.words.size();
    switch (packed.format) {
    case vk::Format::eR16G16B16A16Unorm:
        out.rgba.resize(n * 2);
        for (std::size_t i = 0; i < n; ++i) {
            out.rgba[2 * i] = level8_of16(packed.words[i] & 0xFFFFU);
            out.rgba[2 * i + 1] = level8_of16(packed.words[i] >> 16);
        }
        return out;
    case vk::Format::eR8G8B8A8Unorm:
        out.rgba.resize(n * 4);
        std::memcpy(out.rgba.data(), packed.words.data(), out.rgba.size());
        return out;
    case vk::Format::eB8G8R8A8Unorm:
        out.rgba.resize(n * 4);
        std::memcpy(out.rgba.data(), packed.words.data(), out.rgba.size());
        for (std::size_t i = 0; i < out.rgba.size(); i += 4) std::swap(out.rgba[i], out.rgba[i + 2]);
        return out;
    case vk::Format::eA2B10G10R10UnormPack32:
    case vk::Format::eA2R10G10B10UnormPack32: {
        // The first channel in bits 0-9: R in A2B10G10R10, B in A2R10G10B10.
        const bool red_first = packed.format == vk::Format::eA2B10G10R10UnormPack32;
        out.rgba.resize(n * 4);
        for (std::size_t i = 0; i < n; ++i) {
            const std::uint32_t w = packed.words[i];
            const std::uint8_t first = level8(w & 0x3FFU);
            const std::uint8_t green = level8((w >> 10) & 0x3FFU);
            const std::uint8_t third = level8((w >> 20) & 0x3FFU);
            out.rgba[4 * i] = red_first ? first : third;
            out.rgba[4 * i + 1] = green;
            out.rgba[4 * i + 2] = red_first ? third : first;
            out.rgba[4 * i + 3] = static_cast<std::uint8_t>(85U * (w >> 30));
        }
        return out;
    }
    default: return out;
    }
}

double share_between_levels(const Packed& packed) {
    if (!gpu::ten_bit(packed.format) || packed.words.empty()) return 0.0;
    std::size_t between = 0;
    for (const std::uint32_t w : packed.words) {
        for (const std::uint32_t v : {w & 0x3FFU, (w >> 10) & 0x3FFU, (w >> 20) & 0x3FFU})
            between += level10(level8(v)) != v ? 1 : 0;
    }
    return static_cast<double>(between) / (3.0 * static_cast<double>(packed.words.size()));
}

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

Difference compare(const Pixels& a, const Pixels& b, int levels, bool alpha) {
    Difference d;
    if (a.width != b.width || a.height != b.height || a.rgba.size() != b.rgba.size())
        return {.share = 1.0, .largest = 255};
    const std::size_t channels = alpha ? 4 : 3;
    std::size_t over = 0;
    for (std::size_t i = 0; i < a.rgba.size(); i += 4) {
        int worst = 0;
        for (std::size_t c = 0; c < channels; ++c)
            worst = std::max(worst, std::abs(int{a.rgba[i + c]} - int{b.rgba[i + c]}));
        d.largest = std::max(d.largest, worst);
        if (worst > levels) ++over;
    }
    d.share = a.rgba.empty() ? 0.0 : static_cast<double>(over) / (static_cast<double>(a.rgba.size()) / 4.0);
    return d;
}

} // namespace atlas::gpu
