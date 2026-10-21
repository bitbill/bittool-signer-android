#!/bin/bash
# Copies the translation catalogue from the iOS repository, which owns it, and regenerates the Android string resources
# of :core:l10n. Never edit tools/l10n/translations/*.json or tools/l10n/interface-strings.json by hand: they are
# byte-for-byte copies (release check "l10n: catalogue copies = iOS", docs/release.md).
#
#   tools/l10n/sync-from-ios.sh [path/to/bittool-signer-ios]     (default: ~/Documents/bitsigner-ios/BitSigner)
#
# tools/engine/sync-from-ios.sh runs it after the engine copy, so one sync brings both.
#
# Steps:
#   1. refuses unless the iOS languages are exactly import_ios_strings.LANGUAGES (a new or dropped language needs
#      Android resource qualifiers first);
#   2. copies translations/<lang>.json and engine/data/interface-strings.json, then compares them with cmp;
#   3. prints the English keys iOS added or removed, so Android-only strings with the same meaning can be retired
#      (DECISIONS §4: prefer the iOS string);
#   4. runs tools/l10n/import_ios_strings.py. Resource names recorded in strings-index.tsv never change. An override in
#      tools/l10n/overrides/ whose iOS translation changed stops the import with the key and both texts: review it
#      (delete it when iOS adopted the fix, else update its "ios"), then rerun this script;
#   5. checks the Android-only strings (tools/l10n/android_strings.py check): they do not depend on the catalogue, but a
#      deleted duplicate must also leave tools/l10n/android/<lang>.json.
set -euo pipefail
IOS=${1:-$HOME/Documents/bitsigner-ios/BitSigner}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
L10N="$ROOT/tools/l10n"
SRC_KEYS="$IOS/engine/data/interface-strings.json"
[ -d "$IOS/translations" ] && [ -f "$SRC_KEYS" ] || { echo "No iOS catalogue at $IOS (translations/, engine/data/interface-strings.json)" >&2; exit 1; }

# -I: isolated (no user site, no PYTHON* variables); -B: no __pycache__ in the tree.
PY="python3 -I -B"

$PY - "$IOS/translations" "$L10N" <<'EOF'
import pathlib, sys
sys.path.insert(0, sys.argv[2])
import import_ios_strings as ios
have = {p.stem for p in pathlib.Path(sys.argv[1]).glob('*.json')}
want = set(ios.LANGUAGES)
if have != want:
    extra, missing = sorted(have - want), sorted(want - have)
    sys.exit('iOS languages differ from import_ios_strings.LANGUAGES: '
             + (f'new in iOS {", ".join(extra)} (add its Android qualifiers) ' if extra else '')
             + (f'gone from iOS {", ".join(missing)}' if missing else ''))
EOF

OLD_KEYS=$(mktemp)
trap 'rm -f "$OLD_KEYS"' EXIT
cp "$L10N/interface-strings.json" "$OLD_KEYS"

for f in "$IOS"/translations/*.json; do
  cp "$f" "$L10N/translations/$(basename "$f")"
  cmp "$f" "$L10N/translations/$(basename "$f")"
done
cp "$SRC_KEYS" "$L10N/interface-strings.json"
cmp "$SRC_KEYS" "$L10N/interface-strings.json"

$PY - "$OLD_KEYS" "$L10N/interface-strings.json" <<'EOF'
import json, sys
old, new = (json.load(open(p, encoding='utf-8')) for p in sys.argv[1:3])
added = [k for k in new if k not in set(old)]
removed = [k for k in old if k not in set(new)]
print(f'interface-strings.json: {len(old)} -> {len(new)} keys')
for k in added:
    print(f'  + {k}')
for k in removed:
    print(f'  - {k}')
if added:
    print('Look for Android-only strings with the same meaning (strings_<area>.xml) and switch them to the iOS '
          'resource (tools/l10n/strings-index.tsv).')
EOF

if ! $PY "$L10N/import_ios_strings.py"; then
  echo "The import failed: nothing was generated. If an override is named above, review tools/l10n/overrides/, then rerun." >&2
  exit 1
fi
$PY "$L10N/import_ios_strings.py" --check
$PY "$L10N/android_strings.py" check
echo "Catalogue synced from $IOS."
echo "Next: python3 -B -m unittest discover -s tools/l10n -p 'test_*.py'; tools/gradle-slot.sh ./gradlew :core:l10n:lintDebug"
