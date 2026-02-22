cmake_minimum_required(VERSION 3.22.1)
project(vgmplayer)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 14)

# Silence common libvgm warnings on Android
add_compile_options(
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-unknown-pragmas
    -Wno-implicit-function-declaration
    -Wno-missing-field-initializers
    -Wno-deprecated-declarations
    -Wno-format
)

add_definitions(
    -DHAVE_STDINT_H
    -DVGM_LITTLE_ENDIAN
    -D_CRT_SECURE_NO_WARNINGS
)

# Include libvgm's own CMake configuration
set(UTIL_CHARSET_CONV OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libvgm vgm_build)

# JNI glue shared library
add_library(vgmplayer SHARED vgmplayer_jni.cpp)

target_include_directories(vgmplayer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libvgm)
target_link_libraries(vgmplayer
    vgm-player
    vgm-emu
    vgm-utils
    log
    android
)
