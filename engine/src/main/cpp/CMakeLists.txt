cmake_minimum_required(VERSION 3.22.1)
project(voiengine C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# ---- ggml + llama (static, CPU only) --------------------------------------
# One ggml copy is shared by both llama and whisper so their symbols never clash.
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
# Snapdragon 8 Gen 1+ / Tensor G2+ / Dimensity 9000+: dotprod, fp16 and int8 matmul.
set(GGML_CPU_ARM_ARCH     "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/llama.cpp build-llama)

# ---- whisper (compiled against llama.cpp's ggml) ---------------------------
add_library(whisper STATIC
        ${THIRD_PARTY}/whisper.cpp/src/whisper.cpp
        ${THIRD_PARTY}/whisper.cpp/src/aneforge/whisper-aneforge.cpp)
target_include_directories(whisper PUBLIC
        ${THIRD_PARTY}/whisper.cpp/include
        ${THIRD_PARTY}/whisper.cpp/src)
target_link_libraries(whisper PUBLIC ggml)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="voi")

# ---- JNI bridge -------------------------------------------------------------
add_library(voiengine SHARED voi_whisper.cpp voi_llama.cpp)
target_link_libraries(voiengine PRIVATE whisper llama ggml android log)
target_link_options(voiengine PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)
