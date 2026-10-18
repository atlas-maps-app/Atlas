// Frames in flight (gpu/Frames.hpp).
#include "gpu/Frames.hpp"

#include <algorithm>
#include <chrono>
#include <format>

namespace atlas::gpu {

namespace {
constexpr std::uint32_t marks = static_cast<std::uint32_t>(Mark::count);
} // namespace

Result<Frames> Frames::create(const Device& device) {
    Frames frames;
    frames.device_ = &device;
    const vk::raii::Device& d = device.handle();
    for (std::uint32_t i = 0; i < in_flight; ++i) {
        FrameSlot& s = frames.slots_[i];
        s.slot = i;
        ATLAS_VK_LET(pool,
                     d.createCommandPool({.flags = vk::CommandPoolCreateFlagBits::eTransient,
                                          .queueFamilyIndex = device.queue_family()}),
                     "a frame's command pool");
        s.pool = std::move(pool);
        ATLAS_VK_LET(buffers,
                     d.allocateCommandBuffers(
                         {.commandPool = *s.pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                     "a frame's command buffer");
        s.cmd = std::move(buffers.front());
        ATLAS_VK_LET(fence, d.createFence({}), "a frame's fence");
        s.done = std::move(fence);
        ATLAS_VK_LET(semaphore, d.createSemaphore({}), "a frame's acquire semaphore");
        s.acquired = std::move(semaphore);
    }
    if (device.timestamp_bits() > 0) {
        ATLAS_VK_LET(pool, d.createQueryPool({.queryType = vk::QueryType::eTimestamp, .queryCount = in_flight * marks}),
                     "the timestamp queries");
        frames.timestamps_ = std::move(pool);
        frames.period_ns_ = static_cast<double>(device.facts().limits.timestampPeriod);
        frames.mask_ =
            device.timestamp_bits() >= 64 ? ~std::uint64_t{0} : ((std::uint64_t{1} << device.timestamp_bits()) - 1);
    }
    if (device.enabled().pipeline_statistics) {
        ATLAS_VK_LET(
            pool,
            d.createQueryPool({.queryType = vk::QueryType::ePipelineStatistics,
                               .queryCount = in_flight,
                               .pipelineStatistics = vk::QueryPipelineStatisticFlagBits::eFragmentShaderInvocations}),
            "the pipeline statistics queries");
        frames.statistics_ = std::move(pool);
    }
    return frames;
}

void Frames::read_back(FrameSlot& slot) {
    if (!slot.pending) return;
    slot.pending = false;
    GpuTimes t;
    t.frame = slot.number;
    if (*timestamps_) {
        std::array<std::uint64_t, std::size_t{marks} * 2> values{};
        const vk::Result r =
            timestamps_.getResults(slot.slot * marks, marks, sizeof values, values.data(), 2 * sizeof(std::uint64_t),
                                   vk::QueryResultFlagBits::e64 | vk::QueryResultFlagBits::eWithAvailability);
        bool all = r == vk::Result::eSuccess || r == vk::Result::eNotReady;
        for (std::size_t m = 0; m < marks; ++m) all = all && values[2 * m + 1] != 0;
        if (all) {
            auto at = [&](Mark m) {
                return static_cast<double>(values[2 * static_cast<std::size_t>(m)] & mask_) * period_ns_;
            };
            t.total_ns = std::max(0.0, at(Mark::after) - at(Mark::start));
            t.offscreen_ns = std::max(0.0, at(Mark::offscreen) - at(Mark::start));
            t.main_ns = std::max(0.0, at(Mark::main) - at(Mark::offscreen));
            t.after_ns = std::max(0.0, at(Mark::after) - at(Mark::main));
            t.valid = true;
        }
    }
    if (*statistics_) {
        std::array<std::uint64_t, 2> value{};
        const vk::Result r =
            statistics_.getResults(slot.slot, 1, sizeof value, value.data(), sizeof value,
                                   vk::QueryResultFlagBits::e64 | vk::QueryResultFlagBits::eWithAvailability);
        if ((r == vk::Result::eSuccess || r == vk::Result::eNotReady) && value[1] != 0) t.fragments = value[0];
    }
    last_ = t;
}

Result<FrameSlot*> Frames::begin(std::uint64_t number) {
    FrameSlot& slot = slots_[next_];
    next_ = (next_ + 1) % in_flight;
    const vk::raii::Device& d = device_->handle();
    if (slot.pending) {
        const vk::Result waited = d.waitForFences(*slot.done, vk::True, UINT64_MAX);
        if (waited != vk::Result::eSuccess) return fail(waited, "a frame's fence");
        read_back(slot);
    }
    ATLAS_VK_CHECK(d.resetFences(*slot.done), "resetting a frame's fence");
    ATLAS_VK_CHECK(slot.pool.reset({}), "resetting a frame's command pool");
    ATLAS_VK_CHECK(slot.cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}),
                   "beginning a frame's commands");
    slot.number = number;
    const vk::raii::CommandBuffer& cmd = slot.cmd;
    if (*timestamps_) cmd.resetQueryPool(*timestamps_, slot.slot * marks, marks);
    if (*statistics_) {
        cmd.resetQueryPool(*statistics_, slot.slot, 1);
        cmd.beginQuery(*statistics_, slot.slot, {});
    }
    mark(slot, Mark::start);
    return &slot;
}

void Frames::mark(const FrameSlot& slot, Mark m) const {
    if (!*timestamps_) return;
    const vk::PipelineStageFlagBits stage =
        m == Mark::start ? vk::PipelineStageFlagBits::eTopOfPipe : vk::PipelineStageFlagBits::eBottomOfPipe;
    slot.cmd.writeTimestamp(stage, *timestamps_, slot.slot * marks + static_cast<std::uint32_t>(m));
}

Result<void> Frames::submit(FrameSlot& slot, bool acquired, vk::Semaphore rendered) {
    const vk::raii::CommandBuffer& cmd = slot.cmd;
    if (*statistics_) cmd.endQuery(*statistics_, slot.slot);
    ATLAS_VK_CHECK(cmd.end(), "ending a frame's commands");
    const vk::Semaphore wait = *slot.acquired;
    // The acquired image is first touched by the main pass's colour writes (or the barrier before them).
    const vk::PipelineStageFlags wait_stage = vk::PipelineStageFlagBits::eColorAttachmentOutput;
    const vk::CommandBuffer raw = *cmd;
    const vk::SubmitInfo info{.waitSemaphoreCount = acquired ? 1U : 0U,
                              .pWaitSemaphores = &wait,
                              .pWaitDstStageMask = &wait_stage,
                              .commandBufferCount = 1,
                              .pCommandBuffers = &raw,
                              .signalSemaphoreCount = rendered ? 1U : 0U,
                              .pSignalSemaphores = &rendered};
    ATLAS_VK_CHECK(device_->queue().submit(info, *slot.done), std::format("submitting frame {}", slot.number));
    slot.pending = true;
    return {};
}

void Frames::drain() {
    for (std::uint32_t i = 0; i < in_flight; ++i) {
        FrameSlot& slot = slots_[(next_ + i) % in_flight];
        if (!slot.pending) continue;
        (void)device_->handle().waitForFences(*slot.done, vk::True, UINT64_MAX);
        read_back(slot);
    }
}

Renderer::Renderer(const Device& device, Output& output, Content& content, PipelineWatch* watch)
    : device_(device), output_(&output), content_(content), watch_(watch), sync_(device.enabled().synchronization2) {}

Renderer::~Renderer() {
    drain();
    release_frame();
}

Result<std::unique_ptr<Renderer>> Renderer::create(const Device& device, Output& output, Content& content,
                                                   const Configuration& configuration, PipelineWatch* watch) {
    std::unique_ptr<Renderer> self(
        new Renderer(device, output, content, watch)); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    ATLAS_GPU_LET(frames, Frames::create(device));
    self->frames_ = std::make_unique<Frames>(std::move(frames));
    ATLAS_GPU_CHECK(self->remake(configuration));
    return self;
}

void Renderer::release_frame() {
    if (!pass_) return; // nothing made yet (prepared_ is set only once a pass is)
    // Named by the pass's configuration and colour, as its pipelines are: a driver crashing as a 10-bit frame goes is
    // told from one crashing as an 8-bit frame goes. The name lasts while the frame goes, and no longer: the next
    // frame's pass and content (remake) are made under their own names.
    const std::string detail =
        std::format("(configuration {} {})", name_of(pass_->spec().config), colour_name(pass_->spec().colour_format));
    const Announced named =
        announce_release({.kind = "frame", .detail = detail, .ten_bit = ten_bit(pass_->spec().colour_format)}, watch_);
    if (prepared_) content_.release();
    prepared_ = false;
    pass_.reset();
}

Result<void> Renderer::remake(const Configuration& configuration) {
    release_frame();
    // The scene's and the composite's colour attachments follow the output (gpu/Facts.hpp's colour): a resolve keeps
    // one format, and the output's colour is one the device draws the frame in.
    const MainPassSpec spec{.config = configuration,
                            .extent = output_->extent(),
                            .colour_format = output_->format(),
                            .output_format = output_->format(),
                            .depth_stencil = device_.facts().depth_stencil,
                            .output_final = output_->final_layout()};
    ATLAS_GPU_LET(pass, MainPass::create(device_, spec, output_->views(), output_->images()));
    pass_ = std::make_unique<MainPass>(std::move(pass));
    // From here the content holds what this renderer prepared, whole or in part: released with the frame.
    prepared_ = true;
    return content_.prepare(*pass_, *output_);
}

void Renderer::drain() {
    if (frames_) frames_->drain();
}

Result<void> Renderer::reconfigure(const Configuration& configuration) {
    drain();
    return remake(configuration);
}

Result<void> Renderer::retarget(Output& output) {
    drain();
    output_ = &output;
    return remake(pass_ ? pass_->spec().config : Configuration{});
}

Result<FrameReport> Renderer::draw(std::uint64_t desired_ns) {
    using clock = std::chrono::steady_clock;
    const auto start = clock::now();
    FrameReport report;
    report.number = ++number_;
    ATLAS_GPU_LET(slot, frames_->begin(report.number));
    std::uint32_t image = 0;
    report.status = output_->acquire(*slot->acquired, image);
    if (report.status != Acquired::ok && report.status != Acquired::suboptimal) {
        report.gpu = frames_->last_times();
        return report;
    }
    report.image = image;
    const auto recording = clock::now();
    content_.record({.cmd = slot->cmd,
                     .slot = *slot,
                     .number = report.number,
                     .image = image,
                     .pass = *pass_,
                     .output = *output_,
                     .sync = sync_,
                     .frames = *frames_});
    report.record_ms = std::chrono::duration<double, std::milli>(clock::now() - recording).count();
    ATLAS_GPU_CHECK(frames_->submit(*slot, output_->acquire_signals(), output_->rendered(image)));
    const Acquired presented = output_->present(image, report.number, desired_ns);
    if (presented != Acquired::ok) report.status = presented;
    report.gpu = frames_->last_times();
    report.cpu_ms = std::chrono::duration<double, std::milli>(clock::now() - start).count();
    return report;
}

} // namespace atlas::gpu
