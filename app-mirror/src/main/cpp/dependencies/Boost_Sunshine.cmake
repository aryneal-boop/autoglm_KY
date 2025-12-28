#
# Loads the boost library giving the priority to the system package first, with a fallback to FetchContent.
#
include_guard(GLOBAL)

set(BOOST_VERSION 1.86)
set(BOOST_COMPONENTS
        filesystem
        locale
        log
        program_options
        system)  # system is not used by Sunshine, but by Simple-Web-Server, added here for convenience

if(BOOST_USE_STATIC)
    set(Boost_USE_STATIC_LIBS ON)  # cmake-lint: disable=C0103
endif()

find_package(Boost CONFIG ${BOOST_VERSION} COMPONENTS ${BOOST_COMPONENTS})
if(NOT Boost_FOUND)
    message(STATUS "Boost v${BOOST_VERSION}.x package not found in the system. Falling back to FetchContent.")
    include(FetchContent)

    # Avoid warning about DOWNLOAD_EXTRACT_TIMESTAMP in CMake 3.24:
    if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24.0")
        cmake_policy(SET CMP0135 NEW)
    endif()

    # more components required for compiling boost targets
    list(APPEND BOOST_COMPONENTS
            asio
            crc
            format
            process
            property_tree)

    set(BOOST_ENABLE_CMAKE ON)

    # Limit boost to the required libraries only
    set(BOOST_INCLUDE_LIBRARIES
            ${BOOST_COMPONENTS})
    set(_BOOST_LOCAL_ARCHIVE "${CMAKE_CURRENT_LIST_DIR}/boost-1.86.0-cmake.tar.xz")
    get_filename_component(_BOOST_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
    set(_BOOST_LOCAL_ARCHIVE_ROOT "${_BOOST_REPO_ROOT}/boost-1.86.0-cmake.tar.xz")

    unset(_BOOST_FETCHCONTENT_ARGS)
    if(EXISTS "${_BOOST_LOCAL_ARCHIVE}")
        set(_BOOST_LOCAL_ARCHIVE_TO_USE "${_BOOST_LOCAL_ARCHIVE}")
    elseif(EXISTS "${_BOOST_LOCAL_ARCHIVE_ROOT}")
        set(_BOOST_LOCAL_ARCHIVE_TO_USE "${_BOOST_LOCAL_ARCHIVE_ROOT}")
    endif()

    if(DEFINED _BOOST_LOCAL_ARCHIVE_TO_USE)
        if(WIN32)
            set(_BOOST_LOCAL_EXTRACT_DIR "$ENV{SystemDrive}/_cs_boost_1_86_0")
        else()
            set(_BOOST_LOCAL_EXTRACT_DIR "${_BOOST_REPO_ROOT}/_local_boost_1_86_0")
        endif()
        if(NOT EXISTS "${_BOOST_LOCAL_EXTRACT_DIR}/CMakeLists.txt")
            file(MAKE_DIRECTORY "${_BOOST_LOCAL_EXTRACT_DIR}")
            file(ARCHIVE_EXTRACT INPUT "${_BOOST_LOCAL_ARCHIVE_TO_USE}" DESTINATION "${_BOOST_LOCAL_EXTRACT_DIR}")
        endif()
        set(_BOOST_FETCHCONTENT_ARGS SOURCE_DIR "${_BOOST_LOCAL_EXTRACT_DIR}")
    else()
        set(BOOST_URL
                "https://github.com/boostorg/boost/releases/download/boost-1.86.0/boost-1.86.0-cmake.tar.xz")
        set(BOOST_HASH
                "MD5=D02759931CEDC02ADED80402906C5EB6")
        set(_BOOST_FETCHCONTENT_ARGS URL ${BOOST_URL} URL_HASH ${BOOST_HASH})
    endif()

    if(CMAKE_VERSION VERSION_LESS "3.24.0")
        FetchContent_Declare(
                Boost
                ${_BOOST_FETCHCONTENT_ARGS}
        )
    elseif(APPLE AND CMAKE_VERSION VERSION_GREATER_EQUAL "3.25.0")
        # add SYSTEM to FetchContent_Declare, this fails on debian bookworm
        FetchContent_Declare(
                Boost
                ${_BOOST_FETCHCONTENT_ARGS}
                SYSTEM  # requires CMake 3.25+
                OVERRIDE_FIND_PACKAGE  # requires CMake 3.24+, but we have a macro to handle it for other versions
        )
    elseif(CMAKE_VERSION VERSION_GREATER_EQUAL "3.24.0")
        FetchContent_Declare(
                Boost
                ${_BOOST_FETCHCONTENT_ARGS}
                OVERRIDE_FIND_PACKAGE  # requires CMake 3.24+, but we have a macro to handle it for other versions
        )
    endif()

    FetchContent_MakeAvailable(Boost)
    set(FETCH_CONTENT_BOOST_USED TRUE)

    set(Boost_FOUND TRUE)  # cmake-lint: disable=C0103
    set(Boost_INCLUDE_DIRS  # cmake-lint: disable=C0103
            "$<BUILD_INTERFACE:${Boost_SOURCE_DIR}/libs/headers/include>;$<INSTALL_INTERFACE:include/boost-1_85>")

    # 添加 Boost::boost 别名目标
    if(NOT TARGET Boost::boost)
        add_library(Boost::boost INTERFACE IMPORTED)
        set_target_properties(Boost::boost PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${Boost_INCLUDE_DIRS}")
    endif()

    # Add wordexp include directory for Android
    if(ANDROID)
        # 确保在 Android 平台上添加 wordexp 头文件目录
        set(WORDEXP_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/libwordexp")
        if(NOT EXISTS "${WORDEXP_INCLUDE_DIR}/wordexp.h")
            message(FATAL_ERROR "wordexp.h not found in ${WORDEXP_INCLUDE_DIR}")
        endif()
        list(APPEND Boost_INCLUDE_DIRS "${WORDEXP_INCLUDE_DIR}")
        
        # 为 boost_process target 专门设置 include 路径
        if(TARGET boost_process)
            target_include_directories(boost_process PRIVATE "${WORDEXP_INCLUDE_DIR}")
        endif()
    endif()

    if(WIN32)
        # Windows build is failing to create .h file in this directory
        file(MAKE_DIRECTORY ${Boost_BINARY_DIR}/libs/log/src/windows)
    endif()

    set(Boost_LIBRARIES "")  # cmake-lint: disable=C0103
    foreach(component ${BOOST_COMPONENTS})
        list(APPEND Boost_LIBRARIES "Boost::${component}")
    endforeach()
endif()

message(STATUS "Boost include dirs: ${Boost_INCLUDE_DIRS}")
message(STATUS "Boost libraries: ${Boost_LIBRARIES}")