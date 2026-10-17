# Microbenchmarks (plan-vector T5, T7: nanobench): each bench/<name>_bench.cpp is an executable over the core's
# objects. They print a table; CI's host-release job runs them and keeps the output, and the plan's per-loop budgets
# (ranks, stage F, the noise fields, tile builds) are checked by their own packages' tests.
add_library(atlas_nanobench STATIC "${CMAKE_CURRENT_BINARY_DIR}/nanobench.cpp")
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/nanobench.cpp"
    CONTENT "#define ANKERL_NANOBENCH_IMPLEMENT\n#include <nanobench.h>\n")
target_include_directories(atlas_nanobench SYSTEM PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/nanobench/include")
target_link_libraries(atlas_nanobench PRIVATE atlas_options)

file(GLOB _bench_sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*_bench.cpp")
foreach(source IN LISTS _bench_sources)
    get_filename_component(name "${source}" NAME_WE)
    add_executable(${name} "${source}" ${_core_objects})
    target_link_libraries(${name} PRIVATE atlas_core_requirements atlas_nanobench)
    add_dependencies(${name} atlas_core_shaders)
    atlas_tidy(${name})
endforeach()
