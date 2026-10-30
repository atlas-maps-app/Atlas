// The screen-wide noise fields made on the GPU (plan-vector D10, N17; shaders/fx/fields.slang): fx/Fields.hpp's nine
// fields of a frame, every byte make_fields', in the GPU's four images' layout, wherever the device passes the byte
// check. Each noise is fx/noise.slang's turbulence (each fused operation of the port's an exact emulated fma) and each
// field keeps what make_fields keeps of it, from the same tables and with the same arithmetic (mat's two fused
// operations emulated too); the check holds both to the CPU's on the device before the core relies on them: the
// renders' eleven noises (fx::noise_byte_check), then the nine fields' kept bytes over the same blocks.
//
// The core takes it (fx/FieldCache.hpp's make_on_gpu) for a size the disk does not hold, on a device that passes the
// check where it is a GPU (not lavapipe or SwiftShader, which run the shader on the CPU many times slower than the
// CPU's own make) and the crash guard has not turned the GPU's noise off: the session runs the check and the making
// behind the guard's noise stage (gpu/Guard.hpp, standin/Session.hpp), the frame waiting for the fields, which it draws
// with at once; the bytes go on to the disk as the CPU's would. Elsewhere the CPU's workers make them, as before.
#pragma once

#include "fx/Fields.hpp"
#include "fx/GpuNoise.hpp"
#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>

namespace atlas::fx {

/// A size's fields made on the GPU.
struct GpuMade {
    /// The four images' bytes one after the other (fx/Fields.hpp's layout; the canopy's padded to a whole word), in
    /// host-visible memory: what FieldImages uploads from, and what the fields view.
    std::shared_ptr<const gpu::Buffer> buffer;
    /// The fields, viewing the buffer's bytes (and keeping it alive).
    std::shared_ptr<const ScreenFields> fields;
    double ms = 0; ///< from the commands' recording to the fence
};

/// fields.slang's pipeline, its inputs (the nine specs and lattices, the keep tables) and a command pool on one
/// device.
class GpuFields {
public:
    /// The module and the pipeline made through `cache`, each announced to `watch` (may be null).
    [[nodiscard]] static gpu::Result<std::unique_ptr<GpuFields>>
    create(const gpu::Device& device, const gpu::PipelineCache& cache, gpu::PipelineWatch* watch);
    GpuFields(const GpuFields&) = delete;
    GpuFields& operator=(const GpuFields&) = delete;
    GpuFields(GpuFields&&) = delete;
    GpuFields& operator=(GpuFields&&) = delete;
    ~GpuFields();

    /// The byte check: fx::noise_byte_check, then the nine fields of its two blocks at its filter scale against
    /// make_fields' bytes of the same pixels; the first difference named. On the device's queue, waiting for it.
    [[nodiscard]] gpu::Result<NoiseCheck> check() const;
    /// The fields of `size`, on the device's queue, waiting for them.
    [[nodiscard]] gpu::Result<GpuMade> make(FieldSize size) const;

private:
    explicit GpuFields(const gpu::Device& device, const gpu::PipelineCache& cache) noexcept
        : device_(device), cache_(cache) {}

    /// The fields of `block` of a frame at filter scale `scale`, as fields of the block's size.
    [[nodiscard]] gpu::Result<GpuMade> run(Block block, float scale) const;

    const gpu::Device& device_;
    const gpu::PipelineCache& cache_;
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
    gpu::Buffer lattices_; ///< the nine specs, then their lattices
    gpu::Buffer keep_;     ///< make_fields' keep tables
    vk::raii::CommandPool commands_{nullptr};
};

/// VK facts' fields line on `device` (fields_line with the GPU's part): the byte check, then the fields of `size` made
/// on the GPU, timed, against the CPU's, the GPU's pipelines made through `cache` and announced to `watch`; where `off`
/// says why the GPU's noise is off (the crash guard's verdict, a CPU implementation), none, and the line says so. The
/// caller runs it behind the guard's noise stage.
[[nodiscard]] std::string fields_line(FieldSize size, unsigned threads, const gpu::Device& device,
                                      const gpu::PipelineCache& cache, gpu::PipelineWatch* watch, std::string_view off);

} // namespace atlas::fx
