// The pass registry (plan-vector D19): every pass of the renderer, one registration line each in PassRegistry.cpp (the
// coordinator's lines, after K7 created the file), in the order the frame records phases that share a slot and an
// order. A frame builder makes its own passes from it (make_passes): passes hold pipelines for one main pass, so each
// builder (the map's, VK check's, the likeness candidate's) has its own.
#pragma once

#include "frame/Pass.hpp"

#include <functional>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::frame {

/// One registered pass: its name and how to make it.
struct Registration {
    std::string_view name;
    std::unique_ptr<Pass> (*make)();
};

/// Every registered pass, in the registry's order.
[[nodiscard]] std::span<const Registration> registrations() noexcept;

/// A new instance of every registered pass, in the registry's order; with `only` given, of those named in it alone.
[[nodiscard]] std::vector<std::unique_ptr<Pass>> make_passes(std::span<const std::string_view> only = {});

} // namespace atlas::frame
