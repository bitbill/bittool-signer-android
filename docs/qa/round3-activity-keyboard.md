# QA round 3: tour "activity-keyboard" (in-app BIP39 keyboard, Activity on a device, round-2 and sign re-check)

Device: emulator-5554, AVD `Pixel_3a_API_31_arm64-v8a`, API 31, 1080×2220 at 440 dpi (about 393×807 dp), 3-button
navigation, Gboard as the system keyboard, no screen lock (keyguard never showed). Build: the installed
`com.bittool.signer.debug` 1.0.0-debug, installed 21:18:07 from the 21:16 `app-apk-debug.apk` (main 0bdb276: the
in-app keyboard 78b6d59, the lost-key recovery, the translations and the QA round-2 fixes). Every run went through
`tools/qa/ui.py launch` (UI-test harness). Screenshots: `build/qa/round3/activity-keyboard/NNN-name.png`, each with a
downscaled `NNN-name-s.png`. iOS paths are relative to `BitSigner/BitToolSigner/`, reference shots are in
`build/ios-reference/`. At the end the font scale is 1.0, auto-rotate is on (`user_rotation` 0), and the signer is on
Wallets in a harness run with one fixture wallet.

## Summary

**The in-app keyboard does what DECISIONS §11 asks.** While it is up, the phrase editor has no input-method connection.
`dumpsys input_method` shows `mInputShown=false`, and the app's last `StartInput` is `CHECK_FOCUS` with `inputType=0x0`
(a null editor). When focus moves from the name field (Gboard up, `inputType=0xa001`), Gboard goes away and the app's
next `StartInput` is again `0x0`.

These all worked on the device:
- **Import with the keyboard:** the reference phrase gave 73C5DA0A. A Spanish phrase typed without accents ("dragon …
  rebano … dueno … jamon …") was detected as "12 words · Español" and gave 1359CC37, which matches my own BIP32
  computation.
- **Keys:** the suggestion row (up to five "Insert …" chips, accented spellings such as "jamón", and the three hints),
  the letter preview, backspace (one tap deletes one character; holding it deletes 13 characters in 1 s and 51 in 2.5 s,
  then stops at empty), Paste at the caret, space and Done.
- **Back:** it closes the keyboard first, then opens the Discard dialog.
- **Switching keyboards:** globe → warning (Back keeps the keyboard) → Switch anyway → Gboard in incognito
  (`imeOptions=0x43000000`) with the "BitTool keyboard" chip → back to the in-app keyboard with no IME connection.
  The choice holds for the presentation, and the next import starts on the in-app keyboard again.
- **Chinese phrase:** committed through Gboard after the warning, "12 words · 简体中文", B2E0F94C (matches my
  computation).
- **Appearance:** light, dark, font 2.0 (labels capped) and Arabic (the keyboard and the chips stay left to right; the
  labels are translated).

**Landscape fails when the phone is rotated while the app is open** (activity-keyboard-01, P2). After the rotation the
keyboard keeps its portrait rows and "Continue" stays pinned, so the editor is not on screen at all: the phrase is typed
blind. Rotating back to portrait leaves the landscape layout in place, with "Continue" hidden and short rows. A cold
start in landscape lays out correctly. This points to a window size that is not updated when the activity handles
the configuration change itself.

**Activity works end to end. This is the first device tour of it.**
- **Signing:** 29 signatures over the tour (27 in one journal), across EVM (personal, EIP-1559, EIP-712, legacy
  Avalanche, USDT approve, USDT transfer, unknown call, Permit2), Bitcoin (BIP84, a 2-input PSBT as a file, base64 and a
  4-part UR, high fee, 2-of-2), TRON (transfer, message) and Solana (transfer, message, opaque). Requests came in through
  the system picker and through Paste.
- **List:** rows, logos (generic logo for chain 43114), sticky day header and footnote all match `light-30-activity.png`.
- **Detail:** each detail I opened decoded the request again with a matching fingerprint, and "Signed Request" matches
  `light-31c`.
- **Signature again:** the Show QR sheet matches `light-31d`. Its QR decodes to the published `responseFrames`. The
  file exported through the system picker is byte-identical to the vector (203 bytes).
- **Retention:** the 26th-newest record kept only its summary and showed the "25 most recent" note.
- **Deleting a wallet:** its records read "Deleted wallet" and keep fingerprint 73C5DA0A.
- **Clear Activity:** the confirmation closes when the app goes to the background, and confirming it leaves the empty
  state.
- **Unreadable journal:** the alert at launch, "Activity Unavailable" with no Clear, and "could not be saved" after a
  signature whose result still shows.
- **Not reachable:** the restart notice cannot be reached in the harness (activity-keyboard-07).

Every response QR I decoded matches the published vectors: evm-personal, evm-eip1559, evm-legacy-multibyte-v,
bitcoin-bip84-psbt (a frame of 3), tron-trx-transfer and solana-legacy-transfer. So does the Activity re-display of
evm-personal.

**Round-2 regressions and round-1 sign fixes:**
- regression-01..14: 13 are fixed on the device. regression-01 is fixed for the default path, because the in-app
  keyboard has no IME at all. With the system keyboard chosen, Gboard still shows its candidate strip and a composing
  underline (activity-keyboard-02).
- sign-01..18: 16 are fixed on the device. sign-08 has its code in place but I could not see it (the decode finished
  too fast). sign-09 was not tested (camera permission).
- No crash or ANR of `com.bittool.signer.debug` in logcat for the whole tour (21:20–23:19). The only `FATAL
  EXCEPTION`s are uiautomator's own dumper (`AccessibilityNodeInfoDumper.childNafCheck` NPE).

Counts: **P0 0 · P1 0 · P2 1 · P3 6** (two of the P3s are QA tooling).

Test conditions: host load average 17–30 for much of the tour, because other agents were building. Some steps were
slow: Solana matching took about 3 minutes once, and a signature cycle took 41–308 s. Screenshots sometimes lagged a
frame behind, and I retook those. None of these timings is a finding.

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (screenshot) | Suspected owner (file:line) |
|---|---|---|---|---|---|---|
| activity-keyboard-01 | P2 | Import step 1 with the in-app keyboard, after a rotation | Portrait: Import → tap the editor. Rotate to landscape (`user_rotation 1`), tap the editor, type "abandon ab". Rotate back to portrait | Landscape: "Continue" steps aside, the rows get shorter, and the editor stays above the keyboard (FormSteps.kt KDoc :183-191, Bip39Keyboard.kt:149 "get shorter on short windows (landscape)"). Portrait: full rows, "Continue" pinned. Android-only, so no iOS counterpart. DECISIONS §7 quality bar | After the rotation to landscape, the keyboard keeps its portrait rows (679 px tall), "Continue" stays pinned under the top bar, and **the editor is not on screen**: the phrase is typed blind, only the suggestion chips show what is typed (`027-land-kb.png`, `028-land-kb-typing.png`). A cold start in landscape is correct: editor visible, short rows, "Continue" aside (`029-land-cold-kb.png`). Rotating that run back to portrait keeps the landscape layout: short rows, "Continue" hidden, also after Done and refocus (`030-portrait-after-land.png`, `031-portrait-after-land-refocus.png`) | The layout reads the window height from `LocalWindowInfo.current.containerDpSize`: core:ui `keyboard/Bip39Keyboard.kt:438` (`rememberKeyboardMetrics`), feature:setup `flow/FormSteps.kt:212` (`shortWindow`). It is not updated when `MainActivity` handles rotation itself (`app/src/main/AndroidManifest.xml:50`, `configChanges="orientation\|screenSize\|…"`). Use a size that follows configuration changes (`LocalConfiguration.current.screenHeightDp`, `currentWindowSize()`, or `BoxWithConstraints`). Same API in `flow/PushedSteps.kt:99` (Security Tips placement) |
| activity-keyboard-02 | P3 | Import step 1, system keyboard chosen | Import → editor → globe → Switch anyway → type "aban" on Gboard's keys | No dictionary suggestions on the phrase (DECISIONS §6; iOS `.autocorrectionDisabled()`, `Features/Setup/RecoveryPhraseViews.swift:198`). This is what QA regression-01 asked for | Gboard (incognito) shows its candidate strip "aboutaban \| about an \| about a an" and underlines the word being composed, one tap from text that is not a BIP39 word. The editor's `inputType` is `0xa0001` (`TYPE_TEXT_FLAG_NO_SUGGESTIONS` is set), and this Gboard ignores it (`103-system-kb-typing.png`). The default in-app keyboard is not affected, and the switch warning names the risk, hence P3 | feature:setup `phrase/RecoveryPhraseInput.kt:162` (`NoSuggestions`) and `:205-240` (`applyNoSuggestions`): the flag is a hint that Gboard does not honour. Make the BIP39 suggestion bar the only candidates in Latin scripts, e.g. by switching to `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` while the detected language is Latin and keeping the plain class for CJK. Otherwise accept and document it |
| activity-keyboard-03 | P3 | Import step 1 at font scale 2.0 with an error | Font 2.0 → Import → type "dragon madera jamón xyzzy" → Continue → tap the editor | Status line and editor stay visible while the person fixes the error (FormSteps.kt:311-312, QA setup-02) | The error callout (495 px), "Continue" and the keyboard leave about 145 dp. The status line ("Unknown: “xyzzy”") is under the top bar and the editor shows two lines (`024-dark-f2-kb.png`, `024a-dark-f2-before.png`). iOS pins the error in the same footer (`Features/Setup/WalletSetupFlow.swift:192-200`), so this is polish, not parity. Same class as regression-08 (fixed) | feature:setup `flow/FormSteps.kt:284-300` (`ErrorCallout` in the footer above the in-app keyboard). At accessibility sizes, cap the callout to a few lines or fold it into the status line while the keyboard is up |
| activity-keyboard-04 | P3 | Activity › Signature › Output | Open any record and scroll to Output | "Show QR Code" and "Export as File" are list buttons in the accent colour (`Features/Activity/ActivityTab.swift:246-256`, `light-31b-activity-input-output.png`). The wallet page got the same treatment in round 2 (regression-14) | Both rows have label-coloured titles (black or white) with accent icons, so they read as navigation rows (`067-detail-output.png`, dark `134-dark-activity-io.png`) | feature:activity `ActivityDetailScreen.kt:329-347` (`NavRow(showChevron = false)`). Use the accent action row that feature:wallets keeps privately (`detail/WalletDetailScreen.kt:454` `ActionRow`), moved to core:designsystem |
| activity-keyboard-05 | P3 | In-app keyboard, Paste key | With nothing pasteable on the clipboard (the app's own Paste menu item was absent too), tap the Paste key | The key looks unavailable, or says why nothing happens (it has "the editor's Paste menu semantics", `Bip39KeyboardEdits.kt:67-71`, and that menu hides Paste) | The key looks enabled and plays its press feedback, but nothing is inserted and no hint is shown (`101-phrase-text-menu.png`, `102-phrase-insert-menu.png`; field text unchanged) | core:ui `keyboard/Bip39Keyboard.kt:240-250` (paste key): dim it while `ClipboardManager.hasPrimaryClip()`/`hasText()` is false (no clipboard read, so no Android 12 toast) |
| activity-keyboard-06 | P3 | QA harness (tooling) | `ui.py launch --locale ar`, then `launch --keep` (no locale), then `launch --keep --locale en-US` and rotate | Each launch shows the locale it asks for (en-US by default, `ui.py` header and `DebugUiTestHarness.kt:74`) | `launch --keep` without a locale opened in Arabic (`026-land-import.png`). With `--locale en-US` the app opened in English but switched to Arabic at the next configuration change (`029` → `030`). On API < 33, AppCompat's stored per-app locale (`autoStoreLocales`) is applied asynchronously and overrides the harness's `setApplicationLocales`. A tour can silently run in the previous run's language | app (debug) `harness/DebugUiTestHarness.kt:164-169` (`applyLocale`) with `app/src/main/AndroidManifest.xml:58-63` (`AppLocalesMetadataHolderService` `autoStoreLocales=true`). Clear or overwrite AppCompat's stored locale file during harness configuration, or apply the locale after AppCompat's sync |
| activity-keyboard-07 | P3 | QA harness: Activity restart notice (tooling) | Brief: check the restart notice. `launch --unreadable-journal` (fresh with `--fixtures 1`, and with `--keep`) | A harness path reaches `SignerState.activityRestarted` ("Activity Started Again", `ActivityScreen.kt:146-160`, QA L1), so the notice and its "OK" can be checked on a device and in e2e | Only "Activity Unavailable" is reachable: the alert at launch, no Clear, and "could not be saved" after signing (`093`–`097`). `FixtureJournalCipher` does not override `discardLostKey()`, so `startOverIfKeyLost()` never fires and the notice never appears. Related to backlog O2 | app (debug) `harness/FixtureJournalCipher.kt` (no `discardLostKey`; the default `false` is in core:crypto `vault/DeviceKeyStore.kt:73`). Add e.g. `bts.uitest.loseJournalKey`, which deletes the fixture journal key and makes `discardLostKey()` return true, plus a `ui.py --lose-journal-key` |

Not counted (decided, parity, or tracked elsewhere, all confirmed on the device):
- The unreadable journal is a dead end. "Activity Unavailable" offers no way to start again (DECISIONS §10, iOS
  parity).
- Activity detail section headers ("Request fingerprint", "Input", "Output") are accent green, where iOS uses grey. This
  is the design-system rule for every `GroupedSection` (`GroupedSection.kt:94`); only the list's day header was moved to
  grey in round 2.
- A Bitcoin record's Input shows only Action and Network fee: the engine's `display` has no `amount`/`counterparty` for
  PSBTs. iOS is the same (`ActivityTab.swift:218-240`).
- The unlimited approve's summary is engine English with the raw maximum value, in the row and the detail header, as on
  iOS (engine text).
- The animated QR's accessibility label stays "part 1 of 5" while the counter runs (the label updates on pause, as
  in round 1).
- Arabic "2 كلمة" (no dual form) is the same string as iOS `ar.lproj` (`%d كلمة`).

## Round-2 regression fixes (docs/qa/round2-regression.md) on the device

| ID | Status | Evidence |
|---|---|---|
| regression-01 | **fixed for the default path; open with the system keyboard** | In-app keyboard: no IME connection at all (`StartInput … inputType=0x0`, `mInputShown=false`). With the system keyboard Gboard still suggests: activity-keyboard-02 |
| regression-02 | fixed | Recovery Phrase unlocked with Enter: no focus ring on the cross (`086-recovery-after-enter.png`) |
| regression-03 | fixed | TRON index 12 + Enter: no ring on Back. The outlined TRON chip is its selected style, the same after a touch (`115-tron-index-enter.png`, `116a-tron-chip-selected-crop.png`) |
| regression-04 | fixed | Rename → Cancel at the end of the page: Rename stays at y 1249–1403, Delete at 1458 (`084-detail-bottom.png`, `085-after-rename-cancel.png`) |
| regression-05 | fixed | Paste "garbage-request" → keyboard put away, the text and the error both visible (`081-paste-garbage.png`) |
| regression-06 | fixed | Review › Signing Wallet text is `'Fingerprint 73C5DA0A'` with no isolates (dump), also on the Authorize sheet (`079-authorize-kb.png`) |
| regression-07 | fixed | Welcome and onboarding symbols grow with the text at 2.0: onboarding points 24×20 dp at 1.0 → 29×34 dp at 2.0 (`120-f2-welcome.png`, `121-f2-onboarding-1.png`) |
| regression-08 | fixed | Backup at 2.0: the footer holds only the button, Security Tips is in the page, two word rows visible (`123-f2-backup-revealed.png`) |
| regression-09 | fixed | Tapping the fingerprint shows "Copied"; tapping "Added Oct 21, 2026" copies nothing (`083a-tap-fingerprint-copied.png`, `083b-tap-date-no-copy.png`) |
| regression-10 | fixed | Arabic Wallets list: `البصمة ⁨73C5DA0A⁩`, value in the monospaced face (`110-ar-wallets.png`, `110a-ar-fingerprint-crop.png`) |
| regression-11 | fixed | ja onboarding page 3 fully Japanese (`124-ja-onboarding-3.png`) |
| regression-12 | fixed (ja) | Brightness capsule "標準", desc "画面の明るさ" (`114-ja-pair.png`). Arabic not re-checked |
| regression-13 | fixed (API 31) | ja Create: "新しい / リカバリーフレーズを作成します。", "「25 番目の単語」を / 追加します。" (`125-ja-create.png`) |
| regression-14 | fixed | Security, Rename and Delete rows are accent list buttons without chevrons (`084-detail-bottom.png`) |

## Round-1 sign fixes (docs/qa/round1-sign.md), quick device check

| ID | Status | Evidence |
|---|---|---|
| sign-01 | fixed | 27 receipts in one harness journal, listed and opened (`060`–`063`, `072`) |
| sign-02 | fixed | Arabic review headline "…public interoperability test." keeps its period at the end (`111-ar-review-personal.png`) |
| sign-03 | fixed | ja step 4 "返送" (`113-ja-scan-how.png`) |
| sign-04 | fixed (B32) | Font 2.0 EIP-712: the acknowledgement moves into the list, and the bar shows "Check the box at the end of the review to sign." Tapping the hint brings the checkbox into view (`117`, `118`). Bar plus tab bar still take about 42 % of the height |
| sign-05 | fixed | No "Failed to find provider info"/"Timeout waiting for provider" naming the app. The EmojiCompat initializer is removed (`AndroidManifest.xml:66-77`). The only font-provider queries (22:25, "Google Sans") came while DocumentsUI was open |
| sign-06 | fixed | See regression-05 (`081`) |
| sign-07 | fixed | `binary.bin` → "Choose a PSBT file…" scrolled into view above the tab bar (`126-import-binary-error.png`) |
| sign-08 | code present, not observed | Spinner on the Import File tile (`ScanHomeScreen.kt:282-305`); at the moment of the test the decode finished before the first frame (`127-import-busy.png` already shows the review) |
| sign-09 | not tested | Needs camera permission denial (see "Not tested") |
| sign-10 | fixed | Paste, Authorize, QR and Clear sheets/dialogs are captured (`079`, `081`, `068`, `090`) |
| sign-11 | fixed | Authorize sheet is content-sized: 538–1403 with the keyboard, 1223–2088 without (`079`, `080`) |
| sign-12 | fixed | Language subtitles "Simplified Chinese", "Traditional Chinese", "Japanese" (`129-language.png`) |
| sign-13 | fixed | ja tiles "リクエスト / を貼り付け" and icons at the same height (`112-ja-scan-home.png`) |
| sign-14 | fixed | Result "Payload (JSON)" stays expanded after Wallets → Scan (`128-payload-kept.png`) |
| sign-15 | fixed | Long-press on USDT: pressed highlight, and the menu has its corner at the finger, flipped near the edge (`130-lp1-…`, `130-lp2-…`) |
| sign-16 | fixed | Notices reflow into paragraphs, separators are dividers (`131-notices.png`) |
| sign-17 | fixed | While "Finding the matching wallet…" ran (about 3 min, Solana transfer under load) there was no empty bar above the tab bar |
| sign-18 | fixed | `ui.py logcat` works, dumps fail loudly (seen several times), typing is chunked (long URs pasted correctly) |

## Verified working

**In-app BIP39 keyboard**
- **No IME while it is up:** `dumpsys input_method` (`ime-dumpsys-inapp.txt`): `mInputShown=false`, the app's last
  `StartInput` is `CHECK_FOCUS`/`WINDOW_FOCUS_GAIN` with `inputType=0x0`, and `mInputEditorInfo inputType=0x0`. It stays
  like this after editor taps, after moving from the name field (Gboard closes), after "BitTool keyboard", after
  Background → return (`104`), and through 12-word phrases typed at the caret.
- **Imports, fingerprints matching my offline BIP32:**
  - English reference phrase (keys, Insert chips and Paste) → **73C5DA0A** (`013`).
  - Spanish without accents → "12 words · Español" → **1359CC37** (`014`, `015`).
  - Chinese via Gboard → **B2E0F94C** (`018`, `019`).
- **Suggestion row:** "Type a word to see suggestions", up to five chips in a scrolling row with "Insert …" names and
  `suggestion.<word>` tags (`004`), accented canonical words ("jamón", `021`), "No matching word" (`022`), "Each
  Chinese word is one character", and Insert adding a trailing space.
- **Keys:**
  - Backspace: one tap deletes one character; held it repeats with acceleration (13 characters at 1 s, 51 at 2.5 s)
    and stops at empty (`006`).
  - Paste inserts at the caret, exactly as copied (twice in a row, `009`).
  - Space and Done work; Done closes the keyboard and leaves the page as it was.
  - The letter preview shows inside the window while a key is held (`100`).
  - The whole cell is the touch target; the key fires on release.
- **Back:** closes the keyboard first (`011`), then the Discard dialog (`023`).
- **Globe:** the warning "Switch to the system keyboard?" (`016`). Back or "Keep BitTool keyboard" keeps the in-app
  keyboard. "Switch anyway" brings up Gboard with incognito, `inputType=0xa0001`, `imeOptions=0x43000000`, the
  suggestion bar with "BitTool keyboard" chip, the hint, and Done (`017`). The chip switches back with no IME (`019`).
  The choice persists after Done, and a new import starts on the in-app keyboard.
- **Phrase editor menu:** no Copy, Cut or Share; only "Select all" (`101`, `102`).
- **Error callout:** the length error ("it has 4 words", iOS order) stays above the keyboard (`022`).
- **Appearance:**
  - Light (`002`) and dark (`020`, `022`); the tray runs under the navigation bar.
  - Font 2.0: labels capped, rows unchanged (`024`).
  - Arabic: QWERTY stays left to right, paste on the left and backspace on the right, translated "لوحة مفاتيح النظام"
    and "تم", chip names "إدراج abandon" (`025`).
  - Cold landscape correct (`029`).
- **Wallet name:** the field caps at 40 characters, as iOS `WalletSetupFlow.swift:188`.

**Activity**
- **Signing:**
  - 29 signatures in total:
    - 27 in the first harness journal: 26 through the picker and Paste, plus one with the Authorize sheet's IME Enter.
    - 1 in the unreadable-journal run: the personal_sign that hit "could not be saved".
    - 1 in the fresh fixture run: the 4-part Bitcoin UR (Payload JSON check).
  - Requests came through the system picker (DocumentsUI: Downloads/qa3req, `.txt`, `.psbt` binary, base64 and a
    4-part UR) and through Paste (single-line and two-line URs).
  - No "Unable to Continue" in a readable journal.
- **List:**
  - Grouped under "Wednesday, Oct 21, 2026" (sticky grey header), newest first.
  - Rows: 36 dp logo, 2-line title, "summary · wallet" on one line, h:mm a time, chevron.
  - Generic logo for Avalanche (43114); footnote at the end (`060`–`063`).
  - Matches `light-30-activity.png`. Dark: `132`.
- **Detail:**
  - Header logo, title, summary and the "Signature created" pill, then Date "Oct 21, 2026, 9:46:50 PM", Network,
    Wallet, Fingerprint and Risk ("High" or "Warnings shown"), then the request fingerprint in 8-character groups and
    its footer (`064`, `133`).
  - Fresh re-review: personal_sign, EIP-1559, approve, TRON, BTC and USDT transfer all decoded again with a matching
    fingerprint.
  - Essentials: Action, Amount ("Unlimited USDT"), Spender ("0x123456…345678"), Network fee ("0.0024 ETH"), and To for
    TRON (`074`–`076`).
  - "All Decoded Fields" opens "Signed Request" like `light-31c` (`066`).
- **Output:**
  - Show QR Code opens a sheet like `light-31d`; its QR decodes to the published `responseFrames` (`068`).
  - Animated 5-frame crypto-psbt with Pause in dark (`135`).
  - Export as File through the system picker writes `bittool-signer-signature.txt`, 203 bytes, **byte-identical to the
    vector** (`exported/evm-personal-export.txt`).
  - The Signature Payload and Request Payload disclosures show the raw text with "Copy Payload". The copied text has no
    zero-width break characters, which are display-only (`069`, `136`).
- **Retention:** with 26 records the oldest has no Input or Output and shows "The request and signature are kept for
  the 25 most recent signatures…" (`073`).
- **Deleted wallet:** after "Reference Wallet" was deleted, the rows read "… · Deleted wallet". The detail shows Wallet
  "Deleted wallet" and Fingerprint 73C5DA0A, and the re-review still works (`087`, `088`).
- **Persistence:** 27 records survive a cold restart (`089`).
- **Clear Activity:**
  - "Clear all activity?" with "This only removes the local records…" and a red "Clear Activity" (`090`).
  - The dialog closes on Home → return (`091`).
  - Confirming leaves "No Signatures Yet" with no Clear (`092`).
- **Unreadable journal:**
  - "Unable to Continue — The local activity record could not be read." at launch (`093`).
  - "Activity Unavailable" with no Clear (`094`).
  - Signing shows "…could not be saved. Any completed signature remains available.", and the result QR is still the
    vector (`096`, `097`).
  - Activity stays unavailable afterwards.

**Elsewhere (seen while touring)**
- **Authorize sheet:** content-sized above the keyboard, password `inputType=0x81` with the incognito flag, signing with
  IME Enter (`079`, `080`).
- **Wallet detail:** fingerprint copy (`083a`).
- **Show Recovery Phrase:** concealed after unlock, no stray focus (`086`).
- **Delete wallet flow:** confirmation switch → password dialog with tags → removed.
- **Network page:** 1008 tokens / 329 contracts; the long-press menu (`130`).
- **Notices:** reflowed (`131`).
- **Language page:** cleaned names (`129`).

## Not tested, and why

- **Day boundary in the Activity list:** every record is from one day. Changing the device date or time zone is a
  system setting I may not change.
- **Predictive back gesture on the keyboard (sinking tray):** API 31 has no predictive back.
- **API 34+ `setAccessibilityDataSensitive` on the keyboard and editor:** API 31 device.
- **Haptics and TalkBack speech:** emulator. Only accessibility names and roles were checked in the tree.
- **A Japanese or Korean phrase:** the IME has only English. Adding Gboard languages needs downloads, so the CJK path
  was covered with the Chinese phrase committed through Gboard's clipboard chip. The Chinese text reached the clipboard
  through HTML Viewer from a local file (`/sdcard/Download/qa3/zh.html`).
- **sign-09, scanner with camera denied:** changing the app's camera permission state; the virtual camera shows no
  QR codes.
- **sign-08 progress spinner:** decode finished within a frame at test time.
- **Restart notice ("Activity Started Again"):** not reachable through the harness (activity-keyboard-07). It needs a
  real screen-lock removal (backlog O1).
- **Export failure path, 100-record cap, payload size limits (32 KiB / 2,048 frames / 2 MiB):** need more than 100
  signatures or crafted requests. This was not attempted at 1–5 minutes per signature under load.
- **Accessibility Copy/Cut actions on the phrase editor (final audit A01):** adb cannot fire accessibility actions.
  The text menu has no Copy or Cut.

## Environment notes

- **Shared scratchpad:** an agent on emulator-5556 shares this session's scratchpad and overwrote a helper named
  `kb.py` mid-tour (its copy targets 5556 via `a.sh`). I moved all my helpers to
  `/private/tmp/claude-501/-Users-tanguopeng-Documents-bitool-signer-android/1bc3e92b-171d-46e2-bf4f-c5850df58c91/scratchpad/ak5554/`.
  They are `kb5554.py` (keys, IME state), `sig5554.py` / `batch5554.py` (sign cycles via picker or Paste),
  `detail5554.py`, `bip.py` (offline fingerprints), `qrdecode` (CoreImage) and `cmpvec.py` (QR against vectors). Each
  pins `-s emulator-5554` / `ANDROID_SERIAL`. No input went to another device.
- **Files left on the device:** request files in `/sdcard/Download/qa3req/` and `zh.html` plus the exported signature
  in `/sdcard/Download/qa3/`.
- **Settings changed and restored:** font scale (back to 1.0) and rotation (`accelerometer_rotation` 1,
  `user_rotation` 0).
- **Security settings:** no device PIN, lock or fingerprint was touched.
- **Signer run IDs:** the tour used several harness runs: a reset run for the imports and Activity, a fresh
  `--fixtures 1 --unreadable-journal` run, a fresh run for Welcome/onboarding at 2.0 and in Japanese, and a fresh
  `--fixtures 1` run at the end.
