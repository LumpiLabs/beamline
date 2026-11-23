# The AirPlay receiver: UxPlay's lib/ with BoringSSL as libcrypto and libplist, all from the pinned
# submodules in airplay/third_party, plus our mDNS backend and JNI glue. UxPlay's own build looks
# for its dependencies with pkg-config and builds a desktop app, so the lists here are our own.
cmake_minimum_required(VERSION 3.22)
project(beamline_airplay C CXX)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party)
set(UXPLAY ${THIRD_PARTY}/uxplay/lib)
set(LIBPLIST ${THIRD_PARTY}/libplist)

# BoringSSL as libcrypto, plus its decrepit library for the one older call UxPlay makes
# (BIO_f_base64). Its pre-generated sources need neither Perl nor Go; BUILD_TESTING off keeps the
# tests (which do need Go) out.
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/boringssl boringssl EXCLUDE_FROM_ALL)

# libplist, whose own build uses autotools: the C library only (src/Makefile.am, libcnary/Makefile.am).
add_library(plist STATIC
  ${LIBPLIST}/src/base64.c
  ${LIBPLIST}/src/bytearray.c
  ${LIBPLIST}/src/hashtable.c
  ${LIBPLIST}/src/ptrarray.c
  ${LIBPLIST}/src/time64.c
  ${LIBPLIST}/src/common.c
  ${LIBPLIST}/src/xplist.c
  ${LIBPLIST}/src/bplist.c
  ${LIBPLIST}/src/jsmn.c
  ${LIBPLIST}/src/jplist.c
  ${LIBPLIST}/src/oplist.c
  ${LIBPLIST}/src/out-default.c
  ${LIBPLIST}/src/out-plutil.c
  ${LIBPLIST}/src/out-limd.c
  ${LIBPLIST}/src/plist.c
  ${LIBPLIST}/libcnary/node.c
  ${LIBPLIST}/libcnary/node_list.c
)
target_include_directories(plist
  PUBLIC ${LIBPLIST}/include
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libplist ${LIBPLIST} ${LIBPLIST}/src ${LIBPLIST}/libcnary/include
)
target_compile_definitions(plist PUBLIC LIBPLIST_STATIC PRIVATE HAVE_CONFIG_H)
target_compile_options(plist PRIVATE -fvisibility=hidden -Wno-unused-parameter)

# UxPlay's protocol library, without its dns_sd and mdnsd backends: dnssd_android.c replaces them.
file(GLOB UXPLAY_SOURCES CONFIGURE_DEPENDS
  ${UXPLAY}/*.c
  ${UXPLAY}/playfair/*.c
  ${UXPLAY}/fairplay_v2/*.c
  ${UXPLAY}/llhttp/*.c
)
add_library(uxplay STATIC ${UXPLAY_SOURCES} dnssd_android.c)
target_include_directories(uxplay
  PUBLIC ${UXPLAY} ${CMAKE_CURRENT_SOURCE_DIR}
  PRIVATE ${UXPLAY}/playfair ${UXPLAY}/fairplay_v2 ${UXPLAY}/llhttp
)
# As UxPlay's lib/CMakeLists.txt: NOHOLD lets a new client drop an idle one; libplist is newer than 2.3.
target_compile_definitions(uxplay PRIVATE NOHOLD PLIST_210 PLIST_230 _REENTRANT)
target_compile_options(uxplay PRIVATE -O2 -Wno-unused-parameter)
target_link_libraries(uxplay PUBLIC crypto decrepit plist)

add_library(beamline-airplay SHARED airplay_jni.c)
target_link_libraries(beamline-airplay PRIVATE uxplay log)
target_link_options(beamline-airplay PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
