cmake_minimum_required(VERSION 3.22)
project(voicepersona_asr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Absolute paths keep the source list and include dirs valid regardless of how
# deep the Gradle/NDK build directory sits.
get_filename_component(VOICEPERSONA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)

include(FetchContent)
set(FETCHCONTENT_BASE_DIR "${VOICEPERSONA_ROOT}/Android/src/.native-deps" CACHE PATH "")
set(TRANSCRIBE_GIT_TAG "be7a8b35e9ba2df20298bd26e32d53407c3bcbcd")

FetchContent_Declare(
    transcribe_cpp
    GIT_REPOSITORY https://github.com/handy-computer/transcribe.cpp.git
    GIT_TAG        ${TRANSCRIBE_GIT_TAG}
    GIT_SHALLOW    FALSE
)
FetchContent_GetProperties(transcribe_cpp)
if(NOT transcribe_cpp_POPULATED)
    FetchContent_Populate(transcribe_cpp)
endif()

# transcribe.cpp publishes its public header with
# `$<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/include>`. CMAKE_SOURCE_DIR here is our
# module, not transcribe.cpp, so a nested build (FetchContent or
# add_subdirectory) never puts transcribe.h on the include path. The relative
# form below resolves correctly in both a standalone and a nested build, and the
# patch must land before the subdirectory is configured. Verified against the
# pinned revision; a FATAL_ERROR keeps a future upstream change from silently
# passing.
set(_transcribe_src_cmake "${transcribe_cpp_SOURCE_DIR}/src/CMakeLists.txt")
file(READ "${_transcribe_src_cmake}" _transcribe_src_text)
# Bracket arguments: a quoted argument would expand ${CMAKE_SOURCE_DIR} at
# parse time and the pattern would never match the file's literal text.
string(REPLACE
    [==[$<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/include>]==]
    [==[$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/../include>]==]
    _transcribe_src_text_fixed
    "${_transcribe_src_text}")
if(_transcribe_src_text_fixed STREQUAL _transcribe_src_text)
    message(FATAL_ERROR "transcribe.cpp include patch did not apply; upstream layout changed")
endif()
file(WRITE "${_transcribe_src_cmake}" "${_transcribe_src_text_fixed}")

# FetchContent_MakeAvailable skips add_subdirectory for already-populated
# content, which is exactly the state after FetchContent_Populate above.
add_subdirectory("${transcribe_cpp_SOURCE_DIR}" "${transcribe_cpp_BINARY_DIR}")

add_library(voicepersona_asr SHARED
    asrbridge.cpp
    ${VOICEPERSONA_ROOT}/native/asr_engine.cpp
)

target_include_directories(voicepersona_asr PRIVATE
    ${VOICEPERSONA_ROOT}/native
    ${transcribe_cpp_SOURCE_DIR}/include
)

target_link_libraries(voicepersona_asr PRIVATE
    transcribe
    log
    android
)
