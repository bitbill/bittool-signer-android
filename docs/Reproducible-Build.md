# Verifying BitTool Signer for Android

BitTool Signer is an offline signer, and its value rests on **not having to trust us**. This document says what you
can check yourself, what you cannot, and why. (The iOS app has the same document; the Android app can be checked
further, because its installable package is published.)

## What cannot be verified byte for byte

1. **The app Google Play installs.** Play builds it from the uploaded App Bundle, splits it per device and signs it
   with Play's app signing key, so no file you build yourself will have the same hash.
2. **The whole APK, rebuilt from source.** R8, the Android build tools and the native toolchain make no determinism
   promise across machines, so this document does not claim that a rebuilt APK is identical to the published one.

Do not compare the hash of a Play-installed APK with anything. That route does not work.

## What can be verified — the part that decides what you sign

Every transaction parser, risk rule, signing plan and signature encoder lives in **one file**, the engine:

```
APK:    assets/engine/signer.js
Source: core/engine/src/main/assets/engine/signer.js   (built from engine/, the same bytes as the iOS app's)
```

Asset files are not changed by Play's processing, so this file can be taken out of **any** copy of the app — the
APK from the website, or the one Google Play installed on your phone — and compared byte for byte with what you build
from source. The app also checks it itself: it refuses to start an engine whose length or SHA-256 differs from the
values compiled into it, and Settings › Fully Open Source › This build shows that SHA-256.

The Kotlin and C layers (interface, Android Keystore, encrypted storage, the QuickJS host) are published too, but they
can be checked by reading, not by comparing hashes.

### Step 1: rebuild the engine from source

You need Node.js and npm (the versions a release used are in its `release/release-manifest.json`).

```bash
git clone https://github.com/bitbill/bittool-signer-android
cd bittool-signer-android
git checkout android-v1.0.0          # the tag or commit you are checking
cd engine
npm ci --ignore-scripts              # exact versions from the lockfile, no install hooks
npm run build
shasum -a 256 ../core/engine/src/main/assets/engine/signer.js
```

`--ignore-scripts` is not optional: a dependency's install hook could change the output. The hash must equal
`engineBundle.shippedSHA256` in `release/release-manifest.json`. The committed `signer.js` is overwritten in place,
so a clean `git status` afterwards also shows that the committed file is exactly what the source builds:

```bash
cd .. && git status --porcelain core/engine/src/main/assets/engine/signer.js   # no output = identical
```

### Step 2: take signer.js out of the app you use

From the phone, with USB debugging on (no root needed):

```bash
adb shell pm path com.bittool.signer          # prints package:/data/app/…/base.apk
adb pull /data/app/…/base.apk bittool-signer.apk
unzip -p bittool-signer.apk assets/engine/signer.js | shasum -a 256
```

Or from the APK downloaded from the website or the GitHub release:

```bash
unzip -p BitToolSigner-1.0.0-1000001-apk.apk assets/engine/signer.js | shasum -a 256
```

If the hash matches Step 1, **the code that decides what you are signing is exactly what this source builds.**

### Step 3: check the downloaded APK itself (website / GitHub channel)

```bash
shasum -a 256 BitToolSigner-1.0.0-1000001-apk.apk               # = artifacts[…apk].sha256 in release-manifest.json
apksigner verify --print-certs BitToolSigner-1.0.0-1000001-apk.apk
```

The certificate SHA-256 must equal `signing.certificateSHA256` in the release manifest and the value published on
https://bittool.com/signer; it never changes between releases. After installing, Settings › Fully Open Source ›
This build shows the same certificate. Android itself refuses an update signed by any other key.

The claim that the app never goes online can be checked on the APK too:

```bash
apkanalyzer manifest permissions BitToolSigner-1.0.0-1000001-apk.apk   # no android.permission.INTERNET
```

### Step 4: check the whole source snapshot

`tools/release/release-manifest.sh` writes the release record into `release/`:

- `SHA256SUMS.txt`: the SHA-256 of **every file tracked by Git**, in a fixed order;
- `release-manifest.json`: the commit, the version, the hash of `SHA256SUMS.txt`, the engine hash **as rebuilt after a
  clean dependency install in a throwaway copy of that commit**, and the published APK and App Bundle hashes with the
  signing certificate;
- `clean-install.log`, `clean-build.log`: the full output of that rebuild.

Run it yourself on the same tag (with a clean tree) and compare:

```bash
tools/release/release-manifest.sh /tmp/mine
diff release/SHA256SUMS.txt /tmp/mine/SHA256SUMS.txt
```

## Do not stop at hashes — read the code

A hash proves that the code you are reading is the code that was packaged. It proves nothing about whether that code
is safe. These are the places worth your time:

| What to look at | Where |
| --- | --- |
| Transaction parsing and risk assessment | `engine/src/bitcoin.js`, `evm.js`, `solana.js`, `tron.js` |
| Signing plans and the bytes that are signed | `engine/src/resolver.js`, `engine/src/keys.js` |
| Recovery phrase encryption at rest | `core/crypto/src/main/kotlin/com/bittool/signer/core/crypto/vault/` |
| Passwords, sessions and the request pipeline | `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt` |
| The engine host (no network, files or eval) | `core/engine/src/main/cpp/`, `core/engine/src/main/kotlin/…/engine/` |
| The claim that it never goes online | `app/src/main/AndroidManifest.xml` (INTERNET removed) and `tools/release/check-offline.sh` |

## What this document does and does not promise

**It promises**: published source, locked dependencies, a reproducible engine artifact, a published signing
certificate, and a verification path you can walk end to end without relying on us.

**It does not promise**: the absence of unknown defects. Matching hashes mean the code was not substituted. They do not
mean it is free of bugs. Weigh your funds at risk against your own review, not against this document.
