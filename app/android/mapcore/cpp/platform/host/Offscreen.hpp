// The host build's output (plan-vector T10, K1): frames drawn into offscreen images instead of a swapchain, read back
// to memory for the tests (identical across runs and frame routes), the likeness harness's candidates and VK check's
// twin later. The images take the frame's colour as a phone's swapchain does (gpu/Facts.hpp): R8G8B8A8_UNORM by
// default, or where asked (Depth::deepest, as VK bench's colour stage asks) 10 bits a channel where the device draws
// the frame in them (colour_offscreen, as if a surface offered every format; lavapipe does); the frame leaves them
// ready to copy. A frame reads back at 8 bits a channel either way, a 10-bit one converted exactly (to_rgba8), so its
// bytes, its hash and the likeness gates see 8 bits.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace atlas::platform {

/// A frame's pixels, top row first, four bytes (R, G, B, A) each.
struct Pixels {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint8_t> rgba;

    [[nodiscard]] bool operator==(const Pixels&) const = default;
};

/// A frame as its image holds it, top row first, in the format's own packing (32-bit words in the host's byte order):
/// one word a pixel of R8G8B8A8 or B8G8R8A8 bytes, or of A2B10G10R10 or A2R10G10B10 (the first channel in bits 0-9);
/// two of R16G16B16A16 (the tests' reference at 16 bits a channel).
struct Packed {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    vk::Format format = vk::Format::eUndefined;
    std::vector<std::uint32_t> words;
};

/// The 32-bit words a pixel of `format` takes (1, 2 for R16G16B16A16_UNORM; 0 for a format the host does not read).
[[nodiscard]] std::uint32_t words_per_pixel(vk::Format format) noexcept;

/// A 10-bit channel's nearest 8-bit level: round(v · 255 / 1023), in integers and exact. It is never half way, so no
/// rule for ties enters: v · 85 / 341 = k + 1/2 would need 170 · v = 341 · (2k + 1), whose sides differ in parity
/// wherever 341 divides v.
[[nodiscard]] constexpr std::uint8_t level8(std::uint32_t v10) noexcept {
    return static_cast<std::uint8_t>((510U * v10 + 1023U) / 2046U);
}

/// The 10-bit value nearest an 8-bit level: round(v · 1023 / 255), never half way either (682 · v = 85 · (2k + 1)
/// differs in parity wherever 85 divides v). level8(level10(v)) is v for every level.
[[nodiscard]] constexpr std::uint32_t level10(std::uint8_t v8) noexcept { return (2046U * v8 + 255U) / 510U; }

/// A 16-bit channel's nearest 8-bit level: round(v / 257), never half way (2v = 257 · odd has no solution).
[[nodiscard]] constexpr std::uint8_t level8_of16(std::uint32_t v16) noexcept {
    return static_cast<std::uint8_t>((2U * v16 + 257U) / 514U);
}

/// The frame at 8 bits a channel, R, G, B, A: an 8-bit format's bytes as they are (B8G8R8A8's put in order), a 10-bit
/// channel at its nearest 8-bit level (level8), a 2-bit alpha a at 85 · a, a 16-bit channel at its nearest level
/// (level8_of16). Empty pixels for another format.
[[nodiscard]] Pixels to_rgba8(const Packed& packed);

/// The share of a 10-bit frame's colour channels (R, G, B) that lie between two 8-bit levels: not the 10-bit value of
/// any 8-bit level (level10 of level8), so more than 8 bits hold them. 0 for an 8-bit frame.
[[nodiscard]] double share_between_levels(const Packed& packed);

/// Offscreen images that stand in for a swapchain.
class Offscreen final : public gpu::Output {
public:
    /// `images` images of `extent` in the frame's colour offscreen at `depth` (8 bits by default; at Depth::deepest
    /// 10 where the device draws the frame in them).
    [[nodiscard]] static gpu::Result<std::unique_ptr<Offscreen>> create(const gpu::Device& device, vk::Extent2D extent,
                                                                        std::uint32_t images = 2,
                                                                        gpu::Depth depth = gpu::default_depth);
    /// The same in `format`, which the frame's attachments then take too: the tests' reference at 16 bits a channel
    /// (R16G16B16A16_UNORM), where the device draws the frame in it.
    [[nodiscard]] static gpu::Result<std::unique_ptr<Offscreen>> create(const gpu::Device& device, vk::Extent2D extent,
                                                                        std::uint32_t images, vk::Format format);
    ~Offscreen() override = default;

    [[nodiscard]] vk::Extent2D extent() const noexcept override { return extent_; }
    [[nodiscard]] vk::Format format() const noexcept override { return colour_.format; }
    /// The images' colour, and why (gpu/Facts.hpp's colour_offscreen).
    [[nodiscard]] const gpu::Colour& colour() const noexcept { return colour_; }
    [[nodiscard]] std::span<const vk::Image> images() const noexcept override { return handles_; }
    [[nodiscard]] std::span<const vk::ImageView> views() const noexcept override { return view_handles_; }
    [[nodiscard]] vk::ImageLayout final_layout() const noexcept override {
        return vk::ImageLayout::eTransferSrcOptimal;
    }
    [[nodiscard]] bool transfer_source() const noexcept override { return true; }
    [[nodiscard]] gpu::Acquired acquire(vk::Semaphore acquired, std::uint32_t& index) override;
    [[nodiscard]] bool acquire_signals() const noexcept override { return false; }
    [[nodiscard]] vk::Semaphore rendered(std::uint32_t /*index*/) const noexcept override { return {}; }
    [[nodiscard]] gpu::Acquired present(std::uint32_t index, std::uint64_t frame, std::uint64_t desired_ns) override;

    /// The image the last presented frame went to.
    [[nodiscard]] std::uint32_t latest() const noexcept { return latest_; }
    /// Copies image `index` to memory at 8 bits a channel (to_rgba8); the frames that drew it must have finished
    /// (Renderer::drain).
    [[nodiscard]] gpu::Result<Pixels> read(std::uint32_t index) const;
    /// Copies image `index` to memory as the image holds it.
    [[nodiscard]] gpu::Result<Packed> read_packed(std::uint32_t index) const;
    /// Fills every image with one colour (a sentinel a frame that never writes its output leaves behind).
    [[nodiscard]] gpu::Result<void> fill(const std::array<float, 4>& rgba) const;

private:
    Offscreen() = default;
    [[nodiscard]] static gpu::Result<std::unique_ptr<Offscreen>> make(const gpu::Device& device, vk::Extent2D extent,
                                                                      std::uint32_t images, gpu::Colour colour);
    const gpu::Device* device_ = nullptr;
    vk::Extent2D extent_;
    gpu::Colour colour_;
    std::vector<gpu::Image> images_;
    std::vector<vk::Image> handles_;
    std::vector<vk::raii::ImageView> views_;
    std::vector<vk::ImageView> view_handles_;
    std::uint32_t next_ = 0;
    std::uint32_t latest_ = 0;
};

/// FNV-1a over the pixels (a frame's hash in logs).
[[nodiscard]] std::uint64_t hash_of(const Pixels& pixels) noexcept;

/// Writes the pixels as a binary PPM (for looking at a test's frame); false when the file cannot be written.
[[nodiscard]] bool write_ppm(const Pixels& pixels, const std::string& path);

/// The share of pixels that differ by more than `levels` in any channel (the colour's alone without `alpha`), and the
/// largest difference.
struct Difference {
    double share = 0;
    int largest = 0;
};
[[nodiscard]] Difference compare(const Pixels& a, const Pixels& b, int levels, bool alpha = true);

} // namespace atlas::platform
