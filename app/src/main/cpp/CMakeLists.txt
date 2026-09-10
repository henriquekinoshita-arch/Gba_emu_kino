cmake_minimum_required(VERSION 3.22.1)
project(kinogba_jni C)

set(MGBA_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/mgba)

if(NOT EXISTS ${MGBA_SOURCE_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "mGBA sources not found at ${MGBA_SOURCE_DIR}.\n"
        "Run: git submodule update --init --recursive")
endif()

# mGBA auto-detects a "library only" build (LIBMGBA_ONLY) because we add it
# with EXCLUDE_FROM_ALL below. That mode disables the Qt/SDL frontends, the
# debugger CLI, and every optional third-party dependency (zlib/png/sqlite3/
# ffmpeg/...), producing a single static "mgba" target with just the GBA/GB
# cores. We only need the GBA core.
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(USE_LZMA OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(BUILD_QT OFF CACHE BOOL "" FORCE)
set(BUILD_SDL OFF CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO OFF CACHE BOOL "" FORCE)

add_subdirectory(${MGBA_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/mgba EXCLUDE_FROM_ALL)

add_library(kinogba SHARED
    mgba_jni.c
    ring_buffer.c
)

target_link_libraries(kinogba
    PRIVATE
        mgba
        log
        android
)

target_compile_options(kinogba PRIVATE -Wall -Wextra -Wno-unused-parameter)
set_target_properties(kinogba PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)

# mGBA's own CMakeLists.txt applies feature defines like ENABLE_VFS to the
# "mgba" target via COMPILE_DEFINITIONS (not PUBLIC/INTERFACE), so they are
# not exported to consumers via target_link_libraries. mgba_jni.c must
# replicate the exact same defines mgba.a was actually compiled with, or
# every conditionally-compiled member of "struct mCore" after the first
# mismatch shifts to the wrong offset - a real ABI mismatch, not just a
# missing-declaration warning.
#
# Confirmed against mGBA's own CMakeLists.txt (see the "ENABLES" list built
# around `if(ENABLE_VFS: list(APPEND ENABLES VFS DIRECTORIES))`): turning on
# ENABLE_VFS (which this build always does) *always* also turns on
# ENABLE_DIRECTORIES as a paired feature. Missing that here was a real bug -
# it segfaulted immediately on core->init() on a real device because the
# struct mCore layout mgba.a used and the one mgba_jni.c assumed disagreed
# on whether the "dirs" member (gated on ENABLE_VFS && ENABLE_DIRECTORIES)
# exists, shifting every field after it. Reproduced and confirmed fixed with
# a host (non-Android) build of libmgba.a plus a small harness that calls
# GBACoreCreate()/init()/loadROM() the same way mgba_jni.c does.
target_compile_definitions(kinogba PRIVATE ENABLE_VFS ENABLE_DIRECTORIES)
