// The screen-wide noise fields and the byte tables on the GPU (plan-vector D5.5, D5.6, D10, K17a): set 1's noise_rgba,
// noise_rg and noise_r (combined image samplers: R8G8B8A8, R8G8 and R8 UNORM at the frame's upright device pixels,
// fx/Fields.hpp's three images) and byte_tables (a storage buffer: the to-linear table, the to-sRGB table and `mat`'s
// opaque table, 64 KB each; fx/ByteTables.hpp, fx/Mat.hpp). FieldCache is their provider (frame/Globals.hpp), which
// the world holds (World::add_globals): each frame it gives set 1 its bindings (an unchanged one costs a comparison)
// and asks its worker for the fields of the frame's size (made on worker threads, or mapped from cacheDir/fields);
// when they arrive it makes images of their size, binds them and records their upload before the frame's passes. The
// effects that read them (mat, the grain) run once they are bound for the frame's size (FRAME_FIELDS, which the frame
// keeps in full mode alone); until then, and for the moment a resize takes, they are stubbed. A size's images live
// until the frames that read them have finished (D5.2's two frames in flight), however often the size changes: what
// is let go is counted in the renderer's frame numbers, as each frame's record gives them (FrameBuilder::emit runs
// the update alone), and a frame builder drawing the world finishes its frames before another draws it. The shaders
// read the fields and the tables through shaders/fx/mat.slang.
#pragma once

#include "frame/Globals.hpp"
#include "fx/ByteTables.hpp"
#include "fx/Fields.hpp"
#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "gpu/Sync.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::fx {

/// The fields' images and the byte tables' buffer on one device.
class FieldImages {
public:
    /// The tables' buffer (written now) and the sampler; no images until the first stage.
    [[nodiscard]] static gpu::Result<std::unique_ptr<FieldImages>> create(const gpu::Device& device);
    FieldImages(const FieldImages&) = delete;
    FieldImages& operator=(const FieldImages&) = delete;
    FieldImages(FieldImages&&) = delete;
    FieldImages& operator=(FieldImages&&) = delete;
    ~FieldImages();

    /// Makes images for `fields` (new ones when the size changed) and copies their bytes into a staging buffer;
    /// record_upload records the rest.
    [[nodiscard]] gpu::Result<void> stage(const ScreenFields& fields);
    /// Whether staged bytes wait for record_upload.
    [[nodiscard]] bool staged() const noexcept { return static_cast<bool>(staging_); }
    /// Records the staged bytes' upload into `cmd`, outside any render pass, as frame `frame`, then the images made
    /// ready for the vertex, fragment and compute stages' reads: the staging buffer is kept until that frame has
    /// finished, and the images the new ones replaced until the frame before it has.
    void record_upload(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync, std::uint64_t frame);
    /// Lets go of what only frames up to `finished` read (the renderer's frame numbers).
    void collect(std::uint64_t finished) noexcept;

    /// Whether images are made, and their size.
    [[nodiscard]] bool ready() const noexcept { return ready_; }
    [[nodiscard]] FieldSize size() const noexcept { return current_.size; }
    /// Image `image`'s view (0 rgba, 1 rg, 2 r; fx/Fields.hpp's placement_of).
    [[nodiscard]] vk::ImageView view(std::uint32_t image) const noexcept;
    [[nodiscard]] vk::Sampler sampler() const noexcept { return *sampler_; }
    [[nodiscard]] vk::Buffer tables() const noexcept { return tables_.handle(); }
    /// The GPU memory held: the images, the tables and what waits to be let go.
    [[nodiscard]] vk::DeviceSize bytes() const noexcept;
    /// How many sizes' images, or staging buffers, wait for their frames to finish (the replaced images once their
    /// replacement's upload is recorded).
    [[nodiscard]] std::size_t retired() const noexcept { return retired_.size() + replaced_.size(); }

private:
    explicit FieldImages(const gpu::Device& device) noexcept : device_(device) {}

    /// A size's three images and their views.
    struct Images {
        FieldSize size;
        std::array<gpu::Image, 3> images;
        std::array<vk::raii::ImageView, 3> views{vk::raii::ImageView{nullptr}, vk::raii::ImageView{nullptr},
                                                 vk::raii::ImageView{nullptr}};
    };
    /// What waits for the frames that read it to finish.
    struct Retired {
        Images images;
        gpu::Buffer staging;
        std::uint64_t last_frame = 0;
    };

    const gpu::Device& device_;
    gpu::Buffer tables_;
    vk::raii::Sampler sampler_{nullptr};
    Images current_;
    bool ready_ = false;
    gpu::Buffer staging_;          ///< staged bytes waiting for record_upload
    std::vector<Images> replaced_; ///< the images staging replaced, until record_upload knows the frame
    std::vector<Retired> retired_;
};

/// FieldCache's choices.
struct FieldCacheOptions {
    /// Where the fields are kept (cacheDir/fields); empty: nothing kept, every start makes them.
    std::filesystem::path dir;
    /// The worker's threads, its own among them.
    unsigned threads = 3;
};

/// The provider of set 1's noise_rgba, noise_rg, noise_r and byte_tables.
class FieldCache final : public frame::GlobalProvider {
public:
    [[nodiscard]] static gpu::Result<std::unique_ptr<FieldCache>> create(const gpu::Device& device,
                                                                         FieldCacheOptions options = {});
    ~FieldCache() override;
    FieldCache(const FieldCache&) = delete;
    FieldCache& operator=(const FieldCache&) = delete;
    FieldCache(FieldCache&&) = delete;
    FieldCache& operator=(FieldCache&&) = delete;

    // frame::GlobalProvider
    [[nodiscard]] std::string_view name() const noexcept override { return "fields"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return bound; }
    void update(const frame::FrameState& state, frame::Globals& globals) override;
    [[nodiscard]] std::uint32_t frame_flags(const frame::FrameState& state) const noexcept override;
    void record(const gpu::FrameContext& frame) override;

    /// Called on a worker's thread when fields are published (cheap and never blocking: the frame loop's wake, which
    /// the world's owner passes here as it does to World::set_wake).
    void set_wake(std::function<void()> wake) { worker_->on_publish(std::move(wake)); }
    /// The size the last frame asked for, and whether its fields are bound.
    [[nodiscard]] std::optional<FieldSize> wanted() const noexcept { return wanted_; }
    [[nodiscard]] bool bound_for(FieldSize size) const noexcept;
    [[nodiscard]] FieldWorker& worker() noexcept { return *worker_; }
    [[nodiscard]] const FieldImages& images() const noexcept { return *images_; }

private:
    FieldCache() = default;
    /// Stages the worker's latest fields when they are new and of `size`.
    void take(FieldSize size);

    static constexpr std::array<frame::Global, 4> bound{frame::Global::noise_rgba, frame::Global::noise_rg,
                                                        frame::Global::noise_r, frame::Global::byte_tables};

    std::unique_ptr<FieldImages> images_;
    std::unique_ptr<FieldWorker> worker_;
    std::optional<FieldSize> wanted_;
    std::uint64_t taken_version_ = 0;
    std::uint64_t failed_version_ = 0;
    bool uploaded_ = false; ///< the current images hold their fields, or will once the next record has run
};

} // namespace atlas::fx
