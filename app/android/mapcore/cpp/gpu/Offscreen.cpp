// The offscreen output (gpu/Offscreen.hpp).
#include "gpu/Offscreen.hpp"

#include <cstring>
#include <format>

namespace atlas::gpu {

gpu::Result<std::unique_ptr<Offscreen>> Offscreen::create(const gpu::Device& device, vk::Extent2D extent,
                                                          std::uint32_t images, gpu::Depth depth) {
    return make(device, extent, images, gpu::colour_offscreen(device.facts(), depth));
}

gpu::Result<std::unique_ptr<Offscreen>> Offscreen::create(const gpu::Device& device, vk::Extent2D extent,
                                                          std::uint32_t images, vk::Format format) {
    if (words_per_pixel(format) == 0) return gpu::fail("offscreen images of " + vk::to_string(format));
    return make(device, extent, images, {.format = format, .reason = "offscreen: asked for"});
}

gpu::Result<std::unique_ptr<Offscreen>> Offscreen::make(const gpu::Device& device, vk::Extent2D extent,
                                                        std::uint32_t images, gpu::Colour colour) {
    std::unique_ptr<Offscreen> self(new Offscreen()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    self->device_ = &device;
    self->extent_ = extent;
    self->colour_ = std::move(colour);
    const vk::Format format = self->colour_.format;
    for (std::uint32_t i = 0; i < images; ++i) {
        ATLAS_GPU_LET(image, device.memory().image({.imageType = vk::ImageType::e2D,
                                                    .format = format,
                                                    .extent = {extent.width, extent.height, 1},
                                                    .mipLevels = 1,
                                                    .arrayLayers = 1,
                                                    .samples = vk::SampleCountFlagBits::e1,
                                                    .tiling = vk::ImageTiling::eOptimal,
                                                    .usage = vk::ImageUsageFlagBits::eColorAttachment |
                                                             vk::ImageUsageFlagBits::eTransferSrc |
                                                             vk::ImageUsageFlagBits::eTransferDst,
                                                    .initialLayout = vk::ImageLayout::eUndefined},
                                                   gpu::MemoryUse::gpu));
        ATLAS_VK_LET(
            view,
            device.handle().createImageView({.image = image.handle(),
                                             .viewType = vk::ImageViewType::e2D,
                                             .format = format,
                                             .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                                  .levelCount = 1,
                                                                  .layerCount = 1}}),
            "an offscreen image's view");
        self->handles_.push_back(image.handle());
        self->images_.push_back(std::move(image));
        self->view_handles_.push_back(*view);
        self->views_.push_back(std::move(view));
    }
    return self;
}

gpu::Acquired Offscreen::acquire(vk::Semaphore /*acquired*/, std::uint32_t& index) {
    index = next_;
    next_ = (next_ + 1) % static_cast<std::uint32_t>(handles_.size());
    return gpu::Acquired::ok;
}

gpu::Acquired Offscreen::present(std::uint32_t index, std::uint64_t /*frame*/, std::uint64_t /*desired_ns*/) {
    latest_ = index;
    return gpu::Acquired::ok;
}

gpu::Result<Pixels> Offscreen::read(std::uint32_t index) const {
    ATLAS_GPU_LET(packed, read_packed(index));
    return to_rgba8(packed);
}

gpu::Result<Packed> Offscreen::read_packed(std::uint32_t index) const {
    const vk::raii::Device& d = device_->handle();
    const vk::DeviceSize bytes = vk::DeviceSize{extent_.width} * extent_.height * 4 * words_per_pixel(colour_.format);
    ATLAS_GPU_LET(buffer,
                  device_->memory().buffer(bytes, vk::BufferUsageFlagBits::eTransferDst, gpu::MemoryUse::readback));
    ATLAS_VK_LET(pool,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device_->queue_family()}),
                 "the readback's command pool");
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "the readback's command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the readback's commands");
    // The frame left the image in TRANSFER_SRC_OPTIMAL after its last write (the main pass's dependency, or the barrier
    // after the frosted pass); the copy waits for those writes through this barrier.
    const vk::ImageMemoryBarrier before{
        .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eTransferWrite,
        .dstAccessMask = vk::AccessFlagBits::eTransferRead,
        .oldLayout = vk::ImageLayout::eTransferSrcOptimal,
        .newLayout = vk::ImageLayout::eTransferSrcOptimal,
        .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
        .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
        .image = handles_[index],
        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1}};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eColorAttachmentOutput | vk::PipelineStageFlagBits::eTransfer,
                        vk::PipelineStageFlagBits::eTransfer, {}, {}, {}, before);
    const vk::BufferImageCopy region{.bufferOffset = 0,
                                     .bufferRowLength = 0,
                                     .bufferImageHeight = 0,
                                     .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                          .mipLevel = 0,
                                                          .baseArrayLayer = 0,
                                                          .layerCount = 1},
                                     .imageOffset = {.x = 0, .y = 0, .z = 0},
                                     .imageExtent = {.width = extent_.width, .height = extent_.height, .depth = 1}};
    cmd.copyImageToBuffer(handles_[index], vk::ImageLayout::eTransferSrcOptimal, buffer.handle(), region);
    const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                                    .dstAccessMask = vk::AccessFlagBits::eHostRead};
    cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer, vk::PipelineStageFlagBits::eHost, {}, to_host, {}, {});
    ATLAS_VK_CHECK(cmd.end(), "the readback's commands");
    ATLAS_VK_LET(fence, d.createFence({}), "the readback's fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device_->queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the readback");
    if (const vk::Result r = d.waitForFences(*fence, vk::True, UINT64_MAX); r != vk::Result::eSuccess)
        return gpu::fail(r, "the readback");
    buffer.invalidate();
    Packed out{.width = extent_.width, .height = extent_.height, .format = colour_.format, .words = {}};
    const auto mapped = buffer.bytes();
    out.words.resize(static_cast<std::size_t>(bytes) / 4);
    std::memcpy(out.words.data(), mapped.data(), static_cast<std::size_t>(bytes));
    return out;
}

gpu::Result<void> Offscreen::fill(const std::array<float, 4>& rgba) const {
    const vk::raii::Device& d = device_->handle();
    ATLAS_VK_LET(pool,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device_->queue_family()}),
                 "the fill's command pool");
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "the fill's command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the fill's commands");
    const vk::ImageSubresourceRange range{
        .aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1};
    for (const vk::Image image : handles_) {
        const vk::ImageMemoryBarrier to_clear{.srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite |
                                                               vk::AccessFlagBits::eTransferRead,
                                              .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                                              .oldLayout = vk::ImageLayout::eUndefined,
                                              .newLayout = vk::ImageLayout::eTransferDstOptimal,
                                              .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                              .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                              .image = image,
                                              .subresourceRange = range};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eColorAttachmentOutput | vk::PipelineStageFlagBits::eTransfer,
                            vk::PipelineStageFlagBits::eTransfer, {}, {}, {}, to_clear);
        cmd.clearColorImage(image, vk::ImageLayout::eTransferDstOptimal, vk::ClearColorValue{rgba}, range);
        // Left as a frame leaves it, so a frame that writes nothing still reads back as the fill.
        const vk::ImageMemoryBarrier to_read{.srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                                             .dstAccessMask = vk::AccessFlagBits::eTransferRead |
                                                              vk::AccessFlagBits::eColorAttachmentWrite,
                                             .oldLayout = vk::ImageLayout::eTransferDstOptimal,
                                             .newLayout = vk::ImageLayout::eTransferSrcOptimal,
                                             .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                                             .image = image,
                                             .subresourceRange = range};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                            vk::PipelineStageFlagBits::eTransfer | vk::PipelineStageFlagBits::eColorAttachmentOutput,
                            {}, {}, {}, to_read);
    }
    ATLAS_VK_CHECK(cmd.end(), "the fill's commands");
    ATLAS_VK_LET(fence, d.createFence({}), "the fill's fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device_->queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the fill");
    if (const vk::Result r = d.waitForFences(*fence, vk::True, UINT64_MAX); r != vk::Result::eSuccess)
        return gpu::fail(r, "the fill");
    return {};
}

} // namespace atlas::gpu
