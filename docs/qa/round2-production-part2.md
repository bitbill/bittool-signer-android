# QA round 2 — production tour, part 2 (resumed instance; stopped by the orchestrator)

emulator-5556 (AVD SignerQA3), debug build with the production Keystore policy (PIN set by the user's authorisation,
one test fingerprint enrolled). Results below come from this instance's own inputs only (clean window up to 15:20:21,
confirmed in logcat). From 15:20:57 another instance drove the device; nothing after that is attributable.

## Verified (clean window)
- **EIP-1559 signing** (request-id 8f235e99…4202, path m/44'/60'/0'/0/0, source fingerprint 73C5DA0A): review shows
  "Send 0.000000000000000001 ETH", to "0x0000 … dEaD", "Network fee up to 0.00063 ETH", signing wallet "Wallet 2";
  authorize sheet "Fingerprint 73C5DA0A" → "Signature Created", "ur:eth-signature · CBOR · 89 bytes". Independent
  verifier (own Keccak, secp256k1 recovery, BIP32): chainId 1, nonce 7, tip 1 gwei, max fee 30 gwei, gas 21000, value
  1 wei; recovered signer 0x9858EfFD232B4033E47d90003D41EC34EcaEda94 = m/44'/60'/0'/0/0 of the reference phrase. MATCH.
- **Activity**: row "Native transfer", "0.000000000000000001 ETH · Wallet 2", day header "Wednesday, Oct 21, 2026";
  detail (Date, Network, Wallet, Fingerprint, Request fingerprint, no-broadcast note); survives force-stop + relaunch;
  `no_backup/BitToolSigner/receipts.bin` starts with "BTSR" and contains no plaintext.
- **App Lock**: cancelling the enable prompt leaves it off; fingerprint enables it; cold launch prompts with "Use a
  Wallet Password"; fingerprint unlocks; 45 s background stays unlocked, 125 s locks; password form ("Enter the
  password of any wallet on this device.", wallet menu, Unlock disabled until wallet + password, "Use Biometrics
  Instead"); wrong passwords → "The password is incorrect." and back-off "Try again in 8 seconds" (min(300,
  2^(count−4)) at count 7); attempts file 66 bytes while counted, 54 bytes after a success.

## Defects
None found in the clean window.

## Not verified / not attributable
Correct-password unlock (ran against another wallet because of the other driver), reveal, delete and biometric delete
confirmation, Activity Output section and Export as File, the lost-key path (needs OS lock-screen changes), wallets-04.
