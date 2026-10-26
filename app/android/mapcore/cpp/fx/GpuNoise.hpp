// WebKit's turbulence on the GPU (plan-vector N17, D10; shaders/fx/noise.slang): blocks of a turbulence's filter
// pixels with fx::fill's bytes wherever the device's binary32 addition and multiplication are correctly rounded (each
// fused operation of the port's is an exact emulated fma there), and the byte check that holds a device to the CPU's
// bytes before the core relies on its noise: the renders' filters' noises, each over blocks near the frame's corner and
// far from it, at a phone's filter scale, every channel.
#pragma once

#include "fx/Noise.hpp"
#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"

#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace atlas::fx {

/// A seed's lattice as the GPU reads it (noise.slang's `lattice`): its selector's 514 entries, then its gradients'
/// floats' bits, channel c's entry i at 514 + (c · 256 + i) · 2.
[[nodiscard]] std::vector<std::uint32_t> lattice_words(const Lattice& lattice);

/// The GPU's turbulence: noise.slang's fill, its set layout (the lattice and the pixels, storage buffers) and a pool
/// for the sets its users bind.
class GpuNoise {
public:
    /// The fill, its module and pipeline made through `cache`, each announced to `watch` (may be null); room for
    /// `sets` sets.
    [[nodiscard]] static gpu::Result<std::unique_ptr<GpuNoise>>
    create(const gpu::Device& device, const gpu::PipelineCache& cache, gpu::PipelineWatch* watch, std::uint32_t sets);
    GpuNoise(const GpuNoise&) = delete;
    GpuNoise& operator=(const GpuNoise&) = delete;
    GpuNoise(GpuNoise&&) = delete;
    GpuNoise& operator=(GpuNoise&&) = delete;
    ~GpuNoise();

    /// A set reading `lattice` (lattice_words of a seed's) and writing `pixels`.
    [[nodiscard]] gpu::Result<vk::raii::DescriptorSet> bind(vk::Buffer lattice, vk::Buffer pixels) const;

    /// Records the fill of `block` of `t` on `grid`, its channels in `need`, packed a r g b a word and a row `stride`
    /// words from the last in the set's pixels.
    void record(const vk::raii::CommandBuffer& cmd, vk::DescriptorSet set, const Turbulence& t, Grid grid, Block block,
                std::uint8_t need, std::uint32_t stride) const;

private:
    GpuNoise() = default;

    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
    const gpu::Device* device_ = nullptr;
};

/// The byte check's verdict on a device.
struct NoiseCheck {
    bool exact = false;
    std::uint64_t pixels = 0; ///< the pixels checked, each of its four channels
    std::uint64_t differ = 0; ///< those whose bytes are not the CPU's
    std::string first;        ///< the first of them, named
};

/// The renders' filters' noises the check holds a device to: the screen fields' (mat, grain, ripple, glints, cloud
/// shadows), the trees' and reflections' (crown, its tone, leaf, foliage), the regions' canopy, and a glint band at eye
/// level.
[[nodiscard]] std::span<const Turbulence> checked_noises() noexcept;

/// Runs the byte check on `device` (on its queue, waiting for it: before frames are drawn, or between them): each of
/// checked_noises over `side` × `side` pixels at the frame's corner and at (1500, 2000), at a filter scale of 1080 /
/// 412, every channel, against fx::fill.
[[nodiscard]] gpu::Result<NoiseCheck> noise_byte_check(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                       std::uint32_t side = 64);

} // namespace atlas::fx
