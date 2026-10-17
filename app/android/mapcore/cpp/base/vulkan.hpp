// Vulkan for the core (plan-vector T5): Vulkan-Hpp's RAII bindings over the vendored Vulkan-Headers, configured here
// once so every translation unit sees the same Vulkan-Hpp. Include this, never <vulkan/...> directly; the units that
// use it compile it as a precompiled header (CMakeLists.txt), so its size costs memory once.
//
// - The core opens the Vulkan loader itself (libvulkan.so on the phone, the path the tests are given on hosts) and
//   hands vkGetInstanceProcAddr to vk::raii::Context, so the host build loads lavapipe through the loader exactly as
//   the phone loads its driver, and nothing links libvulkan. Dispatch is dynamic; RAII objects carry their own
//   dispatchers. The global default dispatcher is declared but never defined: a call that would fall back to it
//   fails to link, instead of running through an empty table.
// - No exceptions: creation and every fallible call return std::expected<T, vk::Result>, and a failure comes back as a
//   value (Vulkan-Hpp would otherwise assert on it in debug builds).
// - Designated initializers instead of struct constructors: vk::BufferCreateInfo{.size = n, .usage = ...}.
// - VK_USE_PLATFORM_ANDROID_KHR on every target (CMake's atlas_vulkan_headers): the host build compiles the phone's
//   code paths, Android surfaces and Android's Vulkan profiles included; vulkan_android.h only declares
//   ANativeWindow and AHardwareBuffer.
#pragma once

#if defined(VULKAN_HPP)
#error "include base/vulkan.hpp before any Vulkan-Hpp header, so its configuration applies"
#endif
#if !defined(VK_NO_PROTOTYPES) || !defined(VK_USE_PLATFORM_ANDROID_KHR)
#error "the core builds with VK_NO_PROTOTYPES and VK_USE_PLATFORM_ANDROID_KHR (CMake's atlas_vulkan_headers)"
#endif

#define VULKAN_HPP_DISPATCH_LOADER_DYNAMIC 1
#define VULKAN_HPP_ENABLE_DYNAMIC_LOADER_TOOL 0
#define VULKAN_HPP_NO_EXCEPTIONS
#define VULKAN_HPP_USE_STD_EXPECTED
#define VULKAN_HPP_ASSERT_ON_RESULT(expression) static_cast<void>(expression)
#define VULKAN_HPP_NO_CONSTRUCTORS
#define VULKAN_HPP_NO_SMART_HANDLE

#include <vulkan/vulkan_raii.hpp> // IWYU pragma: export
