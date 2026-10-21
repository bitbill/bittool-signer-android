#!/usr/bin/env python3
"""Checks of built release artifacts, called by tools/release/verify-release.sh (docs/release.md). Each subcommand
prints one line per finding and exits 0 when everything holds, 1 otherwise. An artifact is an APK or an App Bundle
(`.aab`, whose base module is read in place: `base/assets/…`, `base/dex/…`, `base/lib/…`).

    artifact_checks.py aab-base AAB OUT.apk
        Repackage the base module of an App Bundle in APK layout (AndroidManifest.xml and resources.pb in proto
        format at the root, classes*.dex, lib/, assets/, res/). `aapt2 dump badging|permissions` read such a proto APK,
        and tools/release/check-offline.sh scans its dex and native libraries, without bundletool (the SDK does not
        ship it). The output is for inspection only: it is not installable.

    artifact_checks.py badging AAPT2 VERSION_NAME VERSION_CODE ARTIFACT...
        Package name, versionName, versionCode, minSdk 26, targetSdk 36, label and ABIs of each artifact (aapt2 dump
        badging), and that all artifacts agree.

    artifact_checks.py assets LISTING_DIR ARTIFACT...
        The assets of each artifact are exactly the signer's own (gaps.md G8) plus ART's baseline profile; writes
        LISTING_DIR/<artifact>.assets.txt (path, size, stored/deflated).

    artifact_checks.py engine PIN_SHA256 PIN_BYTES IOS_REPO|- ARTIFACT...
        assets/engine/signer.js of each artifact has the pinned SHA-256 and size (EngineBundle.EXPECTED_SHA256), equals
        the iOS repo's engine/bundle-manifest.json and BitToolSigner/Resources/signer.js when IOS_REPO is given, and is
        stored uncompressed in every APK (noCompress "js": the digest a user computes is over the shipped bytes).

    artifact_checks.py data REPO_ROOT IOS_REPO|- FLAVOR=ARTIFACT...
        The data assets are byte-identical with the iOS resources (token registry, known contracts, token icon pack and
        index, the ten BIP39 lists, the engine's third-party notices), and the Terms of Use / Privacy Policy are the
        flavor's own Android texts (app/src/<flavor>/assets/legal/).

    artifact_checks.py notices SDK_DEPENDENCIES_TXT NOTICES_TXT OUT_LIST
        Writes the release runtime classpath (AGP's sdkDependencies.txt) as group:artifact:version lines to OUT_LIST,
        and names every non-AndroidX library that THIRD_PARTY_LICENSES_ANDROID.txt mentions neither by version nor by
        name (BOMs carry no code). AndroidX and Compose are covered by their blanket Apache-2.0 sections, so they are
        listed for the manual review (backlog B19) but not matched.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

ENGINE = 'engine/signer.js'
DATA_FROM_IOS = [
    'registry/token-registry.json', 'registry/known-contracts.json', 'registry/TokenIcons.pack',
    'registry/TokenIcons.index.json', 'legal/THIRD_PARTY_LICENSES.txt',
] + [f'bip39/bip39-{lang}.txt' for lang in (
    'chinese_simplified', 'chinese_traditional', 'czech', 'english', 'french', 'italian', 'japanese', 'korean',
    'portuguese', 'spanish')]
FLAVOR_LEGAL = ['legal/TERMS_OF_USE.txt', 'legal/PRIVACY_POLICY.txt']
# Every asset the app ships (gaps.md G8). Nothing else may be packaged: no library test assets (QuickJS's test_*.js
# once were), no fonts or models fetched by a library, no stray files.
EXPECTED_ASSETS = set([ENGINE] + DATA_FROM_IOS + FLAVOR_LEGAL + ['legal/THIRD_PARTY_LICENSES_ANDROID.txt'])
# Written by AGP from the baseline profiles (androidx.profileinstaller); APKs only: a bundle keeps them in
# BUNDLE-METADATA and Play adds them to the APKs it generates.
OPTIONAL_ASSETS = {'dexopt/baseline.prof', 'dexopt/baseline.profm'}
EXPECTED_PACKAGE = 'com.bittool.signer'
EXPECTED_LABEL = 'BitTool Signer'
EXPECTED_MIN_SDK = '26'
EXPECTED_TARGET_SDK = '36'
EXPECTED_ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64']


def is_bundle(path):
    return path.endswith('.aab')


def asset_entries(z, path):
    """{asset path relative to assets/: ZipInfo} of an APK or of an App Bundle's base module."""
    prefix = 'base/assets/' if is_bundle(path) else 'assets/'
    return {i.filename[len(prefix):]: i for i in z.infolist()
            if i.filename.startswith(prefix) and not i.filename.endswith('/')}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def short(path):
    return os.path.basename(path)


def cmd_aab_base(aab, out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with zipfile.ZipFile(aab) as z, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as o:
        names = set()
        for info in z.infolist():
            name = info.filename
            if not name.startswith('base/') or name.endswith('/'):
                continue
            rel = name[len('base/'):]
            if rel == 'manifest/AndroidManifest.xml':
                rel = 'AndroidManifest.xml'
            elif rel.startswith('dex/'):
                rel = rel[len('dex/'):]
            elif rel.startswith('root/'):
                rel = rel[len('root/'):]
            elif rel in ('assets.pb', 'native.pb'):
                continue
            if rel in names:
                print(f'duplicate entry {rel} in {aab}')
                return 1
            names.add(rel)
            o.writestr(rel, z.read(info))
    if 'AndroidManifest.xml' not in names or 'resources.pb' not in names:
        print(f'{aab}: no base module manifest / resources.pb')
        return 1
    print(f'{short(aab)}: base module repackaged as {out} ({len(names)} entries)')
    return 0


def badging(aapt2, artifact):
    target = artifact
    if is_bundle(artifact):
        raise SystemExit('badging needs an APK (use aab-base for a bundle)')
    out = subprocess.run([aapt2, 'dump', 'badging', target], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip() or f'aapt2 dump badging failed ({out.returncode})')
    text = out.stdout
    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text, re.M)
    facts = {
        'package': package.group(1) if package else None,
        'versionCode': package.group(2) if package else None,
        'versionName': package.group(3) if package else None,
        'minSdk': (re.search(r"^minSdkVersion:'([^']*)'", text, re.M) or [None, None])[1],
        'targetSdk': (re.search(r"^targetSdkVersion:'([^']*)'", text, re.M) or [None, None])[1],
        'label': (re.search(r"^application-label:'([^']*)'", text, re.M) or [None, None])[1],
        'abis': re.findall(r"'([^']+)'", (re.search(r'^native-code:(.*)$', text, re.M) or [None, ''])[1]),
    }
    return facts


def cmd_badging(aapt2, version_name, version_code, artifacts):
    expected = {
        'package': EXPECTED_PACKAGE, 'versionName': version_name, 'versionCode': version_code,
        'minSdk': EXPECTED_MIN_SDK, 'targetSdk': EXPECTED_TARGET_SDK, 'label': EXPECTED_LABEL, 'abis': EXPECTED_ABIS,
    }
    bad = 0
    seen = []
    for artifact in artifacts:
        try:
            facts = badging(aapt2, artifact)
        except RuntimeError as e:
            print(f'BAD  {short(artifact)}: {e}')
            bad += 1
            continue
        seen.append(facts)
        problems = [f'{k} {facts[k]!r} (expected {v!r})' for k, v in expected.items() if facts[k] != v]
        bad += bool(problems)
        print(('BAD  ' if problems else 'OK   ') + f"{short(artifact)}: {facts['package']} {facts['versionName']} "
              f"({facts['versionCode']}), minSdk {facts['minSdk']}, targetSdk {facts['targetSdk']}, "
              f"'{facts['label']}', {' '.join(facts['abis'])}" + (': ' + '; '.join(problems) if problems else ''))
    if len({json.dumps(f, sort_keys=True) for f in seen}) > 1:
        print('BAD  the artifacts disagree (one versionCode for both flavors)')
        bad += 1
    if not bad:
        print(f'all {len(artifacts)}: {EXPECTED_PACKAGE} {version_name} ({version_code}), minSdk {EXPECTED_MIN_SDK}, '
              f'targetSdk {EXPECTED_TARGET_SDK}, {" ".join(EXPECTED_ABIS)}')
    return 1 if bad else 0


def cmd_assets(listing_dir, artifacts):
    os.makedirs(listing_dir, exist_ok=True)
    bad = 0
    counts = []
    for artifact in artifacts:
        with zipfile.ZipFile(artifact) as z:
            entries = asset_entries(z, artifact)
        names = set(entries)
        with open(os.path.join(listing_dir, short(artifact) + '.assets.txt'), 'w') as f:
            for name in sorted(names):
                i = entries[name]
                f.write(f"{i.file_size:>10}  {'stored  ' if i.compress_type == zipfile.ZIP_STORED else 'deflated'}  "
                        f"assets/{name}\n")
        unexpected = sorted(names - EXPECTED_ASSETS - OPTIONAL_ASSETS)
        missing = sorted(EXPECTED_ASSETS - names)
        problems = ([f'unexpected {", ".join(unexpected)}'] if unexpected else []) + \
                   ([f'missing {", ".join(missing)}'] if missing else [])
        bad += bool(problems)
        total = sum(entries[n].file_size for n in names)
        print(('BAD  ' if problems else 'OK   ') + f'{short(artifact)}: {len(names)} assets, {total:,} bytes'
              + (': ' + '; '.join(problems) if problems else ''))
        counts.append(str(len(names)))
    if not bad:
        print(f'{" / ".join(counts)} assets: exactly the {len(EXPECTED_ASSETS)} signer assets (+ ART baseline profile '
              f'in APKs), no library test assets')
    return 1 if bad else 0


def ios_engine(ios):
    """(manifest sha256, manifest bytes, resource sha256, resource bytes) of the iOS engine, or None."""
    if ios == '-':
        return None
    with open(os.path.join(ios, 'engine/bundle-manifest.json')) as f:
        manifest = json.load(f)
    with open(os.path.join(ios, 'BitToolSigner/Resources/signer.js'), 'rb') as f:
        data = f.read()
    return manifest['sha256'], int(manifest['bytes']), sha256(data), len(data)


def cmd_engine(pin_sha, pin_bytes, ios, artifacts):
    bad = 0
    pin_bytes = int(pin_bytes)
    reference = ios_engine(ios)
    if reference:
        m_sha, m_bytes, r_sha, r_bytes = reference
        same = m_sha == pin_sha and m_bytes == pin_bytes and r_sha == pin_sha and r_bytes == pin_bytes
        bad += not same
        print(('OK   ' if same else 'BAD  ') + f'pin {pin_sha} ({pin_bytes} B); iOS bundle-manifest.json {m_sha} '
              f'({m_bytes} B); iOS Resources/signer.js {r_sha} ({r_bytes} B)')
    else:
        print(f'--   pin {pin_sha} ({pin_bytes} B); iOS repository not available, compared with the pin only')
    for artifact in artifacts:
        with zipfile.ZipFile(artifact) as z:
            info = asset_entries(z, artifact).get(ENGINE)
            if info is None:
                print(f'BAD  {short(artifact)}: no assets/{ENGINE}')
                bad += 1
                continue
            data = z.read(info)
        problems = []
        digest = sha256(data)
        if digest != pin_sha or len(data) != pin_bytes:
            problems.append(f'{digest} ({len(data)} B) is not the pin')
        if not is_bundle(artifact) and info.compress_type != zipfile.ZIP_STORED:
            problems.append('compressed (expected stored: noCompress "js")')
        bad += bool(problems)
        print(('BAD  ' if problems else 'OK   ') + f'{short(artifact)}: assets/{ENGINE} {digest} ({len(data)} B'
              + (', stored' if not is_bundle(artifact) and info.compress_type == zipfile.ZIP_STORED else '') + ')'
              + (': ' + '; '.join(problems) if problems else ''))
    if not bad:
        print(f'signer.js {pin_sha} ({pin_bytes} B) in all {len(artifacts)} = EngineBundle pin'
              + (' = iOS bundle-manifest.json = iOS Resources/signer.js' if reference else ' (iOS not compared)'))
    return 1 if bad else 0


def cmd_data(root, ios, pairs):
    bad = 0
    for pair in pairs:
        flavor, artifact = pair.split('=', 1)
        with zipfile.ZipFile(artifact) as z:
            entries = asset_entries(z, artifact)
            shipped = {name: z.read(entries[name]) for name in DATA_FROM_IOS + FLAVOR_LEGAL if name in entries}
        problems = [f'missing assets/{n}' for n in DATA_FROM_IOS + FLAVOR_LEGAL if n not in shipped]
        compared = 0
        if ios != '-':
            for name in DATA_FROM_IOS:
                if name not in shipped:
                    continue
                with open(os.path.join(ios, 'BitToolSigner/Resources', os.path.basename(name)), 'rb') as f:
                    if f.read() != shipped[name]:
                        problems.append(f'assets/{name} differs from the iOS resource')
                compared += 1
        for name in FLAVOR_LEGAL:
            if name not in shipped:
                continue
            with open(os.path.join(root, 'app/src', flavor, 'assets', name), 'rb') as f:
                if f.read() != shipped[name]:
                    problems.append(f'assets/{name} is not app/src/{flavor}/assets/{name}')
        bad += bool(problems)
        source = f'{compared} files equal the iOS resources' if ios != '-' else 'iOS repository not available'
        print(('BAD  ' if problems else 'OK   ') + f'{short(artifact)} ({flavor}): {source}; Terms and Privacy are the '
              f'{flavor} texts' + (': ' + '; '.join(problems) if problems else ''))
    if not bad:
        print(f'all {len(pairs)}: {len(DATA_FROM_IOS)} data files '
              + ('= iOS resources' if ios != '-' else 'present (iOS not compared)')
              + '; Terms of Use and Privacy Policy = the flavor texts (app/src/<flavor>/assets/legal)')
    return 1 if bad else 0


def cmd_notices(deps_file, notices_file, out_list):
    with open(deps_file) as f:
        libs = re.findall(r'groupId: "([^"]+)"\s+artifactId: "([^"]+)"\s+version: "([^"]+)"', f.read())
    with open(notices_file) as f:
        notices = f.read()
    if not libs:
        print(f'BAD  no library found in {deps_file}')
        return 1
    os.makedirs(os.path.dirname(os.path.abspath(out_list)), exist_ok=True)
    with open(out_list, 'w') as f:
        f.writelines(f'{g}:{a}:{v}\n' for g, a, v in sorted(set(libs)))

    def mentioned(word):
        return re.search(r'(?<![\w.])' + re.escape(word) + r'(?![\w.])', notices) is not None

    unmatched = sorted({f'{g}:{a}:{v}' for g, a, v in libs
                        if not g.startswith('androidx.') and not a.endswith('-bom')
                        and not mentioned(v) and not mentioned(a)})
    others = len({(g, a) for g, a, _ in libs if not g.startswith('androidx.')})
    if unmatched:
        print(f'BAD  not in {os.path.basename(notices_file)}: {", ".join(unmatched)}')
        return 1
    print(f'{len(set(libs))} runtime libraries listed; the {others} non-AndroidX ones all have a notice')
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    command, args = argv[1], argv[2:]
    try:
        if command == 'aab-base' and len(args) == 2:
            return cmd_aab_base(*args)
        if command == 'badging' and len(args) >= 4:
            return cmd_badging(args[0], args[1], args[2], args[3:])
        if command == 'assets' and len(args) >= 2:
            return cmd_assets(args[0], args[1:])
        if command == 'engine' and len(args) >= 4:
            return cmd_engine(args[0], args[1], args[2], args[3:])
        if command == 'data' and len(args) >= 3:
            return cmd_data(args[0], args[1], args[2:])
        if command == 'notices' and len(args) == 3:
            return cmd_notices(*args)
    except (OSError, zipfile.BadZipFile, KeyError, ValueError) as e:
        print(f'BAD  {command}: {e}')
        return 1
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
