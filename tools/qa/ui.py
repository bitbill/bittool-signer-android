#!/usr/bin/env python3
"""Drives the debug app on the emulator for QA tours (adb + uiautomator; no test APK needed).

    tools/qa/ui.py launch [--fixtures N] [--dark] [--locale zh-Hans] [--lock] [--keep] [--run UUID] [--lose-keys]
                          [--unreadable-journal] [--lose-journal-key]
                                              fresh harness run (or --keep: the same run id again), capture allowed
    tools/qa/ui.py dump [--all]               list the app's visible nodes: [index] text | desc | resource-id | bounds
    tools/qa/ui.py tap TEXT_OR_ID [--nth K] [--all]
                                              tap the node whose text, content-desc or resource-id (test tag) matches
    tools/qa/ui.py tapxy X Y
    tools/qa/ui.py type TEXT                  type into the focused field (spaces supported; ASCII only)
    tools/qa/ui.py key back|enter|home|tab|del
    tools/qa/ui.py swipe up|down|left|right
    tools/qa/ui.py focus                      the window and the app that have the input focus now
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

`--lose-journal-key` (`bts.uitest.loseJournalKey`) loses the run's Activity journal key for good, as removing the
device's screen lock does (QA L1): the app proves the loss, starts a fresh journal without any alert, and Activity
shows "Activity Started Again" with OK until it is tapped (also across a `--keep` relaunch without the flag). It works
on a fresh run (with --fixtures: the run gets a journal the lost key sealed) and with --keep (the run's records are
what the loss takes); the next signature is saved under a new key. Every launch with the flag loses the key again.
With --unreadable-journal as well, the lost key wins; a later --unreadable-journal alone shows "Activity Unavailable"
again even when no signature has replaced the lost key since.

`--locale` (default en-US, also with --keep) wins over the language an earlier run or the Language page stored. Below
API 33 a launch whose stored or device language differs relaunches the activity once right after the first frame
(API 33+ the system does the same), so wait for the screen before the first dump.

`dump` (and everything that finds nodes) deletes the previous dump first, asks uiautomator for every window
(`--windows`: dialogs, sheets and the keyboard too, where the device supports it) and fails loudly when uiautomator
does not produce a fresh dump (e.g. "could not get idle state" under load) instead of reading a stale file. `type`
sends the text in short chunks: `input text` drops characters of long strings on a loaded emulator.

Only the app's own nodes (package com.bittool.signer.debug) are listed and matched: another app on the same emulator
uses the same test tags (`onboarding.next`, `tab.scan`), and a dump taken while the signer's window has no hierarchy
listed that app's nodes (QA round 2). `--all` adds every other window (the system bars, the keyboard, a permission
dialog or BiometricPrompt), the app's windows still first, so `tap Back --all` is the app's Back, not the navigation
bar's.

Input (`tap`, `tapxy`, `type`, `key`, `swipe`) goes only to the app: before each one the tool reads the focused window
from `dumpsys window displays` and its owner from `dumpsys window windows` (never `grep -m1 mCurrentFocus` over the
whole `dumpsys window`, whose first section is the LAST ANR's stale window) and stops when it is another app's. A
tapped node must belong to the window that has the focus (a system dialog's node with `--all`); `--force` skips the
check. `dump` warns when another app's window has the focus.
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


def parse_nodes(xml, package=PKG, every_package=False):
    """The nodes of a uiautomator dump (one window or `--windows`), the app's windows first (window_trees): only
    [package]'s nodes unless [every_package]. A node without a package attribute is not the app's."""
    root = ET.fromstring(xml)
    out = []
    for n in (node for tree in window_trees(root, package) for node in tree.iter('node')):
        if not every_package and n.get('package') != package:
            continue
        b = re.findall(r'\d+', n.get('bounds', ''))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        out.append({
            'text': n.get('text', ''), 'desc': n.get('content-desc', ''), 'id': n.get('resource-id', ''),
            'package': n.get('package', ''),
            'cls': n.get('class', '').split('.')[-1], 'clickable': n.get('clickable') == 'true',
            'bounds': (x1, y1, x2, y2), 'center': ((x1 + x2) // 2, (y1 + y2) // 2),
        })
    return out


def nodes(every_package=False):
    return parse_nodes(dump_xml(), every_package=every_package)


def find(query, nth=0, ns=None, every_package=False):
    ns = nodes(every_package) if ns is None else ns

    def keys(n):
        return [n['text'], n['desc'], n['id'], n['id'].split('/')[-1]]
    exact = [n for n in ns if query in keys(n)]
    if exact:
        return exact[nth] if nth < len(exact) else None
    q = query.lower()
    loose = [n for n in ns if any(q in k.lower() for k in keys(n) if k)]
    return loose[nth] if nth < len(loose) else None


WINDOW_HEADER = re.compile(r'^\s*Window #\d+ Window\{([0-9a-f]+) u\d+ (.*)\}:\s*$')
FOCUS_LINE = re.compile(r'^\s*(mCurrentFocus|mFocusedApp)=(.*)$')
SECTION = re.compile(r'^WINDOW MANAGER .*\(dumpsys window (\w+)\)')


def parse_focus(text):
    """The focused window and app in `dumpsys window displays` + `dumpsys window windows` output:
    {'window': title, 'window_package': package, 'app_package': package}, each None when unknown.

    Only the DISPLAY CONTENTS section is read for the focus (its first display): a full `dumpsys window` starts with
    the LAST ANR section, whose mCurrentFocus is the window of an old ANR. A dialog's mCurrentFocus names its title, not
    its package ("Window{150ec4b u0 BitTool Signer}"), so the package comes from that window's entry in the WINDOWS
    section ("package=...")."""
    section = None
    current = app = None
    packages = {}
    pending = None
    for line in text.splitlines():
        m = SECTION.match(line)
        if m:
            section = m.group(1)
            continue
        if section == 'displays':
            f = FOCUS_LINE.match(line)
            if f and f.group(1) == 'mCurrentFocus' and current is None:
                current = f.group(2).strip()
            elif f and f.group(1) == 'mFocusedApp' and app is None:
                app = f.group(2).strip()
        elif section == 'windows':
            w = WINDOW_HEADER.match(line)
            if w:
                pending = w.group(1)
                continue
            p = re.search(r'\bpackage=(\S+)', line)
            if p and pending:
                packages[pending] = p.group(1)
                pending = None
    out = {'window': None, 'window_package': None, 'app_package': None}
    w = re.match(r'Window\{([0-9a-f]+) u\d+ (.*)\}', current or '')
    if w:
        out['window'] = w.group(2)
        out['window_package'] = packages.get(w.group(1)) or (w.group(2).split('/')[0] if '/' in w.group(2) else None)
    a = re.match(r'ActivityRecord\{[0-9a-f]+ u\d+ ([^/ ]+)/', app or '')
    if a:
        out['app_package'] = a.group(1)
    return out


def focus_state():
    """parse_focus of the device now."""
    return parse_focus(adb('shell', 'dumpsys window displays; dumpsys window windows'))


def require_focus(package=PKG):
    """Raises AdbError unless [package]'s window has the input focus, so input never reaches another app; --force
    skips the check."""
    if arg('--force'):
        return
    state = focus_state()
    if state['window_package'] != package:
        raise AdbError(f"not sent: the focused window is {state['window']!r} ({state['window_package']}), the focused "
                       f"app {state['app_package']}; expected {package} (--force sends it anyway)")


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
        if arg('--lose-journal-key'):
            extras += ['--ez', 'bts.uitest.loseJournalKey', 'true']
        print(adb('shell', 'am', 'start', '-W', '-n', f'{PKG}/{ACTIVITY}', *extras).strip())
        time.sleep(3)
    elif cmd == 'dump':
        state = focus_state()
        if state['window_package'] != PKG:
            print(f"warning: the focused window is {state['window']!r} ({state['window_package']}), the focused app "
                  f"{state['app_package']}", file=sys.stderr)
        for i, n in enumerate(nodes(bool(arg('--all')))):
            if n['text'] or n['desc'] or (n['id'] and not n['id'].startswith('android:')):
                flag = '*' if n['clickable'] else ' '
                print(f"[{i}]{flag} {n['text'][:70]!r} | {n['desc'][:40]!r} | {n['id'].split('/')[-1]} | {n['cls']} | {n['bounds']}")
    elif cmd == 'tap':
        n = find(sys.argv[2], arg('--nth', 0, int), every_package=bool(arg('--all')))
        if not n:
            sys.exit(f'not found: {sys.argv[2]}')
        require_focus(n['package'] or PKG)
        adb('shell', 'input', 'tap', *map(str, n['center']))
        time.sleep(0.8)
        print('tapped', n['text'] or n['desc'] or n['id'], n['center'])
    elif cmd == 'tapxy':
        require_focus()
        adb('shell', 'input', 'tap', sys.argv[2], sys.argv[3])
        time.sleep(0.8)
    elif cmd == 'type':
        require_focus()
        type_text(sys.argv[2])
        time.sleep(0.5)
    elif cmd == 'key':
        code = {'back': '4', 'enter': '66', 'home': '3', 'tab': '61', 'del': '67'}[sys.argv[2]]
        require_focus()
        adb('shell', 'input', 'keyevent', code)
        time.sleep(0.8)
    elif cmd == 'swipe':
        d = sys.argv[2]
        w, h = 1080, 2220
        seg = {'up': (w // 2, h * 3 // 4, w // 2, h // 4), 'down': (w // 2, h // 4, w // 2, h * 3 // 4),
               'left': (w * 4 // 5, h // 2, w // 5, h // 2), 'right': (w // 5, h // 2, w * 4 // 5, h // 2)}[d]
        require_focus()
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
    elif cmd == 'focus':
        state = focus_state()
        print(f"window {state['window']!r} ({state['window_package']}), app {state['app_package']}")
    elif cmd == 'wait':
        deadline = time.time() + arg('--timeout', 20, int)
        while time.time() < deadline:
            try:
                if find(sys.argv[2], every_package=bool(arg('--all'))):
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
