# Port spec: the offline engine contract, host responsibilities and test vectors

Source of truth: the iOS app at `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner` (cited as `path:line`, relative
to that root). Engine sources are under `engine/` (`engine/src/*.js`, `engine/protocols.js`); the shipped artifact is
`BitToolSigner/Resources/signer.js`. The Android host already exists as a scaffold in
`core/engine/src/main/kotlin/com/bittool/signer/core/engine/**` of this repository (see §12.1); this spec is the
contract that scaffold, and every caller, must satisfy.

Every value marked **(golden)** below was produced on 2026-10-20 by running the shipped `signer.js`
(SHA-256 `6933d0d8…1077`) in a Node 20.20.2 `vm` context with a WebCrypto-backed `crypto.getRandomValues`, i.e. the
same bundle bytes Android ships. All of them reproduced exactly; they are safe to hard-code in Android tests.

Contents

0. Key facts and risks (read first)
1. The bundle (file, hash, provenance, stale documents)
2. Dispatch protocol and JSON rules
3. The iOS host bridge (`Core/SigningEngine.swift`), line by line
4. Native seed derivation (`Core/BIP39Seed.swift`) and native randomness
5. Context policy (fresh / warm / scan) and lifecycle
6. Operation reference (inputs, validation, outputs, errors, iOS call sites)
7. Shared result shapes (public index, request, review, plan, task, sign result, response, payload view)
8. Error catalogue
9. Host-side invariants (numbered; port them as tests)
10. Host strings in this layer (verbatim)
11. Test vectors and tests to mirror
12. Android mapping
13. Out of scope (iOS-only)

---

## 0. Key facts and risks (read first)

1. **One function.** The bundle defines exactly one global, `BitToolSignerEngine`, whose `dispatch(json: string):
   string` takes `{"operation": …, …params}` and returns `{"ok":true,"result":…}` or
   `{"ok":false,"error":{"code","message"}}` (`engine/src/index.js:36`). It never throws (everything is caught), is
   synchronous, and has no network, timers, filesystem or storage.
2. **The host owns the safety rails the engine cannot:** the operation allowlist (production vs DEBUG), fresh /
   warm / scan context separation, 1 MiB request and 2 MiB response limits, bounded error text (code ≤ 80, message ≤
   400), native CSPRNG injection (`crypto.getRandomValues` *and* `Math.random`) **before** the bundle runs, native
   BIP39 seed derivation (the engine receives `seed`, never the phrase, in production), and every plan/result
   identity check in §9.
3. **Pass-through JSON must stay exactly as received.** The engine re-hashes the request (`requestHash`), the plan
   (`planHash = requestHash(plan without planHash)`) and checks `previousResult.requestHash/planHash`
   (`engine/src/resolver.js:211, 226-228`). Keep `request`, `plan`, `publicIndex`, the previous result as
   `kotlinx.serialization.json.JsonObject`; never round-trip through data classes, never add/drop a key, never turn
   an absent key into `null` (a `null` where the engine expects absence changes the hash or fails validation:
   `resolveSigningPlan` with `publicKey: null` returns `status:"invalid"` — golden, §6.10).
4. **Taproot signing needs entropy.** BIP340 signatures use fresh 32-byte auxiliary randomness from
   `crypto.getRandomValues` (`engine/src/bitcoin.js:211, 217`). If the bootstrap is missing, Taproot signing fails;
   if it is wired to a weak source, signatures leak nothing about keys (BIP340 is safe with bad aux) but the
   security design (Security.md F18) is violated. Test: signing the same Taproot PSBT twice must give two different
   PSBTs (§11.2 V-NATIVE-2).
5. **Performance is the biggest Android risk.** The wrapper embeds **QuickJS "BigNum 2024-02-14"** whose BigInt is
   libbf-based (verified from `libquickjs-android-wrapper.so` strings: `QuickJS memory usage -- BigNum 2024-02-14
   version`, `bf_*` symbols). `derivePublicIndex` from a seed measured **≈ 7 s** on the emulator, a fresh runtime
   **≈ 0.2 s** (`docs/port/DECISIONS.md` §5). `signPlanTask` internally re-derives the whole public index
   (`resolver.js:237`, `publicIndexFromSeed`), so **every signature costs at least one `derivePublicIndex`**. iOS
   JavaScriptCore (no JIT) took 66–75 ms per VM start (`IMPLEMENTATION_STATUS.md:597`). UI must show progress for
   create/import/repair/reset/sign; never block the main thread; benchmark `setGCThreshold` and a newer QuickJS (§12.7).
6. **JNI strings are modified UTF-8.** The scaffold already had to rewrite a raw U+0000 in `signer.js` before
   `compile` (`EngineBundle.jniSafeSource`). The same marshalling applies to the request/response strings of every
   `dispatch` call, so supplementary-plane characters (emoji in EIP-712 strings, Solana/TRON/EVM messages, token
   names) are not guaranteed to round-trip, and CheckJNI (debuggable builds) aborts on 4-byte UTF-8 from
   `NewStringUTF`. **Make the transport ASCII-only in both directions** (§12.4) and keep the non-ASCII golden test
   (V-UNICODE, §11.2).
7. **`signPlanTask` result identity is the host's job.** After every signature the host must check `requestHash ==
   plan.requestHash`, `planHash == plan.planHash`, `taskId == task.id` (`Models/AppStore.swift:429-431`), and the
   plan's every task `walletKeyId` must equal the local wallet's (`AppStore.swift:391, 420`).
8. **Bitcoin multi-wallet plans chain through `previousResult`.** Always pass the *original* frozen request plus the
   previous task's whole result object; results keep the **original** `requestHash` for all tasks
   (`resolver.js:209-224, 241`). Only the final task's result goes to `encodeResponse` (`AppStore.swift:433-443`).
9. **Engine text is English and is the hashed/committed text.** Review labels, actions, headlines and resolver
   messages are English; the interface translates at draw time and falls back to English
   (`Models/ReviewText.swift`, `docs/Reproducible-Build.md:125-128`). Engine error messages and resolver status
   messages are shown untranslated on iOS.
10. **Stale documents.** `engine/README.md:133` still quotes the old bundle (854,090 bytes, `36a00e6a…`);
    `docs/Security.md:44` still describes a 3-xpub wallet pairing; `engine/test/fixtures/evm-public-ui.json`
    carries a pre-rename `walletKeyId` (`95f7c3fc…`) that the current engine does not produce; `native-vectors.json`
    predates the `qrSignature` field. The code and `engine/bundle-manifest.json` are authoritative.

---

## 1. The bundle

| Property | Value | Source |
|---|---|---|
| File (iOS) | `BitToolSigner/Resources/signer.js` | `engine/build.mjs:4` |
| File (Android) | `core/engine/src/main/assets/engine/signer.js` (byte-identical, already present) | scaffold `EngineBundle.ASSET_PATH` |
| Size | **1,181,026 bytes** | `engine/bundle-manifest.json:5` |
| SHA-256 | **`6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077`** | `engine/bundle-manifest.json:4`; verified with `shasum` on both copies |
| Version | `1.0.0` (`capabilities().version`) | `engine/src/index.js:15` |
| Format | esbuild IIFE, `globalName: 'BitToolSignerEngine'`, `target: safari17`, minified, `legalComments: 'eof'`, banner `// BitTool Signer 1.0.0 — generated offline engine. Rebuild: cd engine && npm ci && npm run build.` | `engine/build.mjs:4` |
| Inputs | 273 module paths (manifest `inputs`), including `data/token-registry.engine.json` (3,350 tokens) and `data/known-contracts.engine.json` (1,720 + 27 every-chain contracts) **embedded inside the bundle** | `engine/bundle-manifest.json:6+` |
| Dependencies | exact-pinned: `@ngraveio/bc-ur 1.1.13`, `@noble/curves 2.4.0`, `@noble/hashes 2.4.0`, `@scure/base 2.0.0`, `@scure/bip32 2.4.0`, `@scure/bip39 2.4.0`, `@scure/btc-signer 2.4.1`, `buffer 6.0.3`, `ethers 6.17.0`, `fflate 0.8.3` | `engine/package.json` |
| Special characters | exactly **one** non-ASCII code unit: a raw U+0000 inside the EVM personal-message regex `/[<NUL>-\x08\x0b\x0c\x0e-\x1f\x7f]/`; **no** supplementary characters | measured with Python on the shipped file |
| Rebuild | `cd engine && npm ci --ignore-scripts && npm run build` (Node ≥ 20.19.0) reproduces the file byte-for-byte | `engine/README.md:9-16`, `docs/Reproducible-Build.md:42-68`, `IMPLEMENTATION_STATUS.md` "Clean-room engine rebuild: Byte-identical" |

Host rules for the bundle:

- iOS loads it as a UTF-8 string from the app bundle once per `SigningEngine` instance and caches it
  (`Core/SigningEngine.swift:202-214`); it does **not** hash-check it at runtime (the code signature covers it).
  Android decision: verify SHA-256 against a build-time constant **before** the first compile (scaffold does this).
- The Open Source page on iOS computes the digest and byte count **on the device each time the page opens**
  (`Features/Settings/SettingsTab.swift:304-325`, footer string "The engine digest is the SHA-256 of signer.js as it is
  bundled in this app, computed on this device each time you open this page."). Android must hash the asset bytes
  when that page opens, not print the constant.
- The token registry and known-contract tables used by review are the copies **inside** `signer.js`. The app's
  `token-registry.json`, `known-contracts.json`, `TokenIcons.pack` are for host browsing/icons only and are never
  sent to the engine.

---

## 2. Dispatch protocol and JSON rules

### 2.1 Envelope (`engine/src/index.js:35-36`)

```js
export function dispatch(json){try{check(typeof json==='string'&&json.length<=1048576,'LIMIT','Request exceeds one megabyte.');
  const result=execute(parseProtocolJSON(json,1048576));return JSON.stringify({ok:true,result});}
  catch(error){const controlled=error instanceof EngineError||error instanceof ProtocolError;
  return JSON.stringify({ok:false,error:{code:controlled?error.code:'INVALID_REQUEST',
  message:controlled?error.message:'The payload is malformed, unsupported or inconsistent. No signature was produced.'}});}}
```

- Input: one JSON text. `json.length` (UTF-16 code units) ≤ 1,048,576, else `LIMIT` / `'Request exceeds one megabyte.'`.
- `execute` requires an object (`'REQUEST'`, `'Expected a JSON operation.'`) and switches on `operation`; unknown →
  `OPERATION` / `'Unsupported engine operation.'` (`index.js:35`).
- Output: `JSON.stringify` of the envelope. Consequences: `undefined` members vanish, so result keys are either
  present or absent (never `null` unless the engine set `null`, e.g. Solana `signatures` slots); integers > 2^53 are
  always emitted as strings by the engine (e.g. `value_sat` via `sats()`, TRON `number()`).
- Controlled errors are `EngineError(code, message)` (`engine/src/util.js:3`) and `ProtocolError(message)` whose
  code is always `QR_PROTOCOL` (`engine/protocols.js:16-18`). Anything else (library exceptions, BigInt mixing,
  stack overflow caught as `InternalError`) becomes the generic `INVALID_REQUEST` text — no input text, no stack.
- Exception: `resolveSigningPlan` never errors through the envelope for bad requests; it **returns** `ok:true` with
  `{version:1,status:"invalid",message,tasks:[]}` (`resolver.js:199`, §6.10).

### 2.2 The strict JSON parser (`engine/protocols.js:114-157`)

`parseProtocolJSON(text, 1048576)` replaces `JSON.parse` for every dispatch input (and for EIP-712 JSON inside an
`eth-sign-request`, with the default 131,072-byte limit):

| Rule | Error (`QR_PROTOCOL`) |
|---|---|
| `Buffer.byteLength(text)` (UTF-8) ≤ limit | `'JSON payload too large'` |
| depth ≤ 32 and ≤ 20,000 values total | `'JSON complexity limit exceeded'` |
| object keys must be strings; no duplicate key at any level | `'Invalid JSON object key'`, `'Duplicate JSON key'` |
| numbers: finite; integers must be safe (`|n| ≤ 2^53−1`) | `'Unsafe JSON number; encode large integers as strings'` |
| no trailing data | `'Trailing JSON data'` |
| other syntax errors | `'Invalid JSON value'`, `'Invalid JSON object'`, `'Invalid JSON array'`, `'Unterminated JSON string'` |

Objects are created with `Object.create(null)` (prototype-less). String literals are decoded with `JSON.parse` of the
literal slice, so **`\uXXXX` escapes are fully supported** (basis of the ASCII transport, §12.4; verified golden: the
ASCII-escaped form of a request with `👋 世界 é` gives the same `requestHash`).

### 2.3 Canonical hashing (`engine/src/util.js:22-30`)

`stable(value)`: `null`/string/boolean → `JSON.stringify`; number must be a safe integer (`PRECISION`, `'Use decimal
strings for amounts larger than safe integers.'`); arrays in order; objects must be plain (`REQUEST`, `'Expected a
plain JSON object.'`) with keys sorted by JS code-unit order; depth < 32 (`LIMIT`, `'Request nesting is too deep.'`).
`requestHash(x) = lowercase hex SHA-256(UTF-8(stable(x)))`. Note `stable` rejects `undefined` and non-integer numbers:
the F-04 regression (`IMPLEMENTATION_STATUS.md:100-108`) was an `undefined` field making every Bitcoin burn
unsignable — test through the full `resolveSigningPlan`, not helper outputs.

### 2.4 JSON rules for the host

1. Build requests as `{…params, "operation": name}`; iOS overwrites any caller-supplied `operation`
   (`SigningEngine.swift:185-186`). Keys sorted recursively (iOS `.sortedKeys`, `:190`) — order is irrelevant to the
   engine but makes requests deterministic and loggable-by-size.
2. Never emit duplicate keys (the parser rejects them).
3. Integers only, all ≤ 2^53−1. All amounts, gas values, chain IDs > 2^53 travel as strings — this is already how
   decoded requests look; the host never builds amounts itself.
4. **Absent means absent.** Do not serialize Kotlin nulls (`explicitNulls = false` is not enough for hand-built
   `JsonObject`s; just never `put` a null). `derivePublicIndex` with `{"seed":…, "passphrase":""}` fails with `SEED`
   `'Provide either a seed or a recovery phrase, not both.'` because `""` is not `undefined` (`engine/src/keys.js:28`).
5. Booleans are JSON booleans (`riskAcknowledged: true|false`).
6. Size is measured on the final request text including `operation`: > 1,048,576 UTF-8 bytes →
   `REQUEST_TOO_LARGE` before the engine is touched (`SigningEngine.swift:190-193`).

---

## 3. The iOS host bridge (`BitToolSigner/Core/SigningEngine.swift`)

### 3.1 Types

- `struct SigningEngineError: LocalizedError, Sendable { code: String; message: String }`; `errorDescription` is
  `message` (`:4-8`). UI shows `error.localizedDescription` → the engine/host English message.
- `protocol IncrementalQREngine { beginScanSession(); scan(frame:) -> [String: Any]; endScanSession() }` (`:98-102`).
- `actor SigningEngine: IncrementalQREngine` (`:108`): all calls serialized; one instance owned by `AppStore`
  (`Models/AppStore.swift:67`, `OfflineSigningEngine` protocol `:5-12` with default no-op `releaseWarmContext`).

### 3.2 Allowlists (`:118-129`, enforced in `run` `:146-153`)

| Set | Operations |
|---|---|
| `productionOperations` | `mnemonic`, `capabilities`, `derivePublicIndex`, `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing`, `decodeQR`, `decodePayload`, `review`, `resolveSigningPlan`, `signPlanTask`, `encodeResponse` |
| `warmOperations` (subset) | `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing` |
| `testOperations` (`#if DEBUG` only) | `deriveAccounts`, `deriveBitcoinCosigner`, `encodeQR`, `sign`, `createPairing`, `validateMnemonic`, `wordlist` |
| Only via scan-session API | `scanBegin`, `scanAdd` |
| Never sent by the host (engine has them) | `validateBitcoinPolicy`, `deriveWalletPublic` (alias of `derivePublicIndex`) |

Anything else: `SigningEngineError(code: "UNSUPPORTED_OPERATION", message: "This signing operation is not supported.")`
(`:152`). A Release test asserts `sign` is refused (`BitToolSignerTests/HardeningTests.swift:169-171`).

### 3.3 `run(operation:parameters:)` (`:146-163`)

1. Allowlist check (above).
2. `requestJSON` (`:184-195`): copy parameters, set `"operation"`, `JSONSerialization.isValidJSONObject` else
   `INVALID_REQUEST` / `"The signing request has an invalid format."`; serialize with `.sortedKeys`; if > 1,048,576
   bytes or not UTF-8 → `REQUEST_TOO_LARGE` / `"This request exceeds the 1 MB offline signing limit."`.
3. Warm op → reuse `publicContext` or create one and keep it; otherwise `makeContext()` (fresh) and drop it after the
   call. Wrapped in `autoreleasepool` (JSC memory hygiene; no Android equivalent needed).

### 3.4 Context creation (`EngineContext.init`, `:18-71`)

1. `JSVirtualMachine()` + `JSContext` — failure → `ENGINE_UNAVAILABLE` / `"The offline signing engine could not be started."` (`:19-21`).
2. Install exception handler that sets `evaluationFailed` (`:24`).
3. Register native `__bitSignerRandom(count) -> [NSNumber]?` returning `SecureRandom.bytes(count)` or `nil` (`:25-29`).
4. Evaluate the bootstrap (§3.6). Exception → `ENTROPY_UNAVAILABLE` / `"The engine’s secure entropy source could not be installed."` (`:61-63`).
5. Evaluate `signer.js` with its file URL; exception, missing `BitToolSignerEngine` or missing `dispatch` →
   `ENGINE_INVALID` / `"The bundled signing engine could not be loaded."` (`:64-68`).
6. Keep a reference to `dispatch`; replace the exception handler with one that sets `failed` (`:69-70`).

Source loading (`:202-214`): `signer.js` from the main bundle (or `Resources/` subdirectory); DEBUG may substitute a
test file URL (`init(bundle:sourceURL:)`, `:138-144`, test-only). Missing/unreadable →
`ENGINE_MISSING` / `"The bundled signing engine is unavailable. Reinstall BitTool Signer."` (`:209-211`).

### 3.5 `call(_:)` — one dispatch (`:73-94`)

- **The JSON is passed as the argument of `dispatch`; request text is never interpolated into script** (`:73`,
  Security.md:67). Test: `SecurityTests.swift:107-133` echoes `"\"; globalThis.invocations = 99; //"` unchanged.
- Failure if the call returned nothing, an exception fired, the response is > **2,097,152 UTF-8 bytes**, it is not
  UTF-8, not a JSON object, or has no Boolean `ok` → `ENGINE_FAILURE` / `"The offline engine could not process this request."` (`:77-83`).
- `ok == false` → `SigningEngineError(code: String((error.code ?? "SIGNING_FAILED").prefix(80)), message:
  String((error.message ?? "This request could not be signed.").prefix(400)))` (`:84-90`). (Swift `prefix` counts
  grapheme clusters.)
- `ok == true`: `result` object returned as-is; a non-object result → `["value": result]`; missing → `[:]` (`:91-93`).
- `context.exception` cleared after each call (`:76`).

### 3.6 The entropy bootstrap — verbatim (`:32-60`, after Swift's 8-space indentation strip)

```js
(function (random) {
    'use strict';
    delete globalThis.__bitSignerRandom;
    Object.defineProperty(globalThis, 'crypto', {value: Object.freeze({
        getRandomValues: function (array) {
            var integer = array instanceof Int8Array || array instanceof Uint8Array ||
                array instanceof Uint8ClampedArray || array instanceof Int16Array ||
                array instanceof Uint16Array || array instanceof Int32Array ||
                array instanceof Uint32Array ||
                (typeof BigInt64Array !== 'undefined' && array instanceof BigInt64Array) ||
                (typeof BigUint64Array !== 'undefined' && array instanceof BigUint64Array);
            if (!integer || array.byteLength > 65536) throw new Error('Invalid entropy request');
            if (array.byteLength === 0) return array;
            var bytes = random(array.byteLength);
            if (!bytes || bytes.length !== array.byteLength) throw new Error('Secure randomness unavailable');
            new Uint8Array(array.buffer, array.byteOffset, array.byteLength).set(bytes);
            return array;
        }
    }), writable: false, configurable: false});
    // Some bundled libraries use Math.random for internal identifiers
    // during module initialization. Back it with native entropy too.
    Object.defineProperty(Math, 'random', {value: function () {
        var bytes = new Uint32Array(2);
        crypto.getRandomValues(bytes);
        return ((bytes[0] >>> 5) * 67108864 + (bytes[1] >>> 6)) / 9007199254740992;
    }, writable: false, configurable: false});
})(__bitSignerRandom);
```

Required behaviour (Android must match exactly, whatever the native transport):

- The native entry point is captured and **deleted from `globalThis` before the bundle runs**.
- `crypto` is a frozen, non-writable, non-configurable global with only `getRandomValues`.
- Only integer typed arrays; `byteLength > 65536` → `Error('Invalid entropy request')`; zero length returns the array;
  native failure or wrong length → `Error('Secure randomness unavailable')`; fills the array's own byte view in place
  and returns the same array.
- `Math.random` is replaced (non-writable, non-configurable) by a 53-bit value from two native `Uint32`s:
  `((a >>> 5) * 2^26 + (b >>> 6)) / 2^53`.
- The scaffold's Android variant passes hex from Kotlin (`QuickJsContextHost.BOOTSTRAP`) because wrapper strings are
  the safe type; its decoding loop and every check above are equivalent. Keep it.

### 3.7 Scan session (`:165-178`)

- `beginScanSession()`: drop any old `scanContext`, create a fresh context, call `scanBegin` in it, keep it (`:165-170`).
- `scan(frame:)`: no context → `SigningEngineError(code: "SCAN", message: "The scan session ended. Scan again.")`
  (`:172-173`); else `scanAdd` with `{"frame": frame}` in that context.
- `endScanSession()`: drop it (`:178`). Errors from `scanAdd` do **not** drop the context on iOS; the UI stops feeding
  frames and offers a reset that calls `beginScanSession` again (`Features/Sign/ScanTab.swift:26-50, 79-84`).

### 3.8 `releaseWarmContext()` (`:180-182`)

Drops the warm context. Called from `AppStore.suspend()` (`Models/AppStore.swift:86-94`) — i.e. background, App Lock,
screen capture — and Connect's prefetch warms it again when allowed (`AppStore.swift:268-301`).

---

## 4. Native seed derivation and randomness

### 4.1 `BIP39Seed.hex(mnemonic:passphrase:)` (`Core/BIP39Seed.swift:9-53`)

- Phrase normalization: NFKD (`decomposedStringWithCompatibilityMapping`) → `lowercased()` → split on any whitespace
  → join with single `" "` (`:22-26`). This matches the engine's `normalizeMnemonic` (`engine/src/keys.js:14`: NFKD,
  trim, lowercase, `\s+` → `" "`). The caller passes the **canonical** phrase (validated natively, NFC words joined
  by spaces — `BIP39Mnemonic.validatedCanonical`, `AppStore.swift:172-175, 184`).
- Bounds: normalized phrase non-empty and ≤ **1,024** UTF-8 bytes (`maximumPhraseBytes`, `:17`, sized for 24 Korean
  words); passphrase ≤ 1,024 UTF-8 bytes (raw, before NFKD). Violations → `VaultError.invalidSecret` (`:27-30`).
- Password = UTF-8 of the normalized phrase; salt = UTF-8 of NFKD(`"mnemonic" + passphrase`); PBKDF2-HMAC-SHA512,
  **2,048** iterations, 64-byte output (`:31-45`). Failure → `VaultError.keyDerivationFailure`.
- Output: 128 lowercase hex chars. All buffers wiped with `memset_s` (`:34, 50-52`).
- Android: implement PBKDF2 over raw bytes with `Mac("HmacSHA512")` (do not use `SecretKeyFactory` with `char[]`,
  whose encoding is provider-defined), NFKD via `java.text.Normalizer.Form.NFKD`, lowercase with `Locale.ROOT`,
  whitespace split on Unicode whitespace (`Character.isWhitespace` plus U+3000/U+00A0 handled by NFKD), wipe
  `ByteArray`s. Placement: `:core:crypto` (DECISIONS.md §3).

### 4.2 Randomness

- `SecureRandom.bytes(count)` (`Core/VaultCrypto.swift:47-57`): `1...65,536` bytes else `VaultError.randomFailure`;
  `SecRandomCopyBytes`. Android: `java.security.SecureRandom` (default, non-blocking). Keep the 1…65,536 bound.
- `SecureRandom.entropyHex(wordCount:)` (`:59-62`): `wordCount ∈ {12,15,18,21,24}` else `VaultError.invalidSecret`;
  `wordCount / 3 * 4` bytes (16, 20, 24, 28, 32) as lowercase hex → the `mnemonic` operation's `entropy`.

---

## 5. Context policy and lifecycle

### 5.1 Which runtime runs what

| Operation | Context (iOS) | Receives a secret | Receives external/untrusted data | Notes |
|---|---|---|---|---|
| `mnemonic` | fresh | entropy in, phrase out | no | |
| `capabilities` | fresh | no | no | not called by iOS UI |
| `derivePublicIndex` | fresh | **seed** | no | ≈ 7 s on emulator |
| `derivePublicAddresses` | **warm** | no | no (device's own authenticated public index) | xpub/branch caches persist (`engine/src/public-index.js:50-64`, 64 entries each) |
| `createPublicPairing` | **warm** | no | no | |
| `createWalletPairing` | **warm** | no | no | used by the Connect prefetch |
| `decodeQR` | fresh | no | **yes** | production uses the scan session instead (`AppStore.swift:330-334`) |
| `scanBegin` / `scanAdd` | **scan** (one per camera session) | no | **yes** | module-level `scanSession` lives only in that context (`index.js:30-34`) |
| `decodePayload` | fresh | no | **yes** | paste / file |
| `review` | fresh | no | **yes** | also Activity re-review |
| `resolveSigningPlan` | fresh | no | **yes** | |
| `signPlanTask` | fresh | **seed** | **yes** | re-derives the public index inside |
| `encodeResponse` | fresh | no | **yes** (request) | |
| DEBUG test ops | fresh | `sign`/`deriveAccounts`/`deriveBitcoinCosigner` take a mnemonic | — | JS PBKDF2 runs here (slow on QuickJS) |

Security.md:64 states the rule: "Any operation that receives secrets or untrusted input … gets a fresh JavaScript VM,
released afterwards. Only … public operations over the device's own authenticated public index … reuse a warm VM,
which never receives secrets or external data and is dropped whenever the app is suspended … An animated QR scan uses
its own VM for incremental frame collection, discarded when the scan ends." The warm VM must only ever receive a
`publicIndex` that came from the authenticated local repository (`AppStore.publicWallets`), never one decoded from a
QR or a file.

### 5.2 Lifecycle events → engine actions

| Event (iOS) | Engine action | Source |
|---|---|---|
| App suspend: background, App Lock, screen capture | `releaseWarmContext()`; prefetched Connect QRs cleared | `AppStore.swift:86-94`, Security.md:64, `IMPLEMENTATION_STATUS.md:704-722` |
| Connect prefetch allowed (active, unlocked, not captured, wallets visible) | after 600 ms, `createWalletPairing` per ready wallet on the warm context (utility priority); result discarded if the security epoch or that wallet's index changed | `AppStore.swift:270-301` |
| Scanner sheet appears / user taps reset | `beginScanSession()` | `ScanTab.swift:26-40, 166-168` |
| Scanner sheet dismissed | `endScanSession()` | `ScanTab.swift:42-45, 164` |
| Request replaced / rejected / app suspended mid-operation | the JS call is not interrupted; its late result is discarded by epoch checks | `AppStore.swift:109-112`, SessionTests (§11.3) |

There is no timeout and no interrupt on iOS; engine complexity limits bound the work.

---

## 6. Operation reference

Notation: `→` result object (inside `ok:true`); "Errors" lists `code` / `'message'` exactly as the engine emits.
"iOS call" gives the exact parameters `AppStore` sends and the fields it consumes.

### 6.1 `mnemonic` (`engine/src/keys.js:16`)

- In: `{"entropy": hex}` — `hexToBytes(entropy, 32)`: string (`ENCODING`, `'Expected hexadecimal text.'`), optional
  `0x`, even length, ≤ 64 hex digits (`ENCODING`, `'Invalid or oversized hexadecimal data.'`); byte length ∈
  {16,20,24,28,32} (`ENTROPY`, `'Entropy must contain 128–256 bits in 32-bit steps.'`).
- → `{"mnemonic": "<English words, single spaces>", "wordCount": 12|15|18|21|24}`.
- iOS call (`AppStore.swift:160-168`): `{"entropy": SecureRandom.entropyHex(wordCount:)}`; reads `mnemonic` (or
  `value`) as String else `"Unable to generate recovery phrase."`; discards the result if the security or sensitive
  epoch changed.
- Golden: entropy `00`×16 → `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon
  about`, `wordCount` 12.

### 6.2 `capabilities` (`engine/src/index.js:14-23`)

- In: none. → static object: `version:"1.0.0"`, `offline:true`, `chains:["bitcoin","evm","tron","solana"]`,
  `protocols:["crypto-psbt","eth-sign-request/eth-signature","sol-sign-request/sol-signature","tron-sign-request/tron-signature","BBQR PSBT"]`,
  `authorization`, `bitcoin{supported,policy,rejected}`, `evm{supported,networks:[1,56,42161,8453,10,137,100,43114],riskGated,rejected}`,
  `tron{supported,rejected,offlineLimits}`, `solana{supported,riskGated,rejected}`, `releaseQualification`.
- Allowlisted; no iOS UI caller. Fresh context. Useful as a cheap smoke test.

### 6.3 `derivePublicIndex` (`engine/src/public-index.js:23-34`, alias `deriveWalletPublic`)

- In (production): `{"seed": "<128 hex>"}`. In (tests): `{"mnemonic": "...", "passphrase"?: "..."}`.
- Validation (`keys.js:17-32`): seed given → `mnemonic` and `passphrase` must be **absent** (`SEED`, `'Provide either a
  seed or a recovery phrase, not both.'`); seed must match `/^[0-9a-f]{128}$/i` (`SEED`, `'Invalid wallet seed.'`).
  Mnemonic path: string ≤ 512 chars (`MNEMONIC`, `'Invalid recovery phrase.'`), **English** wordlist + checksum
  (`MNEMONIC`, `'Recovery phrase has invalid words or checksum.'`), passphrase string ≤ 1,024 chars (`PASSPHRASE`,
  `'BIP39 passphrase is too long.'`). Seed bytes and HD root are wiped after use.
- → the public index v1 (§7.1). ~30.6 KB compact JSON for the reference wallet.
- iOS calls (all `{"seed": BIP39Seed.hex(...)}`): `createWallet` (`AppStore.swift:185`), `authenticatedPublicIndex`
  for repair/complete setup (`:229-233`), `existingWallet` (`:484-491`), `resetPassword` (`:498-512`). Each result goes
  through `WalletPublicMetadata(raw)` (§9.18) and the identity comparisons in §9.19.
- Golden (reference phrase `abandon … about`, passphrase ""): `walletKeyId`
  **`99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96`**, `masterFingerprint` **`73c5da0a`**; seed
  input and phrase input produce identical indexes (`HardeningTests.swift:27-34`). More in §11.4.

### 6.4 `derivePublicAddresses` (`public-index.js:94-107`)

- In: `{"publicIndex", "chain", "profile"?, "start"=0, "count"=20}`.
- Validation: `validatePublicIndex` (§7.1); `start` integer ≥ 0, `count` integer 1…100, `start + count ≤ 2^31`
  (`INDEX`, `'Invalid address page'`).
  - `chain == "solana"`: truthy `profile` → that one preset (unknown → `PROFILE`, `'Unsupported Solana profile'`);
    falsy (`""` or absent) → all three; → `{addresses:[…], hasMore:false}` (no `nextIndex`); `start`/`count` are
    still validated first but otherwise ignored.
  - Otherwise `chain ∈ {bitcoin, evm, tron}` (`CHAIN`, `'Unsupported address chain'`); `profile ?? "native"`
    (note `??`: an empty string stays empty and is **invalid for Bitcoin**); Bitcoin profile must be one of
    `native|taproot|nested|legacy` (`PROFILE`, `'A cosigner key cannot define a multisig receive address'`); EVM/TRON
    ignore the profile. Addresses are receive branch `/0/i` for `i ∈ [start, end)`, `end = min(start+count, 2^31)`;
    → `{addresses, hasMore: end < 2^31, nextIndex: end}`.
- Address entry: `{chain, profile, path, index, branch, publicKey, address, masterFingerprint}` (Solana entries have
  no `branch`; `profile` is the node's script profile, `"standard"` for EVM/TRON).
- iOS call (`AppStore.swift:243-248`, `Features/Wallets/AddressesView.swift:136-140`): page size 20;
  `profile = bitcoin ? <selected native|taproot|nested|legacy> : solana ? "" : "standard"`; `count = solana ? 3 :
  min(20, limit − start)` with `limit = solana ? 3 : 2,147,483,648`. Reads `addresses` (else `"Unable to derive public
  addresses."`) → `PublicAccount{chain,address,path,publicKey,index}` (`Models/Models.swift:34-49`).
- Golden: BTC native `[0]` `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` (pub `0330d54f…af3c`), `[1]`
  `bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g`, `hasMore:true`, `nextIndex:2`; TRON page `start 99, count 20` →
  20 addresses, `hasMore:true`, `nextIndex:119`; `start 2147483646, count 2` → 2 addresses, `hasMore:false`.

### 6.5 `createPublicPairing` (`engine/protocols.js:561-599`, alias `createPairing` DEBUG)

- In: `{"publicIndex", "chain", "profile"?, "outputMode"?, "index"=0, "fragmentLength"?}` (iOS never sends
  `fragmentLength`; default 180, range 40…800 → `'Invalid QR fragment length'`).
- Validation (`QR_PROTOCOL` unless noted): `validatePublicIndex` (`PUBLIC_INDEX` codes); chain in the four
  (`'Unsupported pairing chain'`); `index` integer ≥ 0 and `< 2^31` for TRON, `== 0` otherwise (`'Invalid pairing
  address index'`).

| Chain | `profile` | `outputMode` (default) | Allowed | QR `type` / frames |
|---|---|---|---|---|
| bitcoin | `native` (default), `taproot`, `nested`, `legacy` | `accountXpub` | `accountXpub`, `address` | `crypto-account` (script tags: native 404, taproot 409, nested 400(404), legacy 403) or `address` |
| bitcoin | `multisig-native`, `multisig-nested` | `accountXpub` | `accountXpub` only | `crypto-account` with 401 / 400(401) |
| evm | ignored | `wallet` | `wallet`, `address` | `crypto-hdkey` (account node, `children` `0/*`) or `address` (index 0) |
| solana | `standard` (default), `trust`, `legacy` | `wallet` | `wallet`, `address` | `crypto-multi-accounts` with exactly one 32-byte key or `address` |
| tron | echoed (`profile || 'standard'`) | `address` | `address` only | `address` (any index 0…2^31−1) |

  Errors: `'Unsupported Bitcoin profile'`, `'Unsupported Bitcoin export mode'` (e.g. the removed `publicKey` mode),
  `'Unsupported export mode'`, `'Unsupported Solana profile'`.
- → address mode: `{type:"address", frames:[address], text:address, address, path, profile, outputMode,
  payloadFormat:"text", payload:address, payloadBytes:<UTF-8 length>}`.
  UR modes: `{type, frames:[UPPERCASE UR…], text: frames.join("\n"), path, address, profile, outputMode,
  payloadFormat:"json", payload:<describeUR object>, payloadBytes:<CBOR length>, note?}`; Bitcoin `note` is
  `'Cosigner account key only. No threshold or complete wallet policy is included.'` (multisig) or `'Public account
  key. Receive and change addresses can be derived.'`; Bitcoin `path` is the account origin for `accountXpub`, the
  `/0/0` leaf for `address`; `address` is the `/0/0` address, or `""` for multisig cosigners. EVM `path` is the
  account origin in `wallet` mode. Keys carry `name: "BitTool Signer"`, `note: "BitTool Signer public account"`;
  `crypto-multi-accounts` adds `device:"BitTool Signer"`, `version:"1.0.0"`.
- iOS call (`AppStore.swift:249-253`; parameters from `Features/Wallets/ConnectView.swift:116-132, 323`):
  `profile = bitcoin ? (multisig ? btcMulti : btcSingle) : solana ? solanaProfile : "standard"`;
  `outputMode = bitcoin ? (multisig ? "accountXpub" : btcMode) : evm ? evmMode : solana ? solanaMode : "address"`;
  `index = tron ? tronIndex : 0`. `PairingPayload` (`Models.swift:571-589`) reads `frames` (non-empty, else
  `"Unable to encode public wallet information."`), `text ?? frames.joined("\n")`, `address`, `path`, `outputMode`,
  `note`, and the payload view (§7.8).
- Golden (reference wallet): BTC native accountXpub → 1 frame of 366 chars, SHA-256 of `text`
  `312e53499008fa7ebb20b8a23dcc3a8d0821e34b4dda9d484704362e47d3bfee`, `payloadBytes` 170, payload
  `master-fingerprint` **1942346250** (= `0x73c5da0a`), `parent-fingerprint` 2129865691; EVM wallet → 1 frame,
  prefix `UR:CRYPTO-HDKEY/`, 364 chars, `parent-fingerprint` **3543024904** (> Int.MAX — never parse into `Int`);
  TRON index 3 → `TRhVWK5XEDkQBDevcdCWW7RW51aRncty4W`, path `m/44'/195'/0'/0/3`; EVM with `outputMode:"accountXpub"`
  → `QR_PROTOCOL` `'Unsupported export mode'`.

### 6.6 `createWalletPairing` (`protocols.js:608-700`, spec `docs/Wallet-Pairing-v1.md`)

- In: `{"publicIndex", "mode": "full"}`; any other mode → `QR_PROTOCOL` `'Unsupported wallet pairing mode'`.
- Builds the logical object `{protocol:"bittool-signer-wallet", version:1, mode:"full", network:"mainnet",
  masterFingerprint:<lowercase>, accounts:[ btc p2wpkh m/84', btc p2tr m/86', btc p2sh-p2wpkh m/49', btc p2pkh m/44'
  (each {chain:"btc", accountPath, script, xpub, derive:{receive:"0/*",change:"1/*"}}), {chain:"evm", accountPath,
  xpub, derive:"0/*"}, {chain:"tron", accountPath, xpub, derive:"0/*"}, {chain:"sol", profile:"bip44-solana",
  accounts:[{index:0,profile:"standard",path,address},{index:1,"trust"},{index:2,"legacy"}]} ]}` — multisig profiles
  are deliberately excluded. Canonical CBOR (RFC 8949 §4.2.1) ≤ 4,096 bytes (`'Wallet pairing payload is too
  large'`), wrapped as a CBOR byte string in `ur:bytes`, fragment `min(400, ceil(len/2))`, ≥ 2 frames (`'Wallet
  pairing QR must be animated'`).
- → `{type:"bytes", frames:[…], text: frames.join("\n"), mode:"full", payload:<logical object>, payloadBytes:<inner
  CBOR length>, verify:[{chain, profile, path, address}×9]}`. **No `payloadFormat`** (no describeQR view here).
  `verify` order: btc native, taproot, nested, legacy; evm standard `m/44'/60'/0'/0/0`; tron standard
  `m/44'/195'/0'/0/0`; sol standard, trust, legacy.
- iOS call (`AppStore.swift:305-309`): `{"publicIndex", "mode": "full"}`; `WalletPairingPayload`
  (`Models.swift:535-569`) reads `frames` (non-empty), `payload` (object; else `"Unable to encode the wallet
  pairing."`), `text`, `mode`, `payloadBytes`, `verify` rows (require `chain`, `address`, `path`; `profile` defaults
  `"standard"`; row id `chain + "/" + profile`), and pretty-prints `payload` (sorted keys).
- Golden: 4 frames × 831 chars, `UR:BYTES/1-4/…`…`UR:BYTES/4-4/…`, SHA-256 of `text`
  `fd13470f20bb040cbd0a212b073b62198eb401012471f3e7c9924d04127ab0e8`, `payloadBytes` **1561**; frames, payload and
  verify equal `docs/bittool-handoff/fixtures/wallet-pairing-current.json` exactly (inner CBOR SHA-256
  `c8db82cd92e0cf45cb6329b2429494b04a3b6bad7653c9bed1d2a5ef10f4a5fc`).

### 6.7 Scan: `scanBegin`, `scanAdd`, `decodeQR` (`index.js:30-34`, `protocols.js:274-365`)

- `scanBegin` → `{started:true}` (creates a new `QRCollector` in this context).
- `scanAdd` `{"frame": string}` → status. Without `scanBegin` in the same context: `SCAN` / `'No scan session is
  active.'`.
- `decodeQR` `{"frames": [string]}` (1…2,048 entries, else `QR_PROTOCOL` `'Invalid QR frame count'`) → status after
  adding every frame to a fresh collector (identical result to incremental — F13).
- Status: `{complete:false, progress:<number>}` or `{complete:true, progress:1, request:<request>}`. `progress`: BBQr
  `received/total`; UR fountain `min(0.99, estimatedPercentComplete())` (golden: 5-part PSBT at fragment 60 →
  0.114…, 0.228…, 0.342…, 0.457…, complete); otherwise 0. It may be an integer (`0`, `1`): read as Double.
  After completion, further frames (even repeats) return the same complete status.

`QRCollector.add(input)` rules (all `QR_PROTOCOL`):

| Check | Message |
|---|---|
| string, 1…4,300 chars (length before trim) | `'QR frame size exceeds limits'` |
| trimmed non-empty | `'Empty QR frame'` |
| (already complete, or exact frame already seen → return status, no error) | — |
| ≤ 2,048 distinct frames; ≤ 1,048,576 retained chars | `'Invalid QR frame count'`, `'QR scan memory limit exceeded'` |
| kind by prefix: `B$` → BBQr, `/^ur:/i` → UR, else static; all frames one kind | `'Multiple unrelated QR payloads'` |
| static: one frame only; `cHNidP…` (Base64, strict) or `/^70736274ff/i` (hex); ≤ 131,072 bytes; PSBT magic | `'Multiple unrelated QR payloads'`, `'Unsupported signing QR format'`, `'Invalid base64 bytes'` / `'Invalid hexadecimal bytes'`, `'QR payload exceeds supported size'`, `'Invalid PSBT magic'` |
| UR type ∈ `crypto-psbt`, `psbt`, `eth-sign-request`, `sol-sign-request`, `tron-sign-request`; one type per session | `'Unsupported signing UR type'`, `'Mixed UR types'` |
| UR parts: 1 (single) or 2 (`seq-count/…`); single only as the only frame; no multipart after single | `'Invalid UR frame'`, `'Multiple static QR payloads'`, `'Invalid fountain sequence'`, `'Fountain sequence exceeds limits'` |
| fountain part CBOR `[seq,count,length,checksum,fragment]` consistent; one session key `type:count:length:checksum:fragLen` | `'Invalid fountain fragment'`, `'Invalid fountain fragment metadata'`, `'Inconsistent fountain fragment size'`, `'Mixed UR fountain sessions'` |
| UR decoder error | `'UR checksum failure'` |
| BBQr header `B$[H2Z]P<total:2 base36><index:2 base36><body>` | `'Unsupported BBQR header, file type, or alphabet'`, `'Invalid BBQR fragment index'`, `'Mixed BBQR encodings or counts'`, `'Invalid BBQR encoded data'`, `'Nonfinal BBQR base32 fragment must end on a 5-byte boundary'`, `'Unequal BBQR fragment lengths'`, `'Conflicting BBQR fragment'`, `'Oversized final BBQR fragment'`, `'BBQR decompressed payload exceeds supported size'` (Z: raw DEFLATE inflated in 128-byte steps, output ≤ 131,072), `'BBQR PSBT magic mismatch'` |
| strict CBOR of the completed UR (definite lengths, no duplicate map keys, depth ≤ 24, ≤ 20,000 nodes, safe ints, no floats, no trailing bytes, valid UTF-8) | `'Truncated CBOR'`, `'CBOR complexity limit exceeded'`, `'Indefinite or reserved CBOR encoding is unsupported'`, `'Unsupported CBOR simple value'`, `'CBOR integer exceeds exact numeric range'`, `'Invalid UTF-8'`, `'CBOR array too large'`, `'CBOR map too large'`, `'Unsupported CBOR map key'`, `'Duplicate CBOR map key'`, `'Unsupported CBOR type'`, `'Trailing CBOR data'` |

Bytewords/UR parse exceptions thrown by `@ngraveio/bc-ur` itself become the generic `INVALID_REQUEST` (golden:
`ur:eth-sign-request/zz`). A pairing export (e.g. `ur:crypto-account`) is refused with `'Unsupported signing UR type'`
(`HardeningTests.swift:136-140`).

iOS host around the scan (all in Kotlin-portable form in `docs/port/scan-sign.md` §6-7):
`QRScanFrameGate` (`Features/Sign/QRScannerView.swift:207-239`) — `nil` → `.nonText`; > **16,384 UTF-8 bytes** →
`.tooLarge`; blank after trimming → `.empty`; the same outcome within < 1 s of the previous one (and the clock not
going backwards) → `.throttled`; accepted text is passed **unchanged** (no trimming). `ScanSession`
(`ScanTab.swift:9-86`) de-duplicates frames, queues them while a call is in flight, processes one at a time, keeps
`progress = max(progress, value)`, stops on the first error, re-queues a frame whose call straddled a security-epoch
change. `AppStore.receive(frame:)` (`AppStore.swift:330-341`) calls `clearRequest()`, `scan(frame:)`, checks epochs,
and on `complete == true` with a `request` object calls `prepare(request)`; returns `progress as Double ?? (review ==
nil ? 0 : 1)`. Fallback `receive(frames:)` (`:343-351`) — only for non-incremental engines (tests): ≤ 2,048 frames and
≤ 1,048,576 total UTF-8 bytes else `"QR transfer is too large."`.

### 6.8 `decodePayload` (`protocols.js:261-272`)

- In: `{"payload": string}`; `Buffer.byteLength ≤ 262,144` (`'File payload exceeds supported size'`); trimmed
  non-empty (`'Empty signing payload'`).
- If the text starts with `cHNidP` or (case-insensitive) `70736274ff` and contains only Base64 characters and
  whitespace, all whitespace is removed (line-wrapped PSBT files, F15). A PSBT text → `{complete:true, progress:1,
  request:{chain:"bitcoin", psbt:<base64>, transport:{type:"psbt-base64"|"psbt-hex", payloadEncoding:"psbt",
  payloadBytes:<hex>}}}`. Anything else: split on `\r?\n`, trim each line, drop empty lines, `decodeQR(frames)`.
- Golden: `"hello"` → `QR_PROTOCOL` `'Unsupported signing QR format'`.
- iOS call (`AppStore.swift:352-360`): text ≤ **262,144 UTF-8 bytes** else `"Text payload exceeds 256 KB."`;
  `complete == true` and `request` object, else `"The request is incomplete. Import all QR frames."`; then
  `prepare(request)`. File import (`ScanTab.swift:225-238`): file ≤ **512,000 bytes** else `"This file is larger than
  512 KB."`; bytes starting `70 73 62 74 ff` → Base64 text; else UTF-8 text; else `"Choose a PSBT file or a text
  file containing a UR request."`. Paste sheet: `"Paste a PSBT (Base64 or hex) or a UR signing request. Multi-part UR
  requests can be pasted one part per line."`.

### 6.9 `review` (`index.js:24-25`)

- In: `{"request": <request>}`. `request` must be an object whose `chain ∈ {bitcoin, evm, tron, solana}` (`REQUEST`,
  `'Unsupported or missing chain.'`); `requestHash(request)` must be computable (§2.3); then the chain parser runs
  (`parseBitcoin`, `parseEVM`, `parseTron`, `parseSolana`). **`review` does not validate the transport binding**;
  `resolveSigningPlan` and `signPlanTask` do.
- → review result (§7.3). `canSign` is always `true` when the call succeeds (`util.js:33`); unsupported content is an
  error, not `canSign:false`.
- iOS calls: `prepare` (`AppStore.swift:361-373`) with the frozen request; Activity
  `review(storedRequest:expecting:)` (`:581-591`). Consumed through `TransactionReview` (`Models.swift:357-402`).

### 6.10 `resolveSigningPlan` (`engine/src/resolver.js:166-200`)

- In: `{"request": <request>, "wallets": [{"localWalletId": string, "publicIndex"?: <index>, …}]}`. Extra wallet keys
  are ignored (iOS sends `publicMetadataStatus` for incomplete wallets).
- Never fails through the envelope for request problems: any `EngineError`/`ProtocolError` → `{version:1,
  status:"invalid", message:<that message>, tasks:[]}`; any other exception → same with `'Invalid or unsupported
  signing request'`.
- Order of checks: chain known (`REQUEST`, `'Unsupported signing request'`); `validateRequestTransport` (re-decode
  `transport.payloadBytes` CBOR when `payloadEncoding == "cbor"` and require `requestHash(original) ==
  requestHash(request)` → `'Signing request differs from its original QR payload'`; for `"psbt"` the PSBT bytes must
  equal → `'Signing request differs from its original PSBT payload'`); `wallets` array ≤ 100 (`LIMIT`, `'Invalid public
  wallet collection'`); unique `localWalletId` (`WALLET`, `'Duplicate local wallet identifier'`); `masterFingerprint`
  8 hex (`SIGNER`, `'Invalid master fingerprint'`); `publicKey` shape per chain (`SIGNER`, `'Invalid signer public
  key'`); each `localWalletId` string 1…128 chars (`WALLET`, `'Invalid local wallet identifier'`); wallets without a
  valid `publicIndex` are "incomplete".
- Matching (README.md:100-109; resolver.js:57-111): EVM by registered path + identity, or index 0 only without a
  path; Solana among the three presets and (transactions) only a required, unsigned signer slot; TRON by path (any
  non-hardened index) or address against the 100 cached addresses only; Bitcoin by derivation metadata checked against
  the committed scripts, else `/0/0` of the four single-sig origins (cosigners only with explicit script keys); no
  identity at all → `missingSigner`.
- Failure results (`resolver.js:17-18`): `{version:1, status, message, tasks:[], review?}`

| `status` | `message` (engine English, shown verbatim on iOS) | When |
|---|---|---|
| `noMatch` | `'No matching wallet'` | nothing matched and no incomplete wallet |
| `missingSigner` | `'Missing signer information'` | no address / public key / fingerprint, or fingerprint without path |
| `ambiguous` | `'Ambiguous wallet match'` | two matches share a `walletKeyId`, or > 1 match on a non-Bitcoin chain |
| `setupRequired` | `'Wallet setup required'` | nothing matched and ≥ 1 wallet lacks a valid public index |
| `invalid` | controlled engine message or `'Invalid or unsupported signing request'` | any validation error |

  `review` (the parsed review) is attached to `noMatch`, `missingSigner`, `ambiguous`, `setupRequired`; not to `invalid`.
- Success → plan (§7.4) with `status:"ready"`, `planHash`.
- iOS call (`AppStore.swift:374-403`): `{"request": immutable, "wallets": candidates}` with candidates for **every**
  vault wallet in display order: `{"localWalletId": wallet.id.uuidString}` plus `"publicIndex": index.raw` when a
  ready public index exists, else `"publicMetadataStatus": "needsCompletion"|"invalid"`. Consumes `status`, `message`,
  and on `ready` the whole object (`SigningPlan`, `Models.swift:684-702`) plus the refined `review`.
- Golden (evm-public-ui request + reference wallet `10000000-0000-0000-0000-000000000001`): `requestHash`
  `a05ea69096eb986f75275407e4749ca938511775d67fed164e44f237d67efa57`, task `id`
  `c9c7617f633591154e91d3eb5346e4f195655f4dba4e71df9ddb436761604e88`, `planHash`
  `b3e62c7cd74c8bfb7bf9b6a892331dea7959f31ec84df8697ed6d1f0b4ec73c8`, `payloadDigest`
  `45154f1461335db3b8b9a3a2cc483dcdb7af9bacfca543481e550623c05f1f99`, `signingBytesDigest`
  `8d632f824a7373c9edd31d7d39e6ecef7cb47708b0366f64e12384d0f3f95ebd`; with `wallets: []` → `noMatch` (with `review`);
  with `publicKey: null` added → `invalid` / `'Signing request differs from its original QR payload'`.

### 6.11 `signPlanTask` (`resolver.js:225-243`)

- In: `{"request", "plan", "taskId", "seed"` (or `"mnemonic","passphrase"?` in tests)`, "previousResult"?,
  "riskAcknowledged"=false}`.
- Checks in order (every one is a hard error):
  1. `plan.status == "ready"` and `plan.version == 1` — `PLAN`, `'A resolved signing plan is required'`.
  2. `plan.planHash == requestHash(plan − planHash)` and `plan.requestHash == requestHash(request)` — `INTEGRITY`,
     `'The request or signing plan changed after review'` (golden: altering a task path, or the request nonce).
  3. `validateRequestTransport(request)` (as in §6.10, thrown as `QR_PROTOCOL`).
  4. `plan.tasks` 1…100 (`PLAN`, `'Invalid signing tasks'`); `taskId` present (`PLAN`, `'The signing task does not
     belong to this plan'`).
  5. Re-parse; signing-bytes digest equals `plan.signingBytesDigest` — `INTEGRITY`, `'Signing bytes changed after
     review'`.
  6. `decodeStatus ∈ {partiallyDecoded, opaque}` requires `riskAcknowledged === true` — `RISK`, `'Confirm this
     request’s decoding risks before signing'` (the engine does **not** gate `riskLevel == high`; the host does).
  7. `previousResult` (Bitcoin only): its `requestHash`/`planHash` equal the plan's (`INTEGRITY`, `'Previous signature
     does not belong to this signing plan'`); unsigned tx unchanged (`'Previous signature changed the transaction'`);
     PSBT globals, every input field except `partialSig`/`tapKeySig`/`tapScriptSig`, every output unchanged (`'Previous
     signature changed PSBT global metadata'` / `…input metadata` / `…output metadata`); no existing signature dropped
     or changed (`'Previous signature dropped an existing signature'`, `'Previous signature changed an existing
     signature'`).
  8. With the seed: `walletKeyId(root) == task.walletKeyId` (`SIGNER`, `'The decrypted wallet does not match the
     signing plan'`); rebuild the public index from the seed, re-run `resolveSigningPlan` with only this wallet and
     require `status ready`, one task, `stable(task) == stable(plan task)` (`INTEGRITY`, `'The signing targets do not
     match the request and decrypted wallet'`); every target key re-derived equals the approved key (`SIGNER`,
     `'Decrypted public key differs from the approved key'`).
  9. Chain signer runs (with its own `SIGNER`/`SIGNATURE` checks, and self-verification of produced signatures).
- → chain result (§7.6) plus `requestHash: plan.requestHash` (**original**), `planHash`, `taskId`,
  `completedTaskIds: [...(previousResult.completedTaskIds ?? []), taskId]`.
- iOS call (`AppStore.swift:463-472`): `{"request": object(pendingRequestData), "plan": plan.raw, "taskId": task.id,
  "seed": BIP39Seed.hex(secret.mnemonic, secret.passphrase), "riskAcknowledged": Bool}` + `"previousResult":
  object(previousResultData)` when a previous task completed. Vault unlock happens first (wrong password →
  `VaultError.authenticationFailed`, and the engine is **not** called — SessionTests asserts zero `signPlanTask`
  invocations).
- Golden (evm-public-ui): signature
  `6e0c75cba30c35be7c7b81ecfe9a7d5d3c6a5de57f3e41a8f33302a0667387f74ccbdc4de1695cfc98ddde775c7817f5f0a5de39b51b2617c08ad712d3f3e6e91b`
  (65 bytes, v = 27), `qrSignature` identical, `completedTaskIds` = `[taskId]`. Errors: wrong seed (`00`×64) →
  `SIGNER` `'The decrypted wallet does not match the signing plan'`; `taskId:"x"` → `PLAN` `'The signing task does not
  belong to this plan'`; opaque calldata without acknowledgement → `RISK`.

### 6.12 `encodeResponse` (`protocols.js:398-421`)

- In: `{"request", "result", "fragmentLength"?}` (iOS never sends `fragmentLength`; default 180).
- Response transport chosen by `request.transport.type` (absent → `bittool-signer-json` → `QR_PROTOCOL` `'Unsupported
  response profile'`):

| Request transport | Response |
|---|---|
| `bbqr-psbt` (encoding `H`) | BBQr `H` (hex) frames of `result.psbt` |
| `bbqr-psbt` (encoding `2` or `Z`) | BBQr `2` (Base32) frames — `Z` is never produced |
| `crypto-psbt` / `psbt` | same UR type, `result.psbt` |
| `psbt-base64` / `psbt-hex` | one static frame with the signed PSBT in the same text encoding if ≤ 1,800 chars, else animated `crypto-psbt` |
| `eth-sign-request` | `eth-signature` `{1: uuid-tagged requestId (echoed bytes, 1–64), 2: result.qrSignature ‖ result.signature}` (65–73 bytes) |
| `sol-sign-request` | `sol-signature` (64-byte signature, 16-byte request id) |
| `tron-sign-request` | `tron-signature` (65-byte signature, 16-byte request id) |

  `requestId` may be hex (as decoded) or canonical UUID text (8-4-4-4-12) which is converted to 16 bytes
  (`protocols.js:386-393`).
- → `{type, frames, payloadFormat, payload, payloadBytes}` — UR frames are **uppercase**, BBQr headers per spec,
  static PSBT text unchanged; **no `text` member** (the host joins frames with `"\n"`). The payload view is re-decoded
  from the produced frames (§7.8); frames that do not round-trip fail here (`'The exported QR code does not decode'`,
  `'The PSBT QR code does not decode to its own type'`).
- iOS call (`AppStore.swift:433-443`): only for the **final** task (`pendingTasks.count == 1`) with `{"request":
  request, "result": <that task's result>}`; `frames` (may be empty) and `text = encoded.text ?? frames.joined("\n")`;
  both empty → `"Unable to encode signature response."`; `signaturePayload = QRPayloadView(encoded)`.
- Golden: evm-public-ui → 1 frame `UR:ETH-SIGNATURE/OEADTPDAGDMYCNHYNLVAVSFZFYONCAGYWPFMSOFWPFAOHDFPJTBNKPSBOTBNECRNKEKGLYWPZENYKIHLFNIMHLVWLBFMFPPDWFEOAONBIYJKLTYLGSSBUOGTVYINHHZTMKUTUEKTHHKSCHYKWTONUEESRECWDSCHRTLETSBGTEWFVAWLCWTLLRUESN`,
  `payloadFormat:"json"`, `payload:{"request-id":{"uuid":"8f235e99e6e84044a51d51ec3ec942b0"},"signature":"6e0c…1b"}`,
  `payloadBytes` 89. A BTC `psbt-base64` request → `type:"psbt-base64"`, 1 frame, payload `{psbt_base64, decoded}`,
  `payloadBytes` 379.

### 6.13 DEBUG-only operations (test utilities)

| Operation | In | → | Notes |
|---|---|---|---|
| `deriveAccounts` | `{mnemonic, passphrase?, index=0}` | `{fingerprint, masterFingerprint, accounts:[{chain,name,address,path,publicKey,masterFingerprint, xpub?,accountPublicKey?,accountPath?,chainCode?,parentFingerprint?}×4]}` | JS PBKDF2; golden addresses §11.4 |
| `deriveBitcoinCosigner` | `{mnemonic, passphrase?, account=0, network, scriptType}` | cosigner `{xpub, accountPath, …, note}` | |
| `encodeQR` | `{type, payload, encoding='hex'|'base64', requestId?, fragmentLength?, bbqrEncoding='2'}` | `{type, frames}` | types: `bbqr-psbt`, `crypto-psbt`, `psbt`, `eth-signature`, `sol-signature`, `tron-signature`; `'Invalid signature size'` |
| `sign` | `{request, mnemonic, passphrase?, reviewedHash?}` | chain result (no plan fields) | unplanned signing; `INTEGRITY` if `reviewedHash` differs; `FINGERPRINT` on `request.masterFingerprint` mismatch |
| `createPairing` | as `createPublicPairing` | same | alias |
| `validateMnemonic` | `{mnemonic}` | `{valid: Bool}` | English only |
| `wordlist` | `{}` | `{words:[2048]}` | English only |
| `validateBitcoinPolicy` | `{descriptor}` | policy summary | **never called by the host**, not even in DEBUG |

---

## 7. Shared result shapes

Field order below is the engine's emission order; consumers must not depend on order.

### 7.1 Public index v1 (`public-index.js:17-49`; host check `Models.swift:470-531`)

```text
{
  version: 1,
  walletKeyId: <64 hex, lowercase>,             // SHA-256(UTF8("BitTool-Signer/WalletKeyId/v1") ‖ master compressed pubkey ‖ master chain code)
  masterFingerprint: <8 hex, lowercase>,
  bitcoinPublicNodes: [AccountNode × 6],         // native m/84'/0'/0', taproot m/86'/0'/0', nested m/49'/0'/0', legacy m/44'/0'/0',
                                                 // multisig-native m/48'/0'/0'/2', multisig-nested m/48'/0'/0'/1'
  evmAccountNode: AccountNode,                   // m/44'/60'/0', scriptProfile "standard"
  tronAccountNode: AccountNode,                  // m/44'/195'/0'
  tronAddressIndex: [AddressEntry × 100],        // m/44'/195'/0'/0/0 … /0/99, index = position
  solanaPublicKeys: [SolanaKey × 3],             // standard m/44'/501'/0'/0', trust m/44'/501'/0', legacy m/44'/501'
  evmDefaultAddress: AddressEntry                // m/44'/60'/0'/0/0
}
AccountNode  = {chain, originPath, accountPath, path (all three equal), xpub ("xpub…"), publicKey (33-byte hex),
                compressedPublicKey (= publicKey), chainCode (32-byte hex), parentFingerprint (8 hex), depth (3, or 4 for m/48'),
                scriptProfile, children (["0/*","1/*"] bitcoin, ["0/*"] otherwise), masterFingerprint}
AddressEntry = {chain, profile, path, index, branch, publicKey (33-byte hex), address, masterFingerprint}
SolanaKey    = {chain:"solana", profile, path, index:0, publicKey (32-byte hex), address (Base58), masterFingerprint}
```

Engine `validatePublicIndex` (`PUBLIC_INDEX`): version 1, 64-hex `walletKeyId`, 8-hex fingerprint (`'Wallet setup
required'`); 6 BTC nodes, 3 Solana keys, 100 TRON entries (`'Wallet setup required'`); exactly one node per BTC profile
with its registered origin (`'Invalid Bitcoin public index'`); EVM/TRON origins (`'Invalid public account origin'`);
TRON entries `index == i`, path, `02|03` keys (`'Invalid TRON address index'`); Solana profile/path pairs (`'Invalid
Solana public index'`), valid non-small-order Ed25519 keys whose Base58 equals `address` (`'Invalid Solana preset
key'`), distinct keys (`'Duplicate Solana public key'`). Node use additionally verifies xpub against metadata (`'Public
node metadata does not match its extended key'`, `'Invalid registered public node'`). Host-side checks: §9.18.

### 7.2 Request objects (what decoders return and the host freezes)

| Source | Shape |
|---|---|
| `crypto-psbt` / `psbt` UR | `{chain:"bitcoin", psbt:<base64>, transport:{type, payloadEncoding:"cbor", payloadBytes:<hex of UR CBOR>}}` |
| static PSBT text / file | `{chain:"bitcoin", psbt, transport:{type:"psbt-base64"|"psbt-hex", payloadEncoding:"psbt", payloadBytes:<hex PSBT>}}` |
| BBQr | `{chain:"bitcoin", psbt, transport:{type:"bbqr-psbt", encoding:"H"|"2"|"Z", payloadEncoding:"psbt", payloadBytes}}` |
| `eth-sign-request` | `{chain:"evm", path, masterFingerprint?, transport:{type, payloadEncoding:"cbor", payloadBytes, requestId?<hex 1–64 bytes>, origin?(≤256), dataType:1|2|3|4, chainId?}, chainId?, address?<"0x"+lowercase hex>, kind:"transaction"|"typedData"|"personal", transaction:"0x…" | typedData:{…} | message:"0x…"}` |
| `tron-sign-request` | `{chain:"tron", path, masterFingerprint?, transport:{…, dataType:1|2}, address?<Base58>, kind:"transaction", transaction:{raw_data_hex}}` or `{…, kind:"message", message:<hex>, messageStandard:"TIP191"}` |
| `sol-sign-request` | `{chain:"solana", path, masterFingerprint?, kind:"transaction"|"message", message:<base64>, transport:{…, signType:1|2}, address?<Base58>}` |

Paths are concrete (`m/…`, 1–10 components, indices < 2^31); `masterFingerprint` is the keypath source fingerprint as
8 lowercase hex. Sol/TRON `requestId` must be 16 bytes; ETH 1–64 bytes (imToken uses 36). The `transport.payloadBytes`
string binds the request to its original QR bytes and must never be altered. Golden (evm-public-ui): `chain evm, path
m/44'/60'/0'/0/0, masterFingerprint 73c5da0a, transport {type eth-sign-request, payloadEncoding cbor, requestId
8f235e99e6e84044a51d51ec3ec942b0, origin "Public test fixture", dataType 3, chainId 1}, chainId 1, address
0x9858effd232b4033e47d90003d41ec34ecaeda94, kind personal`.

Internal requests without a transport (e.g. the iOS tests' `{"chain":"evm","kind":"transaction","transaction":{…}}`
with a JSON transaction object, or a synthetic `transport` lacking `payloadEncoding`) are reviewable and signable, but
`encodeResponse` refuses a missing transport. Production requests always come from the decoders.

### 7.3 Review result (`util.js:31-35` + chain parsers)

```text
{
  chain, title, summary,
  fields: [{label, value: String}],            // ordered; labels may carry groups "Instruction 2 · …", prefixes "Call 2: ", "Safe: "
  warnings: [String],                          // severe ones start "HIGH RISK", "HIGH FEE" or "UNLIMITED" (case-insensitive prefix)
  requestHash: <64 hex>,
  canSign: true,
  decodeStatus: "fullyDecoded" | "partiallyDecoded" | "opaque",
  riskLevel: "normal" | "warning" | "high",
  network?: {chainId?: String, name, symbol},  // EVM has chainId; Bitcoin/TRON/Solana {name, symbol}
  display?: {action, amount?, symbol?, headline?, counterpartyLabel?, counterparty?, fee?, feeKind?("energyBudget" on TRON),
             note?, assetKind?("nft"|"nftOrToken"|"multiToken"|"nftOrder"), assetContract?, assetContractLabel?, tokenId?,
             give?:[String], receive?:[String], others?:[String]},
  contract?: {address, role:"spender"|"operator"|"contract", known:Bool, name?, protocol?, category?, everyChain?, token?},
  asset?: {chainFamily, chainId?, assetAddress, name, symbol, decimals, icon, tokenProgram?, rawAmount?, displayAmount?}
}
```

Golden (evm-public-ui): `title "Sign Ethereum message"`, `summary "Personal signature (EIP-191)"`, fields `Derivation
path`, `Requested signer`, `Network context`, `Message`, `Exact message bytes`, warning `"Message signatures can authorize
logins, orders or asset actions. Verify the entire message and origin."`, `decodeStatus fullyDecoded`, `riskLevel high`,
`display {action "Sign Message", headline "BitSigner public UI fixture. No funds or permissions."}`, `network {chainId
"1", name "Ethereum", symbol "ETH"}`. Host parsing rules (lenient-but-typed, NFT keys only with a known `assetKind`,
bounds 300 chars / 32 lines, `requiresAcknowledgement = decodeStatus != fullyDecoded || riskLevel == high`) are in
`Models.swift:51-402` and specified in `docs/port/models-state.md` §3 and `docs/port/scan-sign.md` §10.

### 7.4 Signing plan (`resolver.js:188-198`)

```text
{
  version: 1, status: "ready",
  requestHash,                                 // = requestHash(request)
  payloadDigest,                               // SHA-256(originalPayloadBytes bytes)
  protocolProfile,                             // transport.type or "internal"
  originalPayloadBytes,                        // transport.payloadBytes, or hex(UTF-8(stable(request))) without transport
  signingBytesDigest,                          // chain digest of the exact signing bytes
  chain,
  review,                                      // Bitcoin: refined with verified change (summary/fields/warnings/display/riskLevel may differ)
  tasks: [Task],                               // sorted by localWalletId (string compare)
  planHash                                     // = requestHash(plan without planHash)
}
```

### 7.5 Task and targets

```text
Task = {id (= requestHash({requestHash, ...task-without-id})), localWalletId, walletKeyId, chain,
        targets: [Target], paths: [String], publicKeys: [String], addresses: [String],
        inputs: [Int] (Bitcoin input indexes, else []), signatureAlgorithm ("ECDSA / BIP340" | "ECDSA secp256k1" | "Ed25519"),
        signingBytesDigest}
Target = AddressEntry-like {chain, profile, path, index, branch?, publicKey, address, masterFingerprint}
         + Bitcoin: inputIndex, leafHashes? (Taproot script path)
         + Solana transactions: signerSlot
```

Targets are sorted by `inputIndex`, then `path`; ≤ 200 per task (`LIMIT`, `'Too many signing targets'`). Multiple
inputs of one wallet form one task; separate wallets are separate tasks with separate passwords. Golden BTC target:
`{chain:"bitcoin", profile:"native", path:"m/84'/0'/0'/0/0", index:0, branch:0, publicKey:"0330d54f…af3c",
address:"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", masterFingerprint:"73c5da0a", inputIndex:0}`.

### 7.6 `signPlanTask` result per chain (plus `requestHash`, `planHash`, `taskId`, `completedTaskIds`)

| Chain | Fields | Source |
|---|---|---|
| bitcoin | `chain`, `requestHash`, `psbt` (Base64, partial signatures added, never finalized), `signedInputs`, `signedInputIndexes` | `bitcoin.js:230` |
| evm | `chain`, `requestHash`, `address` (checksummed), `signature` (130 hex = r‖s‖v, v 27/28), `digest` ("0x…"), `qrSignature` (tx: r‖s‖minimal-bytes(v) with EIP-155 v for legacy, y-parity for typed; message/typed data: = `signature`), tx only: `serializedTransaction`, `transactionHash`, `signatureV` | `evm.js:729-743` |
| tron | `chain`, `requestHash`, `address`, `signature` (130 hex, r‖s‖recId), `digest`, `signatureV` ("27"/"28"), tx only: `txID`, `signedTransaction {visible:false, txID, raw_data, raw_data_hex, signature:[…existing, new]}` | `tron.js:116-118` |
| solana | `chain`, `requestHash`, `address`, `publicKey`, `signature` (128 hex), tx only: `signatures` ([Base64] all slots), `serializedTransaction` (Base64), `signerIndex` | `solana.js:430` |

### 7.7 `encodeResponse` result

`{type, frames:[String], payloadFormat:"json"|"hex"|"text", payload, payloadBytes}` (§6.12).

### 7.8 The payload view (`describeQR`, `protocols.js:423-519`)

- `payloadFormat:"text"` — a plain-text QR (address, static PSBT is *not* this case): `payload` = the frame,
  `payloadBytes` = UTF-8 length.
- `payloadFormat:"json"` — a registry map decoded with field names (`UR_FIELD_NAMES`: `crypto-hdkey` →
  `is-master, is-private, key-data, chain-code, use-info, origin, children, parent-fingerprint, name, note`;
  `crypto-keypath` → `components, source-fingerprint, depth`; `crypto-coin-info` → `type, network`;
  `crypto-account` → `master-fingerprint, output-descriptors`; `crypto-multi-accounts` → `master-fingerprint, keys,
  device, device-id, version`; `eth-signature` → `request-id, signature, origin`; `sol-`/`tron-signature` →
  `request-id, signature`); tags become one-key objects named by `CBOR_TAG_NAMES` (`uuid`, `crypto-hdkey`,
  `witness-public-key-hash`, `taproot`, … or `tag-<n>`); bytes as lowercase hex. PSBT responses (`crypto-psbt`,
  `psbt`, `bbqr-psbt`, `psbt-base64`, `psbt-hex`): `{psbt_base64, decoded?}` where `decoded` uses Bitcoin Core
  `decodepsbt` names (`psbt_version`, `tx{txid, version, locktime, vin, vout[{n, value_sat, scriptPubKey{hex,
  address?}}]}`, `inputs[{partial_signatures?, taproot_key_path_sig?, taproot_script_path_sigs?, final_scriptSig?,
  final_scriptwitness?}]`); omitted if the bytes are only PSBT-framed.
- `payloadFormat:"hex"` — a UR whose CBOR is one byte string.
- Numbers inside payloads are uint32 fingerprints and satoshis up to 2^53: keep them as JSON numbers / `Long`;
  `QRPayloadView` (`Models.swift:591-650`) shows `title` ("Payload (JSON)" / "Payload (Hex)" / "Payload (Text)"),
  `summary` (e.g. `ur:eth-signature · CBOR · 89 bytes`) and pretty, key-sorted JSON in Apple's format (`"key" :
  value`, 2-space indent — the exact printer is specified in `docs/port/models-state.md` §0.6).

---

## 8. Error catalogue

### 8.1 Host (bridge) errors

| Code | Message (verbatim) | Where |
|---|---|---|
| `UNSUPPORTED_OPERATION` | `This signing operation is not supported.` | `SigningEngine.swift:152` |
| `INVALID_REQUEST` | `The signing request has an invalid format.` | `:188` |
| `REQUEST_TOO_LARGE` | `This request exceeds the 1 MB offline signing limit.` | `:192` |
| `ENGINE_UNAVAILABLE` | `The offline signing engine could not be started.` | `:20` |
| `ENTROPY_UNAVAILABLE` | `The engine’s secure entropy source could not be installed.` (typographic apostrophe U+2019) | `:62` |
| `ENGINE_INVALID` | `The bundled signing engine could not be loaded.` | `:67` |
| `ENGINE_MISSING` | `The bundled signing engine is unavailable. Reinstall BitTool Signer.` | `:210` |
| `ENGINE_FAILURE` | `The offline engine could not process this request.` | `:82` |
| `SCAN` | `The scan session ended. Scan again.` | `:173` |
| default code / message for an error envelope without them | `SIGNING_FAILED` / `This request could not be signed.` | `:87-88` |

None of these strings is in the localization tables (`tools/l10n/strings-index.tsv`); iOS shows them in English.

### 8.2 Engine dispatch-level errors

`LIMIT` `'Request exceeds one megabyte.'`; `OPERATION` `'Unsupported engine operation.'`; `REQUEST` `'Expected a JSON
operation.'`; `QR_PROTOCOL` parser messages (§2.2); generic `INVALID_REQUEST` `'The payload is malformed, unsupported or
inconsistent. No signature was produced.'`.

### 8.3 Engine error codes (all `EngineError` codes in `engine/src/*.js`)

`ADDRESS`, `AMOUNT`, `CALLDATA`, `CHAIN`, `CHAIN_ID`, `DESCRIPTOR`, `ENCODING`, `ENTROPY`, `FEE`, `FINGERPRINT`, `GAS`,
`INDEX`, `INSTRUCTION`, `INTEGER`, `INTEGRITY`, `LIMIT`, `LOOKUP_TABLE`, `MESSAGE`, `MNEMONIC`, `NETWORK`, `OPERATION`,
`OUTPOINT`, `PASSPHRASE`, `PATH`, `PERMISSION`, `PLAN`, `POLICY`, `PRECISION`, `PREVOUT`, `PROFILE`, `PROTOBUF`,
`PUBLIC_INDEX`, `REQUEST`, `RESOURCE`, `RISK`, `SAFE_SCHEMA`, `SCAN`, `SEED`, `SIGHASH`, `SIGNATURE`, `SIGNER`,
`TOKEN_METADATA`, `TRANSACTION`, `TRUNCATED`, `TYPED_DATA`, `UNSUPPORTED`, `WALLET` — plus `QR_PROTOCOL`
(`ProtocolError`) and `INVALID_REQUEST` (generic). Messages are controlled English sentences; a few embed ≤ 40 chars
of request text (e.g. `'Unsupported typed data type: '+String(type).slice(0,40)`). The host must not branch on
messages; it may branch on codes, but iOS does not (it only displays). The resolver status messages (§6.10) are mapped
to explanations by exact string in `Features/Sign/RequestReviewView.swift:138-153` (scan-sign spec).

---

## 9. Host-side invariants

Each item is a behaviour the Android `SignerStore`/engine layer must reproduce; cite in tests.

**Requests**

1. **Freeze once.** `prepare` serializes the decoded request with sorted keys; > 1,048,576 bytes → `"Request exceeds the
   supported size."`; invalid → `"Invalid request format."`; re-parses it (`"Invalid local document."` on failure) and
   uses that one immutable object for `review`, `resolveSigningPlan`, every `signPlanTask`, `encodeResponse`, and the
   Activity record (`AppStore.swift:113-122, 361-365, 425, 455`). `clearRequest()` runs first (`:95-100`).
2. **Commitment check after `review`:** `requestHash` matches `^[0-9a-fA-F]{64}$`, `result.chain == request.chain`,
   chain is one of the four, `fields` non-empty; else `"Missing or invalid transaction commitment."` (`:367-371`).
3. `canSign != true` → `matchingError = "Unsupported signing protocol"`, no matching (`:373`).
4. **Candidates are every wallet** (no selected wallet, no seed search): `localWalletId` + `publicIndex` or
   `publicMetadataStatus` (`:375-380`). Android: one consistent UUID spelling (lowercase `UUID.toString()`); map back
   with case-insensitive UUID parsing (`SigningTask` uses `UUID(uuidString:)`, `Models.swift:670`).
5. **Collection snapshot:** capture `{walletIDs, indexes as canonical bytes}` before resolving; if it differs after,
   `matchingError = "Wallet information changed. Match this request again."` and no plan (`:374, 384-386`).
6. `status != "ready"` → `matchingError = message ?? "No matching wallet"` (`:387-389`); review stays visible.
7. **Plan parse** (`Models.swift:684-702`): `status == "ready"`, `requestHash == review.requestHash`, `planHash` 64 hex,
   1…**64** tasks (host cap below the engine's 100) else `"Invalid signing plan."`; each task: non-empty `id`,
   parsable `localWalletId`, 64-char `walletKeyId`, known chain, non-empty `paths`, `publicKeys.count ==
   paths.count` (`"Invalid signing plan."`); unique ids else `"Duplicate signing task."`. Keep the whole plan object
   (`raw`) for `signPlanTask`.
8. **Every task's `walletKeyId` equals the local wallet's index `walletKeyId`** else `"Invalid signing wallet
   identity."` (`AppStore.swift:391`).
9. **Refined review:** if the plan's `review` has the same `requestHash` and `chain` and non-empty fields, it replaces the
   displayed review (Bitcoin verified change, recomputed summary — R2-02', `IMPLEMENTATION_STATUS.md:27`)
   (`AppStore.swift:395-398`). The receipt summary comes from this refined review.
10. **Signing preconditions** (`:412-420`): not `busy`, no signed response yet (`"This signing request is already being
    processed."`); if the snapshot changed or any task's wallet is a duplicate identity → invalidate matching and throw
    `"Wallet information changed. Match this request again."`; no `matchingError`; the task is the **first pending**
    task and its id matches; `review.requestHash == plan.requestHash`; the wallet's `walletKeyId` matches — else
    `"No matching signing task. Review the request again."`.
11. **Password first, engine second.** Unlock the vault for that wallet; a wrong password throws before any engine call
    (`:463-466`; SessionTests asserts `signPlanTask` count 0). Each task needs its own password; no password or risk
    consent is cached.
12. **Result identity:** `requestHash == plan.requestHash`, `planHash == plan.planHash`, `taskId == task.id`, else
    `"Request changed. Review the transaction again."` (`:429-431`). Freeze the result (sorted JSON) as
    `previousResultData` for the next task (`:432, 444, 470`).
13. **Publish only when complete:** `encodeResponse` only for the final task; earlier tasks produce a receipt with
    `resultType "Partial signature created"` and no response payload (`:433-457`). Final: `"Signature created"`.
14. **Epochs after every await:** `requireSession(epoch, requestID)` after review, resolve, unlock, sign, encode
    (`:109-112`); `suspend()` rotates `securityEpoch`, `sensitiveEpoch`, `requestEpoch`, clears `signingOperation`/
    `busy`, clears Connect prefetch, releases the warm context, and sets `matchingError = "Matching interrupted. Try
    again."` when a review exists without a plan or error (`:86-94`). Late results are discarded (CancellationError).
15. **Invalidate on wallet changes:** a reload that changes the snapshot while a plan exists and no response was
    produced clears the plan, completed tasks, previous result and response and sets `"Wallet information changed.
    Match this request again."` (`:101-108, 142-144`). Renaming does not change the snapshot (plan survives).
16. **Retry without authorization:** `retryMatching()` re-runs `prepare` on the frozen request when there is a review,
    no plan, no signed response; else `"No interrupted or unmatched request to retry."` (`:405-410`).
17. **Activity re-review** (`:581-591`): stored request JSON ≤ 32,768 bytes (`SigningReceipt.payloadLimit`) else `"The
    stored request could not be read."`; fresh `review`; `requestHash` equals the record's (case-insensitive) and
    fields non-empty else `"The stored request does not match this signature's fingerprint."`; never touches the pending
    request, never matches, never asks a password.
18. **Public index acceptance** (`WalletPublicMetadata.init`, `Models.swift:477-502`) before storing or using any
    engine-produced index: `version == 1` (integer); 64-hex `walletKeyId`; 8-hex `masterFingerprint`; 6 BTC nodes,
    EVM and TRON nodes, 100 TRON entries, 3 Solana keys; deep public-only scan (depth ≤ 12, arrays ≤ 256, strings ≤
    2,048 UTF-8 bytes and not starting `xprv`, no key named — case-insensitive — `mnemonic`, `seed`, `privatekey`,
    `privateextendedkey`, `password`, `passphrase`, `xprv`, `secret`; only strings/numbers/null/arrays/objects) else
    `"Invalid public wallet data. Repair this wallet."`; BTC origin set exactly the six; EVM `m/44'/60'/0'`, TRON
    `m/44'/195'/0'`; every node: 33-byte hex key starting `02`/`03`, 32-byte chain code, 4-byte parent fingerprint,
    depth 3…4, xpub starting `xpub` and < 120 chars; Solana path set exactly the three, keys 32-byte hex, address 1…128
    chars; TRON index set exactly 0…99 with `path == "m/44'/195'/0'/0/<index>"` and 33-byte keys — else `"Public wallet
    data is incomplete or inconsistent."`; canonical sorted JSON ≤ 262,144 bytes else `"Public wallet data is too
    large."`. Store `walletKeyId`/`masterFingerprint` lowercased.
19. **Identity comparisons use `walletKeyId`, never the fingerprint:** duplicate import refused (`"This recovery phrase
    and passphrase already belong to an existing wallet."`, `AppStore.swift:191-193`); `existingWallet` (`:484-491`);
    `resetPassword` must derive the same `walletKeyId` before writing (`"This recovery phrase belongs to a different
    wallet."`, `:505-507`); duplicates among ready indexes are flagged (`duplicateWalletIDs`, `:140-141`) and the
    resolver returns `ambiguous`. The fingerprint is display-only (uppercased: `store.fingerprint(for:)`, `:157-159`).
20. **Warm context discipline:** only `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing`, only with a
    `publicIndex` from the authenticated local repository; released on every suspend; prefetch results are discarded
    if the epoch or that wallet's index bytes changed while computing (`:290-301`).
21. **Risk consent:** the UI requires consent when `decodeStatus != fullyDecoded || riskLevel == high`
    (`Models.swift:374`); the value passed is `riskAcknowledged` for this request only, reset on suspend/new request
    (Security.md:80, 115). The engine enforces it only for partial/opaque.
22. **Never alter scanned bytes:** frames are passed unchanged (no trim, no case change) — the gate only rejects
    (`QRScannerView.swift:228-238`); the decoded request's `transport.payloadBytes` is re-validated by the engine.
23. **Receipts store the canonical request JSON and the response frames/text** (both public), ≤ 32 KiB each, payloads
    on the newest 25 of ≤ 100 records (`AppStore.swift:445-457, 554-569`; `docs/port/models-state.md` §6).
24. **Bounded display of engine errors:** code ≤ 80, message ≤ 400 (§3.5); unexpected exceptions never show input text.

---

## 10. Host strings in this layer (verbatim English keys)

Localized on iOS (`String(localized:)`, present in `tools/l10n/strings-index.tsv`): "Invalid local document.",
"Invalid request format.", "Request exceeds the supported size.", "Unable to generate recovery phrase.", "Wallet setup
required.", "Unable to derive public addresses.", "QR transfer is too large.", "Text payload exceeds 256 KB.", "The
request is incomplete. Import all QR frames.", "Missing or invalid transaction commitment.", "Invalid signing wallet
identity.", "No interrupted or unmatched request to retry.", "This signing request is already being processed.",
"Wallet information changed. Match this request again.", "No matching signing task. Review the request again.",
"Request changed. Review the transaction again.", "Unable to encode signature response.", "The stored request could
not be read.", "The stored request does not match this signature's fingerprint.", "Invalid public wallet data. Repair
this wallet.", "Public wallet data is incomplete or inconsistent.", "Public wallet data is too large.", "Unable to
encode the wallet pairing.", "Unable to encode public wallet information.", "Invalid signing plan.", "Duplicate signing
task.", "This file is larger than 512 KB.", "Choose a PSBT file or a text file containing a UR request.".

Plain Swift strings shown verbatim (English only on iOS): "Matching interrupted. Try again." (`AppStore.swift:92`),
"Wallet information changed. Match this request again." (`:107, :385` — the same text also exists localized),
"Unsupported signing protocol" (`:373`), the resolver messages (§6.10), all §8.1 bridge messages, all engine error
messages, `QRPayloadView.summary` texts ("BBQr · PSBT (%@) · %@", "Plain text · PSBT Base64 · …", "Plain text · PSBT hex
· …", "Plain text · …", "ur:%@ · CBOR · …"), and the payload note "Fingerprints are stored as 32-bit numbers: \(value)
is fingerprint \(HEX)." (`Features/Wallets/ConnectView.swift`, `payloadNotes`; interpolations: the UInt32 value and the
uppercase hex fingerprint; not in the tables). Resolver explanations (localized, `RequestReviewView.swift:138-153`): "This
request does not belong to any wallet on this device. Check that you scanned the request for this wallet.", "The
request does not say which key should sign. Export it again from your other wallet with its account details.", "More
than one saved wallet matches. Delete the duplicate wallet, then scan again.", "A wallet needs repair before it can be
matched. Open it in Wallets and repair its data.", "BitTool Signer can show this request but cannot sign this kind of
request.", "This request cannot be signed. Nothing has been signed."; retry is offered when the message contains
"interrupted" or "changed" (`:134-136`).

Android may keep the untranslated ones English for parity (DECISIONS.md §4); engine-produced review text goes through
`IosStrings.lookup` at draw time.

---

## 11. Test vectors and tests to mirror

### 11.1 Fixture files (copy byte-for-byte; record the SHA-256)

| File (iOS repo) | SHA-256 | Android location | Use |
|---|---|---|---|
| `engine/test/native-vectors.json` | `58fbf7749f6b7b76849dfce81f3050c3d9a69376989dda571aaf58314bcf8aef` | `core/engine/src/androidTest/assets/vectors/` | V-NATIVE |
| `engine/test/fixtures/public-plan-vectors.json` (= `BitToolSignerTests/PublicPlanVectors.json`) | `8d072172902cef1d030da897defe699d4bc3afc78cab1b4995233fcfcf59d335` | same | V-PLAN, V-SCAN |
| `engine/test/fixtures/evm-public-ui.json` | `8e02d234837fba39317e7300203be04bec89b050d8d47ebe569086d3362734e7` | same | V-UI (ignore its stale `walletKeyId`) |
| `engine/test/fixtures/keystone-tron-public.json` | `efaa0eb606bf10bcd81f676906264362194529e3d5ec44006945e764be5912a7` | same | V-TRON-WIRE |
| `docs/bittool-handoff/fixtures/signing-roundtrips.json` | `05c8f96f972b42212cd420f09ffbea0e86783ac9b0d4fb2b69607c9e3be58021` | same | V-ROUNDTRIP |
| `docs/bittool-handoff/fixtures/wallet-pairing-current.json` | `4d65170a4108e406f5fbb5f52e2a715e3a49acc5c061cb745fc406ee08e29476` | same | V-WALLET-PAIRING |
| `BitToolSignerTests/BIP39LanguageVectors.json` (44 vectors in 10 languages, 10 invalid) | `e308d0d3e48bd2947b031afa0cb3aaedb15bd6097bfb87a42ed6023a6bddcf35` | `core/crypto/src/test/resources/` | V-BIP39 (JVM) |

All test wallets are published BIP39 fixtures; never fund them.

### 11.2 Engine vectors (instrumented, real QuickJS)

Reference seeds (computed with PBKDF2, match Trezor/BIP39 vectors):
`abandon … about` / "" → `5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4`;
`legal winner thank year wave sausage worth useful legal winner thank yellow` / "" →
`878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe5f377ba02bf3f8544ab800b955e51fbff09828f682052a20faa6addbbddfb096`.
Use seeds (fast production path) wherever the operation accepts them.

- **V-ROUNDTRIP (highest value, end-to-end).** For each of the 9 vectors (`evm-personal`, `evm-eip1559`,
  `evm-legacy-multibyte-v`, `evm-eip712`, `solana-message`, `solana-legacy-transfer`, `tron-message`,
  `tron-trx-transfer`, `bitcoin-bip84-psbt`): `beginScanSession`, `scan` each `request.frames` until complete →
  `review` → `resolveSigningPlan` with the reference wallet (any UUID) → `status == "ready"` → `signPlanTask` with the
  seed and `riskAcknowledged = decodeStatus != "fullyDecoded"` → `encodeResponse` → **`frames ==
  expected.responseFrames` exactly** and `type == expected.responseType`. Verified golden in Node for all 9 (request
  frame counts 2,1,1,3,1,2,1,2,2; responses 1 frame each except Bitcoin 3; `evm-eip712` is `partiallyDecoded`/`high`).
- **V-NATIVE** (debug `sign` op with the vector `mnemonic`; slow on QuickJS → `@LargeTest`). For all 9:
  `review(request).requestHash == result.requestHash`. For vectors 0, 1, 3, 4, 5, 6, 7, 8 every field in `result`
  equals the engine output (the engine adds `qrSignature` on EVM vector 3 that the file predates — compare the
  vector's fields only). **V-NATIVE-2:** vector 2 (Taproot key path) matches every field except `psbt`; signing it
  twice yields two different `psbt` values (proves `crypto.getRandomValues` is live).
- **V-PLAN** (`public-plan-vectors.json`): derive both indexes from seeds with the fixture `localWalletId`s; for each
  vector `resolveSigningPlan` → task wallet ids == `expected.taskWalletIds` (lowercase string compare) and task paths
  == `expected.taskPaths`. Golden `requestHash` / `planHash`:

| Vector | `requestHash` | `planHash` |
|---|---|---|
| EVM registered index 8 | `7a11854f3ee455201faf08146e3aa13a6f92ddc3ea49b4b6d28836044fe8a565` | `b4921fdef66a27919ab6db876ea248156c2a2dad545184bf393a524286b1aed2` |
| SOL standard | `223a84f032be3def8cbe881d6d58186ea1bf477ccce5b566194ded52603217ec` | `f9c9316eca16d46567a4f2d76af76b48b732c4be1196b9146f31fe24e0674b47` |
| SOL trust | `9a9a1e3c9ffd89dcec9218f5363a3a68b7f769d26e91967b367208bb72a03b75` | `b885b77e2840bfc180cb024e96ea6146cbf67f888c46aaab3b4c36580f053106` |
| SOL legacy | `f5ed061241afbe92312bfdbff4006f7d023859c6e3cec9dadee03d71f0e57ec9` | `1fb9e1a7b101f9c94224832caf9359436d2062b77daefc80e99bbf8804f0713b` |
| TRON index 99 without path | `ca35f003626fcd9e66cba1d7e895ef2656056864b62ad64095f678ab88e07717` | `74e086e32c7ee10de8214e7e23edf590c16e678eea98b204eb161985bfae8156` |
| BTC two local BIP48 cosigners without derivations | `8a0bdb3b85b9780564fe034768a3ec379df4abede94d47532fec5d76f6d33c00` | `8bf240f06b7c8ecaeb678f1c967f620a390733a1715332ec129c364e6ea59bef` |

  Then, as `BitToolSignerTests.swift:67-123` does for "SOL legacy", "TRON index 99 without path" and "BTC two local
  BIP48 cosigners without derivations": sign every task in order with its wallet's seed, chaining `previousResult`;
  assert each result's `requestHash`/`planHash` equal the plan's, `completedTaskIds` grows by one, Bitcoin
  `signedInputs == 1` and `psbt` differs from the previous one, others `signature.length == 128` (Solana) / `130`
  (EVM/TRON), `address ∈ task.addresses`, Solana `signerIndex == targets[0].signerSlot` (when present). Golden SOL
  legacy signature `368c752d5639ff4991e168f2cb059d54b114bf82ff330c25316dd5e392e74262f8b7bfe3f629557aa416b0cc07824ff3debb449b1d94f521034bc667b2bb9509`;
  TRON index 99 signature `ec63b10e7327c9e4aa48c9388711e5aec2fd03a0ba3c7645615d7c7662f99d2304e66236c32f373ccd9b3e6a9583ed0dddd3717c61736c04546645473a22e95d01`, `signatureV` "28", address `TTTFe9haCY6CACG9iTM8uyL89pFEPy4ctW`.
- **V-UI** (`evm-public-ui.json`): scan session over `frames` → request (golden §7.2); review/plan/sign/encode goldens
  §6.10–6.12.
- **V-TRON-WIRE** (`keystone-tron-public.json`, from `public-index.test.js:184-189`): scan `requestUR` → `chain tron`,
  `kind transaction`, `transaction.raw_data_hex == rawDataHex`, `transport.requestId == requestId`; debug `encodeQR
  {type:"tron-signature", payload: signatureHex, requestId, fragmentLength: 800}` → `frames[0].lowercase() ==
  signatureUR`; a 64-byte payload → `QR_PROTOCOL` `'Invalid signature size'`.
- **V-WALLET-PAIRING:** `createWalletPairing` on the reference index → `frames`, `payload`, `verify` equal the fixture's
  `frames`, `logicalPayload`, `verify`; `payloadBytes == innerCBORBytes (1561)`.
- **V-UNICODE (JNI/ASCII transport):** EIP-712 `Mail` request `{chain:"evm", kind:"typedData", path:"m/44'/60'/0'/0/0",
  address:"0x9858EfFD232B4033E47d90003D41EC34EcaEda94", typedData:{types:{EIP712Domain:[name string, version string,
  chainId uint256, verifyingContract address], Person:[name string, wallet address], Mail:[from Person, to Person,
  contents string]}, primaryType:"Mail", domain:{name:"Ether Mail", version:"1", chainId:1,
  verifyingContract:"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"}, message:{from:{name:"Cow",
  wallet:"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"}, to:{name:"Bob", wallet:"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},
  contents:"Hello, Bob! 👋 世界 é"}}}` → `requestHash`
  `0167651da736e683cc984f5e1906bcabb2474238388d9221173a3d4ea5e0ceb6`, `partiallyDecoded`/`high`, field "Signed values"
  contains `Hello, Bob! 👋 世界 é` exactly; resolve with `10000000-0000-0000-0000-000000000001` → `planHash`
  `00e60fe62f3d205b752e861d41f90954ff30c1def68dc6d4769c63df51443910`; sign (acknowledged) → digest
  `0xd7c5051212411cfefcac0204e0250ef6bf767c4099619d647692feb687ff4714`, signature
  `fb6e968fd138407f72766dc32b0984ae4ced150fb6324ca7132a38f3f53f911f7b29956912ef4e1eacc7e57cd82813d467369b2e78259098efddbb2ddd3fd0c31b`.
  Solana message `{chain:"solana", kind:"message", message: base64(UTF-8("Sign in ✓ 😀 日本")),
  address:"HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"}` → `requestHash`
  `46625032e99d8d4d3780fb3b32fc9b6600f74da09838749ffde64b4c57a4eab2`, `display.headline == "Sign in ✓ 😀 日本"`. Run it
  in a **debuggable** build (CheckJNI on).
- **V-ERRORS (raw dispatch goldens):** `{"operation":"capabilities","operation":"wordlist"}` → `QR_PROTOCOL` `'Duplicate
  JSON key'`; `'x' × 1,048,577` → `LIMIT`; `{"operation":"deriveAccounts","index":9007199254740993}` → `QR_PROTOCOL`
  `'Unsafe JSON number; encode large integers as strings'`; `{"operation":"review"}` → `REQUEST` `'Unsupported or
  missing chain.'`; `{"operation":"nope"}` → `OPERATION`; `decodeQR ["ur:eth-sign-request/zz"]` → generic
  `INVALID_REQUEST`; `decodeQR ["unsupported-sensitive-fixture"]` → `'Unsupported signing QR format'`; a 4,301-char
  frame → `'QR frame size exceeds limits'`; `["ur:sensitive-metadata/aaaa"]` → `'Unsupported signing UR type'`;
  `signPlanTask {}` → `PLAN`; `derivePublicIndex {seed:"00"}` → `SEED` `'Invalid wallet seed.'`; `scanAdd` in a
  fresh context → `SCAN` `'No scan session is active.'`; mixing a UR part and a BBQr frame in one session →
  `'Multiple unrelated QR payloads'`. No error message may contain the input (`dispatch` with `"mnemonic":"SECRET"`
  must not echo `SECRET`, `engine.test.mjs:129`).

### 11.3 iOS test files — what each asserts and where it goes on Android

| File | Asserts | Android |
|---|---|---|
| `BitToolSignerTests.swift` (`EngineIntegrationTests`) | entropy `00`×16 → reference phrase; `deriveAccounts` 4 known addresses; index passes `WalletPublicMetadata`; EVM `wallet` pairing frame starts `ur:crypto-hdkey/` (case-insensitive); fixed EVM plan: review `canSign`, sign with mnemonic → `requestHash`, signer address, `serializedTransaction` starts `0x02`, signature 130 hex; `encodeResponse` refuses a transport-less request; debug `encodeQR` export frames start `ur:<type>/`; a nonce-1 request with the old plan fails; malformed UR rejected; `0xdeadbeef` calldata reviewable but needs `riskAcknowledged`; PublicPlanVectors chain (V-PLAN) | `:core:engine` androidTest |
| `HardeningTests.swift` | Trezor seeds (`TREZOR`): reference → `c55257c3…3b04`, `legal winner…` → `2e890581…f607`; whitespace/case/tab normalization equal; NFKD `é` == `é`; blank phrase throws; seed vs phrase index parity for "" and `"Public passphrase ✓"`; bundled English wordlist == engine `wordlist`, suggestions; SeedQR digits; phrase draft; public file HMAC tamper → invalid; vault header bytes authenticated; monotonic clock; **incremental scan:** pairing frames (fragment 40, > 1 frame) rejected by the scan session, `scan` after `endScanSession` throws, PSBT `encodeQR` fragment 60 (> 3 parts) reassembled from `parts + parts` with identical `psbt`; warm context stable results (`0x9858…Eda94` twice); Release refuses `sign`; address grouping; review display/network parsing, `requiresAcknowledgement` | seed tests → `:core:crypto` JVM; scan/warm → `:core:engine` androidTest; rest per owning spec |
| `SecurityTests.swift` | password policy; entropy bounds (65,537 → `randomFailure`, 13 words → `invalidSecret`); PBKDF2-SHA256 vault vector `05b079f1…01d9`; AES round trip; tamper/throttling/rotation/reset; **DEBUG bridge isolation** with an injected source: `invocations == 1` on two calls (fresh scope), different `random`, `hasNetwork == false`, echo of `"\"; globalThis.invocations = 99; //"` unchanged; bundled engine BIP39 + EVM address vector | isolation test needs a test-only source injection that bypasses the SHA check (`@VisibleForTesting` factory, never reachable in release) |
| `SessionTests.swift` | migration/repair keeps vault bytes; wallet pairing: ≥ 2 frames, all `UR:BYTES/`, 6 `"xpub"`, 3 `"address"`, four BTC origins, no `m/48'`, first-address chains `btc×4, evm, tron, sol×3`, unique ids/addresses/paths, BTC prefixes `bc1q bc1p 3 1`, profiles order; fingerprint `73C5DA0A`; duplicate import refused; incomplete wallet flow and duplicate flagging; per-signature passwords (wrong password → zero `signPlanTask` calls); response frame `ur:eth-signature/`, payload view title/summary; missing/conflicting selectors never yield a task; stale public cache cannot authorize a different seed (engine `SIGNER`); background/replace/interrupt cancellation with gates; collection changes cancel in-flight signing and late matches; multi-wallet orchestration (separate passwords, publish only when complete, receipts newest-first); interrupted creation leaves nothing; Connect prefetch only when allowed, cleared on suspend, coin payload view contains `"master-fingerprint" : 1942346250`, TRON address payload is text | `:core:data` (Robolectric/JVM with a controllable fake engine) + a few androidTest with the real engine |
| `ActivityRecordTests.swift` | receipt keeps canonical request JSON and response frames/text; re-review reproduces labels/values/display; mismatched/garbage stored request refused; payload retention 25; no secrets in the file; size limits; essentials lines | `:core:data` |
| `KnownContractTests.swift` | bundled `known-contracts.json` integrity (> 1,500, counts, lowercase EVM, TRON `T…` 34, Solana 32–44); directory reachability; `ReviewContract` parsing (hit, miss, token, nameless, unknown role, empty/oversize address) | `:core:model` / `:core:data` JVM |
| `NFTReviewDisplayTests.swift` | 10 engine `display` fixtures parse with kinds; bounds (300 chars, 32 lines, middle cut); unknown kind fails closed; token-ID row rules; flow sections; row styles | `:core:model` JVM + Compose screenshot |
| `QRPayloadViewTests.swift` | exact pretty JSON text, titles, summaries (`ur:eth-signature · CBOR · 89 bytes`, `Plain text · 34 bytes`, `BBQr · PSBT (hex) · 379 bytes`), invalid → nil | `:core:model` JVM |
| `QRScannerTests.swift` | frame gate: non-text / empty / whitespace issues, 16,384-byte UTF-8 boundary, 1 s throttle, distinct frames immediate and unchanged, failures don't block, earlier clock value | `:core:scanner` JVM |
| `ScanRequestRouterTests.swift` | scan request token consumed once | `:core:navigation` JVM |
| `TokenRegistryBundleTests.swift` | > 3,000 assets, 10 networks, counts; icon pack has exactly the registry icons, content-addressed (SHA-256 prefix), path traversal rejected | `:core:data` JVM |
| `ReviewTextTests.swift` | untranslated phrases fall back to English; prefix headlines keep values; `shortDecimal` 8 places rounding up with string carry (`0.999999999 ETH` → `1.00000000 ETH`, huge whole parts) | `:core:l10n` JVM |
| `BIP39LanguageTests.swift` | 10 wordlists with pinned hashes; V-BIP39 seeds; forgiving input; checksum/length/unknown/mixed failures; Italian/Spanish ambiguity seeds (`ff24afd4…4a78`, `dc1bb7e8…01d0`); scanned phrases keep language; longest-word 24-word phrases ≤ 1,024 bytes in every list; limits | `:core:crypto` JVM |
| `AppAccessTests`, `AppNameTests`, `BackupVerificationTests`, `CompatibleWalletsTests`, `LegalDocumentTests`, `PassphraseFieldTests`, `PrivacyShieldTests`, `RecoveryCardsTests`, `RecoveryInputViewTests`, `RecoveryPrivacyTests`, `RecoveryPrivacyViewTests`, `RecoveryScreenshotTests` | no engine involvement (App Lock, naming, backup check, wallets list, legal text, fields, shield, cards, recovery privacy) | owning specs (`app-shell.md`, `setup-backup.md`, `activity-settings.md`) |

### 11.4 More golden values (reference wallet unless stated)

- Addresses: BTC native `/0/0` `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu`; taproot `/0/0`
  `bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr` (x-only key `cc8a4bc6…c115`), `/0/1`
  `bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh`, change `/1/0`
  `bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7`; nested `37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf`; legacy
  `1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA`; EVM `0x9858EfFD232B4033E47d90003D41EC34EcaEda94`; TRON `/0/0`
  `TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH`, `/0/99` `TTTFe9haCY6CACG9iTM8uyL89pFEPy4ctW`; Solana standard
  `HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk`, trust `GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL`, legacy
  `D2PPQSYFe83nDzk96FqGumVU8JA7J8vj2Rhjc2oXzEi5`.
- Account xpubs: BIP84 `xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V`
  (parent fingerprint `7ef32bdb`, chain code `4a53a0ab…90fc`); BIP86 `xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ`;
  BIP49 `xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLAet9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7`;
  BIP44 BTC `xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj`;
  EVM `xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt`;
  TRON `xpub6D1AabNHCupeiLM65ZR9UStMhJ1vCpyV4XbZdyhMZBiJXALQtmn9p42VTQckoHVn8WNqS7dqnJokZHAHcHGoaQgmv8D45oNUKx6DZMNZBCd`.
- Identities: reference + passphrase `TREZOR` → `walletKeyId` `b62a07c54d5654209cbb8d65df4f0310e78ee721b959c1deddc31d0a22a77d30`,
  fingerprint `b4e3f5ed`; `legal winner…` + "" → `c30375e5a8a45eabf171fa7511e59e6b0250e37788774141d7b212037d9a6605`,
  `b8688df1`, EVM `0x58A57ed9d8d624cBD12e2C467D34787555bB1b25`.
- Seed for `"Public passphrase ✓"` (used by HardeningTests parity): `6192d64c7e14e3cc8f51574fae2900b52f16d83e035b93ad882c3d3e31db7cb8d1ce2e781d21279a7700999f6170ec9509422ae81632622d22e2518747fe7d5d`.

### 11.5 Engine (Node) suites — for awareness, not to port

The iOS repo's `npm test` runs 210 Node tests over the same sources (`engine/test/*.test.mjs|js`: protocols,
public-index, resolver, chain-expansion, hardening F1–F18, wallet-pairing, NFT EVM/Solana, payload views, fee policy,
audits BTS-2026-1017/1018/1018-r2, known contracts). They certify the engine logic; Android only has to prove the
**host** reproduces the same bytes (§11.2). Keep a CI job that checks the Android asset's SHA-256 equals
`engine/bundle-manifest.json`'s.

---

## 12. Android mapping

### 12.1 What already exists (scaffold, `core/engine`)

`SignerEngine` interface + `EngineOperation` enum (`production`, `isWarm` flags), `QuickJsSignerEngine` (allowlist,
sorted-key request JSON, 1 MiB / 2 MiB checks, envelope decoding, warm/scan/fresh hosts), `QuickJsContextHost`
(memory limit 512 MiB, max stack 8 MiB, hex-transport bootstrap equivalent to §3.6, bytecode `execute`, `dispatch`
handle, release + destroy), `EngineBundle` (asset load, SHA-256 check against `6933d0d8…1077`, `QuickJSLoader.init()`,
`compile` once in a throwaway context, `jniSafeSource` for the raw U+0000), `EngineThread` (one daemon thread, 32 MiB
stack), `EngineModule` (Hilt; `@Named(ENGINE_TEST_OPERATIONS)` = `BuildConfig.DEBUG`), `EngineSmokeTest`
(androidTest: masterFingerprint `73c5da0a`, four first addresses, mnemonic). Gaps to close are listed in §12.9.

### 12.2 Module placement

- `:core:engine` — the QuickJS host, `SignerEngine`, the typed call facade (§12.5), bundle verification, engine
  androidTests and vector assets. No domain parsing beyond the envelope.
- `:core:model` — typed readers of results (`TransactionReview`, `SigningPlan`, `SigningTask`, `PairingPayload`,
  `WalletPairingPayload`, `QRPayloadView`, `PublicAccount`, `WalletPublicMetadata` rules) — JVM-only, per
  `models-state.md`.
- `:core:crypto` — `BIP39Seed` (PBKDF2-HMAC-SHA512, NFKD), `SecureRandom`, entropy hex.
- `:core:data` — `SignerStore` with every invariant of §9 and the epoch machinery.

### 12.3 QuickJS (`wang.harlon.quickjs:wrapper-android:3.2.3`) usage

Verified API (`javap` of `wrapper-java-3.2.3.jar` / the AAR):

| Need | Call |
|---|---|
| Load native lib (once per process) | `com.whl.quickjs.android.QuickJSLoader.init()` |
| New runtime + context | `QuickJSContext.create()` — **records the creating thread; every later call checks it** (`"Must be call same thread in QuickJSContext.create!"`) |
| Limits | `setMemoryLimit(int bytes)`, `setMaxStackSize(int bytes)`, `setGCThreshold(int bytes)`, `runGC()`, `getMemoryUsedSize()` |
| Globals | `getGlobalObject(): JSObject`; `JSObject.setProperty(name, JSCallFunction)` (also `String/int/long/double/boolean/byte[]/JSObject`); `getJSObject(name)`, `getJSFunction(name)` |
| Native callback | `JSCallFunction { args -> … }` (args arrive as Java boxed values) |
| Evaluate source | `evaluate(script, fileName)` |
| Bytecode | `compile(source, fileName): ByteArray`; `execute(bytecode): Any?` |
| Call | `JSFunction.call(vararg args): Any?` (strings in, string out) |
| Cleanup | `JSObject.release()` for every handle you obtained, then `context.destroy()` (also `close()`); using a destroyed context throws `"Can not called this after QuickJSContext was destroyed!"` |
| Errors | `QuickJSException extends RuntimeException` (`isJSError()`) |

Rules:

1. **One dedicated engine thread** (scaffold `EngineThread`) creates, uses and destroys every context. Expose only
   `suspend` functions that `withContext(engineDispatcher)`. This also serializes everything like the iOS actor.
2. **Stack:** thread stack (32 MiB) must exceed `setMaxStackSize` (8 MiB) by a wide margin, otherwise deep recursion
   crashes natively instead of throwing a catchable `InternalError`. Default Java threads (~1 MiB) are not enough.
3. **Memory:** 256–512 MiB cap per runtime; up to three runtimes can coexist (warm + scan + fresh). Measure
   `getMemoryUsedSize()` after `derivePublicIndex` and a 100-input PSBT review on a low-end device.
4. **Destroy discipline:** fresh hosts are destroyed in `finally` (success, engine error, `QuickJSException`). If a
   `QuickJSException` escapes from the **warm** or **scan** host (OOM, uncatchable error), destroy that host too and
   null it, so the next call starts clean.
5. **No interrupt/timeout API** in the wrapper (and none on iOS). Coroutine cancellation only takes effect after the
   JS call returns; callers rely on epoch checks (§9.14) exactly like iOS.
6. **Bytecode caching:** compile `signer.js` **once per process, in memory**, after the SHA-256 check, then `execute`
   the bytecode in each fresh runtime (scaffold). **Never persist bytecode** to disk or load it from writable storage:
   QuickJS's bytecode reader is not hardened against malicious input, and disk bytecode would escape the SHA-256 check.
   Prewarm (load + verify + compile) on `Dispatchers.Default`-scheduled engine-thread work shortly after app start or
   when the Scan/Wallets tab first appears, so the first scan is not delayed.
7. **R8:** the AAR ships `-keepclasseswithmembers class * { native <methods>; }` and `-keep class com.whl.quickjs.**{*;}`
   as consumer rules; nothing else needed.

### 12.4 ASCII-only string transport (required)

Reason: the wrapper marshals Java strings via JNI modified UTF-8 (proven for `compile` by the scaffold's U+0000
problem). For `dispatch`:

- **Requests:** after building the canonical JSON, replace every UTF-16 code unit ≥ U+0080 with `\uXXXX` (lowercase
  or uppercase hex, 4 digits). Lossless: non-ASCII can only appear inside JSON strings, and the engine's parser decodes
  escapes (§2.2; golden equality in V-UNICODE). U+0000…U+001F are already escaped by kotlinx.
- **Responses:** after `execute(bundle)`, evaluate one more host script that captures `dispatch` and exposes an
  escaping wrapper; call that instead of `BitToolSignerEngine.dispatch`:

```js
(function (engine) {
    'use strict';
    var dispatch = engine.dispatch, hex = '0123456789abcdef';
    globalThis.__bitSignerDispatch = function (json) {
        return dispatch(json).replace(/[\u0080-￿]/g, function (c) {
            var n = c.charCodeAt(0);
            return '\\u' + hex[n >> 12] + hex[(n >> 8) & 15] + hex[(n >> 4) & 15] + hex[n & 15];
        });
    };
})(BitToolSignerEngine);
```

- Limits: check the iOS limits on the **unescaped** forms — request: UTF-8 size of the canonical JSON ≤ 1,048,576
  (`REQUEST_TOO_LARGE`); response: parse the ASCII text with kotlinx and require the UTF-8 size of its compact
  re-encoding ≤ 2,097,152 (`ENGINE_FAILURE`), plus a hard pre-parse cap on the raw ASCII length (e.g. 12 MiB) to bound
  allocation. The engine itself sees the escaped request; a request whose escaped form exceeds 1 MiB would get the
  engine's `LIMIT`. No QR- or file-sourced request can reach that (payloads are ≤ 128 KiB / 256 KiB).
- Keep V-UNICODE in a debuggable build (CheckJNI aborts the process on invalid modified UTF-8 in `NewStringUTF`).

### 12.5 Coroutine API surface

Keep the scaffold interface and add a thin typed facade so call sites cannot drift from iOS parameters:

```kotlin
interface SignerEngine {
    suspend fun run(operation: EngineOperation, parameters: JsonObject = JsonObject(emptyMap())): JsonObject
    suspend fun beginScanSession()
    suspend fun scan(frame: String): JsonObject      // throws EngineException("SCAN", "The scan session ended. Scan again.") without a session
    suspend fun endScanSession()
    suspend fun releaseWarmContext()
    suspend fun bundleDigest(): BundleDigest           // computed from asset bytes (Open Source page), not the constant
}

class EngineCalls @Inject constructor(private val engine: SignerEngine) {
    suspend fun mnemonic(entropyHex: String): JsonObject
    suspend fun derivePublicIndex(seedHex: CharArray): JsonObject             // {"seed"} only; wipes nothing it cannot
    suspend fun derivePublicAddresses(index: JsonObject, chain: String, profile: String, start: Int, count: Int): JsonObject
    suspend fun createPublicPairing(index: JsonObject, chain: String, profile: String, outputMode: String, addressIndex: Int): JsonObject
    suspend fun createWalletPairing(index: JsonObject): JsonObject           // mode "full"
    suspend fun decodePayload(text: String): JsonObject
    suspend fun review(request: JsonObject): JsonObject
    suspend fun resolveSigningPlan(request: JsonObject, wallets: JsonArray): JsonObject
    suspend fun signPlanTask(request: JsonObject, plan: JsonObject, taskId: String, seedHex: CharArray,
                             riskAcknowledged: Boolean, previousResult: JsonObject?): JsonObject
    suspend fun encodeResponse(request: JsonObject, result: JsonObject): JsonObject
}
```

- `EngineException(code, message)` mirrors `SigningEngineError`; truncate code to 80 and message to 400 **grapheme
  clusters** (`BreakIterator`) for strict parity (scaffold uses `take`, i.e. UTF-16 units; equivalent for today's
  ASCII messages).
- Seeds: `CharArray` → `String(seed)` only while building the JSON; zero the `CharArray` in the caller; the JSON
  `String` cannot be wiped (same caveat as iOS, Security.md:72). Never log requests, results or seeds; log at most the
  operation name and error code in debug builds.
- Results are `JsonObject`; numbers stay `JsonPrimitive` (read with `longOrNull`/`doubleOrNull`, never coerce
  fingerprints to `Int`).
- `isWarm` operations must receive a `publicIndex` from the authenticated repository only (enforce by taking a
  `WalletPublicMetadata` type, not a raw `JsonObject`, in the facade).

### 12.6 Bundle packaging and verification

- Asset `assets/engine/signer.js`, `androidResources.noCompress += "js"` (scaffold) so the APK entry is stored, and
  verifiable with `unzip -p <apk> assets/engine/signer.js | shasum -a 256`. Unlike iOS (FairPlay, Reproducible-Build.md:9-21)
  the APK is not encrypted; for Play installs pull `base.apk` (`adb shell pm path com.bittool.signer`). Write the
  Android variant of the Open Source page steps accordingly (activity-settings spec).
- Build-time guard: a JVM unit test (as Ownbit's `JsBundleIntegrityTest`) asserting the asset's SHA-256 and byte count
  equal `EngineBundle.EXPECTED_SHA256` / 1,181,026, and a CI check against `engine/bundle-manifest.json`. Runtime:
  verify before first compile (≈ 5 ms for 1.18 MB); mismatch → `ENGINE_INVALID`.
- The wrapper AAR merges five test scripts into the app's assets root (`test_assert_define.js`,
  `test_base_module1.mjs`, `test_base_module2.mjs`, `test_module_import_dynamic.js`, `test_polyfill_date.js`). They are
  never evaluated, but an auditor will find unexpected JS in the APK: exclude them (e.g. an
  `androidComponents.onVariants` assets filter, or `androidResources.ignoreAssetsPattern` that keeps AGP's defaults and
  adds `!test_*.js:!test_*.mjs`).
- Native libraries: all four ABIs ship `libquickjs-android-wrapper.so` with **16 KB-aligned LOAD segments**
  (`p_align 0x4000`, verified), so Play's 16 KB page-size requirement is met. `apkRelease` may `abiFilters` to
  `arm64-v8a`, `armeabi-v7a` (+ `x86_64` for debug/emulator); `playRelease` uses AAB ABI splits.
- No `INTERNET` permission anywhere; the engine has no network code (`typeof fetch === 'undefined'` in the context;
  keep the isolation test asserting `fetch`/`XMLHttpRequest` absent).

### 12.7 Performance plan

- Measured on the emulator: fresh runtime ≈ 0.2 s, `derivePublicIndex` ≈ 7 s. A signing flow = scan session (1 runtime)
  + review + resolve + sign + encode (4 fresh runtimes ≈ 0.8 s) + ≥ 1 full index derivation inside `signPlanTask`.
  Create/import/repair/reset/"is this phrase already here?" each run `derivePublicIndex`.
- UX: every such action shows a determinate-looking busy state ("Finding the matching wallet…", signing spinner on the
  Sign button) and stays cancellable by navigation; results land through epoch checks.
- Levers to benchmark (keep the bundle byte-identical): (a) `setGCThreshold` (e.g. 16–64 MiB) — BigInt-heavy curve math
  allocates heavily; (b) a wrapper build embedding a QuickJS release newer than 2024-02-14 (later QuickJS releases
  replaced the libbf BigInt with a faster native implementation — verify before adopting); (c) real arm64 devices
  (emulator numbers are indicative only); (d) optionally run `review` and `resolveSigningPlan` of the same frozen
  request in one fresh runtime (both untrusted, no secret) to save ~0.2 s — iOS uses two contexts; behaviour is
  identical.
- Do not "optimize" by moving public-index derivation into Kotlin: `signPlanTask` re-derives inside the engine anyway,
  and a second implementation would be a byte-compatibility liability.

### 12.8 Testing strategy

- **The real engine runs only in instrumented tests.** `wrapper-java-3.2.3.jar` contains classes only (no desktop
  native; `QuickJSContext` says "In the JVM, you need to manually call System.loadLibrary"), and building the desktop
  native from source would test a binary that never ships. Put §11.2 in `core/engine/src/androidTest`, run with
  `:core:engine:connectedDebugAndroidTest` (emulator-5554, DECISIONS.md §2) and add a Gradle Managed Device
  (x86_64 ATD image) for CI. Mark mnemonic-based debug tests (`sign`, `deriveAccounts`) `@LargeTest` (JS PBKDF2).
- **JVM unit tests** for the host: request building (sorted keys, `operation` overwrite, null-free), ASCII escaping
  round trip, size limits on unescaped forms, envelope parsing (missing `ok`, non-object result → `{"value":…}`,
  missing error fields → `SIGNING_FAILED` / default message, truncation), allowlist (release vs debug), context routing
  (inject a fake `JsRuntimeFactory` behind `QuickJsContextHost` to assert fresh/warm/scan usage and destruction), and
  `SignerStore` invariants with a fake engine that records invocations and can hold calls (port of
  `ControlledSigningEngine`/`EngineGate`, `SessionTests.swift:467-513`).
- BIP39/PBKDF2 vectors and Trezor seeds in `:core:crypto` JVM tests.

### 12.9 Pitfalls checklist (for the implementer and reviewer)

1. Do not copy Ownbit's engine parameters: its "Ownbit edition" adds `deviceName`, `tronScheme`, `tronAddressCount`,
   `refreshPublicIndex`; BitTool Signer's engine has none of them (`core/jsengine/.../PublicSignerJsBridge.kt` in
   Ownbit). Its device name is fixed to "BitTool Signer" inside the engine.
2. Never send `"passphrase"` together with `"seed"` (even empty) — `SEED` error.
3. Never send `null` for an optional key; never add keys to a plan or result you pass back.
4. Solana address paging sends `profile: ""` (all three presets); Bitcoin never sends `""`.
5. TRON is the only chain with a non-zero pairing `index`; others must send 0.
6. `encodeResponse` returns no `text`: join frames with `"\n"`.
7. Fingerprints in payload views exceed `Int.MAX_VALUE` (e.g. 3543024904).
8. Keep frame strings exactly as scanned; UR frames produced by the engine are uppercase and must be rendered as given
   (alphanumeric QR mode).
9. `resolveSigningPlan` reports request problems as `status:"invalid"`, not as an exception.
10. The engine only gates partial/opaque decoding; the host must also gate `riskLevel == "high"`.
11. Use one UUID spelling for `localWalletId` throughout a session (tasks sort by it).
12. Scan session after an engine error: either keep it (iOS) or end it (Ownbit); in both cases the UI must call
    `beginScanSession()` again on "Try Again". Survive configuration changes (hold the session in the ViewModel/store;
    end it on dismissal and in `onCleared`).
13. Scaffold gaps: ASCII transport (§12.4); `engineSha256` returns the constant instead of hashing on demand; warm/scan
    host not discarded after a `QuickJSException`; grapheme-aware truncation; the AAR test-asset exclusion; a
    test-only source-injection path for the isolation test.

---

## 13. Out of scope (iOS-only)

- JavaScriptCore specifics: `JSVirtualMachine`/`JSContext`, `@convention(block)` callbacks, `context.exceptionHandler`,
  `autoreleasepool`, `withSourceURL`, `Bundle.url(forResource:)` lookup (`SigningEngine.swift:13-95, 155-162,
  202-214`) — replaced by the QuickJS host above.
- CommonCrypto `CCKeyDerivationPBKDF` and `memset_s` (`BIP39Seed.swift`) — replaced by `javax.crypto.Mac` PBKDF2 and
  `ByteArray.fill(0)`; `SecRandomCopyBytes` — replaced by `java.security.SecureRandom`.
- DEBUG `SigningEngine(bundle:sourceURL:)` source injection as written (keep only an equivalent test-only factory).
- App Store / FairPlay reproducibility discussion and Apple Configurator `.ipa` extraction
  (`docs/Reproducible-Build.md:7-21, 70-85`) — replaced by APK extraction; `otool -L` network check — replaced by "no
  `INTERNET` permission" and dependency review.
- iOS performance notes about JIT-less JavaScriptCore (README.md:23, Security.md:65) — QuickJS has its own (§12.7).
- Face ID, Liquid Glass, iOS 17/18 fallbacks, simulator UI-test context, PrivacyInfo.xcprivacy — not part of this layer.
