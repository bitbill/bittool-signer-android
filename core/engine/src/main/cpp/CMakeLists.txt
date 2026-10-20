cmake_minimum_required(VERSION 3.22.1)
project(bittoolsigner_engine C)

# Vendored QuickJS (quickjs/VERSION, MIT, https://bellard.org/quickjs/), built from source with pinned flags.
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/quickjs/VERSION" QJS_VERSION)
string(STRIP "${QJS_VERSION}" QJS_VERSION)

add_library(bittoolsigner_engine SHARED
    signer_jni.c
    quickjs/quickjs.c
    quickjs/libregexp.c
    quickjs/libunicode.c
    quickjs/cutils.c
    quickjs/dtoa.c
)

target_compile_definitions(bittoolsigner_engine PRIVATE _GNU_SOURCE "CONFIG_VERSION=\"${QJS_VERSION}\"")
target_compile_options(bittoolsigner_engine PRIVATE
    -O2 -fwrapv -funsigned-char -fvisibility=hidden -ffunction-sections -fdata-sections
    -Wno-unused-parameter -Wno-sign-compare -Wno-implicit-fallthrough -Wno-unused-but-set-variable
)
target_link_options(bittoolsigner_engine PRIVATE
    # 16 KB page alignment (Android 15+ devices, Google Play requirement).
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
    -Wl,--build-id=none
)
target_link_libraries(bittoolsigner_engine PRIVATE m)
