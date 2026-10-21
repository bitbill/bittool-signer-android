#!/usr/bin/env python3
"""Tests of tools/qa/ui.py against a fake adb (no device): python3 -m unittest discover -s tools/qa -p 'test_*.py'"""
import pathlib
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui  # noqa: E402

DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="Wallets" content-desc="" resource-id="tab.wallets" package="com.bittool.signer.debug" class="android.view.View" clickable="true" bounds="[0,2000][270,2200]" />
</hierarchy>'''

# `uiautomator dump --windows`: one tree per window, the dialog window included.
WINDOWS_DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id="0">
<window index="0"><hierarchy rotation="0"><node text="Settings" resource-id="" package="com.bittool.signer.debug" class="android.widget.TextView" bounds="[0,0][100,50]" /></hierarchy></window>
<window index="1"><hierarchy rotation="0"><node text="OK" resource-id="root.error.ok" package="com.bittool.signer.debug" class="android.widget.Button" clickable="true" bounds="[600,1200][800,1300]" /></hierarchy></window>
</display></displays>'''

# `--windows` lists the windows top-most first: the navigation bar and the keyboard lie above the app's dialog and
# activity, and their nodes share names with the app's ("Back", "Done").
SYSTEM_ABOVE_APP_DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id="0">
<window index="0"><hierarchy rotation="0"><node text="" content-desc="Back" resource-id="com.android.systemui:id/back" package="com.android.systemui" class="android.widget.ImageView" clickable="true" bounds="[100,2100][300,2220]" /></hierarchy></window>
<window index="1"><hierarchy rotation="0"><node text="" content-desc="Done" resource-id="" package="com.google.android.inputmethod.latin" class="android.view.View" clickable="true" bounds="[900,1900][1080,2000]" /></hierarchy></window>
<window index="2"><hierarchy rotation="0"><node text="Done" content-desc="" resource-id="signing.done" package="com.bittool.signer.debug" class="android.widget.Button" clickable="true" bounds="[600,1200][800,1300]" /></hierarchy></window>
<window index="3"><hierarchy rotation="0"><node text="" content-desc="Back" resource-id="" package="com.bittool.signer.debug" class="android.view.View" clickable="true" bounds="[0,100][150,250]" /></hierarchy></window>
</display></displays>'''


# Another app on the same emulator, with the same test tags, while the signer's window has no hierarchy (QA round 2).
FOREIGN_TAGS_DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id="0">
<window index="0"><hierarchy rotation="0"><node text="Scan" content-desc="" resource-id="tab.scan" package="com.bittool.wallet.debug" class="android.view.View" clickable="true" bounds="[270,2000][540,2200]" /></hierarchy></window>
<window index="1"><hierarchy rotation="0"><node text="" content-desc="" resource-id="" package="com.bittool.signer.debug" class="android.widget.FrameLayout" bounds="[0,0][1080,2220]" /></hierarchy></window>
</display></displays>'''

# `dumpsys window displays; dumpsys window windows`, the signer's activity focused.
SIGNER_FOCUSED = """WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 rootTasks=10
  mCurrentFocus=Window{24184f0 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity}
  mFocusedApp=ActivityRecord{5a8da49 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity t904}
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #5 Window{e2190f7 u0 InputMethod}:
    mOwnerUid=10109 showForAllUsers=false package=com.google.android.inputmethod.latin appop=NONE
  Window #6 Window{24184f0 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity}:
    mOwnerUid=10335 showForAllUsers=false package=com.bittool.signer.debug appop=NONE
"""

# Another app in front.
OTHER_APP_FOCUSED = """WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  mCurrentFocus=Window{20b1588 u0 com.bittool.wallet.debug/com.bittool.wallet.MainActivity}
  mFocusedApp=ActivityRecord{1b2c3d4 u0 com.bittool.wallet.debug/com.bittool.wallet.MainActivity t905}
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #8 Window{20b1588 u0 com.bittool.wallet.debug/com.bittool.wallet.MainActivity}:
    mOwnerUid=10278 showForAllUsers=false package=com.bittool.wallet.debug appop=NONE
"""

# The whole `dumpsys window`: its first section is the LAST ANR's, with an old, stale mCurrentFocus; the signer's
# dialog window (titled, not named by package) has the focus now.
FULL_WITH_STALE_ANR = """WINDOW MANAGER LAST ANR (dumpsys window lastanr)
  ANR time: 10/21/26 14:35:12
  Window manager state:
  mCurrentFocus=Window{9d8c7b6 u0 Application Not Responding: com.bittool.wallet}
  mFocusedApp=ActivityRecord{6a5b4c3 u0 com.bittool.wallet/.MainActivity t42}
WINDOW MANAGER POLICY STATE (dumpsys window policy)
    mKeyguardOccluded=false
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 rootTasks=10
  mCurrentFocus=Window{150ec4b u0 BitTool Signer}
  mFocusedApp=ActivityRecord{5a8da49 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity t904}
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #6 Window{150ec4b u0 BitTool Signer}:
    mDisplayId=0 rootTaskId=905 mSession=Session{7d76a4a 5409:u0a10335} mClient=android.os.BinderProxy@6c96933
    mOwnerUid=10335 showForAllUsers=false package=com.bittool.signer.debug appop=NONE
  Window #7 Window{24184f0 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity}:
    mOwnerUid=10335 showForAllUsers=false package=com.bittool.signer.debug appop=NONE
"""

# BiometricPrompt (a system window) over the signer.
PROMPT_FOCUSED = """WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  mCurrentFocus=Window{7f804c9 u0 BiometricPrompt}
  mFocusedApp=ActivityRecord{5a8da49 u0 com.bittool.signer.debug/com.bittool.signer.MainActivity t904}
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #3 Window{7f804c9 u0 BiometricPrompt}:
    mOwnerUid=10135 showForAllUsers=true package=com.android.systemui appop=NONE
"""


class FakeAdb:
    """Answers the adb invocations ui.py makes; [dump] is what `uiautomator dump` prints per call, [focus] what
    `dumpsys window displays; dumpsys window windows` prints."""

    def __init__(self, dump_outputs, file_bytes=DUMP, logcat='', focus=SIGNER_FOCUSED):
        self.calls = []
        self.dump_outputs = list(dump_outputs)
        self.file = None
        self.file_bytes = file_bytes
        self.logcat = logcat
        self.focus = focus

    def __call__(self, cmd, capture_output=True, text=None, encoding=None, errors=None):
        args = cmd[1:]
        self.calls.append(args)
        out, code = '', 0
        if args[:3] == ['shell', 'rm', '-f']:
            self.file = None
        elif args[:3] == ['shell', 'uiautomator', 'dump']:
            out = self.dump_outputs.pop(0) if self.dump_outputs else 'ERROR: could not get idle state.'
            if 'dumped to' in out:
                self.file = self.file_bytes
        elif args[:2] == ['exec-out', 'cat']:
            data = self.file or b''
            return subprocess.CompletedProcess(cmd, 0 if self.file else 1, data if not text else data.decode(), b'')
        elif args[:1] == ['logcat']:
            out = self.logcat
        elif args[:1] == ['shell'] and len(args) == 2 and args[1].startswith('dumpsys window'):
            out = self.focus
        if text:
            return subprocess.CompletedProcess(cmd, code, out, '')
        return subprocess.CompletedProcess(cmd, code, out.encode(), b'')


class UiToolTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(ui.time, 'sleep', lambda _: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def fake(self, *args, **kwargs):
        fake = FakeAdb(*args, **kwargs)
        patcher = mock.patch.object(ui.subprocess, 'run', fake)
        patcher.start()
        self.addCleanup(patcher.stop)
        return fake

    def test_adb_output_is_text_so_logcat_filters_it(self):
        self.fake([], logcat='10-21 08:00:00.000 E AndroidRuntime: FATAL EXCEPTION: main\nI other: fine\n')
        out = ui.adb('logcat', '-d', '-t', '2000')
        self.assertIsInstance(out, str)
        self.assertEqual(['10-21 08:00:00.000 E AndroidRuntime: FATAL EXCEPTION: main'], ui.logcat_problems(out))

    def test_dump_deletes_the_previous_file_first_and_reads_a_fresh_dump(self):
        fake = self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'])
        nodes = ui.nodes()
        self.assertEqual(['shell', 'rm', '-f', ui.DUMP_PATH], fake.calls[0])
        self.assertEqual(['shell', 'uiautomator', 'dump', '--windows', ui.DUMP_PATH], fake.calls[1])
        self.assertEqual('tab.wallets', nodes[0]['id'])

    def test_a_failed_dump_is_an_error_not_the_previous_screen(self):
        fake = self.fake(['ERROR: could not get idle state.'] * ui.DUMP_ATTEMPTS)
        fake.file = DUMP  # a stale dump of an earlier screen is still on the device
        with self.assertRaises(ui.AdbError) as error:
            ui.nodes()
        self.assertIn('could not get idle state', str(error.exception))
        self.assertFalse(any(call[:2] == ['exec-out', 'cat'] for call in fake.calls), 'the stale file was read')

    def test_a_failed_dump_is_retried(self):
        self.fake(['ERROR: could not get idle state.', 'UI hierchary dumped to: /sdcard/qa-ui.xml'])
        self.assertEqual('Wallets', ui.find('tab.wallets')['text'])

    def test_a_device_without_the_windows_option_dumps_the_active_window(self):
        fake = self.fake(['Usage: uiautomator dump [--verbose] [file]', 'UI hierchary dumped to: /sdcard/qa-ui.xml'])
        self.assertEqual(1, len(ui.nodes()))
        self.assertEqual(['shell', 'uiautomator', 'dump', ui.DUMP_PATH], fake.calls[2])

    def test_dialog_windows_are_included(self):
        self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'], file_bytes=WINDOWS_DUMP)
        ok = ui.find('root.error.ok')
        self.assertEqual((700, 1250), ok['center'])
        self.assertTrue(ok['clickable'])

    def test_the_apps_windows_are_matched_before_the_systems(self):
        self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'] * 4, file_bytes=SYSTEM_ABOVE_APP_DUMP)
        self.assertEqual((75, 175), ui.find('Back', every_package=True)['center'], "the navigation bar's Back was found")
        self.assertEqual('signing.done', ui.find('Done', every_package=True)['id'], "a keyboard key was found")
        # Top-most first within each group: the app's dialog, its activity, then the system bar and the keyboard.
        order = [n['bounds'] for n in ui.nodes(every_package=True)]
        self.assertEqual([(600, 1200, 800, 1300), (0, 100, 150, 250), (100, 2100, 300, 2220), (900, 1900, 1080, 2000)],
                         order)
        # Without --all only the app's own nodes are listed.
        self.assertEqual([(600, 1200, 800, 1300), (0, 100, 150, 250)], [n['bounds'] for n in ui.nodes()])

    def test_another_apps_nodes_with_the_same_tags_are_never_matched(self):
        # The signer's window came back without a hierarchy; the other app's "tab.scan" must not be tapped.
        self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'] * 2, file_bytes=FOREIGN_TAGS_DUMP)
        self.assertIsNone(ui.find('tab.scan'))
        self.assertEqual('com.bittool.wallet.debug', ui.find('tab.scan', every_package=True)['package'])

    def test_a_node_without_a_package_is_not_the_apps(self):
        xml = '''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="Wallets" resource-id="tab.wallets" class="android.view.View" bounds="[0,2000][270,2200]" /></hierarchy>'''
        self.assertEqual([], ui.parse_nodes(xml))
        self.assertEqual(1, len(ui.parse_nodes(xml, every_package=True)))

    def test_the_focus_is_read_from_the_display_not_the_last_anr(self):
        state = ui.parse_focus(FULL_WITH_STALE_ANR)
        self.assertEqual('BitTool Signer', state['window'])
        self.assertEqual('com.bittool.signer.debug', state['window_package'], "a dialog's owner, by its window entry")
        self.assertEqual('com.bittool.signer.debug', state['app_package'])
        self.assertEqual('com.bittool.wallet.debug', ui.parse_focus(OTHER_APP_FOCUSED)['window_package'])
        self.assertEqual({'window': None, 'window_package': None, 'app_package': None}, ui.parse_focus(''))

    def command(self, *argv):
        patcher = mock.patch.object(sys, 'argv', ['ui.py', *argv])
        patcher.start()
        try:
            with mock.patch('builtins.print'):
                ui.command(argv[0])
        finally:
            patcher.stop()

    def test_input_is_refused_while_another_app_has_the_focus(self):
        fake = self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'], focus=OTHER_APP_FOCUSED)
        for argv in (('tap', 'tab.wallets'), ('tapxy', '10', '10'), ('type', 'abc'), ('key', 'enter'), ('swipe', 'up')):
            with self.assertRaises(ui.AdbError, msg=argv):
                self.command(*argv)
        self.assertFalse(any(call[:2] == ['shell', 'input'] or (call[:1] == ['shell'] and 'input ' in call[1])
                             for call in fake.calls), 'no input was sent')

    def test_input_reaches_the_app_when_it_has_the_focus_or_with_force(self):
        fake = self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'] * 2)
        self.command('tap', 'tab.wallets')
        self.assertIn(['shell', 'input', 'tap', '135', '2100'], fake.calls)
        fake.focus = OTHER_APP_FOCUSED
        self.command('key', 'back', '--force')
        self.assertIn(['shell', 'input', 'keyevent', '4'], fake.calls)

    def test_a_system_node_is_tapped_only_while_its_window_has_the_focus(self):
        prompt = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id="0">
<window index="0"><hierarchy rotation="0"><node text="Use a Wallet Password" resource-id="" package="com.android.systemui" class="android.widget.Button" clickable="true" bounds="[100,1800][500,1900]" /></hierarchy></window>
</display></displays>'''
        fake = self.fake(['UI hierchary dumped to: /sdcard/qa-ui.xml'] * 2, file_bytes=prompt, focus=PROMPT_FOCUSED)
        self.command('tap', 'Use a Wallet Password', '--all')
        self.assertIn(['shell', 'input', 'tap', '300', '1850'], fake.calls)
        fake.focus = SIGNER_FOCUSED
        with self.assertRaises(ui.AdbError):
            self.command('tap', 'Use a Wallet Password', '--all')

    def test_lose_keys_relaunches_the_previous_run_without_a_reset(self):
        shots = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, shots, True)
        (shots / '.run-id').write_text('11111111-2222-3333-4444-555555555555\n', encoding='utf-8')
        for patch in (mock.patch.object(ui, 'SHOTS', str(shots)),
                      mock.patch.object(sys, 'argv', ['ui.py', 'launch', '--lose-keys'])):
            patch.start()
            self.addCleanup(patch.stop)
        fake = self.fake([])
        with mock.patch('builtins.print'):
            ui.command('launch')
        start = next(call for call in fake.calls if call[:3] == ['shell', 'am', 'start'])
        self.assertEqual('11111111-2222-3333-4444-555555555555', start[start.index('bts.uitest.run') + 1])
        self.assertEqual('true', start[start.index('bts.uitest.loseDeviceKeys') + 1])
        self.assertNotIn('bts.uitest.reset', start)

    def launch(self, *flags, run_id=None):
        """`ui.py launch` with [flags]; the `am start` arguments it sends."""
        shots = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, shots, True)
        if run_id:
            (shots / '.run-id').write_text(run_id + '\n', encoding='utf-8')
        patches = (mock.patch.object(ui, 'SHOTS', str(shots)),
                   mock.patch.object(sys, 'argv', ['ui.py', 'launch', *flags]))
        for patch in patches:
            patch.start()
        try:
            fake = self.fake([])
            with mock.patch('builtins.print'):
                ui.command('launch')
        finally:
            for patch in patches:
                patch.stop()
        return next(call for call in fake.calls if call[:3] == ['shell', 'am', 'start'])

    def test_unreadable_journal_asks_the_harness_for_an_unreadable_activity_journal(self):
        # A fresh run (fixtures, reset): Activity is unreadable from the first load.
        start = self.launch('--fixtures', '1', '--unreadable-journal')
        self.assertEqual('--ez', start[start.index('bts.uitest.unreadableJournal') - 1])
        self.assertEqual('true', start[start.index('bts.uitest.unreadableJournal') + 1])
        self.assertIn('bts.uitest.reset', start)
        # With --keep: the previous run's journal (its receipts) becomes unreadable.
        start = self.launch('--keep', '--unreadable-journal', run_id='11111111-2222-3333-4444-555555555555')
        self.assertEqual('11111111-2222-3333-4444-555555555555', start[start.index('bts.uitest.run') + 1])
        self.assertEqual('true', start[start.index('bts.uitest.unreadableJournal') + 1])
        self.assertNotIn('bts.uitest.reset', start)

    def test_a_plain_launch_leaves_the_journal_alone(self):
        start = self.launch('--fixtures', '1')
        self.assertNotIn('bts.uitest.unreadableJournal', start)
        self.assertNotIn('bts.uitest.loseJournalKey', start)

    def test_lose_journal_key_asks_the_harness_to_lose_the_activity_journal_key(self):
        # QA round 3 activity-keyboard-07: the way to Activity's restart notice ("Activity Started Again").
        start = self.launch('--fixtures', '1', '--lose-journal-key')
        self.assertEqual('--ez', start[start.index('bts.uitest.loseJournalKey') - 1])
        self.assertEqual('true', start[start.index('bts.uitest.loseJournalKey') + 1])
        self.assertIn('bts.uitest.reset', start, 'a fresh run works: the harness gives it a journal the lost key sealed')
        self.assertNotIn('bts.uitest.unreadableJournal', start)
        # With --keep: the previous run's records are what the loss takes; the wallet keys are kept.
        start = self.launch('--keep', '--lose-journal-key', run_id='11111111-2222-3333-4444-555555555555')
        self.assertEqual('11111111-2222-3333-4444-555555555555', start[start.index('bts.uitest.run') + 1])
        self.assertEqual('true', start[start.index('bts.uitest.loseJournalKey') + 1])
        self.assertNotIn('bts.uitest.reset', start)
        self.assertNotIn('bts.uitest.loseDeviceKeys', start)

    def test_long_text_is_typed_in_short_quoted_chunks(self):
        fake = self.fake([])
        text = "It's a long & tricky (text); $HOME \"quoted\""
        ui.type_text(text)
        typed = [call[1] for call in fake.calls if call[0] == 'shell']
        self.assertTrue(all(cmd.startswith('input text ') for cmd in typed))
        chunks = [shlex.split(cmd[len('input text '):])[0] for cmd in typed]
        self.assertTrue(all(len(c.replace('%s', ' ')) <= ui.TYPE_CHUNK for c in chunks), chunks)
        self.assertEqual(text, ''.join(chunks).replace('%s', ' '))

    def test_non_ascii_text_is_refused(self):
        with self.assertRaises(ui.AdbError):
            ui.text_chunks('密码')


if __name__ == '__main__':
    unittest.main()
