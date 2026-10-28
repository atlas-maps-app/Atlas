// The screen-wide noise fields made on the GPU (fx/GpuFields.hpp).
#include "fx/GpuFields.hpp"

#include "base/assert.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/fx/fields.hpp"
#include "gpu/Descriptors.hpp"

#include <array>
#include <bit>
#include <chrono>
#include <cstring>
#include <format>
#include <vector>

namespace atlas::fx {

namespace {

using Push = layouts::fx_fields::FieldsPush;
using clock = std::chrono::steady_clock;

/// fields.slang's workgroups: 8 × 8 pixels.
constexpr std::uint32_t group_side = 8;

/// A lattice's words as fields.slang reads it (FIELDS_LATTICE_WORDS): its selector's entries, then its gradients.
constexpr std::size_t words_a_lattice = (2 * lattice_entries) + 2 + (4 * lattice_entries * 2);

/// The byte check's blocks (fx::noise_byte_check's): 64 × 64 pixels at the frame's corner and far from it.
constexpr std::int32_t check_side = 64;
constexpr std::array<Block, 2> check_blocks{{{.left = 0, .top = 0, .width = check_side, .height = check_side},
                                             {.left = 1500, .top = 2000, .width = check_side, .height = check_side}}};

/// fields.slang's `lattice`: the eight specs (base_x's and base_y's bits, the octaves, the channels with fractal noise
/// at bit 8), then the eight lattices.
[[nodiscard]] std::vector<std::uint32_t> lattice_buffer() {
    std::vector<std::uint32_t> out;
    out.reserve((field_count * 4) + (field_count * words_a_lattice));
    for (const FieldSpec& s : screen_fields) {
        out.push_back(std::bit_cast<std::uint32_t>(s.noise.base_x));
        out.push_back(std::bit_cast<std::uint32_t>(s.noise.base_y));
        out.push_back(static_cast<std::uint32_t>(s.noise.octaves));
        out.push_back(static_cast<std::uint32_t>(s.need) | (s.noise.fractal ? 256U : 0U));
    }
    for (const FieldSpec& s : screen_fields) {
        const std::vector<std::uint32_t> words = lattice_words(Lattice(s.noise.seed));
        ATLAS_ASSERT(words.size() == words_a_lattice, "a lattice is fields.slang's FIELDS_LATTICE_WORDS words");
        out.insert(out.end(), words.begin(), words.end());
    }
    return out;
}

[[nodiscard]] double ms_since(clock::time_point started) {
    return std::chrono::duration<double, std::milli>(clock::now() - started).count();
}

} // namespace

GpuFields::~GpuFields() = default;

gpu::Result<std::unique_ptr<GpuFields>> GpuFields::create(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                          gpu::PipelineWatch* watch) {
    std::unique_ptr<GpuFields> self(new GpuFields(device, cache)); // NOLINT(cppcoreguidelines-owning-memory)
    const vk::raii::Device& d = device.handle();
    constexpr auto compute = vk::ShaderStageFlagBits::eCompute;
    const std::array bindings{gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
                              gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = compute},
                              gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = compute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    self->set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2)); // a run's set, freed as it ends
    self->pool_ = std::move(pool);
    const std::array<vk::DescriptorSetLayout, 1> used{*self->set_layout_};
    ATLAS_GPU_LET(layout, gpu::make_layout(d, used, sizeof(Push), compute));
    self->layout_ = std::move(layout);
    auto module = [&] {
        const gpu::Announced named = gpu::announce_module("fx_fields", watch);
        return gpu::make_module(d, spirv::fx_fields);
    }();
    ATLAS_GPU_LET(made_module, std::move(module));
    self->module_ = std::move(made_module);
    const gpu::PipelineLabel label{.name = "fields", .module = "fx_fields"};
    cache.note_made();
    auto pipeline = [&] {
        const gpu::Announced named = gpu::announce(label, watch);
        return gpu::make_compute(d, cache.handle(), label, *self->module_, "fields_make_cs", *self->layout_);
    }();
    ATLAS_GPU_LET(made_pipeline, std::move(pipeline));
    self->pipeline_ = std::move(made_pipeline);
    // The inputs, written once.
    using B = vk::BufferUsageFlagBits;
    const std::vector<std::uint32_t> words = lattice_buffer();
    ATLAS_GPU_LET(lattices, device.memory().buffer(words.size() * 4, B::eStorageBuffer, gpu::MemoryUse::upload));
    std::memcpy(lattices.bytes().data(), words.data(), words.size() * 4);
    lattices.flush();
    self->lattices_ = std::move(lattices);
    const std::vector<std::uint8_t> tables = keep_tables_bytes();
    ATLAS_GPU_LET(keep, device.memory().buffer(tables.size(), B::eStorageBuffer, gpu::MemoryUse::upload));
    std::memcpy(keep.bytes().data(), tables.data(), tables.size());
    keep.flush();
    self->keep_ = std::move(keep);
    ATLAS_VK_LET(commands,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device.queue_family()}),
                 "the fields' command pool");
    self->commands_ = std::move(commands);
    return self;
}

gpu::Result<GpuMade> GpuFields::run(Block block, float scale) const {
    const auto started = clock::now();
    const FieldSize size{.width = static_cast<std::uint32_t>(block.width),
                         .height = static_cast<std::uint32_t>(block.height),
                         .scale = scale};
    const std::size_t pixels = static_cast<std::size_t>(size.width) * size.height;
    using B = vk::BufferUsageFlagBits;
    ATLAS_GPU_LET(out, device_.memory().buffer(pixels * (rgba_bytes + rg_bytes + r_bytes),
                                               B::eStorageBuffer | B::eTransferSrc, gpu::MemoryUse::readback));
    auto buffer = std::make_shared<const gpu::Buffer>(std::move(out));
    const vk::raii::Device& d = device_.handle();
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, set_layout_));
    gpu::SetWriter(*set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, lattices_.handle())
        .buffer(1, vk::DescriptorType::eStorageBuffer, keep_.handle())
        .buffer(2, vk::DescriptorType::eStorageBuffer, buffer->handle())
        .apply(d);
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *commands_, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "the fields' command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the fields' commands");
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *layout_, 0, *set, {});
    const Grid grid = grid_of(scale, scale);
    Push p{};
    p.block = {block.left, block.top, block.width, block.height};
    p.grid = {grid.inv_x, grid.inv_y, 0.0F, 0.0F};
    cmd.pushConstants(*layout_, vk::ShaderStageFlagBits::eCompute, 0,
                      vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
    const auto groups = [](std::uint32_t n) { return (n + group_side - 1) / group_side; };
    cmd.dispatch(groups(size.width), groups(size.height), 1);
    // The writes, for the upload's copy (a later submission's: FieldImages::record_upload) and for the host.
    const vk::MemoryBarrier written{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                    .dstAccessMask = vk::AccessFlagBits::eTransferRead | vk::AccessFlagBits::eHostRead};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader,
                        vk::PipelineStageFlagBits::eTransfer | vk::PipelineStageFlagBits::eHost, {}, written, {}, {});
    ATLAS_VK_CHECK(cmd.end(), "the fields' commands");
    ATLAS_VK_LET(fence, d.createFence({}), "the fields' fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device_.queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the fields' submission");
    if (const vk::Result w = d.waitForFences(*fence, vk::True, UINT64_MAX); w != vk::Result::eSuccess) {
        return gpu::fail(w, "the fields' fence");
    }
    buffer->invalidate();
    const std::span<const std::byte> all = buffer->bytes();
    const auto* bytes = reinterpret_cast<const std::uint8_t*>(all.data());
    const std::span<const std::uint8_t> rgba(bytes, pixels * rgba_bytes);
    const std::span<const std::uint8_t> rg(bytes + (pixels * rgba_bytes), pixels * rg_bytes);
    const std::span<const std::uint8_t> r(bytes + (pixels * (rgba_bytes + rg_bytes)), pixels * r_bytes);
    auto fields = std::make_shared<const ScreenFields>(size, buffer, false, rgba, rg, r);
    return GpuMade{.buffer = std::move(buffer), .fields = std::move(fields), .ms = ms_since(started)};
}

gpu::Result<GpuMade> GpuFields::make(FieldSize size) const {
    ATLAS_ASSERT(size.width > 0 && size.height > 0 && size.width <= 16384 && size.height <= 16384,
                 "fields of a frame of 1 to 16384 px a side");
    return run({.left = 0,
                .top = 0,
                .width = static_cast<std::int32_t>(size.width),
                .height = static_cast<std::int32_t>(size.height)},
               size.scale);
}

gpu::Result<NoiseCheck> GpuFields::check() const {
    ATLAS_GPU_LET(out, noise_byte_check(device_, cache_));
    if (!out.exact) return out;
    // The fields' kept bytes over the same blocks at the same scale, every byte of each against make_fields'.
    const auto scale = static_cast<float>(1080.0 / 412.0);
    for (const Block& b : check_blocks) {
        ATLAS_GPU_LET(made, run(b, scale));
        const std::shared_ptr<const ScreenFields> want = make_block(b, scale);
        for (std::size_t f = 0; f < field_count; ++f) {
            const auto field = static_cast<Field>(f);
            for (std::uint32_t y = 0; y < want->size().height; ++y) {
                for (std::uint32_t x = 0; x < want->size().width; ++x) {
                    ++out.pixels;
                    const std::array<std::uint8_t, 2> got = made.fields->at(field, x, y);
                    const std::array<std::uint8_t, 2> cpu = want->at(field, x, y);
                    if (got == cpu) continue;
                    if (out.differ == 0) {
                        out.first = std::format("field {} at pixel {} {} keeps {:02x}{:02x}, the CPU's {:02x}{:02x}",
                                                screen_fields[f].name, b.left + static_cast<std::int32_t>(x),
                                                b.top + static_cast<std::int32_t>(y), got[0], got[1], cpu[0], cpu[1]);
                    }
                    ++out.differ;
                }
            }
        }
    }
    out.exact = out.differ == 0;
    return out;
}

std::string fields_line(FieldSize size, unsigned threads, const gpu::Device& device, const gpu::PipelineCache& cache,
                        gpu::PipelineWatch* watch, std::string_view off) {
    if (!off.empty()) return fields_line(size, threads, GpuPart{.off = std::string(off)});
    const auto started = clock::now();
    auto made = GpuFields::create(device, cache, watch);
    if (!made) return fields_line(size, threads, GpuPart{.off = made.error().text()});
    auto checked = (*made)->check();
    if (!checked) return fields_line(size, threads, GpuPart{.off = "the byte check: " + checked.error().text()});
    if (!checked->exact) return fields_line(size, threads, GpuPart{.off = "the byte check: " + checked->first});
    auto fields = (*made)->make(size);
    if (!fields) return fields_line(size, threads, GpuPart{.off = fields.error().text()});
    // The GPU's first start at the size: its pipeline, the byte check and the fields.
    return fields_line(size, threads, GpuPart{.fields = fields->fields, .ms = ms_since(started)});
}

} // namespace atlas::fx
