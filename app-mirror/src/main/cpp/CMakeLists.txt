cmake_minimum_required(VERSION 3.24.0)

project("sunshine")

include(dependencies/Boost_Sunshine.cmake)
add_subdirectory(dependencies/moonlight-common-c/enet)
add_subdirectory(dependencies/openssl-cmake)
add_subdirectory(dependencies/Simple-Web-Server)
add_subdirectory(dependencies/libopus)

add_library(
        sunshine
        SHARED
        sunshine.cpp
        logging.cpp
        network.cpp
        config.cpp
        platform/common.cpp
        crypto.cpp
        dependencies/nanors/rs.c
        stat_trackers.cpp
        globals.cpp
        stream.cpp
        rtsp.cpp
        dependencies/moonlight-common-c/src/RtspParser.c
        audio.cpp
        nvhttp.cpp
        file_handler.cpp
        input.cpp
        video.cpp
        video_colorspace.cpp
)

target_include_directories(sunshine PRIVATE
        dependencies/moonlight-common-c/enet/include
        dependencies/ffmpeg
        dependencies/nanors/deps/obl
        dependencies/nanors
        dependencies/libopus/include
        dependencies
        ${Boost_INCLUDE_DIRS})

target_link_libraries(
        sunshine
        android
        mediandk
        log
        enet
        ${OPUS_LIBRARY}
        ssl
        crypto
        simple-web-server
        ${Boost_LIBRARIES}
) 