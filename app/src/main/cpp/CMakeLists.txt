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

# Apply patches to libvgm before building
find_package(Git QUIET)
if(GIT_FOUND)
    # Apply each patch file in the patches directory
    file(GLOB PATCH_FILES "${CMAKE_CURRENT_SOURCE_DIR}/patches/*.patch")
    foreach(PATCH_FILE ${PATCH_FILES})
        get_filename_component(PATCH_NAME ${PATCH_FILE} NAME)
        
        # Check if patch is already applied by trying to apply it in reverse
        execute_process(
            COMMAND ${GIT_EXECUTABLE} apply --check -R ${PATCH_FILE}
            WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/libvgm
            RESULT_VARIABLE PATCH_ALREADY_APPLIED
            OUTPUT_QUIET
            ERROR_QUIET
        )
        
        # If reverse patch fails, patch is not applied yet
        if(NOT PATCH_ALREADY_APPLIED EQUAL 0)
            execute_process(
                COMMAND ${GIT_EXECUTABLE} apply --check ${PATCH_FILE}
                WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/libvgm
                RESULT_VARIABLE PATCH_CHECK_RESULT
                OUTPUT_QUIET
                ERROR_QUIET
            )
            
            if(PATCH_CHECK_RESULT EQUAL 0)
                message(STATUS "Applying patch: ${PATCH_NAME}")
                execute_process(
                    COMMAND ${GIT_EXECUTABLE} apply ${PATCH_FILE}
                    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/libvgm
                    RESULT_VARIABLE PATCH_RESULT
                    OUTPUT_VARIABLE PATCH_OUTPUT
                    ERROR_VARIABLE PATCH_ERROR
                )
                if(PATCH_RESULT EQUAL 0)
                    message(STATUS "  Applied successfully")
                else()
                    message(WARNING "  Failed to apply patch: ${PATCH_ERROR}")
                endif()
            else()
                message(STATUS "  Patch ${PATCH_NAME} already applied or incompatible (skipping)")
            endif()
        else()
            message(STATUS "  Patch ${PATCH_NAME} already applied (skipping)")
        endif()
    endforeach()
else()
    message(WARNING "Git not found, skipping patch application. Run prepare_libvgm_source.sh manually.")
endif()

# Include libvgm's own CMake configuration
set(UTIL_CHARSET_CONV OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libvgm vgm_build)

# Include libgme for NSF and other formats
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GME_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(GME_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(GME_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GME_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GME_ZLIB OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libgme gme_build)

# Include libopenmpt for tracker formats (MOD, XM, S3M, IT, etc.)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt openmpt_build)

# Include libkss for KSS format (MSX music files)
# Note: libkss has its own emu2413/emu8950 which conflicts with libgme's emu2413
# We use linker option --allow-multiple-definition to resolve this
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libkss kss_build)

# JNI glue shared library
add_library(vgmplayer SHARED vgmplayer_jni.cpp)

target_include_directories(vgmplayer PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}/libvgm
    ${CMAKE_CURRENT_SOURCE_DIR}/libgme/gme
    ${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt
    ${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt/libopenmpt
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss/src
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss/modules
)

target_link_libraries(vgmplayer
    vgm-player
    vgm-emu
    vgm-utils
    gme_static
    openmpt_static
    kss
    log
    android
    z
)

# Allow multiple definitions to resolve conflicts between libgme's emu2413 and libkss's emu2413
# Both libraries have their own emu2413 implementation with the same symbols
target_link_options(vgmplayer PRIVATE "-Wl,--allow-multiple-definition")
