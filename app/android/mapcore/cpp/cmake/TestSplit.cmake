# The ctest entry atlas_tests_split (CMakeLists.txt): the split of atlas_tests into one entry per test file (or its
# parts) and atlas_tests_rest runs every test case exactly once. doctest's own filters list each entry's cases
# (--list-test-cases with the entry's arguments, from atlas_tests_split.tsv, counted in its XML): every case passes
# atlas_tests_rest's filter or a file's, and a file's parts take consecutive ranges of its cases, the last one open, so
# the counts add up to the whole list's exactly when no case passes two. It fails when they do not, and when a file's
# entry takes no case (its pattern misses the cases' __FILE__, which would leave them all to atlas_tests_rest, or a
# part lies past the file's last case); it names the cases atlas_tests_rest takes, if any.
#   cmake -DATLAS_TESTS_EXE=<atlas_tests> -DATLAS_TESTS_SPLIT=<atlas_tests_split.tsv> -P TestSplit.cmake
cmake_minimum_required(VERSION 3.28)

foreach(var IN ITEMS ATLAS_TESTS_EXE ATLAS_TESTS_SPLIT)
    if(NOT DEFINED ${var})
        message(FATAL_ERROR "TestSplit.cmake needs -D${var}")
    endif()
endforeach()

# The listing needs no Vulkan: main.cpp keeps the loader, the driver and the layer resident when the environment names
# them, which would cost each listing their loading.
foreach(var IN ITEMS ATLAS_VULKAN_LOADER VK_DRIVER_FILES VK_ADD_LAYER_PATH)
    unset(ENV{${var}})
endforeach()

# The number of test cases the arguments in ARGN select, into out (and the listing into out_names): the cases doctest
# lists in its XML, which keeps to --first and --last, where its own count line counts every case the filters pass.
function(atlas_count_cases out out_names)
    execute_process(COMMAND "${ATLAS_TESTS_EXE}" --list-test-cases --reporters=xml ${ARGN}
        OUTPUT_VARIABLE text ERROR_VARIABLE errors RESULT_VARIABLE result)
    if(NOT result EQUAL 0)
        message(FATAL_ERROR "atlas_tests --list-test-cases ${ARGN} failed (${result}):\n${text}${errors}")
    endif()
    if(NOT text MATCHES "<OverallResultsTestCases")
        message(FATAL_ERROR "atlas_tests --list-test-cases ${ARGN} listed nothing:\n${text}${errors}")
    endif()
    string(REGEX MATCHALL "<TestCase " cases "${text}")
    list(LENGTH cases count)
    set(${out} "${count}" PARENT_SCOPE)
    set(${out_names} "${text}" PARENT_SCOPE)
endfunction()

atlas_count_cases(total ignored)
file(STRINGS "${ATLAS_TESTS_SPLIT}" lines)
set(sum 0)
set(entries 0)
set(empty "")
foreach(line IN LISTS lines)
    string(REPLACE "\t" ";" fields "${line}")
    list(POP_FRONT fields name)
    atlas_count_cases(count names ${fields})
    math(EXPR sum "${sum} + ${count}")
    math(EXPR entries "${entries} + 1")
    if(name STREQUAL "atlas_tests_rest")
        if(count GREATER 0)
            message(STATUS "atlas_tests_rest takes ${count} test cases that no file's entry takes:\n${names}")
        endif()
    elseif(count EQUAL 0)
        list(APPEND empty "${name}")
    endif()
endforeach()

if(empty)
    list(JOIN empty ", " empty)
    message(FATAL_ERROR "these entries take no test case, so their files' cases fall to atlas_tests_rest (does the "
        "cases' __FILE__ end in tests/<area>/<name>.cpp?): ${empty}")
endif()
if(NOT sum EQUAL total)
    message(FATAL_ERROR "the ${entries} entries take ${sum} test cases between them, but atlas_tests has ${total}: "
        "a case passes more than one entry's filter")
endif()
message(STATUS "the ${entries} entries take each of atlas_tests' ${total} test cases once")
