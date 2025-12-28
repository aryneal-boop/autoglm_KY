# 设置 libopus 的 CMake 配置

# 定义库文件的完整路径
set(OPUS_LIB_PATH ${CMAKE_CURRENT_SOURCE_DIR}/${ANDROID_ABI}/libopus.a)

# 添加预编译的静态库
add_library(opus STATIC IMPORTED)

# 设置导入库的路径
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${OPUS_LIB_PATH}
)

# 设置包含目录
target_include_directories(opus INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# 导出库路径变量，以便在主 CMakeLists.txt 中使用
set(OPUS_LIBRARY ${OPUS_LIB_PATH} PARENT_SCOPE) 