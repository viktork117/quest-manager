cmake_minimum_required(VERSION 3.22.1)
project(questmanager_vulkan LANGUAGES CXX)

add_library(questmanager_vulkan SHARED vulkan_panel.cpp)
target_compile_features(questmanager_vulkan PRIVATE cxx_std_17)
target_link_libraries(questmanager_vulkan PRIVATE android jnigraphics log vulkan)
