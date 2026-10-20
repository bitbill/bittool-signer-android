package com.bittool.signer.core.crypto.kdf

/**
 * JNI binding of `libbittoolsigner_crypto.so` (`src/main/cpp/pbkdf2_jni.c`): PBKDF2-HMAC-SHA256 in C with the HMAC
 * pad states precomputed, using the ARMv8 SHA-2 instructions when the CPU reports them (run-time `HWCAP` check) and
 * portable C otherwise. The native code copies the arrays into one buffer and wipes it before returning.
 *
 * [isAvailable] is false where the library cannot load (JVM unit tests, an ABI the APK does not ship); callers then
 * use [Pbkdf2HmacSha256], which produces identical output (both are pinned to the same vectors).
 */
internal object NativePbkdf2 {
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("bittoolsigner_crypto")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Fills [out] with the derived key; 0 on success, negative when the arguments are refused (bounds in the C file). */
    @JvmStatic
    external fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, out: ByteArray): Int

    /** Same as [pbkdf2HmacSha256] with the portable C compression forced (tests compare the two paths). */
    @JvmStatic
    external fun pbkdf2HmacSha256Portable(password: ByteArray, salt: ByteArray, iterations: Int, out: ByteArray): Int

    /** 1 when the ARMv8 SHA-2 instructions are used, 0 for portable C. */
    @JvmStatic
    external fun accelerated(): Int
}
