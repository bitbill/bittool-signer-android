# QA round 1 — tour "setup" (first run and wallet setup)

Device: emulator-5554 (API 31, 1080×2220, 440 dpi ≈ 393×807 dp, 3-button nav, Gboard), debug build
`com.bittool.signer.debug` from `main` (a0797c5). Driven with `tools/qa/ui.py`. Screenshots:
`build/qa/round1/setup/` (each `NN-name.png` has a downscaled `NN-name-s.png`). iOS references: `build/ios-reference/`
(light and dark). iOS paths are relative to `BitSigner/BitToolSigner/`. Font scale restored to 1.0.

## Summary

The setup flow is functionally complete and matches iOS on every rule that decides which wallet you get: all BIP39
vectors I fed in derived the expected master fingerprint (English reference 73C5DA0A, zh-Hans, es without accents,
ja with U+3000, it/es ambiguity both ways, 15 words, and two freshly created phrases recomputed offline from the words
on screen). The state machine (same phrase on re-create, new phrase on a new length, QR reveal not counting, verify
reset on a wrong answer, background clearing passwords and placements and re-concealing, Reset Password mode on a
duplicate, Pair → Connect) behaves as the spec says. No app crash.

What keeps it from flagship quality is keyboard handling and a few visual details: with the keyboard open, the focused
field slides under the bottom bar (you cannot see the line you type in, or the "Confirm passphrase" field). In light
mode the word-suggestion chips have no visible background. IME "Next" on the passphrase field lands on the eye button.
At font scale 2.0 the status line is cut off without an ellipsis. The rest are polish items.

Counts: **P0 0 · P1 2 · P2 4 · P3 9** (two of these were already in `docs/qa/backlog.md` and are marked so).

The test environment was degraded: other agents ran instrumented tests on the same emulator (focus stolen, typed text
landing in their activity, `UiAutomationService already registered`). Load average reached 68, and system_server
restarted once (08:13). Three "BitTool Signer isn't responding" dialogs appeared during that load. System processes ANR'd
at the same time, and a jdb dump showed the app's main thread idle in `nativePollOnce` straight afterwards, so I do not
count them against the app (see "Could not test").

## Issues

| ID | Sev | Screen | Steps | Expected (iOS) | Actual (screenshot) | Suspected owner (file:line) |
|---|---|---|---|---|---|---|
| setup-01 | P1 | Password step, passphrase fields (also every `PasswordField`) | Type a wallet password or passphrase (masked), then `ui.py dump` | Secure text is not readable by accessibility services (iOS `SecureField`; gaps G5) | The typed password and passphrase are in the accessibility tree in plain text (`'Abcdefgh1234-xyz' \| Wallet password`, `'TREZOR' \| BIP39 passphrase` while masked). **Same as backlog B1**: reproduced on device | core:ui `core/ui/src/main/kotlin/com/bittool/signer/core/ui/password/PasswordField.kt:113-131` (`BasicSecureTextField` semantics; needs password semantics / sensitive data) |
| setup-02 | P1 | Import step 1 (editor), Create step 1 (passphrase), Password step | Import: type 3 lines of words. Create: switch the passphrase on and tap "Confirm passphrase". Password: type in "Wallet password" | The focused field stays fully visible above the bar that rises with the keyboard (`light-05-import-phrase.png`: all 3 lines visible; `WalletSetupFlow.swift:107-119`) | The bar grows with the IME but the scroll content is not brought back into view. The editor's caret line is cut by the bar (`12-import-ab.png`, `13-import-complete.png`, `37-japanese.png`). With an error Callout only 2 lines of the editor are left (`39-unknown-status.png`). "BIP39 passphrase" / "Confirm passphrase" end up half under the bar (`49-state.png`, `50-confirm-focused.png`). The requirements checklist is hidden while typing (`16-pw-weak.png`) | feature:setup `feature/setup/src/main/kotlin/com/bittool/signer/feature/setup/ui/SetupUi.kt:240-260` (Scaffold `bottomBar` + `verticalScroll(padding)`: nothing re-runs bring-into-view when the bar's IME padding grows); core:designsystem `BottomActionBar.kt:70` (`navigationBars.union(ime)` inside the bar) |
| setup-03 | P2 | Import step 1, suggestion row (light mode) | Light theme → Import → type "…ab" | Chips are gray capsules (`Palette.raised` on the keyboard toolbar, `RecoveryPhraseViews.swift:295`; `light-05-import-phrase.png`) | Chips have no visible background: `raised` (#F2F2F7) equals the bar's page colour, so the words read as plain text with no tap affordance (`12-import-ab.png`; dark mode is fine, `82-dark-import-typing.png`) | feature:setup `phrase/RecoveryPhraseInput.kt:227` (`.background(colors.raised)`) on a `page` bar; colours `core/designsystem/.../theme/SignerColors.kt:114,116`. Use `card`/`fill` for the chip in light mode |
| setup-04 | P2 | Create/Import step 1, BIP39 passphrase | Passphrase on → type in "BIP39 passphrase" → IME Next / Enter | Focus moves to "Confirm passphrase" (`WalletSetupFlow.swift:263`, `onSubmit { focus = .confirmation }`) | Focus moves to the eye (reveal) IconButton and the keyboard hides (`48-passphrase-next.png`, focus ring on the eye). Typing then continues into the first field ("TREZORTREZOX"). The wallet password pair (not revealable) works | core:ui `PasswordField.kt:127-130` (`performDefaultAction()` = `moveFocus(Next)`), next focusable is the eye `IconButton` at `PasswordField.kt:165`. Make the eye non-focusable for Next traversal (`focusProperties { canFocus = false }`) or move focus to the confirmation explicitly |
| setup-05 | P2 | Import step 1, status line | Font scale 2.0 → Import | One line shrinking to 80 %, stacked under the label when it does not fit (`RecoveryPhraseViews.swift:234-238`), never cut off silently | Reads "12–24 words · any BIP39": "language" is dropped with no ellipsis while space is left on the line (`100-f2-import.png`) | feature:setup `phrase/RecoveryPhraseInput.kt:156-163` (`BasicText(maxLines = 1)` with soft wrap, default `Clip` overflow, Row child not width-bounded so `autoSize` does not take effect). Allow 2 lines when stacked, or `softWrap = false` + ellipsis |
| setup-06 | P2 | Onboarding page 3 (all 20 non-English languages) | `--locale ja` → Create → page 3 | Fully translated page | Body "For the strongest isolation, use a dedicated phone…" and point "BitTool Signer has no internet permission…" are English in Japanese (`131-ja-onboarding-3.png`). **Same as backlog B20**: confirmed on device | core:l10n `core/l10n/src/main/res/values/strings_onboarding.xml:9,14` (no `values-*/strings_onboarding.xml`) |
| setup-07 | P3 | Import step 1 (RTL) | `--locale ar` → Import → type words | Latin words in natural (left) alignment, as UITextView does for LTR paragraphs | The LTR words sit flush right with a ragged left edge, and the caret starts at the right (`126-ar-import-typing.png`, `127-ar-import-error.png`) | feature:setup `phrase/RecoveryPhraseInput.kt:93` (`ltr()` sets `textDirection` only; `TextAlign.Start` follows the RTL layout). Set `textAlign = TextAlign.Left` or provide `LocalLayoutDirection Ltr` for the field (also for the placeholder) |
| setup-08 | P3 | Password step (duplicate phrase) | Import the phrase of an existing wallet → Continue | iOS also decides in `.task` (`WalletSetupFlow.swift:398`) | For about 1 s the normal "Set a Password" page and its "Import Wallet" button show, then the title, message, button and identity block flip to Reset mode (dump sequence: "Set a Password" → "Reset Password"; `26-reset-mode.png`). A fast tap reaches `createWallet`, which refuses with the duplicate error | feature:setup `flow/WalletSetupViewModel.kt:441-462` + `flow/PushedSteps.kt:210-213`. Hold the action (busy or neutral) until the duplicate check answers |
| setup-09 | P3 | Password step while saving | Valid passwords → "Import Wallet" | Back button hidden, title stays centred (`WalletSetupFlow.swift:397`) | The navigation icon is removed, so the "Password" title jumps about 40 dp to the start while busy and back afterwards (`21-pw-filled-enter.png` → `22-importing-busy.png`) | feature:setup `flow/PushedSteps.kt:220` (`SetupNavigation.None` while busy). Keep the slot (disabled or invisible icon) |
| setup-10 | P3 | Import suggestion row | Type "ab" | Chips scroll under the bar, then a spacer and a separate "Done" (`WalletSetupFlow.swift:209-214`) | The last chip is cut mid-word directly against "Done" with no gap or fade ("abou\|Done", `12-import-ab.png`, `82-dark-import-typing.png`, `126-ar-import-typing.png`) | feature:setup `phrase/RecoveryPhraseInput.kt:192-206` (LazyRow + `TextAction` in one Row; add an end gap or fade edge) |
| setup-11 | P3 | Done step hero | Finish any setup | `checkmark.seal.fill` at `.font(size: 64×0.62)` (`Design/DesignSystem.swift:225`) fills about 45 pt (`light-07-imported.png`) | Glyph about 36 dp, visibly smaller inside the same circles (`23-imported.png`) | core:designsystem `component/Content.kt:164-170` (Material icon box = size×0.62, and the 24-grid icon has padding; scale the box by about 1.2) |
| setup-12 | P3 | Welcome, all primary/secondary buttons | Font scale 2.0 | Symbols scale with Dynamic Type | Leading icons stay 20 dp next to text at double size (`90-f2-welcome.png`: "+", download glyph) | core:designsystem `component/Buttons.kt:156` (fixed `Modifier.size(20.dp)`; size from the label's font size as `iconSizeFor` does in setup) |
| setup-13 | P3 | Welcome | Fresh launch, font 1.0, 393×807 dp | Whole page fits above the bar (`light-01-welcome.png`, `dark-01-welcome.png`) | The network logo row is cut by the bar hairline and the network names are hidden until you scroll: the first impression of the app (`01-welcome-light.png`, `80-dark-welcome.png`) | feature:onboarding `WelcomeScreen.kt:110-124` (fixed top paddings 32/28/28/24 + `SpaceEvenly`; no compact-height rule). Shrink the gaps or the hero when the content exceeds the viewport |
| setup-14 | P3 | Verify step at font 2.0 | Font 2.0 → Create → reveal → "I've Written Them Down" | First blank reachable | The footer (chips + Confirm + "Show the Phrase Again") takes about 45 % of the height and the board starts below the fold. The active blank is centred only after the first chip tap (`98-f2-verify.png` → `99-f2-verify-after-chip.png`), so the first chip fills a blank you cannot see | feature:setup `flow/PushedSteps.kt:151-154` (centering only on change, iOS parity). Also centre on first appearance when the active blank is off screen |
| setup-15 | P3 | Done / large titles (ja) | `--locale ja` → finish create | Line breaks at phrase boundaries | "ウォレットを作成しま / した" (`137-ja-done.png`). Same in "上級者向け。「25 番目の単語」を追加しま / す。" (`132-ja-create.png`) | feature:setup `flow/PushedSteps.kt:346` and `SetupUi.kt:285` (`largeTitle` without `LineBreak.Heading` / phrase-based breaking on API 33+) |

## Verified working (do not re-test)

- **Welcome**: copy and order match iOS, light and dark. "Terms of Use" / "Privacy Policy" sheets open, scroll to the
  end, close with "Done" and with system Back, and the text is selectable. System Back on Welcome leaves the app.
- **Onboarding**: 3 pages with iOS copy, no Skip. Continue / Get Started, swipe, the "Back" action and system Back on
  pages 2–3 go back a page. Back on page 1 and "Cancel" close setup without setting the flag, so the next Create shows it
  again. After Get Started it is never shown again: not on the next Create from Welcome, not from Wallets › Add. The
  intent is kept (Import → "Step 1 of 2" Import; Create → "Step 1 of 4"). TalkBack reads "Page n of 3".
- **Create step 1**: default name "My Wallet", then "Wallet 2…" when wallets exist. The 40-grapheme cut works, a blank
  or whitespace-only name disables Create, and the keyboard capitalises words. 24/12 segmented control, 24 is the
  default. Passphrase switch with reveal/hide eyes (`Show secret`/`Hide secret`); a mismatched or empty passphrase
  disables Create, a matching one enables it.
- **Backup**: concealed placeholder (word count only), tap to reveal, "Hide Recovery Phrase". Switching Words ↔ QR
  re-conceals. Revealing the QR does **not** enable "I've Written Them Down"; revealing the words or the cards does. QR
  section texts, the red footnote and the passphrase warning ("It contains the 12 words only…") are present. Pressing
  Create again keeps the same phrase; switching 12 → 24 generates a new one ("Write these 24 words…").
- **Recovery Cards** (secure sheet; checked through the accessibility tree): 12-word and 15-word (saved-wallet sheet)
  weak intro plus the exact warning "With a 12-word phrase each card shows 8 of the 12 words, and the other 4…" / "…15-word
  … 10 of the 15 … other 5…". 24 words: the non-weak intro and no warning. Passphrase warning shown. Card 1 leaves out
  1, 6, 8, 10 (12 words) and 1, 6, 8, 10, 15, 17, 19, 24 (24 words). Cell labels read "Word n, word" / "Word n, not on this
  card". Closing the sheet re-conceals the words.
- **Security Tips**: all 8 tips in iOS order with iOS copy; opens at half height and drags to full; Done closes it.
- **Verify**: the first blank is active. A chip fills it and moves on to the next; a placed chip is drawn at 30 %. Tapping
  a filled blank clears it and makes it active. A wrong answer shows "Some words don't match…", clears the placements and
  keeps the same blanks and chip order. The right answer goes to Step 4. "Show the Phrase Again" returns concealed with
  Continue still enabled. Back from Password keeps the placements. Masked cells read "Word n, hidden" to TalkBack.
- **Password**: strength meter levels 1 (orange), 2 (yellow) and 4 (green); checklist ticks; "Passwords match"; Next/Done
  on the wallet password pair. Saving shows a spinner, disables Cancel and hides the back arrow. Reset mode: title,
  message, identity header (name and fingerprint) and button match iOS, and the result is "Password Reset" with only a
  prominent "Done".
- **Done**: "Pair a Wallet" closes setup into Connect (wallet level) for the new wallet, and Back from there returns to
  the Wallets root. "Done" and system Back both close setup to the Wallets tab.
- **Import parsing** (fingerprints on the Done card): reference phrase → **73C5DA0A**. zh-Hans "的…在" → 03EE42CA, and no
  language picker, which is correct because the simplified and traditional readings are the same string. es typed
  without accents → "12 words · Español", the chip inserts "abierto", 1B6AEF92. ja pasted with U+3000 separators → 日本語,
  0ED2C5A4. "nomina solido…" → picker with "Italiano: nomina solido comodo…" / "Español: nómina sólido cómodo…"; Cancel
  closes it; Español → AF42CDAE, Italiano → DC9810FA. 15 words → 3726240E. Error messages: checksum ("Invalid recovery
  phrase. Check the words and their order."), one unknown word ("“xyzzy” is not…"), several unknown words ("“foo”,
  “baz”, “qux” are not…"; "bar" is correctly accepted), and the 1-word and 2-word length messages. The status line shows
  the empty hint, "1 word", "n words", "· language", a check when the phrase looks complete, and "Unknown: “a”, “b”" in
  red.
- **Suggestions**: up to 5 chips; "Each Chinese word is one character"; "Type a word to see suggestions"; "No matching
  word"; hidden once the word is complete; a chip replaces the trailing partial word and adds a space; "Done" closes the
  keyboard. Chips read "Insert <word>".
- **Paste**: long-press on the empty editor offers only "Paste" (no Copy, Share or Translate). The keyboard is in
  incognito mode (no personalised learning).
- **Discard**: the create and import messages match iOS. "Keep Editing" works. Cancel or system Back with a draft asks
  first, and without a draft it closes at once. "Discard" closes setup, and the next Create starts empty.
- **Background** (Home → return): the password step clears both fields. Verify clears the placements. Backup
  re-conceals the words and switches back from QR to Words. A revealed Recovery Cards sheet is closed and the words are
  concealed, with Continue still enabled. The step-owned Security Tips sheet stays open, which matches iOS
  (`WalletSetupFlow.swift` `BackupPhraseStep.showingTips` is not keyed by `securityEpoch`; the tips hold no secret). The
  import editor text and the passphrase are kept.
- **Recovery QR scanner**: opens as a secure full-screen dialog. Denying the camera shows "Camera Access Needed" (copy
  matches iOS `QRScannerView.swift:89-91`). Close returns to the editor with its text intact.
- Dark mode on every setup screen (dark verify board matches `dark-09g-verify-board.png`). Arabic RTL: mirrored bars,
  progress and grids, Latin digits, words kept LTR. Japanese text fit. Font scale 1.3 and 2.0 on every step (wraps, no
  overlaps apart from setup-05 and setup-14).
- No `FATAL` for `com.bittool.signer.debug` in logcat during the whole tour. Created wallets' fingerprints recomputed
  offline from the words shown: B5DC9651, 22590261 (and 45EC636A for a dark run abandoned at verify).

## Could not test, and why

- **Screenshots of secure windows**: the Recovery Cards sheet, the language picker and the scanner dialog give 0-byte
  captures even with `bts.uitest.allowCapture` (they set `SecureFlagPolicy.SecureOn`, which is correct for production).
  I checked them through the accessibility tree only, so their visual layout against `light-09e-recovery-cards-revealed.png`
  is unverified. The harness would need a debug override in the sheet and dialog window flags.
- **Predictive back gesture**: the emulator uses 3-button navigation, and switching to gesture navigation is a system
  setting change. Only system Back (key event) was tested.
- **Screenshot-detected / screen-recording warnings**: API 34+/35+ callbacks; the emulator is API 31.
- **Real TalkBack speech**: not enabled; labels were checked from the accessibility tree.
- **ANR dialogs** (06:47, 06:59 and 07:56; logcat "Input dispatching timed out"): during them system_server, the
  navigation bar, GMS and the package installer also ANR'd. Load average was 21–68, other agents were running
  instrumented tests on this emulator, and system_server restarted at 08:13. jdb showed the app's main thread idle
  afterwards. I could not establish a cause in the app. Re-check on a quiet device: none appeared while the load stayed
  low.
- After the system_server restart even System UI showed "isn't responding" (`160-state.png`), which supports the
  environmental reading of the ANRs above.
- **Process death during setup**, **App Lock during setup**, **a real recovery-phrase QR scan** (the virtual camera
  shows no QR code), **CJK input methods** (Gboard Pinyin/Japanese packs are not installed; CJK phrases were pasted
  instead): not covered.
- Cold launch measured 2–10 s depending on host load (debug build, shared host). Not representative.
- Not reproduced: once, a swipe that started on "Create New Wallet" on a freshly launched Welcome opened onboarding. Two
  repeats with the same coordinates did not.
