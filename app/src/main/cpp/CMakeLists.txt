cmake_minimum_required(VERSION 3.22)
project(kino_native C CXX)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

# ---------------------------------------------------------------------------
# mGBA, built in its own upstream libretro mode: produces a self-contained
# core .so that we only ever touch through the libretro C ABI (dlopen +
# dlsym in core_bridge.c). We never compile against mGBA's internal headers,
# so there is no ABI coupling between this project and mGBA's struct layouts.
# ---------------------------------------------------------------------------
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
add_subdirectory("${REPO_ROOT}/external/mgba" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

set_target_properties(mgba_libretro PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "kino_core_gba"
    SUFFIX ".so"
)

# NDS core (melondsds_libretro) is NOT built here. Its own CMakeLists.txt
# hardcodes several include/config paths off CMAKE_SOURCE_DIR (its own repo
# root), which breaks the moment it is add_subdirectory()'d into someone
# else's build (CMAKE_SOURCE_DIR then points at *our* root instead). It is
# built as a fully separate top-level CMake project per-ABI by a Gradle task
# (see app/build.gradle.kts) and dropped into src/main/jniLibs/<abi>/, which
# AGP packages automatically alongside this target's outputs.

# ---------------------------------------------------------------------------
# Our own generic libretro JNI bridge: loads exactly one core .so at a time
# via dlopen/dlsym and drives it through the plain libretro C ABI. Does not
# link against either core above - kept fully decoupled at build time too.
# ---------------------------------------------------------------------------
add_library(kino_bridge SHARED
    core_bridge.c
    ring_buffer.c
)

target_include_directories(kino_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(LOG_LIB log)
if(LOG_LIB)
    target_link_libraries(kino_bridge ${LOG_LIB})
endif()
target_link_libraries(kino_bridge ${CMAKE_DL_LIBS})
