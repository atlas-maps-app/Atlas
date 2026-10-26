// WebKit's turbulence on the GPU (fx/GpuNoise.hpp).
#include "fx/GpuNoise.hpp"

#include "gen/layouts.hpp"
#include "gen/spirv/fx/noise.hpp"
#include "gpu/Descriptors.hpp"

#include <array>
#include <bit>
#include <cstring>
#include <format>

namespace atlas::fx {

namespace {

using Push = layouts::fx_noise::NoisePush;

/// noise.slang's workgroups: 8 × 8 pixels.
constexpr std::uint32_t group_side = 8;

/// The selector's entries before the gradients (noise.slang's NOISE_SELECTOR).
constexpr std::size_t selector_words = (2 * lattice_entries) + 2;

/// The renders' filters' noises, as they write them (seed, base frequency, octaves; fractal noise all).
constexpr std::array<Turbulence, 11> checked{{
    {.seed = 13.0F, .base_x = 0.16F, .base_y = 0.75F, .octaves = 2},     // mat
    {.seed = 8.0F, .base_x = 0.9F, .base_y = 0.9F, .octaves = 1},        // the grain
    {.seed = 11.0F, .base_x = 0.012F, .base_y = 0.26F, .octaves = 2},    // the ripple
    {.seed = 5.0F, .base_x = 0.02F, .base_y = 0.3F, .octaves = 2},       // the glints at a map pitch
    {.seed = 23.0F, .base_x = 0.0026F, .base_y = 0.0058F, .octaves = 3}, // the cloud shadows
    {.seed = 5.0F, .base_x = 0.15F, .base_y = 0.15F, .octaves = 2},      // the crowns' displacement
    {.seed = 2.0F, .base_x = 0.42F, .base_y = 0.42F, .octaves = 2},      // the crowns' tone
    {.seed = 3.0F, .base_x = 0.55F, .base_y = 0.55F, .octaves = 2},      // leaf
    {.seed = 4.0F, .base_x = 0.55F, .base_y = 0.55F, .octaves = 2},      // foliage
    {.seed = 17.0F, .base_x = 0.55F, .base_y = 0.55F, .octaves = 2},     // the regions' canopy
    {.seed = 7.0F, .base_x = 0.0451F, .base_y = 0.3172F, .octaves = 2},  // a glint band at eye level (glint2)
}};

} // namespace

std::vector<std::uint32_t> lattice_words(const Lattice& lattice) {
    std::vector<std::uint32_t> out;
    out.reserve(selector_words + lattice.gradients().size());
    for (const std::int32_t s : lattice.selector()) out.push_back(static_cast<std::uint32_t>(s));
    for (const float g : lattice.gradients()) out.push_back(std::bit_cast<std::uint32_t>(g));
    return out;
}

std::span<const Turbulence> checked_noises() noexcept { return checked; }

GpuNoise::~GpuNoise() = default;

gpu::Result<std::unique_ptr<GpuNoise>> GpuNoise::create(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                        gpu::PipelineWatch* watch, std::uint32_t sets) {
    std::unique_ptr<GpuNoise> self(new GpuNoise()); // NOLINT(cppcoreguidelines-owning-memory)
    self->device_ = &device;
    const vk::raii::Device& d = device.handle();
    constexpr auto compute = vk::ShaderStageFlagBits::eCompute;
    const std::array bindings{gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
                              gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = compute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    self->set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, std::max(sets, 1U)));
    self->pool_ = std::move(pool);
    const std::array<vk::DescriptorSetLayout, 1> used{*self->set_layout_};
    ATLAS_GPU_LET(layout, gpu::make_layout(d, used, sizeof(Push), compute));
    self->layout_ = std::move(layout);
    auto module = [&] {
        const gpu::Announced named = gpu::announce_module("fx_noise", watch);
        return gpu::make_module(d, spirv::fx_noise);
    }();
    ATLAS_GPU_LET(made_module, std::move(module));
    self->module_ = std::move(made_module);
    const gpu::PipelineLabel label{.name = "noise", .module = "fx_noise"};
    cache.note_made();
    auto pipeline = [&] {
        const gpu::Announced named = gpu::announce(label, watch);
        return gpu::make_compute(d, cache.handle(), label, *self->module_, "noise_fill_cs", *self->layout_);
    }();
    ATLAS_GPU_LET(made_pipeline, std::move(pipeline));
    self->pipeline_ = std::move(made_pipeline);
    return self;
}

gpu::Result<vk::raii::DescriptorSet> GpuNoise::bind(vk::Buffer lattice, vk::Buffer pixels) const {
    const vk::raii::Device& d = device_->handle();
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, set_layout_));
    gpu::SetWriter(*set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, lattice)
        .buffer(1, vk::DescriptorType::eStorageBuffer, pixels)
        .apply(d);
    return std::move(set);
}

void GpuNoise::record(const vk::raii::CommandBuffer& cmd, vk::DescriptorSet set, const Turbulence& t, Grid grid,
                      Block block, std::uint8_t need, std::uint32_t stride) const {
    if (block.width <= 0 || block.height <= 0) return;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *layout_, 0, set, {});
    Push p{};
    p.block = {block.left, block.top, block.width, block.height};
    p.grid = {grid.inv_x, grid.inv_y, t.base_x, t.base_y};
    p.spec = {static_cast<std::uint32_t>(t.octaves), t.fractal ? 1U : 0U, need, stride};
    cmd.pushConstants(*layout_, vk::ShaderStageFlagBits::eCompute, 0,
                      vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
    const auto groups = [](std::int32_t n) { return (static_cast<std::uint32_t>(n) + group_side - 1) / group_side; };
    cmd.dispatch(groups(block.width), groups(block.height), 1);
}

gpu::Result<NoiseCheck> noise_byte_check(const gpu::Device& device, const gpu::PipelineCache& cache,
                                         std::uint32_t side) {
    constexpr std::size_t places = 2;
    ATLAS_GPU_LET(noise, GpuNoise::create(device, cache, nullptr, static_cast<std::uint32_t>(checked.size() * places)));
    const auto scale = static_cast<float>(1080.0 / 412.0);
    const Grid grid = grid_of(scale, scale);
    const auto n = static_cast<std::int32_t>(side);
    const std::array<Block, places> blocks{
        {{.left = 0, .top = 0, .width = n, .height = n}, {.left = 1500, .top = 2000, .width = n, .height = n}}};
    const std::size_t words = std::size_t{side} * side;
    struct Run {
        std::size_t noise = 0;
        std::size_t place = 0;
        gpu::Buffer pixels;
        vk::raii::DescriptorSet set{nullptr};
    };
    std::vector<gpu::Buffer> lattices;
    std::vector<Run> runs;
    using B = vk::BufferUsageFlagBits;
    for (std::size_t k = 0; k < checked.size(); ++k) {
        const std::vector<std::uint32_t> w = lattice_words(Lattice(checked[k].seed));
        ATLAS_GPU_LET(lattice, device.memory().buffer(w.size() * 4, B::eStorageBuffer, gpu::MemoryUse::upload));
        std::memcpy(lattice.bytes().data(), w.data(), w.size() * 4);
        lattice.flush();
        for (std::size_t p = 0; p < places; ++p) {
            ATLAS_GPU_LET(pixels, device.memory().buffer(words * 4, B::eStorageBuffer, gpu::MemoryUse::readback));
            ATLAS_GPU_LET(set, noise->bind(lattice.handle(), pixels.handle()));
            runs.push_back({.noise = k, .place = p, .pixels = std::move(pixels), .set = std::move(set)});
        }
        lattices.push_back(std::move(lattice));
    }
    // one submit, waited for
    const vk::raii::Device& d = device.handle();
    ATLAS_VK_LET(pool,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device.queue_family()}),
                 "the noise check's command pool");
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "the noise check's command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the noise check's commands");
    for (const Run& r : runs) noise->record(cmd, *r.set, checked[r.noise], grid, blocks[r.place], channels_all, side);
    const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                    .dstAccessMask = vk::AccessFlagBits::eHostRead};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader, vk::PipelineStageFlagBits::eHost, {}, to_host, {},
                        {});
    ATLAS_VK_CHECK(cmd.end(), "the noise check's commands");
    ATLAS_VK_LET(fence, d.createFence({}), "the noise check's fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device.queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the noise check");
    if (const vk::Result w = d.waitForFences(*fence, vk::True, UINT64_MAX); w != vk::Result::eSuccess) {
        return gpu::fail(w, "the noise check");
    }
    // every pixel against the CPU's
    NoiseCheck out;
    std::vector<Argb> want(words);
    for (const Run& r : runs) {
        const Turbulence& t = checked[r.noise];
        const Block& b = blocks[r.place];
        fill(Lattice(t.seed), t, grid, b, channels_all, want, side);
        r.pixels.invalidate();
        const std::span<const std::byte> got = r.pixels.bytes();
        for (std::size_t i = 0; i < words; ++i) {
            Argb g = 0;
            std::memcpy(&g, got.data() + (i * 4), 4);
            ++out.pixels;
            if (g == want[i]) continue;
            if (out.differ == 0) {
                out.first = std::format("seed {} at {} × {}, {} octaves: pixel {} {} is {:08x}, the CPU's {:08x}",
                                        t.seed, t.base_x, t.base_y, t.octaves, b.left + static_cast<int>(i % side),
                                        b.top + static_cast<int>(i / side), g, want[i]);
            }
            ++out.differ;
        }
    }
    out.exact = out.differ == 0;
    return out;
}

} // namespace atlas::fx
