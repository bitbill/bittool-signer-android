#!/usr/bin/env python3
"""Drives the debug app on the emulator for QA tours (adb + uiautomator; no test APK needed).

    tools/qa/ui.py launch [--fixtures N] [--dark] [--locale zh-Hans] [--lock] [--keep]   fresh harness run, capture allowed
    tools/qa/ui.py dump                       list visible nodes: [index] text | desc | resource-id | bounds
    tools/qa/ui.py tap TEXT_OR_ID [--nth K]   tap the node whose text, content-desc or resource-id (test tag) matches
    tools/qa/ui.py tapxy X Y
    tools/qa/ui.py type TEXT                  type into the focused field (spaces supported)
    tools/qa/ui.py key back|enter|home|tab
    tools/qa/ui.py swipe up|down|left|right
    tools/qa/ui.py shot NAME [--size 900]     screenshot to $QA_SHOTS/NAME.png (and a downscaled NAME-s.png to view)
    tools/qa/ui.py wait TEXT_OR_ID [--timeout 20]
    tools/qa/ui.py font 1.0|1.3|2.0           system font scale
    tools/qa/ui.py logcat                     recent crashes / errors of the app

Matching is exact first, then case-insensitive substring. The debug harness (app/src/debug/.../DebugUiTestHarness.kt)
keeps FLAG_SECURE off for the run (`bts.uitest.allowCapture`), otherwise screenshots are black.
"""
import os
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ADB = os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
PKG = 'com.bittool.signer.debug'
ACTIVITY = 'com.bittool.signer.MainActivity'
SHOTS = os.environ.get('QA_SHOTS', os.path.join(os.path.dirname(__file__), '..', '..', 'build', 'qa-shots'))


def adb(*args, capture=True):
    r = subprocess.run([ADB, *args], capture_output=capture, text=not capture or None)
    return r.stdout if capture else None


def nodes():
    subprocess.run([ADB, 'shell', 'uiautomator', 'dump', '/sdcard/qa-ui.xml'], capture_output=True)
    xml = subprocess.run([ADB, 'exec-out', 'cat', '/sdcard/qa-ui.xml'], capture_output=True).stdout
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter('node'):
        b = re.findall(r'\d+', n.get('bounds', ''))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        out.append({
            'text': n.get('text', ''), 'desc': n.get('content-desc', ''), 'id': n.get('resource-id', ''),
            'cls': n.get('class', '').split('.')[-1], 'clickable': n.get('clickable') == 'true',
            'bounds': (x1, y1, x2, y2), 'center': ((x1 + x2) // 2, (y1 + y2) // 2),
        })
    return out


def find(query, nth=0):
    ns = nodes()
    def keys(n):
        return [n['text'], n['desc'], n['id'], n['id'].split('/')[-1]]
    exact = [n for n in ns if query in keys(n)]
    if exact:
        return exact[nth] if nth < len(exact) else None
    q = query.lower()
    loose = [n for n in ns if any(q in k.lower() for k in keys(n) if k)]
    return loose[nth] if nth < len(loose) else None


def arg(name, default=None, cast=str):
    if name in sys.argv:
        i = sys.argv.index(name)
        if i + 1 < len(sys.argv) and not sys.argv[i + 1].startswith('--'):
            return cast(sys.argv[i + 1])
        return True
    return default


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == 'launch':
        adb('shell', 'am', 'force-stop', PKG)
        extras = ['--es', 'bts.uitest.run', str(uuid.uuid4()), '--ez', 'bts.uitest.allowCapture', 'true',
                  '--ei', 'bts.uitest.fixtures', str(arg('--fixtures', 0, int)),
                  '--es', 'bts.uitest.appearance', 'dark' if arg('--dark') else 'light']
        if not arg('--keep'):
            extras += ['--ez', 'bts.uitest.reset', 'true']
        if arg('--locale'):
            extras += ['--es', 'bts.uitest.locale', arg('--locale')]
        if arg('--lock'):
            extras += ['--ez', 'bts.uitest.appLock', 'true']
        print(adb('shell', 'am', 'start', '-W', '-n', f'{PKG}/{ACTIVITY}', *extras).strip())
        time.sleep(3)
    elif cmd == 'dump':
        for i, n in enumerate(nodes()):
            if n['text'] or n['desc'] or (n['id'] and not n['id'].startswith('android:')):
                flag = '*' if n['clickable'] else ' '
                print(f"[{i}]{flag} {n['text'][:70]!r} | {n['desc'][:40]!r} | {n['id'].split('/')[-1]} | {n['cls']} | {n['bounds']}")
    elif cmd == 'tap':
        n = find(sys.argv[2], arg('--nth', 0, int))
        if not n:
            sys.exit(f'not found: {sys.argv[2]}')
        adb('shell', 'input', 'tap', *map(str, n['center']))
        time.sleep(0.8)
        print('tapped', n['text'] or n['desc'] or n['id'], n['center'])
    elif cmd == 'tapxy':
        adb('shell', 'input', 'tap', sys.argv[2], sys.argv[3])
        time.sleep(0.8)
    elif cmd == 'type':
        text = sys.argv[2].replace(' ', '%s').replace("'", "\\'").replace('&', '\\&')
        adb('shell', 'input', 'text', text)
        time.sleep(0.5)
    elif cmd == 'key':
        code = {'back': '4', 'enter': '66', 'home': '3', 'tab': '61', 'del': '67'}[sys.argv[2]]
        adb('shell', 'input', 'keyevent', code)
        time.sleep(0.8)
    elif cmd == 'swipe':
        d = sys.argv[2]
        w, h = 1080, 2220
        seg = {'up': (w // 2, h * 3 // 4, w // 2, h // 4), 'down': (w // 2, h // 4, w // 2, h * 3 // 4),
               'left': (w * 4 // 5, h // 2, w // 5, h // 2), 'right': (w // 5, h // 2, w * 4 // 5, h // 2)}[d]
        adb('shell', 'input', 'swipe', *map(str, seg), '300')
        time.sleep(0.8)
    elif cmd == 'shot':
        os.makedirs(SHOTS, exist_ok=True)
        path = os.path.join(SHOTS, sys.argv[2] + '.png')
        png = subprocess.run([ADB, 'exec-out', 'screencap', '-p'], capture_output=True).stdout
        open(path, 'wb').write(png)
        small = os.path.join(SHOTS, sys.argv[2] + '-s.png')
        subprocess.run(['sips', '-Z', str(arg('--size', 900, int)), path, '--out', small], capture_output=True)
        print(small)
    elif cmd == 'wait':
        deadline = time.time() + arg('--timeout', 20, int)
        while time.time() < deadline:
            if find(sys.argv[2]):
                print('found', sys.argv[2])
                return
            time.sleep(1)
        sys.exit(f'timeout waiting for {sys.argv[2]}')
    elif cmd == 'font':
        adb('shell', 'settings', 'put', 'system', 'font_scale', sys.argv[2])
    elif cmd == 'logcat':
        out = adb('logcat', '-d', '-t', '2000')
        for line in out.splitlines():
            if re.search(r'FATAL|AndroidRuntime|E BitTool|bittool.*Exception', line):
                print(line)
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
