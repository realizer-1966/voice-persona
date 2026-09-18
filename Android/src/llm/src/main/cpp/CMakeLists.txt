cmake_minimum_required(VERSION 3.22)
project(voicepersona_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Absolute paths keep the source list and include dirs valid regardless of how
# deep the Gradle/NDK build directory sits.
get_filename_component(VOICEPERSONA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)

include(FetchContent)
set(FETCHCONTENT_BASE_DIR "${VOICEPERSONA_ROOT}/Android/src/.native-deps" CACHE PATH "")
# Upstream llama.cpp, pinned. Fetched at configure time so CI needs no submodule.
set(LLAMA_GIT_TAG "01041db99dae68529db58c6fc692a9ca623b30a2")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/PrismML-Eng/llama.cpp.git
    GIT_TAG        ${LLAMA_GIT_TAG}
    GIT_SHALLOW    FALSE
)
FetchContent_MakeAvailable(llama)

add_library(voicepersona_llm SHARED
    voicebridge.cpp
    ${VOICEPERSONA_ROOT}/native/voice_engine.cpp
)

target_include_directories(voicepersona_llm PRIVATE
    ${VOICEPERSONA_ROOT}/native
)

target_link_libraries(voicepersona_llm PRIVATE
    llama
    log
    android
)
