cmake_minimum_required(VERSION 3.22.1)
project(nook_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Statisch in unsere eine .so linken, nur das Nötigste bauen.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
# ARMv8.2 mit FP16 + Dot-Product: alle Handys ab ca. 2018 (Snapdragon 845 / Exynos 9810).
# Ältere Geräte erkennt die App selbst (WhisperLib.isSupportedCpu) und sagt das klar.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(nook_whisper SHARED whisper_jni.cpp)
target_link_libraries(nook_whisper whisper log android)
target_compile_options(nook_whisper PRIVATE -O3)
target_link_options(nook_whisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
