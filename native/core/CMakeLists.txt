cmake_minimum_required(VERSION 3.18.1)

project("realesrgan")

set(EIGEN_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/eigen")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=gnu++17")
set(CMAKE_CXX_STANDARD 17)

if(NOT MSVC)
    # MNN 2.4.3 relies on transitive <cstdint> includes that GCC 13+/libstdc++ no longer provide.
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -include cstdint")
endif()

set(
        REALESRGAN_SOURCES

        mnn_model.cpp
        image_tile_interpreter.cpp
        upscaling.cpp
        session.cpp
        jni.cpp
)

option(MNN_VULKAN "" ON)
option(MNN_OPENCL "" ON)

if(ANDROID)
    # Reproducible builds
    add_link_options("LINKER:--hash-style=gnu,--build-id=none")
elseif(WIN32)
    # MinGW only defines _WIN32; MNN's Vulkan/OpenCL loaders test the bare WIN32 macro.
    add_compile_definitions(WIN32)
    set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
else()
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/MNN)

# Patched MNN sources (see the header of each file for the reason).
if(TARGET MNN_VK)
    get_target_property(MNN_VK_SOURCES MNN_VK SOURCES)
    list(FILTER MNN_VK_SOURCES EXCLUDE REGEX "component/VulkanInstance\\.cpp$")
    list(APPEND MNN_VK_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/mnn_patches/VulkanInstance.cpp)
    set_property(TARGET MNN_VK PROPERTY SOURCES ${MNN_VK_SOURCES})
endif()
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/MNN/include)
include_directories(${EIGEN_INCLUDE})

add_library(realesrgan SHARED ${REALESRGAN_SOURCES})

if(WIN32)
    # Static MNN registers its Vulkan/OpenCL runtimes from static initializers; keep every object.
    target_link_libraries(realesrgan "$<LINK_LIBRARY:WHOLE_ARCHIVE,MNN>")
else()
    target_link_libraries(realesrgan MNN)
endif()

if(WIN32)
    set_target_properties(realesrgan PROPERTIES PREFIX "")
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        # Self-contained DLL: no libstdc++/libgcc/libwinpthread runtime DLLs next to it.
        target_link_options(realesrgan PRIVATE
                -static-libgcc -static-libstdc++
                -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic)
    endif()
endif()
