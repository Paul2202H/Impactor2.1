cmake_minimum_required(VERSION 3.22.1)

project("impactor_usb")

add_library(
    impactor_usb
    SHARED
    native_usb.cpp
)

find_library(
    log-lib
    log
)

target_compile_features(
    impactor_usb
    PRIVATE
    cxx_std_17
)

target_link_libraries(
    impactor_usb
    ${log-lib}
)
