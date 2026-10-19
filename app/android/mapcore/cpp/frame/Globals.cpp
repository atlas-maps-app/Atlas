// Set 1's globals that no pass owns (frame/Globals.hpp).
#include "frame/Globals.hpp"

#include "base/assert.hpp"

#include <algorithm>

namespace atlas::frame {

bool Globals::owns(Global g) const noexcept {
    return std::ranges::find(provider_.bindings(), g) != provider_.bindings().end();
}

void Globals::buffer(Global g, vk::Buffer buffer, vk::DeviceSize offset, vk::DeviceSize range) {
    ATLAS_ASSERT(owns(g) && is_buffer(g), "a provider sets a storage buffer binding of its own");
    layout_.set_buffer(g, buffer, offset, range);
}

void Globals::image(Global g, vk::ImageView view, vk::Sampler sampler, vk::ImageLayout layout) {
    ATLAS_ASSERT(owns(g) && !is_buffer(g), "a provider sets an image binding of its own");
    layout_.set_image(g, view, sampler, layout);
}

void Globals::reset(Global g) {
    ATLAS_ASSERT(owns(g), "a provider resets a binding of its own");
    layout_.reset(g);
}

} // namespace atlas::frame
