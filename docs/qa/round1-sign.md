# QA round 1 — tour "sign" (Scan, signing pipeline, Activity, Settings)

Device: emulator-5558, API 31, 1080×2220 @ 440 dpi, debug build `com.bittool.signer.debug` (apk flavor), harness run with
a fixed run id (data kept across relaunches). Screenshots: `build/qa/round1/sign/` (each `NAME.png` has a `NAME-s.png`).
Requests: the published round-trip vectors (`core/data/src/androidTest/resources/vectors/signing-roundtrips.json`) plus
requests generated from the iOS engine with the public BIP39 mnemonics only (USDT unlimited approve, USDT transfer,
unknown contract call, Permit2 PermitSingle, opaque Solana instruction, 2-input BIP84 PSBT as `.psbt`/base64/crypto-psbt
UR, high-fee PSBT, 2-of-2 P2WSH between "abandon…about" and "legal winner…", personal_sign for the second wallet,
incomplete UR, wallet-pairing UR, garbage, truncated PSBT, binary file, 600 KB file). Wallets created through the UI:
"Reference Wallet" (abandon…about) and "Cosigner Wallet" (legal winner…).

## Summary

The signing pipeline is functionally solid: every request type decoded, matched and signed; exported responses for
EVM personal_sign, TRON transfer and Solana transfer are byte-identical to the published vectors; multi-wallet (2-of-2)
signing, per-task passwords, the partial-signature Reject confirmation, risk acknowledgement (and its reset on
background), busy-state Back handling, noMatch and every import error behave as iOS. Settings pages match the iOS
structure and the reference shots; engine SHA-256 `6933d0d8…1077`, 1,181,026 bytes, registry 2026-09-22.1, version
1.0.0 (1) and the APK signing-certificate SHA-256 (`99:92:B7:…:A0:A5`, matches `apksigner`) are correct.

Blocking: **Activity could not be tested at all** — in the UI-test harness every receipt write fails (sign-01), so
every signature ends with an "Unable to Continue" alert and the Activity tab stays empty. Main product defects: verbatim
engine text is laid out forced-RTL in Arabic (the signed message's punctuation and the amount/symbol order flip,
sign-02); "Return" in "How signing works" is translated as the keyboard Enter key in 11 languages (sign-03); at font
scale 2.0 the pinned sign bar leaves ~290 dp for the request (sign-04); the app process queries the Google Play services
font provider at startup (sign-05).

Counts: P0 0 · P1 1 · P2 6 · P3 11.

Test conditions: host load average 17–64 during the tour (three emulators, Gradle, iOS simulator); the emulator's
system_server and MediaProvider restarted once (08:14–08:16). Timings below are therefore not performance findings.

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (screenshot) | Suspected owner · root cause |
|---|---|---|---|---|---|---|
| sign-01 | P1 | Signing → Activity (debug/UI-test harness) | Harness run on an emulator without a screen lock; sign any request (e.g. Paste `rt-evm-personal`) | Receipt appended; Activity lists it (iOS `AppStore.swift:554-569`, ref `light-30-activity.png`) | After every signature "Unable to Continue — The local activity record could not be saved…"; Activity stays "No Signatures Yet". logcat: `keystore2 … generate_key … handle_super_encryption_on_key_init: User ECDH key missing` (`28-unable-to-continue-s.png`, `40-activity-after-sign-s.png`) | :app (debug) + :core:crypto · `KeystoreJournalCipher` `@Inject` ctor uses `unlockedDeviceRequired = true` (`core/crypto/src/main/kotlin/com/bittool/signer/core/crypto/vault/KeystoreJournalCipher.kt:28`), bound at `core/crypto/.../di/CryptoModule.kt:43`; `app/src/debug/kotlin/com/bittool/signer/harness/DebugUiTestModule.kt:27-34` overrides only `DeviceKeyStore`, not `JournalCipher` (the vault already uses a fixture key, so wallets exist while the journal key cannot be generated). Add a `@UiTestOverride JournalCipher` (test policy, as the androidTest does). Production unaffected only because the vault refuses devices without a secure lock screen. Blocks the whole Activity tour and any e2e over Activity. |
| sign-02 | P2 | Review summary (Arabic) | Language العربية; import `rt-evm-personal.txt`; also `btc-multi-input.psbt` | Verbatim engine text keeps its own direction (SwiftUI natural): "BitTool Wallet + BitTool Signer: public interoperability test." / "0.0008 BTC" | Headline reads ".public interoperability test" (period jumps to the start) and the amount "BTC 0.0008"; "Unlimited USDT" would read "USDT Unlimited" — the text of what is being signed is misrendered (`164-review-ar-personal-s.png`, `162-review-ar-top-s.png`) | :core:ui · `core/ui/src/main/kotlin/com/bittool/signer/core/ui/review/ReviewComponents.kt:189-197` (non-NFT amount `BasicText` with no textDirection → Compose resolves Unspecified to the layout direction, i.e. forced RTL) and `:128-135` (text headline). Use `.contentDirection()` (as `Callout(verbatim)` does, `SignerTypography.kt:139`) for every verbatim engine string (amount, headline, secondary headline, action). Same forced-RTL effect on untranslated English footers (`GroupedSection.kt:119-121`, see `167-settings-ar-s.png`). |
| sign-03 | P2 | Scan home › "How signing works" | Language 日本語 (or ko, es, es-419, it, pl, pt-BR, pt-PT, ru, tr, uk) | Step 4 "Return" = return the signature (`ScanTab.swift` StepRow 4) | ja "改行", ko "줄바꿈", es/es-419 "Intro", it "Invio", ru "Ввод", pl/pt-BR/pt-PT/tr/uk "Enter" — the keyboard key (`152-scan-home-ja-dark-bottom-s.png`) | :core:l10n · `core/l10n/src/main/res/values-*/strings_ios.xml` `return_text` (ja line 515), generated from iOS `translations/*.json` key "Return" (same bug upstream). Retranslate as "send back / return the signature". |
| sign-04 | P2 | Review (font scale 2.0) | Font 2.0; import `rt-evm-eip712.txt` | Request content remains comfortably reviewable (spec §11.3 "use stacking at large font scales rather than truncating") | Pinned acknowledgement (~535 px) + Sign (~276 px) + tab bar (~220 px) leave ~290 dp of the 807 dp screen for the request; warnings/fields scroll in a slot (`61-eip712-font2-s.png`, `63-eip712-font2-scrolled2-s.png`) | :feature:sign · `feature/sign/src/main/kotlin/com/bittool/signer/feature/sign/review/RequestReviewScreen.kt:140-144` (everything in `bottomBar`), `SignBar.kt:60-96`. Let the acknowledgement scroll with the content (or collapse to a one-line checkbox) when the bar exceeds ~35 % of the height; consider hiding the tab bar on the review. |
| sign-05 | P2 | App start (all screens) | Launch; `adb logcat` | No IPC to network-capable services (offline by design, DECISIONS §6) | `ActivityThread: Failed to find provider info for com.google.android.gms.fonts`, `Timeout waiting for provider com.google.android.gms … caller=…com.bittool.signer.debug` — the app asks Play services' font provider (which may download) for the emoji font | :app · merged manifest contains `androidx.emoji2.text.EmojiCompatInitializer` under `androidx.startup.InitializationProvider`; `app/src/main/AndroidManifest.xml:10-11` removes only INTERNET/ACCESS_NETWORK_STATE. Remove the initializer (`tools:node="remove"` meta-data) or use the bundled emoji config, and add it to the release manifest check. |
| sign-06 | P2 | Paste Request sheet | Type garbage with the keyboard up; tap "Review Request" (pinned above the IME) | Error visible (`ScanTab.swift:263-325` Callout under the editor) | Spinner stops, nothing visible: the danger callout ("Unsupported signing QR format") is laid out below the editor and "Paste from Clipboard", behind the keyboard; only visible after closing the IME | :feature:sign · `feature/sign/src/main/kotlin/com/bittool/signer/feature/sign/home/PasteRequestSheet.kt:140` (callout at the end of the scrolling body, no `bringIntoViewRequester`/IME dismissal on error). Bring the error into view (or hide the IME) when `state.error` changes. (No screenshot: secure sheet, sign-10.) |
| sign-07 | P2 | Scan home | Import File › `big.txt` / `binary.bin` / the exported signature file | Error noticed after picking a file | The callout appears at y 1802–1857 of a 1867 px viewport, mostly under the tab bar; after the picker closes the screen looks unchanged (`41-import-response-error-s.png`) | :feature:sign · `feature/sign/src/main/kotlin/com/bittool/signer/feature/sign/home/ScanHomeScreen.kt:184` (error placed after the tiles with no scroll-to). Scroll it into view (`BringIntoViewRequester`) or show it in a toast/snackbar as well. |
| sign-08 | P3 | Request import (all) | Any import while the host is loaded | Same engine timing as iOS; progress shown ("still show progress for create/import/repair/sign", DECISIONS §5) | No progress on Scan home between the picker closing and the review appearing (5–270 s here under load); the tiles are only disabled. Paste shows a button spinner, Import File shows nothing | :feature:sign · `ScanHomeScreen.kt:167-184` — only `enabled = !busy`; iOS has the same minimal feedback, but on Android the picker round trip makes the dead time visible. Show a spinner on the Import File tile (or a progress callout) while `busy`. |
| sign-09 | P3 | Scanner (camera denied) | Deny camera; font 1.0 and 2.0 | Status panel below the viewfinder on its material background (`QRScannerView.swift:85-126`, radius 22, ultraThinMaterial) | Panel overlaps the lower viewfinder brackets (slightly at 1.0, half the window at 2.0); its black-62 % background is invisible on the 80 % dim, so bracket strokes cross the text (`91-scanner-denied-s.png`, `175-scanner-denied-font2-s.png`) | :core:scanner · `core/scanner/src/main/kotlin/com/bittool/signer/core/scanner/scan/QrScannerContent.kt:117-141` (overlay rect ignores the panel height), `internal/ScannerPalette.kt:26` (`Panel` alpha). Lift/shrink the window to clear the measured panel and use a visible translucent panel colour. |
| sign-10 | P3 | Paste / Authorize sheets (debug) | `ui.py shot` while a sheet is open | Harness `bts.uitest.allowCapture` makes every window capturable | `screencap` returns 0 bytes (`12-paste-sheet.png`, `27-authorize.png` are empty): `SecureFlagPolicy.SecureOn` ignores the harness | :core:designsystem · `core/designsystem/src/main/kotlin/com/bittool/signer/core/designsystem/component/Dialogs.kt:111,181` — honour the UI-test capture switch (as the activity window does) so tours can document these sheets. |
| sign-11 | P3 | Authorize sheet | Tap "Sign with …", hide the keyboard | Sheet sized to its content (medium detent) with Sign at its bottom | The sheet surface spans [0,66]–[1080,2088]; Sign sits at y≈900 with ~1000 px of empty sheet below (uiautomator bounds; secure window) | :feature:sign · `feature/sign/src/main/kotlin/com/bittool/signer/feature/sign/review/AuthorizeSheet.kt:107-121` puts `StatusToastHost` in the sheet's `Box`; `core/designsystem/.../component/StatusToast.kt:113-115` is `fillMaxSize()`, which forces the sheet to full height. Overlay the toast without contributing to measurement (e.g. `matchParentSize`). Relates to backlog B16. |
| sign-12 | P3 | Settings › Language | Open Language in English, 日本語, 简体中文 | Clean secondary names | Subtitles from ICU script names: "Chinese (Simplified Han)", "中国語 (漢字(簡体字))", and in zh "简体中文 / 中文 (简体中文)" duplicates the endonym (`104-language-dark-s.png`, `150-language-ja-s.png`) | :feature:settings · `feature/settings/src/main/kotlin/com/bittool/signer/feature/settings/language/LanguageScreen.kt:115-117` (`Locale.getDisplayName`). Use `android.icu.text.LocaleDisplayNames` with `DialectHandling.DIALECT_NAMES` ("Simplified Chinese") and drop subtitles equal to the endonym after normalisation. |
| sign-13 | P3 | Scan home option tiles | 日本語 or font 2.0 | Labels balanced; icons aligned across the two tiles | "リクエストを貼り付/け" breaks with an orphan glyph; when one label wraps, the icons of the two tiles sit at different heights (`151-scan-home-ja-dark-s.png`, `172-scan-home-font2-tiles-s.png`) | :feature:sign · `ScanHomeScreen.kt:268-295` (`Arrangement.spacedBy(8.dp, Alignment.CenterVertically)`). Top-align tile content with a fixed icon row; use `LineBreak.Heading`/phrase breaking for CJK labels. |
| sign-14 | P3 | Signature result | Expand "Payload (JSON)"; switch to Wallets and back to Scan | Disclosure state kept (iOS TabView keeps view state) | Payload collapsed again | :core:ui · `core/ui/src/main/kotlin/com/bittool/signer/core/ui/payload/PayloadDisclosure.kt:74` `remember` → `rememberSaveable`. |
| sign-15 | P3 | Networks › network page | Long-press a token or contract row | Context-menu affordance (iOS lift preview) | No pressed/ripple feedback on the row; the menu is anchored flush at the row's start edge (x=0), not at the touch point (`143-token-context-menu-s.png`) | :feature:settings · `feature/settings/src/main/kotlin/com/bittool/signer/feature/settings/reference/NetworkReferenceScreen.kt:297-340` (`detectTapGestures(onLongPress)` without indication; `DropdownMenu` without offset). Use `combinedClickable` with ripple and anchor the menu at the press position. |
| sign-16 | P3 | Settings › Open Source › Third-Party Notices | Open the page | Readable notices | 70-column hard-wrapped licence text re-wraps on a phone, giving ragged half lines; the `=====` separator breaks onto two lines (`124-notices-zh-s.png`). iOS has the same raw rendering. | :feature:settings · `feature/settings/src/main/kotlin/com/bittool/signer/feature/settings/about/DocumentScreens.kt:115-122`. Reflow single newlines inside a paragraph, or allow horizontal scroll per block; draw separators as dividers. |
| sign-17 | P3 | Review (matching in progress) | Import `evm-personal-other-wallet.txt` | Empty bar while matching (iOS) | An empty `BottomActionBar` strip with its hairline stays above the tab bar, then jumps to "Cannot Sign" (`74-nomatch-s.png`) | :feature:sign · `RequestReviewScreen.kt:140-144` always composes `BottomActionBar`; `SignBar.kt:60-96` draws nothing in the matching branch. Skip the bar (or animate its size) while matching. |
| sign-18 | P3 | QA tooling | `tools/qa/ui.py logcat`; `ui.py dump` under load; `ui.py type` with long text | Tool works | `logcat` crashes (`TypeError: cannot use a string pattern on a bytes-like object`); `dump` silently prints the previous screen when `uiautomator dump` fails (stale `/sdcard/qa-ui.xml`); `input text` drops characters beyond ~20 per call | repo tooling · `tools/qa/ui.py:33-35` (`text=not capture or None` gives bytes), `:38-40` (delete the file before dumping, check the exit status), `:113-116` (chunk text, ~10 chars per `input text`). |

Already tracked, seen again (not counted): English-only Android strings in every language (App Lock row and footers,
Open Source "Distribution" / "Signing certificate" / "Signing engine source" / intro callout, "No Results for …") —
backlog B20; password text exposed in the accessibility dump — B1; harness biometric message "Use a wallet password to
continue" on the App Lock switch is fixture text (`FixtureBiometricAuthenticator.kt:22`).

## Verified working (no need to re-test)

- Import of the BIP39 reference phrase through the UI (Welcome › Import, 12 words detected as English, fingerprint
  73C5DA0A); second wallet via Wallets › + › Import Recovery Phrase.
- Scan home: structure, copy, ids (`signing.scan/paste/file`), "How signing works" rows, light/dark, Wallets "Scan"
  opens the scanner once (token consumed; selecting the tab by hand never opens it).
- Paste sheet: instruction, monospaced editor, Review Request disabled while blank, Paste from Clipboard replaces the
  text, multi-part UR one part per line, button pinned above the IME, Cancel/Back discard the text, sheet closes itself on
  success.
- Import File (system picker, any file type): `.psbt` binary → base64, UTF-8 text, 512 KB limit ("This file is larger
  than 512 KB."), non-UTF-8 ("Choose a PSBT file…"), truncated PSBT ("Invalid base64 bytes"), garbage/response UR/
  wallet-pairing UR ("Unsupported signing QR format" / "Unsupported signing UR type"), incomplete UR ("The request is
  incomplete. Import all QR frames."), picker cancel does nothing.
- Review page section order (summary → contract card → warnings → signing wallet(s) → details → request fingerprint in
  8-char upper groups), badges (Decoded / Partially decoded / Not decoded), token icon (USDT), NFT-free asset/counterparty
  boxes, compact and card ContractNotice (unlisted spender red, unlisted contract grey, known spender/known token amber),
  note "Change returns to your wallet", fee line, High risk pill, danger vs warning tone by HIGH RISK/UNLIMITED/HIGH FEE
  prefix, CopyableAddress, Details row styles, Reject without partials returns Home.
- Acknowledgement: shown for partially decoded/opaque/high risk with the right wording, checkbox role + checked state,
  Sign disabled until ticked, reset after background (Home → relaunch) and after each signature.
- Matching states: "Finding the matching wallet…", noMatch box with explanation + disabled "Cannot Sign".
- Authorize sheet: identity header, autofocus with keyboard, password semantics, wrong password → "The password is
  incorrect." with field cleared and refocused, IME Done signs, busy spinner, Back/Cancel consumed while busy, Back
  closes and wipes when idle.
- Multi-wallet 2-of-2 PSBT: "Signing Wallets", two task cards, "Each wallet signs…", "Signature 1 of 2" → "2 of 2",
  per-task password, "Signed" pill, Reject with a partial → "Discard partial signatures?" (Cancel keeps state).
- Signature result: header, QR (white plate in dark), animated 5-frame crypto-psbt QR at 250 ms with play/pause (label
  updates on pause), counter/progress, request card, Format line, Payload (JSON) with PSBT note, Export as File →
  `bittool-signer-signature.txt` via the system picker; exported EVM personal_sign, TRON TRX transfer and Solana legacy
  transfer responses equal the vectors' `responseFrames` byte for byte; Done returns Home without reopening the camera.
- Back on Scan/Activity/Settings roots goes to Wallets and keeps a review/result behind the badge (ARCHITECTURE §2.3).
- Scanner: forced dark, Close, title, hint, permission prompt, denied panel + Open Settings (opens App info), second
  request after one denial, one-time grant shows the camera preview, torch label toggles and resets after background,
  permanently-denied state shows the panel without prompting, Back closes.
- Activity empty state (light, dark, ar, font 2.0) with no Clear button.
- Settings root sections/order/ids, Theme dialog (System/Light/Dark, applied live), Language page (zh-Hans, ja, ar RTL,
  back to System), About values (1.0.0 (1), Network connections None), footer tile.
- Networks: Bitcoin static row, 10 networks with counts, loading spinner, registry footer, Known Public Contracts / All
  EVM networks (27, contracts only, no picker); network page tokens/contracts picker with unfiltered counts, search
  (shared query, clear button, no-results state), "%d dp" with "decimals" label, Copy Contract Address menu.
- Compatibility Formats and Wallets segments (24 wallets, badges, copy), Submit a Wallet (root route, tab kept), Contact
  Us, Terms of Use, Privacy Policy (apk texts: Android wording, contact blocks), Open Source (repository, copy → Copied,
  engine digest/size/registry/networking code, distribution, signing certificate, rebuild steps with version, notices
  incl. the Android file).
- Font scale 1.3 and 2.0 on Review, Scan home, Settings, Activity, Scanner (layout holds; see sign-04/-09/-13); restored
  to 1.0.
- No app crash or ANR in logcat during the tour (system ANRs were the overloaded emulator).

## Not tested and why

- Activity list/detail/re-review/fingerprint match/response QR sheet/export/Clear/retention: every receipt write fails in
  the harness (sign-01). Setting a device PIN to work around it is a security setting I am not permitted to change.
- Camera scanning of real QR codes, scan progress ring, protocol error + "Scan Again": the emulator camera cannot be fed.
- "Matching interrupted" + "Match Again": matching finished before a background could interrupt it.
- Predictive back animations: API 31 emulator (predictive back needs 33+).
- Performance/jank numbers: host load 17–64 and a system_server restart make timings meaningless (decode 5–270 s,
  sign 30–40 s observed); gfxinfo on the animated QR showed only redraws on frame change (31 frames / 8 s).
- App Lock enable/disable with real biometrics (harness fixture always fails); lock-screen flows belong to another tour.
- Visual review of the Paste and Authorize sheets (secure windows, sign-10); checked through accessibility bounds only.
- TalkBack speech (only accessibility-tree labels/states inspected), payload truncation over 30,000 characters,
  clipboard 120 s expiry, export failure path, landscape/tablet navigation rail.
