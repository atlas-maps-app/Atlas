# cmake -DLIB=<lib.a> -DNAME=<crate> -DAR=<ar> -DDIR=<scratch> -P RustOverflowChecks.cmake
#
# That a Rust static library was built with its overflow checks on (-Coverflow-checks=on): its crate's own object
# names the overflow panics of Rust's core (core::panicking::panic_const::panic_const_add_overflow and the like),
# which only an overflow check calls. Built with them off, the crate's code names none: an explicit checked_add or
# wrapping_add calls no panic, and the panics it would not call are dropped by LTO. The core's own objects in the
# archive define them either way, so the crate's objects (<crate>-<hash>.<crate>.<hash>-cgu.<n>.rcgu.o) are taken out
# of the archive into DIR and read alone. Prints one line; fails the build if the crate's code names no overflow panic.
#
# atlas_rust_library runs it after cargo when it is given OVERFLOW_CHECKS (cmake/Rust.cmake): the core's fuzz build.
foreach(_var LIB NAME AR DIR)
    if(NOT DEFINED ${_var})
        message(FATAL_ERROR "RustOverflowChecks.cmake: -D${_var}=… is missing")
    endif()
endforeach()
execute_process(COMMAND "${AR}" t "${LIB}" OUTPUT_VARIABLE _members RESULT_VARIABLE _status)
if(NOT _status EQUAL 0)
    message(FATAL_ERROR "RustOverflowChecks.cmake: ${AR} cannot list ${LIB}")
endif()
string(REPLACE "\n" ";" _members "${_members}")
list(FILTER _members INCLUDE REGEX "^${NAME}-[0-9a-f]+\\.${NAME}\\..*-cgu\\.[0-9]+\\.rcgu\\.o$")
if(NOT _members)
    message(FATAL_ERROR "RustOverflowChecks.cmake: ${LIB} holds no object of the crate ${NAME}")
endif()
file(REMOVE_RECURSE "${DIR}")
file(MAKE_DIRECTORY "${DIR}")
execute_process(COMMAND "${AR}" x "${LIB}" ${_members} WORKING_DIRECTORY "${DIR}" RESULT_VARIABLE _status)
if(NOT _status EQUAL 0)
    message(FATAL_ERROR "RustOverflowChecks.cmake: ${AR} cannot take ${_members} out of ${LIB}")
endif()
set(_kinds "")
foreach(_member IN LISTS _members)
    file(STRINGS "${DIR}/${_member}" _named REGEX "panic_const_[a-z]+_overflow")
    foreach(_line IN LISTS _named)
        string(REGEX MATCHALL "panic_const_[a-z]+_overflow" _found "${_line}")
        list(APPEND _kinds ${_found})
    endforeach()
endforeach()
list(TRANSFORM _kinds REPLACE "^panic_const_([a-z]+)_overflow$" "\\1")
list(REMOVE_DUPLICATES _kinds)
list(SORT _kinds)
get_filename_component(_lib "${LIB}" NAME)
if(NOT _kinds)
    message(FATAL_ERROR "Rust: ${_lib}'s own code names no overflow panic: it was built with its overflow checks off")
endif()
list(JOIN _kinds ", " _kinds)
message(STATUS "Rust: ${_lib} is built with its overflow checks on: its code names the overflow panics of ${_kinds}")
