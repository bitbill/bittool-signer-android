#!/usr/bin/env python3
"""Drives the debug app on the emulator for QA tours (adb + uiautomator; no test APK needed).

    tools/qa/ui.py launch [--fixtures N] [--dark] [--locale zh-Hans] [--lock] [--keep] [--run UUID] [--lose-keys]
                          [--unreadable-journal]
                                              fresh harness run (or --keep: the same run id again), capture allowed
    tools/qa/ui.py dump                       list visible nodes: [index] text | desc | resource-id | bounds
    tools/qa/ui.py tap TEXT_OR_ID [--nth K]   tap the node whose text, content-desc or resource-id (test tag) matches
    tools/qa/ui.py tapxy X Y
    tools/qa/ui.py type TEXT                  type into the focused field (spaces supported; ASCII only)
    tools/qa/ui.py key back|enter|home|tab|del
    tools/qa/ui.py swipe up|down|left|right
    tools/qa/ui.py shot NAME [--size 900]     screenshot to $QA_SHOTS/NAME.png (and a downscaled NAME-s.png to view)
    tools/qa/ui.py wait TEXT_OR_ID [--timeout 20]
    tools/qa/ui.py font 1.0|1.3|2.0           system font scale
    tools/qa/ui.py logcat                     recent crashes / errors of the app

Matching is exact first, then case-insensitive substring. The debug harness (app/src/debug/.../DebugUiTestHarness.kt)
keeps FLAG_SECURE off for the run (`bts.uitest.allowCapture`), otherwise screenshots are black.

`launch --keep` reuses the run id of the previous launch (stored in $QA_SHOTS/.run-id) unless `--run` names one, so a
cold relaunch opens the same wallets. `--lose-keys` (`bts.uitest.loseDeviceKeys`) deletes the run's fixture wallet keys
before anything is injected: every existing wallet then has a provably lost device key (backlog B9/B24). It implies
`--keep` (a reset run has no wallets whose keys could be lost). `--unreadable-journal` (`bts.uitest.unreadableJournal`)
replaces the run's Activity journal with one no key opens, so Activity shows "Activity Unavailable" (and the "could not
be read" alert at launch). It works on a fresh run (with --fixtures) and with --keep; the run keeps the unreadable
journal on later launches, since the app never overwrites one (signatures still complete, with the "could not be
saved" alert); only a fresh run starts a new journal.

`dump` (and everything that finds nodes) deletes the previous dump first, asks uiautomator for every window
(`--windows`: dialogs, sheets and the keyboard too, where the device supports it) and fails loudly when uiautomator
does not produce a fresh dump (e.g. "could not get idle state" under load) instead of reading a stale file. The app's
windows are listed (and matched) before the system's, so `tap Back` is the app's Back, not the navigation bar's. `type`
sends the text in short chunks: `input text` drops characters of long strings on a loaded emulator.
"""
import os
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ADB = os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
PKG = 'com.bittool.signer.debug'
ACTIVITY = 'com.bittool.signer.MainActivity'
SHOTS = os.environ.get('QA_SHOTS', os.path.join(os.path.dirname(__file__), '..', '..', 'build', 'qa-shots'))
DUMP_PATH = '/sdcard/qa-ui.xml'
DUMP_ATTEMPTS = 3
# Characters per `input text` call: longer strings lose characters on a loaded emulator (QA round 1, sign-18).
TYPE_CHUNK = 10


class AdbError(RuntimeError):
    pass


def run(args, binary=False):
    """Runs adb with [args]; the completed process with str output (bytes with [binary])."""
    if binary:
        return subprocess.run([ADB, *args], capture_output=True)
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding='utf-8', errors='replace')


def adb(*args):
    """adb's standard output as text."""
    return run(args).stdout


def dump_xml():
    """A fresh uiautomator dump of the screen (every window where supported) as XML text; raises AdbError otherwise."""
    last = ''
    for attempt in range(DUMP_ATTEMPTS):
        # Never read a dump of an earlier screen: the file goes first, and only a reported success is read.
        run(['shell', 'rm', '-f', DUMP_PATH])
        for options in (['--windows'], []):
            r = run(['shell', 'uiautomator', 'dump', *options, DUMP_PATH])
            output = (r.stdout + r.stderr).strip()
            last = output or f'exit status {r.returncode}'
            # uiautomator prints "UI hierchary dumped to: <file>" (sic) on success, and still exits 0 on most failures.
            if r.returncode == 0 and 'dumped to' in output:
                xml = run(['exec-out', 'cat', DUMP_PATH], binary=True).stdout
                if xml.lstrip().startswith(b'<?xml'):
                    return xml.decode('utf-8', errors='replace')
                last = 'the dump file is missing or empty'
            # An unknown option is reported as a usage error: retry once without it.
            if not re.search(r'unknown|usage|invalid', output, re.IGNORECASE):
                break
        time.sleep(1 + attempt)
    raise AdbError(f'uiautomator dump failed: {last}')


def window_trees(root, package=PKG):
    """The `hierarchy` trees of a dump: one for a plain dump; with `--windows` one per window, the app's own windows
    (activity, dialogs, sheets, menus) first and then the system's (status and navigation bars, keyboard, permission
    dialog), each group top-most first as uiautomator lists them. A query that also matches a system node (the
    navigation bar's "Back" or "Home", a keyboard key such as "Done") therefore finds the app's node."""
    trees = [root] if root.tag == 'hierarchy' else list(root.iter('hierarchy'))

    def foreign(tree):
        first = tree.find('.//node')
        return first is None or first.get('package', package) != package
    return sorted(trees, key=foreign)


def parse_nodes(xml, package=PKG):
    """The nodes of a uiautomator dump (one window or `--windows`): the app's windows first (window_trees)."""
    root = ET.fromstring(xml)
    out = []
    for n in (node for tree in window_trees(root, package) for node in tree.iter('node')):
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


def nodes():
    return parse_nodes(dump_xml())


def find(query, nth=0, ns=None):
    ns = nodes() if ns is None else ns

    def keys(n):
        return [n['text'], n['desc'], n['id'], n['id'].split('/')[-1]]
    exact = [n for n in ns if query in keys(n)]
    if exact:
        return exact[nth] if nth < len(exact) else None
    q = query.lower()
    loose = [n for n in ns if any(q in k.lower() for k in keys(n) if k)]
    return loose[nth] if nth < len(loose) else None


def text_chunks(text, size=TYPE_CHUNK):
    """`input text` arguments for [text]: chunks of at most [size] characters, spaces as %s, quoted for the device shell."""
    if not text.isascii():
        raise AdbError('input text types ASCII only')
    return [shlex.quote(text[i:i + size].replace(' ', '%s')) for i in range(0, len(text), size)]


def type_text(text):
    for chunk in text_chunks(text):
        # One shell command per chunk: the device shell sees the quoted chunk, so &, ', ", ;, (, ) and $ stay literal.
        run(['shell', f'input text {chunk}'])
        time.sleep(0.15)


def arg(name, default=None, cast=str):
    if name in sys.argv:
        i = sys.argv.index(name)
        if i + 1 < len(sys.argv) and not sys.argv[i + 1].startswith('--'):
            return cast(sys.argv[i + 1])
        return True
    return default


def run_id(keep):
    """The run id to launch: --run, else the previous one with --keep, else a new one (remembered for later --keep)."""
    stored = os.path.join(SHOTS, '.run-id')
    chosen = arg('--run')
    if not isinstance(chosen, str):
        chosen = None
        if keep and os.path.exists(stored):
            with open(stored, encoding='utf-8') as f:
                chosen = f.read().strip() or None
    chosen = chosen or str(uuid.uuid4())
    os.makedirs(SHOTS, exist_ok=True)
    with open(stored, 'w', encoding='utf-8') as f:
        f.write(chosen + '\n')
    return chosen


def logcat_problems(text):
    return [line for line in text.splitlines() if re.search(r'FATAL|AndroidRuntime|E BitTool|bittool.*Exception', line)]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    try:
        command(cmd)
    except AdbError as e:
        sys.exit(str(e))


def command(cmd):
    if cmd == 'launch':
        adb('shell', 'am', 'force-stop', PKG)
        # Losing the keys of a reset (empty) run would do nothing: --lose-keys relaunches the previous run.
        keep = bool(arg('--keep')) or bool(arg('--lose-keys'))
        extras = ['--es', 'bts.uitest.run', run_id(keep), '--ez', 'bts.uitest.allowCapture', 'true',
                  '--ei', 'bts.uitest.fixtures', str(arg('--fixtures', 0, int)),
                  '--es', 'bts.uitest.appearance', 'dark' if arg('--dark') else 'light']
        if not keep:
            extras += ['--ez', 'bts.uitest.reset', 'true']
        if arg('--locale'):
            extras += ['--es', 'bts.uitest.locale', arg('--locale')]
        if arg('--lock'):
            extras += ['--ez', 'bts.uitest.appLock', 'true']
        if arg('--lose-keys'):
            extras += ['--ez', 'bts.uitest.loseDeviceKeys', 'true']
        if arg('--unreadable-journal'):
            extras += ['--ez', 'bts.uitest.unreadableJournal', 'true']
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
        type_text(sys.argv[2])
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
        png = run(['exec-out', 'screencap', '-p'], binary=True).stdout
        if not png:
            raise AdbError('screencap returned no image (a secure window is showing?)')
        with open(path, 'wb') as f:
            f.write(png)
        small = os.path.join(SHOTS, sys.argv[2] + '-s.png')
        subprocess.run(['sips', '-Z', str(arg('--size', 900, int)), path, '--out', small], capture_output=True)
        print(small)
    elif cmd == 'wait':
        deadline = time.time() + arg('--timeout', 20, int)
        while time.time() < deadline:
            try:
                if find(sys.argv[2]):
                    print('found', sys.argv[2])
                    return
            except AdbError:
                pass  # a failed dump under load: try again until the deadline
            time.sleep(1)
        sys.exit(f'timeout waiting for {sys.argv[2]}')
    elif cmd == 'font':
        adb('shell', 'settings', 'put', 'system', 'font_scale', sys.argv[2])
    elif cmd == 'logcat':
        for line in logcat_problems(adb('logcat', '-d', '-t', '2000')):
            print(line)
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
