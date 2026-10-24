# Audit evidence - 24 October 2026

Baseline: `bb1e6567fb2a8cc30dd6e8e9447e69b0f330fa1b`.

`verification-summary.json` records the final counts: 1,613 Android/JVM tests across 19 tasks, no failures/skips; incremental and cached results are included. Both release lint tasks completed with zero errors and 70 warnings per variant. Lint does not analyze the plain JVM `core:model` source; that module has 109 JVM tests and was reviewed separately.

The untouched engine suite has 209 passes and one failure caused by the iOS-only resource paths (BTS-06). The temporary path-only adaptation passes the registry integrity test. This adaptation is evidence, not a production fix.

## Reproduction commands

Run from the repository root after installing the committed engine dependencies with `cd engine && npm ci --ignore-scripts` and building the relevant debug unit-test classes:

```sh
node docs/qa/security-audit-2026-10-24/evidence/engine-burn-repro.mjs
node docs/qa/security-audit-2026-10-24/evidence/offline-registry-check.mjs
python3 docs/qa/security-audit-2026-10-24/evidence/wallet-security/run-poc.py
python3 docs/qa/security-audit-2026-10-24/evidence/app-authorization/run-poc.py
python3 docs/qa/security-audit-2026-10-24/evidence/wallet-security/pbkdf2-crosscheck.py .
```

The Kotlin runners use the matching Gradle cache and compiled repository classes. They default to the local macOS JBR 21/Android SDK locations; set `AUDIT_JAVA_HOME`, `ANDROID_HOME` and/or `GRADLE_USER_HOME` when needed. Both accept the repository root as their first argument and write compiler products to a temporary directory. The native PBKDF2 runner accepts `--java-home`; see its help. Public synthetic wallet data is used, and seed contents are never printed. Assertions in the defect reproductions confirm the vulnerable behavior and should fail once it is corrected.

## Automated checks run

The two successful Gradle invocations used `tools/gradle-slot.sh ./gradlew`, `--offline` and `-Psigner.screenshots=false`:

1. `:core:common:test :core:model:test :core:crypto:testDebugUnitTest :core:security:testDebugUnitTest :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :core:scanner:testDebugUnitTest :feature:sign:testDebugUnitTest :feature:wallets:testDebugUnitTest :feature:setup:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testApkDebugUnitTest`
2. `:core:designsystem:testDebugUnitTest :core:ui:testDebugUnitTest :core:navigation:testDebugUnitTest :core:l10n:testDebugUnitTest :feature:activity:testDebugUnitTest :feature:onboarding:testDebugUnitTest :app:testPlayDebugUnitTest :app:checkApkReleasePermissions :app:checkPlayReleasePermissions :app:checkApkReleaseComponents :app:checkPlayReleaseComponents :app:lintApkRelease :app:lintPlayRelease`

`gradle-tests.log` and `gradle-additional.log` retain the outcomes. XML lint reports and merged permission/component reports are included. `npm-audit.json` includes development dependencies; `npm-audit-production.json` excludes them. `bundle-verification.json` contains the esbuild input graph and the byte-identical source-to-asset comparison. The build used the exact options in `engine/build.mjs` with `write:false` to avoid changing the bundled source artifact.

The PBKDF2 log records all 46 cases, the baseline and production-source digests. `app-authorization/engine-independent-output.txt` records an independent run against the shipped asset. Reproductions do not establish successful on-chain execution, and no transactions were broadcast. Physical-device instrumentation was not performed.
