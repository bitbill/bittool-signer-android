cmake_minimum_required(VERSION 3.22.1)
project(bittoolsigner_crypto C)

# PBKDF2-HMAC-SHA256 for the vault's password KDF (vault-crypto.md §17.6 option 1): portable C everywhere, ARMv8
# SHA-2 instructions selected at run time where the CPU reports them (HWCAP_SHA2 / HWCAP2_SHA2).
add_library(bittoolsigner_crypto SHARED pbkdf2_jni.c sha256.c)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(bittoolsigner_crypto PRIVATE sha256_armv8.c)
    set_source_files_properties(sha256_armv8.c PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
    target_compile_definitions(bittoolsigner_crypto PRIVATE BTS_ARMV8_SHA2=1)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_sources(bittoolsigner_crypto PRIVATE sha256_armv8.c)
    set_source_files_properties(sha256_armv8.c PROPERTIES COMPILE_OPTIONS "-march=armv8-a;-mfpu=crypto-neon-fp-armv8")
    target_compile_definitions(bittoolsigner_crypto PRIVATE BTS_ARMV8_SHA2=1)
endif()

target_compile_options(bittoolsigner_crypto PRIVATE
    -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -Wall -Wextra -Werror
)
target_link_options(bittoolsigner_crypto PRIVATE
    # 16 KB page alignment (Android 15+ devices, Google Play requirement), like the engine library.
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
    -Wl,--build-id=none
)
