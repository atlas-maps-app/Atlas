# The core's warnings (plan-vector T10), errors in every build. Third-party code is included as SYSTEM headers or
# compiled with its own flags, so these apply to the core's own code only.
include_guard(GLOBAL)

add_library(atlas_warnings INTERFACE)
target_compile_options(atlas_warnings INTERFACE
    -Wall -Wextra -Wpedantic -Werror
    -Wconversion -Wshadow
    # Numerics: a float silently widened to double changes which operations round where.
    -Wdouble-promotion -Wfloat-conversion
    -Wimplicit-fallthrough -Wformat=2 -Wcast-qual -Wundef -Wunused -Wmissing-variable-declarations
    -Wnull-dereference -Wcomma -Wunreachable-code-aggressive
    # A designated initializer that names some fields and leaves the rest zero is how the core fills Vulkan's and
    # VMA's C structs ({.sType = ..., .size = ...}); the NDK's clang 21 warns on it under -Wextra, Apple's does not.
    -Wno-missing-designated-field-initializers
    $<$<COMPILE_LANGUAGE:CXX>:-Wold-style-cast -Wnon-virtual-dtor -Woverloaded-virtual -Wextra-semi -Wrange-loop-analysis>)
