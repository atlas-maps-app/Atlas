# Rust libraries (the core's buildings' cleaning, and what else includes this file): a crate built by cargo into the
# build directory as a static library, linked by the build's own linker (the NDK's on Android).
#
#   atlas_rust_library(<target> CRATE <dir> LIB <name> [RUSTFLAGS <flags>] [CARGO_ARGS <arg>...])
#
# makes <target>, an imported static library (lib<name>.a, with what Rust's standard library links), and
# <target>_build, the cargo build that makes it: `cargo build --release --offline --locked --lib` in the crate's folder
# (CRATE, relative to the calling CMakeLists.txt's folder), for the Android ABI's target on Android, into
# ATLAS_RUST_DIR (rust/ under the build directory). RUSTFLAGS is passed to rustc as it is; CARGO_ARGS go after cargo's
# own (features, say). A crate takes the standard library and nothing else, so cargo fetches nothing (--offline
# --locked); a crate from the network would fail the build, not be fetched.
#
# The fuzz build (ATLAS_SANITIZE fuzzer, cmake/Sanitizers.cmake) builds every crate so, with no word from the call:
# - instrumented for libFuzzer as the C++ is (sanitizer coverage);
# - with its overflow checks on (-Coverflow-checks=on), which the release profile has off: there an overflow is a
#   panic, which aborts, and libFuzzer reports it where the shipped build would wrap. The build is held to it: after
#   cargo, cmake/RustOverflowChecks.cmake reads the crate's own objects, in a folder of the library's own, and fails
#   the build if they name none of the panics only an overflow check calls.
# The other sanitizers leave a crate as it is (it holds no C++ and shares no memory it does not own).
#
# The Rust it builds with is the one the nearest rust-toolchain.toml pins, in the crate's folder or above it, as
# rustup picks it; for Atlas's crates, app/android/rust-toolchain.toml. A change of that pin, of the crate's
# Cargo.toml, Cargo.lock or a source under src/ builds the library again. rustup installs nothing behind the build's
# back (RUSTUP_AUTO_INSTALL=0): a Rust that is not installed fails the build, and installing it is the machine's own
# step (on CI .github/scripts/rust.sh, which reads the same pin). The Android targets are added the same way.
#
# cargo is found on the PATH, or where rustup puts it (~/.cargo/bin, or $CARGO_HOME/bin): ATLAS_CARGO.
include_guard(GLOBAL)
# Where cargo builds every crate, for the whole build (a cache entry, so any folder sees it): cmake/LinkedBytes.cmake
# counts every archive under it that a link takes.
set(ATLAS_RUST_DIR "${CMAKE_BINARY_DIR}/rust" CACHE INTERNAL "where cargo builds the Rust crates")

function(atlas_rust_library target)
    cmake_parse_arguments(PARSE_ARGV 1 arg "" "CRATE;LIB;RUSTFLAGS" "CARGO_ARGS")
    if(arg_UNPARSED_ARGUMENTS)
        message(FATAL_ERROR "atlas_rust_library(${target}): what is '${arg_UNPARSED_ARGUMENTS}'?")
    endif()
    if(NOT arg_CRATE OR NOT arg_LIB)
        message(FATAL_ERROR "atlas_rust_library(${target}) takes CRATE <dir> and LIB <name>.")
    endif()
    find_program(ATLAS_CARGO cargo HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" REQUIRED)
    get_filename_component(crate "${arg_CRATE}" ABSOLUTE BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
    if(NOT EXISTS "${crate}/Cargo.toml")
        message(FATAL_ERROR "atlas_rust_library(${target}): no Cargo.toml in ${crate}.")
    endif()

    # the pin, as rustup finds it: the nearest folder from the crate's up that holds one
    set(pin "")
    set(dir "${crate}")
    while(TRUE)
        foreach(name IN ITEMS rust-toolchain rust-toolchain.toml)
            if(NOT pin AND EXISTS "${dir}/${name}" AND NOT IS_DIRECTORY "${dir}/${name}")
                set(pin "${dir}/${name}")
            endif()
        endforeach()
        if(pin)
            break()
        endif()
        get_filename_component(up "${dir}" DIRECTORY)
        if(up STREQUAL dir)
            break()
        endif()
        set(dir "${up}")
    endwhile()
    if(NOT pin)
        message(FATAL_ERROR "atlas_rust_library(${target}): no rust-toolchain.toml in ${crate} or above it; the Rust "
            "a crate builds with is pinned.")
    endif()

    set(cargo_dir "${ATLAS_RUST_DIR}")
    set(triple "")
    set(libs "")
    if(CMAKE_SYSTEM_NAME STREQUAL "Android")
        if(ANDROID_ABI STREQUAL "arm64-v8a")
            set(triple aarch64-linux-android)
        elseif(ANDROID_ABI STREQUAL "x86_64")
            set(triple x86_64-linux-android)
        else()
            message(FATAL_ERROR "No Rust target for the Android ABI '${ANDROID_ABI}'.")
        endif()
        # what the standard library links there (cargo rustc -- --print native-static-libs)
        set(libs dl log m c)
    elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        set(libs gcc_s util rt pthread m dl c)
    endif()
    if(triple)
        set(lib "${cargo_dir}/${triple}/release/lib${arg_LIB}.a")
        set(triple_arg --target ${triple})
    else()
        set(lib "${cargo_dir}/release/lib${arg_LIB}.a")
        set(triple_arg "")
    endif()

    set(rustflags "${arg_RUSTFLAGS}")
    set(check "")
    set(check_script "")
    if(ATLAS_SANITIZE STREQUAL "fuzzer")
        string(JOIN " " rustflags ${rustflags} -Cpasses=sancov-module -Cllvm-args=-sanitizer-coverage-level=4
            -Cllvm-args=-sanitizer-coverage-inline-8bit-counters -Cllvm-args=-sanitizer-coverage-pc-table
            -Cllvm-args=-sanitizer-coverage-trace-compares -Coverflow-checks=on -Coverflow-checks=off)
        # this file's folder, wherever the function is called from
        set(check_script "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/RustOverflowChecks.cmake")
        set(check COMMAND "${CMAKE_COMMAND}" "-DLIB=${lib}" "-DNAME=${arg_LIB}" "-DAR=${CMAKE_AR}"
                  "-DDIR=${cargo_dir}/overflow-checks/${arg_LIB}" -P "${check_script}")
    endif()

    file(GLOB_RECURSE sources CONFIGURE_DEPENDS "${crate}/src/*.rs")
    # the crate as the build names it: its folder from the pin's (mapcore/rust/clean), or its own name
    file(RELATIVE_PATH shown "${dir}" "${crate}")
    if(NOT shown)
        get_filename_component(shown "${crate}" NAME)
    endif()
    add_custom_command(
        OUTPUT "${lib}"
        COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${cargo_dir}" "RUSTFLAGS=${rustflags}"
                RUSTUP_AUTO_INSTALL=0 -- "${ATLAS_CARGO}" build --release --offline --locked --lib --quiet
                ${triple_arg} ${arg_CARGO_ARGS}
        ${check}
        DEPENDS ${sources} "${crate}/Cargo.toml" "${crate}/Cargo.lock" "${pin}" ${check_script}
        WORKING_DIRECTORY "${crate}"
        COMMENT "Building lib${arg_LIB}.a (Rust, ${shown})"
        VERBATIM USES_TERMINAL)
    add_custom_target(${target}_build DEPENDS "${lib}")
    add_library(${target} STATIC IMPORTED GLOBAL)
    set_target_properties(${target} PROPERTIES IMPORTED_LOCATION "${lib}" INTERFACE_LINK_LIBRARIES "${libs}")
    add_dependencies(${target} ${target}_build)
    if(rustflags)
        message(STATUS "Rust: lib${arg_LIB}.a from ${shown}, RUSTFLAGS ${rustflags}")
    endif()
endfunction()
