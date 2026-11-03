# The launcher's native side, one pair per game the APK runs (NX_GAMES, from Gradle's nxGames): libnxhost_<title ID>.so from android/host (the host, with that game's
# profile compiled in) and libnxlaunch_<title ID>.so, the JNI shim that starts it (nxlaunch.c), linked to that host. The game's process loads only its own pair (Native.load).
# All are linked for 16 KB pages (Android 15+ devices may use them; NDK 28 does this by default, the flag keeps it explicit)
cmake_minimum_required(VERSION 3.22)
project(nxlaunch C)
if(NOT NX_GAMES)
  message(FATAL_ERROR "NX_GAMES is not set: -DNX_GAMES=<title ID>,… (build.gradle passes nxGames)")
endif()
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../host ${CMAKE_CURRENT_BINARY_DIR}/host)
string(REPLACE "," ";" games "${NX_GAMES}")
foreach(g ${games})
  add_library(nxlaunch_${g} SHARED nxlaunch.c)
  target_include_directories(nxlaunch_${g} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dm/host/android ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dm/gfx/vk_android)   # the host's launcher interface
  target_compile_options(nxlaunch_${g} PRIVATE -O2 -g -Wall -Wextra -Wno-unused-parameter)
  target_link_options(nxlaunch_${g} PRIVATE -Wl,--no-undefined)
  target_link_libraries(nxlaunch_${g} PRIVATE nxhost_${g} android log)
endforeach()
