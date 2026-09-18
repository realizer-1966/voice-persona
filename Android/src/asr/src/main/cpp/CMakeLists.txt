cmake_minimum_required(VERSION 3.22)
project(voicepersona_asr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# transcribe.cpp pins its own ggml vendored inside its source tree through its
# own CMake options. The public header is enough for our wrapper.
set(TRANSCRIBE_SRC_DIR "${CMAKE_CURRENT_BINARY_DIR}/transcribe.cpp-src" CACHE PATH "")

include(FetchContent)
# Both native modules pull heavy repos; one shared cache keeps CI to a single
# clone of each instead of one per module.
set(FETCHCONTENT_BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.native-deps" CACHE PATH "")
set(TRANSCRIBE_GIT_TAG "be7a8b35e9ba2df20298bd26e32d53407c3bcbcd")

FetchContent_Declare(
    transcribe_cpp
    GIT_REPOSITORY https://github.com/handy-computer/transcribe.cpp.git
    GIT_TAG        ${TRANSCRIBE_GIT_TAG}
    GIT_SHALLOW    FALSE
)
FetchContent_MakeAvailable(transcribe_cpp)

message(STATUS "transcribe.cpp source dir: ${transcribe_cpp_SOURCE_DIR}")

add_library(voicepersona_asr SHARED
    asrbridge.cpp
    ../../../../../../native/asr_engine.cpp
)

target_include_directories(voicepersona_asr PRIVATE
    ../../../../../native
    ${transcribe_cpp_SOURCE_DIR}/include
)

target_link_libraries(voicepersona_asr PRIVATE
    transcribe
    log
    android
)
