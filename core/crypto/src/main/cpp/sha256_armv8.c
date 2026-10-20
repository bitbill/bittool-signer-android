// SHA-256 compression with the ARMv8 Cryptography Extension (SHA256H/SHA256H2/SHA256SU0/SHA256SU1), AArch64 and
// AArch32. Compiled with the crypto target flags (CMakeLists.txt) and only called after the run-time HWCAP check in
// pbkdf2_jni.c, so devices without the extension never execute these instructions.
#include "sha256.h"

#include <arm_neon.h>

void bts_sha256_compress_armv8(uint32_t state[8], const uint32_t block[16]) {
    uint32x4_t abcd = vld1q_u32(state);
    uint32x4_t efgh = vld1q_u32(state + 4);
    const uint32x4_t abcd_in = abcd;
    const uint32x4_t efgh_in = efgh;
    uint32x4_t m0 = vld1q_u32(block);
    uint32x4_t m1 = vld1q_u32(block + 4);
    uint32x4_t m2 = vld1q_u32(block + 8);
    uint32x4_t m3 = vld1q_u32(block + 12);
    for (int group = 0; group < 16; group++) {
        const uint32x4_t wk = vaddq_u32(m0, vld1q_u32(BTS_SHA256_K + 4 * group));
        const uint32x4_t previous = abcd;
        abcd = vsha256hq_u32(abcd, efgh, wk);
        efgh = vsha256h2q_u32(efgh, previous, wk);
        // W[t..t+3] for the group 4 ahead (the last four results are unused).
        const uint32x4_t next = vsha256su1q_u32(vsha256su0q_u32(m0, m1), m2, m3);
        m0 = m1;
        m1 = m2;
        m2 = m3;
        m3 = next;
    }
    vst1q_u32(state, vaddq_u32(abcd, abcd_in));
    vst1q_u32(state + 4, vaddq_u32(efgh, efgh_in));
}
