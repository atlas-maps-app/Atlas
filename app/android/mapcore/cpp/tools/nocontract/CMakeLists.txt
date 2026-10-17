# nocontract, the build's check of exact shader modules. A host tool: a subdirectory of host builds, and its own small
# project (built by ExternalProject with the host compiler) when the core is cross-compiled for Android. It needs only
# C++23's <expected> and the C++20 library: Apple clang, clang 19 or later, or GCC 12 or later, but not clang 18 with
# libstdc++ (GitHub's ubuntu-24.04 default), whose <expected> is empty; cmake/Toolchains.cmake picks one that has it.
cmake_minimum_required(VERSION 3.28...4.1)
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(atlas_nocontract LANGUAGES CXX)
    set(CMAKE_CXX_STANDARD 23)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
    set(CMAKE_CXX_EXTENSIONS OFF)
endif()

add_executable(nocontract nocontract.cpp)
target_compile_options(nocontract PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra -Wpedantic -Wconversion -Wshadow)
