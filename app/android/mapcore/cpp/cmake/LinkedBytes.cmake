# cmake -DMAP=<map> -DRUST_DIR=<dir> -DWORK=<dir> -DWHAT=<words> -DOUT=<file> -P LinkedBytes.cmake
#
# The bytes each Rust archive takes in a library linked from it, from lld's map of the link (-Wl,-Map=<map>), which
# lists every input section kept, with its size and the object it came from. The Rust archives are those under
# RUST_DIR, where cargo builds every crate (cmake/Rust.cmake: ATLAS_RUST_DIR), so a crate added is counted with no
# word here or in the caller. The map names an archive as the link was given it, which for one under the build
# folder is relative to it (rust/aarch64-linux-android/release/libatlas_clean.a): each is taken from WORK, the folder
# the link ran in. Counted: the sections a phone loads (code, read-only data, data, unwind tables). Left
# out: the debug sections, which the APK's library is stripped of, and .bss/.tbss, which take no bytes in the file.
# Prints one line an archive, then one of the map's own cost (its bytes, and the milliseconds this read and count of it
# took), and writes them to OUT (CI's summary reads it). Fails where the map lists nothing of one.
#
# The core's Android builds run it after libatlasmap.so links (cpp/CMakeLists.txt): the cost the Rust trial measured
# once by hand (+192 KB on arm64), now printed by every build.
foreach(_var MAP RUST_DIR WORK WHAT OUT)
    if(NOT DEFINED ${_var})
        message(FATAL_ERROR "LinkedBytes.cmake: -D${_var}=… is missing")
    endif()
endforeach()
string(TIMESTAMP _began "%s%f" UTC) # microseconds
# an input section's line: VMA, LMA, size, align (hex but the align), then <path>/<archive>(<member>):(<section>)
file(STRINGS "${MAP}" _lines REGEX "\\.a\\([^)]*\\):\\(")
cmake_path(SET _rust NORMALIZE "${RUST_DIR}/")
set(_paths "")   # each archive's path as the map gives it,
set(_rusts "")   # and whether it lies under RUST_DIR
set(_archives "")
foreach(_line IN LISTS _lines)
    if(NOT _line MATCHES "^ *[0-9a-f]+ +[0-9a-f]+ +([0-9a-f]+) +[0-9]+ +([^ ].*\\.a)\\([^)]*\\):\\(([^)]*)\\)$")
        continue()
    endif()
    set(_path "${CMAKE_MATCH_2}")
    set(_section "${CMAKE_MATCH_3}")
    math(EXPR _size "0x${CMAKE_MATCH_1}")
    list(FIND _paths "${_path}" _i)
    if(_i LESS 0)
        cmake_path(ABSOLUTE_PATH _path BASE_DIRECTORY "${WORK}" NORMALIZE OUTPUT_VARIABLE _absolute)
        string(FIND "${_absolute}" "${_rust}" _at)
        list(APPEND _paths "${_path}")
        if(_at EQUAL 0)
            list(APPEND _rusts 1)
        else()
            list(APPEND _rusts 0)
        endif()
        list(LENGTH _paths _i)
        math(EXPR _i "${_i} - 1")
    endif()
    list(GET _rusts ${_i} _is_rust)
    if(NOT _is_rust)
        continue()
    endif()
    cmake_path(GET _path FILENAME _archive)
    if(NOT _archive IN_LIST _archives)
        list(APPEND _archives "${_archive}")
        set("_code_${_archive}" 0)
        set("_rest_${_archive}" 0)
    endif()
    if(_section MATCHES "^\\.(debug|bss|tbss|comment)")
        continue()
    elseif(_section MATCHES "^\\.text")
        math(EXPR "_code_${_archive}" "${_code_${_archive}} + ${_size}")
    else()
        math(EXPR "_rest_${_archive}" "${_rest_${_archive}} + ${_size}")
    endif()
endforeach()
if(NOT _archives)
    message(FATAL_ERROR "LinkedBytes.cmake: ${MAP} lists nothing of an archive under ${RUST_DIR}")
endif()
list(SORT _archives)
set(_said "")
foreach(_archive IN LISTS _archives)
    math(EXPR _all "${_code_${_archive}} + ${_rest_${_archive}}")
    if(_all EQUAL 0)
        message(FATAL_ERROR "LinkedBytes.cmake: ${MAP} lists no section of ${_archive} that a phone loads")
    endif()
    string(CONCAT _line "Rust: ${_archive} takes ${_all} bytes of ${WHAT}: code ${_code_${_archive}}, data and unwind "
        "tables ${_rest_${_archive}}")
    message(STATUS "${_line}")
    string(APPEND _said "${_line}\n")
endforeach()
string(TIMESTAMP _ended "%s%f" UTC)
math(EXPR _ms "(${_ended} - ${_began}) / 1000")
file(SIZE "${MAP}" _map_bytes)
set(_line "Rust: lld's map of ${WHAT}: ${_map_bytes} bytes, read and counted in ${_ms} ms")
message(STATUS "${_line}")
file(WRITE "${OUT}" "${_said}${_line}\n")
