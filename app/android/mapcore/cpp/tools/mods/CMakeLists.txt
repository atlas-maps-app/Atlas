# The MODS probe (plan-vector T3, package MODS): does `import std`, and Vulkan-Hpp's named module `import vulkan` on
# top of it, build with this toolchain? Needs CMake 4.2 or later (Kitware's, pinned in mapcore/tools.properties); run
# through probe.sh, which tries the host compiler and NDK r30 and writes the answer with its errors. Not part of the
# core's build: the core adopts modules only if both build for Android and on both hosts.
cmake_minimum_required(VERSION 4.2)
# The value CMake 4.2 asks for before it enables `import std` (Help/dev/experimental.rst of v4.2.3).
set(CMAKE_EXPERIMENTAL_CXX_IMPORT_STD "d0edc3af-4c50-42ea-a356-e2862fe7a444")
project(atlas_mods LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_CXX_MODULE_STD ON)

set(ATLAS_VULKAN_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/vulkan-headers/include")

add_executable(use_std use_std.cpp)

add_library(vulkan_module)
target_sources(vulkan_module PUBLIC FILE_SET CXX_MODULES BASE_DIRS "${ATLAS_VULKAN_HEADERS}"
    FILES "${ATLAS_VULKAN_HEADERS}/vulkan/vulkan.cppm")
target_include_directories(vulkan_module PUBLIC "${ATLAS_VULKAN_HEADERS}")
target_compile_definitions(vulkan_module PUBLIC VK_NO_PROTOTYPES VULKAN_HPP_CXX_MODULE_EXPERIMENTAL_WARNING
    VULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1 VULKAN_HPP_NO_EXCEPTIONS VULKAN_HPP_USE_STD_EXPECTED)
target_compile_options(vulkan_module PUBLIC -fno-exceptions -fno-rtti)

add_executable(use_vulkan use_vulkan.cpp)
target_link_libraries(use_vulkan PRIVATE vulkan_module)
