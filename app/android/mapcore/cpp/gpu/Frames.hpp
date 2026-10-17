// Frames in flight (plan-vector D5.2): two slots, each with its command pool and buffer (reset, never freed), its fence
// and its acquire semaphore, and its slice of the GPU timestamp and statistics queries, read back once its fence has
// signalled (never waited for in the frame loop). The output the frames go to is a swapchain on the phone, or a set of
// offscreen images on hosts; both answer the same few calls.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Error.hpp"
#include "gpu/Passes.hpp"
#include "gpu/Sync.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace atlas::gpu {

/// The outcome of asking the output for an image.
enum class Acquired : std::uint8_t { ok, suboptimal, out_of_date, timeout, lost };

/// Where frames go.
class Output {
public:
    Output() = default;
    Output(const Output&) = delete;
    Output& operator=(const Output&) = delete;
    Output(Output&&) = delete;
    Output& operator=(Output&&) = delete;
    virtual ~Output() = default;

    [[nodiscard]] virtual vk::Extent2D extent() const noexcept = 0;
    [[nodiscard]] virtual vk::Format format() const noexcept = 0;
    [[nodiscard]] virtual std::span<const vk::Image> images() const noexcept = 0;
    [[nodiscard]] virtual std::span<const vk::ImageView> views() const noexcept = 0;
    /// The layout the main pass leaves the image in.
    [[nodiscard]] virtual vk::ImageLayout final_layout() const noexcept = 0;
    /// Whether the images can be copied from (the frosted backdrops' copies, a readback).
    [[nodiscard]] virtual bool transfer_source() const noexcept = 0;
    /// The rotation the frame applies itself (the surface's current transform; identity offscreen).
    [[nodiscard]] virtual vk::SurfaceTransformFlagBitsKHR transform() const noexcept {
        return vk::SurfaceTransformFlagBitsKHR::eIdentity;
    }
    /// Asks for the next image, signalling `acquired` when it may be written (when acquire_signals() is true).
    [[nodiscard]] virtual Acquired acquire(vk::Semaphore acquired, std::uint32_t& index) = 0;
    /// Whether acquire() signals its semaphore, so the frame must wait for it (a swapchain); offscreen it does not.
    [[nodiscard]] virtual bool acquire_signals() const noexcept { return true; }
    /// The semaphore that a frame drawn into image `index` signals and the present waits for.
    [[nodiscard]] virtual vk::Semaphore rendered(std::uint32_t index) const noexcept = 0;
    /// Presents image `index` (after its rendered semaphore), at `desired_ns` on the display's clock where the device
    /// can (0: as soon as possible). Offscreen, the image just becomes the latest.
    [[nodiscard]] virtual Acquired present(std::uint32_t index, std::uint64_t frame, std::uint64_t desired_ns) = 0;
};

/// The GPU's times of one frame (nanoseconds; zero where the device has no timestamps).
struct GpuTimes {
    std::uint64_t frame = 0;     // the frame number they belong to
    double total_ns = 0;         // first mark to last
    double offscreen_ns = 0;     // the offscreen passes before the main pass
    double main_ns = 0;          // the main pass
    double after_ns = 0;         // what follows the main pass (the frosted backdrops)
    std::uint64_t fragments = 0; // fragment shader invocations (pipelineStatisticsQuery), 0 where unsupported
    bool valid = false;
};

/// The marks a frame writes, in order.
enum class Mark : std::uint8_t { start = 0, offscreen = 1, main = 2, after = 3, count = 4 };

/// One slot of the frames in flight.
struct FrameSlot {
    vk::raii::CommandPool pool{nullptr};
    vk::raii::CommandBuffer cmd{nullptr};
    vk::raii::Fence done{nullptr};
    vk::raii::Semaphore acquired{nullptr};
    std::uint64_t number = 0; // the frame recorded in it last
    bool pending = false;     // submitted and its results not read yet
    std::uint32_t slot = 0;
};

/// The frames in flight.
class Frames {
public:
    static constexpr std::uint32_t in_flight = 2;

    [[nodiscard]] static Result<Frames> create(const Device& device);

    /// Waits for the next slot's previous frame (it has had a whole frame to finish), reads its GPU times, resets its
    /// command pool and begins its command buffer for frame `number`.
    [[nodiscard]] Result<FrameSlot*> begin(std::uint64_t number);
    /// Writes a timestamp mark.
    void mark(const FrameSlot& slot, Mark mark) const;
    /// Ends the command buffer and submits it: waits for the slot's acquire semaphore (when `acquired`), signals
    /// `rendered` (when given) and the slot's fence.
    [[nodiscard]] Result<void> submit(FrameSlot& slot, bool acquired, vk::Semaphore rendered);
    /// The GPU times of the most recently completed frame.
    [[nodiscard]] const GpuTimes& last_times() const noexcept { return last_; }
    /// Waits until every submitted frame has finished and reads their times (shutdown, resize, tests).
    void drain();

private:
    Frames() = default;
    void read_back(FrameSlot& slot);

    const Device* device_ = nullptr;
    std::array<FrameSlot, in_flight> slots_;
    vk::raii::QueryPool timestamps_{nullptr}; // empty where the queue has no timestamps
    vk::raii::QueryPool statistics_{nullptr}; // empty without pipelineStatisticsQuery
    std::uint32_t next_ = 0;
    double period_ns_ = 1.0;
    std::uint64_t mask_ = 0;
    GpuTimes last_;
};

/// What a frame records into, for the content that draws it.
struct FrameContext {
    const vk::raii::CommandBuffer& cmd;
    const FrameSlot& slot;
    std::uint64_t number;
    std::uint32_t image; // the output image
    const MainPass& pass;
    const Output& output;
    const Sync& sync;
    const Frames& frames;
};

/// What the frames draw: the stand-in for VK bench now, the map's frame builder from K7.
class Content {
public:
    Content() = default;
    Content(const Content&) = delete;
    Content& operator=(const Content&) = delete;
    Content(Content&&) = delete;
    Content& operator=(Content&&) = delete;
    virtual ~Content() = default;

    /// Makes what depends on the main pass and the output (pipelines, images of the output's size, descriptor sets),
    /// after the GPU has finished every frame that used the old ones.
    [[nodiscard]] virtual Result<void> prepare(const MainPass& pass, const Output& output) = 0;
    /// Records one frame: the offscreen passes, then Mark::offscreen; the main pass, then Mark::main; what follows it.
    virtual void record(const FrameContext& frame) = 0;
};

/// One drawn frame, as the frame loop saw it.
struct FrameReport {
    Acquired status = Acquired::ok;
    std::uint64_t number = 0;
    std::uint32_t image = 0;
    double record_ms = 0; // recording the commands
    double cpu_ms = 0;    // from the start of the frame to the present returning
    GpuTimes gpu;         // the most recent frame the GPU finished (one or two frames earlier)
};

/// The frame loop's GPU half: the main pass of one configuration on one output, the frames in flight, the content.
class Renderer {
public:
    [[nodiscard]] static Result<std::unique_ptr<Renderer>> create(const Device& device, Output& output,
                                                                  Content& content, const Configuration& configuration);
    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;
    Renderer(Renderer&&) = delete;
    Renderer& operator=(Renderer&&) = delete;
    ~Renderer();

    /// Draws one frame, presented at `desired_ns` where the output can say when (0: as soon as possible).
    [[nodiscard]] Result<FrameReport> draw(std::uint64_t desired_ns);
    /// Switches the configuration (VK bench): waits for the frames in flight, then remakes the main pass and the
    /// content.
    [[nodiscard]] Result<void> reconfigure(const Configuration& configuration);
    /// The output was remade (a resize, a rotation): the same, for the new output.
    [[nodiscard]] Result<void> retarget(Output& output);
    /// Waits for every frame in flight (before the output or the device goes away).
    void drain();

    [[nodiscard]] const Configuration& configuration() const noexcept { return pass_->spec().config; }
    [[nodiscard]] const MainPass& pass() const noexcept { return *pass_; }
    [[nodiscard]] const Frames& frames() const noexcept { return *frames_; }
    [[nodiscard]] std::uint64_t frame_count() const noexcept { return number_; }

private:
    Renderer(const Device& device, Output& output, Content& content);
    [[nodiscard]] Result<void> remake(const Configuration& configuration);

    const Device& device_;
    Output* output_;
    Content& content_;
    Sync sync_;
    std::unique_ptr<Frames> frames_;
    std::unique_ptr<MainPass> pass_;
    std::uint64_t number_ = 0;
};

} // namespace atlas::gpu
