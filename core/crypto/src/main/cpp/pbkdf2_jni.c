// PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) for the vault's password KDF (vault-crypto.md §6.4, §17.6 option 1): the HMAC
// inner/outer pad states are computed once, so every iteration is two compressions on 32-bit words. Every buffer
// that holds password-derived material is wiped before returning. Exposed to Kotlin as
// com.bittool.signer.core.crypto.kdf.NativePbkdf2.
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "sha256.h"

#if defined(BTS_ARMV8_SHA2)
#include <sys/auxv.h>
#if defined(__aarch64__)
#define BTS_HWCAP_TYPE AT_HWCAP
#define BTS_HWCAP_SHA2_BIT (1UL << 6)  // HWCAP_SHA2, arch/arm64/include/uapi/asm/hwcap.h
#else
#define BTS_HWCAP_TYPE AT_HWCAP2
#define BTS_HWCAP_SHA2_BIT (1UL << 3)  // HWCAP2_SHA2, arch/arm/include/uapi/asm/hwcap.h
#endif
#endif

enum { BLOCK_BYTES = 64, HASH_BYTES = 32, MAX_PASSWORD = 4096, MAX_SALT = 1024, MAX_OUTPUT = 1024 };

static bts_sha256_compress_fn select_compress(void) {
#if defined(BTS_ARMV8_SHA2)
    if (getauxval(BTS_HWCAP_TYPE) & BTS_HWCAP_SHA2_BIT) return bts_sha256_compress_armv8;
#endif
    return bts_sha256_compress_portable;
}

static uint32_t load_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void store_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

typedef struct {
    uint32_t state[8];
    uint32_t words[16];
    uint8_t pending[BLOCK_BYTES];
    size_t pending_length;
    uint64_t total;
    bts_sha256_compress_fn compress;
} hasher;

static void hasher_block(hasher *h, const uint8_t *block) {
    for (int t = 0; t < 16; t++) h->words[t] = load_be32(block + 4 * t);
    h->compress(h->state, h->words);
}

static void hasher_start(hasher *h, const uint32_t from[8], uint64_t absorbed, bts_sha256_compress_fn compress) {
    memcpy(h->state, from, sizeof(h->state));
    h->pending_length = 0;
    h->total = absorbed;
    h->compress = compress;
}

static void hasher_update(hasher *h, const uint8_t *data, size_t length) {
    h->total += length;
    while (length > 0) {
        size_t take = BLOCK_BYTES - h->pending_length;
        if (take > length) take = length;
        memcpy(h->pending + h->pending_length, data, take);
        h->pending_length += take;
        data += take;
        length -= take;
        if (h->pending_length == BLOCK_BYTES) {
            hasher_block(h, h->pending);
            h->pending_length = 0;
        }
    }
}

static void hasher_finish(hasher *h) {
    const uint64_t bits = h->total * 8;
    h->pending[h->pending_length++] = 0x80;
    if (h->pending_length > BLOCK_BYTES - 8) {
        memset(h->pending + h->pending_length, 0, BLOCK_BYTES - h->pending_length);
        hasher_block(h, h->pending);
        h->pending_length = 0;
    }
    memset(h->pending + h->pending_length, 0, BLOCK_BYTES - 8 - h->pending_length);
    for (int k = 0; k < 8; k++) h->pending[BLOCK_BYTES - 1 - k] = (uint8_t)(bits >> (8 * k));
    hasher_block(h, h->pending);
    h->pending_length = 0;
}

static void derive(const uint8_t *password, size_t password_length, const uint8_t *salt, size_t salt_length,
                   uint32_t iterations, uint8_t *out, size_t out_length, bts_sha256_compress_fn compress) {
    uint8_t key[BLOCK_BYTES];
    uint8_t pad[BLOCK_BYTES];
    uint32_t inner[8], outer[8], u[8], t[8], x[8];
    uint32_t block[16];
    uint8_t index_bytes[4];
    uint8_t tail[HASH_BYTES];
    hasher h;

    memset(key, 0, sizeof(key));
    if (password_length > BLOCK_BYTES) {
        hasher_start(&h, BTS_SHA256_IV, 0, compress);
        hasher_update(&h, password, password_length);
        hasher_finish(&h);
        for (int i = 0; i < 8; i++) store_be32(key + 4 * i, h.state[i]);
    } else if (password_length > 0) {
        memcpy(key, password, password_length);
    }
    for (int i = 0; i < BLOCK_BYTES; i++) pad[i] = key[i] ^ 0x36;
    hasher_start(&h, BTS_SHA256_IV, 0, compress);
    hasher_update(&h, pad, BLOCK_BYTES);
    memcpy(inner, h.state, sizeof(inner));
    for (int i = 0; i < BLOCK_BYTES; i++) pad[i] = key[i] ^ 0x5c;
    hasher_start(&h, BTS_SHA256_IV, 0, compress);
    hasher_update(&h, pad, BLOCK_BYTES);
    memcpy(outer, h.state, sizeof(outer));

    // The constant tail of a 32-byte message after one 64-byte pad block: 0x80, zeros, length 768 bits.
    memset(block, 0, sizeof(block));
    block[8] = 0x80000000u;
    block[15] = (BLOCK_BYTES + HASH_BYTES) * 8;

    const size_t blocks = (out_length + HASH_BYTES - 1) / HASH_BYTES;
    for (size_t index = 1; index <= blocks; index++) {
        store_be32(index_bytes, (uint32_t)index);
        hasher_start(&h, inner, BLOCK_BYTES, compress);
        hasher_update(&h, salt, salt_length);
        hasher_update(&h, index_bytes, sizeof(index_bytes));
        hasher_finish(&h);
        memcpy(block, h.state, HASH_BYTES);
        memcpy(u, outer, sizeof(u));
        compress(u, block);
        memcpy(t, u, sizeof(t));
        for (uint32_t j = 2; j <= iterations; j++) {
            memcpy(block, u, HASH_BYTES);
            memcpy(x, inner, sizeof(x));
            compress(x, block);
            memcpy(block, x, HASH_BYTES);
            memcpy(u, outer, sizeof(u));
            compress(u, block);
            for (int k = 0; k < 8; k++) t[k] ^= u[k];
        }
        for (int k = 0; k < 8; k++) store_be32(tail + 4 * k, t[k]);
        const size_t offset = (index - 1) * HASH_BYTES;
        const size_t count = out_length - offset < HASH_BYTES ? out_length - offset : HASH_BYTES;
        memcpy(out + offset, tail, count);
    }

    bts_wipe(key, sizeof(key));
    bts_wipe(pad, sizeof(pad));
    bts_wipe(inner, sizeof(inner));
    bts_wipe(outer, sizeof(outer));
    bts_wipe(u, sizeof(u));
    bts_wipe(t, sizeof(t));
    bts_wipe(x, sizeof(x));
    bts_wipe(block, sizeof(block));
    bts_wipe(tail, sizeof(tail));
    bts_wipe(&h, sizeof(h));
}

// Copies the Java arrays into one native buffer, derives, copies the key back and wipes the buffer. Returns 0 on
// success, a negative value for rejected arguments or an allocation failure ([out] is then untouched).
static jint run(JNIEnv *env, jbyteArray password, jbyteArray salt, jint iterations, jbyteArray out,
                bts_sha256_compress_fn compress) {
    if (password == NULL || salt == NULL || out == NULL || iterations < 1) return -1;
    const jsize password_length = (*env)->GetArrayLength(env, password);
    const jsize salt_length = (*env)->GetArrayLength(env, salt);
    const jsize out_length = (*env)->GetArrayLength(env, out);
    if (password_length > MAX_PASSWORD || salt_length > MAX_SALT || out_length < 1 || out_length > MAX_OUTPUT) return -1;

    const size_t size = (size_t)password_length + (size_t)salt_length + (size_t)out_length;
    uint8_t *buffer = malloc(size);
    if (buffer == NULL) return -2;
    uint8_t *password_bytes = buffer;
    uint8_t *salt_bytes = buffer + password_length;
    uint8_t *derived = salt_bytes + salt_length;
    (*env)->GetByteArrayRegion(env, password, 0, password_length, (jbyte *)password_bytes);
    (*env)->GetByteArrayRegion(env, salt, 0, salt_length, (jbyte *)salt_bytes);

    derive(password_bytes, (size_t)password_length, salt_bytes, (size_t)salt_length, (uint32_t)iterations, derived,
           (size_t)out_length, compress);
    (*env)->SetByteArrayRegion(env, out, 0, out_length, (const jbyte *)derived);

    bts_wipe(buffer, size);
    free(buffer);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_bittool_signer_core_crypto_kdf_NativePbkdf2_pbkdf2HmacSha256(
    JNIEnv *env, jclass clazz, jbyteArray password, jbyteArray salt, jint iterations, jbyteArray out) {
    (void)clazz;
    return run(env, password, salt, iterations, out, select_compress());
}

// The portable compression even where the instructions exist, so the instrumented test checks both paths agree.
JNIEXPORT jint JNICALL Java_com_bittool_signer_core_crypto_kdf_NativePbkdf2_pbkdf2HmacSha256Portable(
    JNIEnv *env, jclass clazz, jbyteArray password, jbyteArray salt, jint iterations, jbyteArray out) {
    (void)clazz;
    return run(env, password, salt, iterations, out, bts_sha256_compress_portable);
}

// 1 when the ARMv8 SHA-2 instructions are in use, 0 for the portable C compression.
JNIEXPORT jint JNICALL Java_com_bittool_signer_core_crypto_kdf_NativePbkdf2_accelerated(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return select_compress() == bts_sha256_compress_portable ? 0 : 1;
}
