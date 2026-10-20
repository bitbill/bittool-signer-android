// SHA-256 compression functions over 32-bit words (FIPS 180-4 §6.2.2), shared by the PBKDF2 loop.
#ifndef BTS_SHA256_H
#define BTS_SHA256_H

#include <stddef.h>
#include <stdint.h>

// One compression: state += rounds(state, block). [block] holds the 16 message words (already big-endian decoded).
typedef void (*bts_sha256_compress_fn)(uint32_t state[8], const uint32_t block[16]);

void bts_sha256_compress_portable(uint32_t state[8], const uint32_t block[16]);

#if defined(BTS_ARMV8_SHA2)
void bts_sha256_compress_armv8(uint32_t state[8], const uint32_t block[16]);
#endif

extern const uint32_t BTS_SHA256_K[64];
extern const uint32_t BTS_SHA256_IV[8];

// Clears [length] bytes in a way the compiler cannot elide (bionic gained explicit_bzero only in API 28).
void bts_wipe(void *pointer, size_t length);

#endif
