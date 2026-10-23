# Google Play listing images

`render.sh` draws the Play Store images with headless Chrome into `fastlane/metadata/android/en-US/images/`, next to
the listing text (`title.txt`, `short_description.txt`, `full_description.txt`):

| File | Size | Source |
|---|---|---|
| `icon.png` | 512 × 512 | `icon.html`: the app icon master (design/Brand in the iOS repo), full bleed; Play rounds the corners |
| `featureGraphic.png` | 1024 × 500 | `feature.html` |
| `phoneScreenshots/1…8_en-US.png` | 1080 × 1920 | `screenshot.html?n=1…8`, captions in `shots.js` |

The screenshots frame real app captures in `screens/`: the dark theme of a debug build on a 1080 × 2220 emulator,
using the harness's public fixture wallets (the `abandon … about` test phrase and two passphrases, renamed Cold Storage,
Everyday and Family Vault) and generated public test requests. The import capture shows four sample words, not a real
phrase. `phone.js` covers the emulator's status bar with a clean one (9:41, airplane mode, battery) in the colour of
the app's top bar and cuts off the navigation bar.

To change a caption, edit `shots.js` and run `tools/store/render.sh`. To replace a screen, capture it at 1080 × 2220
(`adb exec-out screencap -p`) under the same name in `screens/`.

Font: Inter (`fonts/`, SIL Open Font License 1.1, `fonts/OFL.txt`).
