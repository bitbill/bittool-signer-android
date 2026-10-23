# Google Play listing (en-US)

Everything the store listing needs is in `fastlane/metadata/android/en-US/` (fastlane `supply` layout, so it can also
be uploaded with `fastlane supply`); the images are drawn by `tools/store/render.sh` (see `tools/store/README.md`).

| Play Console field | File | Limit |
|---|---|---|
| App name | `title.txt` — BitTool Signer | 14 / 30 |
| Short description | `short_description.txt` | 79 / 80 |
| Full description | `full_description.txt` | 3,973 / 4,000 |
| App icon | `images/icon.png` — 512 × 512 PNG | 14 KB / 1 MB |
| Feature graphic | `images/featureGraphic.png` — 1024 × 500, 24-bit PNG | 302 KB / 15 MB |
| Phone screenshots | `images/phoneScreenshots/1…8_en-US.png` — 1080 × 1920 (9:16) | 8 / 8, ~0.6 MB each |
| Release notes 1.0.0 | `changelogs/1000001.txt` (versionCode of 1.0.0 build 1) | 500 |

## The other App content answers

These follow from what the app does; check each against the current Play Console wording before submitting.

- **Package**: `com.bittool.signer`; Play flavor (`playRelease`). Free, no ads, no in-app purchases.
- **Category**: Finance. **Contact**: website https://bittool.com/signer, plus a support e-mail (required, owner to
  provide).
- **Privacy policy URL**: required by Play. The policy is bundled in the app (Settings › About), but Play needs a public
  page, for example https://bittool.com/signer/privacy (owner to publish).
- **App access**: all functionality is available without an account or special access.
- **Ads**: no ads.
- **Data safety**: no data collected, no data shared. The app has no Internet permission, so nothing can leave the
  device; wallets, the Activity journal and camera frames are processed on the device only. No account, so no
  account deletion.
- **Financial features**: a non-custodial (software) cryptocurrency wallet / signer. It holds no customer funds and
  offers no exchange, transfer or custody service.
- **Target audience**: 18 and over.
- **Content rating (IARC)**: Utility / Productivity category; no violence, sexual content, gambling, user-to-user
  interaction, location sharing or digital purchases.
- **Permissions shown on the listing**: Camera (scanning QR codes), Biometrics (optional App Lock). No Internet, no
  network state.
- **Source code** named in the description: https://github.com/bitbill/bittool-signer-android — the repository must be
  public before the listing goes live.
