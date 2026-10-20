package com.bittool.signer.core.engine

import kotlinx.serialization.json.JsonObject

/**
 * The offline signing engine (bundled `signer.js`), the Android counterpart of iOS `actor SigningEngine`.
 *
 * Every operation that receives a secret or untrusted input runs in a fresh QuickJS runtime, so no state survives into
 * another request. Public derivations over the device's own authenticated public index ([EngineOperation.isWarm]) reuse
 * one warm runtime that never receives secrets or external data; it is dropped by [releaseWarmContext] when the app is
 * suspended. An animated-QR scan session has its own runtime, discarded when the scan ends.
 *
 * Results are the engine's `result` object; a non-object result is wrapped as `{"value": result}` exactly like iOS.
 * Failures throw [EngineException] with the engine's bounded code and message. All calls are serialised on one engine
 * thread; none blocks the caller's thread.
 */
interface SignerEngine {
    suspend fun run(operation: EngineOperation, parameters: JsonObject = JsonObject(emptyMap())): JsonObject

    suspend fun beginScanSession()
    /** Throws EngineException("SCAN", "The scan session ended. Scan again.") without a session. */
    suspend fun scan(frame: String): JsonObject
    suspend fun endScanSession()

    /** Drops the warm public runtime (background, App Lock, screen capture). */
    suspend fun releaseWarmContext()

    /** Loads, verifies and compiles the bundle ahead of the first operation (no secrets involved). */
    suspend fun prewarm()

    /** Digest of the bundled engine computed from the shipped asset bytes, for the Open Source page. */
    suspend fun bundleDigest(): BundleDigest
}

data class BundleDigest(val sha256: String, val bytes: Int, val quickJsVersion: String)

enum class EngineOperation(val wireName: String, val production: Boolean, val isWarm: Boolean = false) {
    Mnemonic("mnemonic", production = true),
    Capabilities("capabilities", production = true),
    DerivePublicIndex("derivePublicIndex", production = true),
    DerivePublicAddresses("derivePublicAddresses", production = true, isWarm = true),
    CreatePublicPairing("createPublicPairing", production = true, isWarm = true),
    CreateWalletPairing("createWalletPairing", production = true, isWarm = true),
    DecodeQR("decodeQR", production = true),
    DecodePayload("decodePayload", production = true),
    Review("review", production = true),
    ResolveSigningPlan("resolveSigningPlan", production = true),
    SignPlanTask("signPlanTask", production = true),
    EncodeResponse("encodeResponse", production = true),

    // Internal utilities used by tests; refused unless the engine is built with test operations enabled.
    DeriveAccounts("deriveAccounts", production = false),
    DeriveBitcoinCosigner("deriveBitcoinCosigner", production = false),
    EncodeQR("encodeQR", production = false),
    Sign("sign", production = false),
    CreatePairing("createPairing", production = false),
    ValidateMnemonic("validateMnemonic", production = false),
    Wordlist("wordlist", production = false),
}
