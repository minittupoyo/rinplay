cmake_minimum_required(VERSION 3.22.1)
project(usbiso LANGUAGES CXX)

add_library(usbiso SHARED usb_iso.cpp)
target_compile_options(usbiso PRIVATE -O2 -Wall)
target_link_libraries(usbiso log)
