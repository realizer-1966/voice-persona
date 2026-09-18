cmake_minimum_required(VERSION 3.22)
project(voicepersona_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Upstream llama.cpp, pinned. Fetched at configure time so CI needs no submodule.
include(FetchContent)
# Both native modules pull heavy repos; one shared cache keeps CI to a single
# clone of each instead of one per module.
set(FETCHCONTENT_BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.native-deps" CACHE PATH "")
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
    ../../../../../../native/voice_engine.cpp
)

target_include_directories(voicepersona_llm PRIVATE
    ../../../../../native
)

target_link_libraries(voicepersona_llm PRIVATE
    llama
    log
    android
)
