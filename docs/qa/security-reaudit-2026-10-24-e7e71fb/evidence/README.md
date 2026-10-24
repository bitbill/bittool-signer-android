# Re-audit evidence: e7e71fb

All evidence targets local commit `e7e71fbc4b5614b68e993e1eec5dc986b4536a86`, compared with `bb1e6567fb2a8cc30dd6e8e9447e69b0f330fa1b`. The tracked working tree was unchanged. Fixtures use public test mnemonics and synthetic transactions. No real wallet data, network broadcasts or production source changes were involved.

## Verification commands

From the repository root, the full Gradle invocation was:

```sh
tools/gradle-slot.sh ./gradlew testDebugUnitTest :core:model:test :app:testApkDebugUnitTest :app:testPlayDebugUnitTest :app:checkApkReleasePermissions :app:checkPlayReleasePermissions :app:checkApkReleaseComponents :app:checkPlayReleaseComponents :app:lintApkRelease :app:lintPlayRelease -Psigner.screenshots=false --offline
```

The complete engine suite was `npm test`, with working directory `engine`. Its exit status was nonzero: 209 reported passes and one failure. The browser-bundle pass is vacuous because its required legacy file is absent. See `engine-tests.log`, `legacy-bundle-test-false-green.log` and `bundle-test-paths.json`.

## Findings and reproduction

- `engine-current-and-bundle-repro.mjs`: source plus exact Android-bundle reproductions for BTS-02/BTS-04; asserts the vulnerable behavior, so it should fail after a correct fix. Run with `node engine-current-and-bundle-repro.mjs /absolute/path/to/repository`. Requires installed locked engine dependencies. The bundled VM has a WebCrypto shim for randomness; it is not the Android host. The harness checks `typeof fetch` is undefined and verifies synthetic signatures.
- `engine-current-and-bundle-repro.log`, `engine-findings-current.json`: reproduced display/summary models, high-risk warnings, signature results and scope limitations.
- `offline-registry-check.mjs`: runs a temporary copy of the original integrity test with exactly three resource-path substitutions. Run with `node offline-registry-check.mjs /absolute/path/to/repository`. `registry-adapted.log` records one pass and 18 name-filtered skips; this diagnosis does not fix the repository suite.
- `wallet-security/SecretCancellationReaudit.kt`, `run-poc.py`, `cancellation-result.txt`: three deterministic cross-dispatcher cancellation checks against the current compiled store, with the production BIP39 seed producer wrapped by a test spy. The canonical test inspects the actual SecretText holder and original array without printing contents.
- `wallet-security/RegressionDiscrimination.kt`, `BaselineDefaultSignerStore.kt`, `check-regression-discrimination.py`, `regression-discrimination-result.txt`: compile the old store in isolation and run the new repository cleanup regression against old/current implementations. The old implementation fails the wipe assertion; current code passes.
- `app-authorization/ScannerReaudit.kt`, `run-poc.py`, `scanner-reaudit-output.txt`: four tests of the current compiled scanner, with FakeSignerStore and the real QR frame gate. Assessment notes explain the actual strict-touch callers and remaining platform limits.

Kotlin runners use this workstation's existing JDK, Android SDK, Gradle cache and compiled classes; paths are explicit in the scripts. They do not install dependencies or run Gradle. Rebuild the relevant classes first, adjust paths for another machine, and execute each Python runner from a temporary copy of its evidence directory. Runners create compiler output alongside themselves. The archived scanner runner was changed only to find its adjacent harness instead of its original temporary directory.

## Build evidence

- `baseline.json` and `reviewed-change.diff`: exact HEAD, timestamp, remote status, clean tracked state and all nine changed files.
- `gradle-verification.log`: successful 1,185-task invocation. Incremental/cache reuse is disclosed in the report.
- `verification-summary.json`: counts parsed from JUnit XML across 19 tasks and both lint XML reports.
- `lint-results-apkRelease.xml`, `lint-results-playRelease.xml`: zero errors and 71 warnings each.
- `bundle-verification.json`: byte-for-byte comparison of committed asset and esbuild output using the current build options with `write: false`; includes its SHA-256 and input graph. No output asset was rewritten.
- `wallet-security/evidence-metadata.json`: hashes captured by the cancellation reviewer. Temporary compiled JARs/classpaths are not archived; their sources and runners are.

The earlier audit's native PBKDF2 and dependency-advisory results are explicitly carried forward because those inputs are unchanged. No fresh advisory database query, ARM JNI test or real-device malicious-overlay exercise is claimed. `SHA256SUMS` in the report directory fingerprints the archived sources, results and report.
