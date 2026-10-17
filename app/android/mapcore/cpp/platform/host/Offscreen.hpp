// The host build's output (plan-vector T10, K1): frames drawn into offscreen images instead of a swapchain, read back
// to memory for the tests (identical across runs and frame routes), the likeness harness's candidates and VK check's
// twin later. The images are R8G8B8A8_UNORM, as the swapchain is, and the frame leaves them ready to copy.
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

/// Offscreen images that stand in for a swapchain.
class Offscreen final : public gpu::Output {
public:
    [[nodiscard]] static gpu::Result<std::unique_ptr<Offscreen>> create(const gpu::Device& device, vk::Extent2D extent,
                                                                        std::uint32_t images = 2);
    ~Offscreen() override = default;

    [[nodiscard]] vk::Extent2D extent() const noexcept override { return extent_; }
    [[nodiscard]] vk::Format format() const noexcept override { return vk::Format::eR8G8B8A8Unorm; }
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
    /// Copies image `index` to memory; the frames that drew it must have finished (Renderer::drain).
    [[nodiscard]] gpu::Result<Pixels> read(std::uint32_t index) const;
    /// Fills every image with one colour (a sentinel a frame that never writes its output leaves behind).
    [[nodiscard]] gpu::Result<void> fill(const std::array<float, 4>& rgba) const;

private:
    Offscreen() = default;
    const gpu::Device* device_ = nullptr;
    vk::Extent2D extent_;
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

/// The share of pixels that differ by more than `levels` in any channel, and the largest difference.
struct Difference {
    double share = 0;
    int largest = 0;
};
[[nodiscard]] Difference compare(const Pixels& a, const Pixels& b, int levels);

} // namespace atlas::platform
