cmake_minimum_required(VERSION 3.22)
project("pty" C)

add_library(pty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(pty ${log-lib})
