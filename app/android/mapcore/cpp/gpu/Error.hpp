// The device layer's errors (plan-vector T3, D5.10): every fallible Vulkan step returns std::expected<T, gpu::Error>,
// which names the step and carries Vulkan's result code, so a failure reaches the facts, the logs and the façade as a
// sentence instead of an abort. Setup paths only: nothing here runs per frame.
#pragma once

#include "base/vulkan.hpp"

#include <expected>
#include <string>
#include <utility>

namespace atlas::gpu {

/// A failed step: what was being done and Vulkan's result (eSuccess when the failure is not Vulkan's, such as a
/// missing loader or a refused device).
struct Error {
    vk::Result code = vk::Result::eSuccess;
    std::string what;

    /// "what: ErrorOutOfDeviceMemory" or just "what".
    [[nodiscard]] std::string text() const {
        return code == vk::Result::eSuccess ? what : what + ": " + vk::to_string(code);
    }
};

template <class T>
using Result = std::expected<T, Error>;

/// An Error for a Vulkan call that returned `code`.
[[nodiscard]] inline std::unexpected<Error> fail(vk::Result code, std::string what) {
    return std::unexpected(Error{.code = code, .what = std::move(what)});
}

/// An Error that is not Vulkan's.
[[nodiscard]] inline std::unexpected<Error> fail(std::string what) {
    return std::unexpected(Error{.code = vk::Result::eSuccess, .what = std::move(what)});
}

} // namespace atlas::gpu

/// Declares `var` from a Vulkan-Hpp expected, or returns the gpu::Error naming `what` from the enclosing function
/// (which returns a gpu::Result): `ATLAS_VK_LET(fence, device.createFence({}), "the frame's fence");`.
#define ATLAS_VK_LET(var, expression, what)                                                                            \
    auto var##_expected_ = (expression);                                                                               \
    if (!var##_expected_) return ::atlas::gpu::fail(var##_expected_.error(), (what));                                  \
    auto var = std::move(*var##_expected_) // NOLINT(bugprone-macro-parentheses): `var` is the name declared

/// Returns the gpu::Error naming `what` when a Vulkan-Hpp expected<void> failed.
#define ATLAS_VK_CHECK(expression, what)                                                                               \
    do {                                                                                                               \
        if (auto atlas_vk_done_ = (expression); !atlas_vk_done_)                                                       \
            return ::atlas::gpu::fail(atlas_vk_done_.error(), (what));                                                 \
    } while (false)

/// Declares `var` from a gpu::Result, or returns its error from the enclosing function.
#define ATLAS_GPU_LET(var, expression)                                                                                 \
    auto var##_expected_ = (expression);                                                                               \
    if (!var##_expected_) return std::unexpected(std::move(var##_expected_.error()));                                  \
    auto var = std::move(*var##_expected_) // NOLINT(bugprone-macro-parentheses): `var` is the name declared

/// Returns a gpu::Result<void>'s error from the enclosing function.
#define ATLAS_GPU_CHECK(expression)                                                                                    \
    do {                                                                                                               \
        if (auto atlas_gpu_done_ = (expression); !atlas_gpu_done_)                                                     \
            return std::unexpected(std::move(atlas_gpu_done_.error()));                                                \
    } while (false)
