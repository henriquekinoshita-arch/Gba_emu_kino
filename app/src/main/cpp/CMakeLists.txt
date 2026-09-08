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
# not exported to consumers via target_link_libraries. mgba_jni.c needs
# ENABLE_VFS itself to see VFileOpen()'s declaration in mgba-util/vfs.h.
# This is a preprocessor-visibility define only; it does not change any
# struct layout mgba.a was actually compiled with (that would additionally
# require ENABLE_DIRECTORIES, which this build never defines), so no ABI
# mismatch is introduced between mgba_jni.c and the prebuilt mgba objects.
target_compile_definitions(kinogba PRIVATE ENABLE_VFS)
