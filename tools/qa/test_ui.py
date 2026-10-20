#!/usr/bin/env python3
"""Tests of tools/qa/ui.py against a fake adb (no device): python3 -m unittest discover -s tools/qa -p 'test_*.py'"""
import pathlib
import shlex
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui  # noqa: E402

DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="Wallets" content-desc="" resource-id="tab.wallets" class="android.view.View" clickable="true" bounds="[0,2000][270,2200]" />
</hierarchy>'''

# `uiautomator dump --windows`: one tree per window, the dialog window included.
WINDOWS_DUMP = b'''<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id="0">
<window index="0"><hierarchy rotation="0"><node text="Settings" resource-id="" class="android.widget.TextView" bounds="[0,0][100,50]" /></hierarchy></window>
<window index="1"><hierarchy rotation="0"><node text="OK" resource-id="root.error.ok" class="android.widget.Button" clickable="true" bounds="[600,1200][800,1300]" /></hierarchy></window>
</display></displays>'''


class FakeAdb:
    """Answers the adb invocations ui.py makes; [dump] is what `uiautomator dump` prints per call."""

    def __init__(self, dump_outputs, file_bytes=DUMP, logcat=''):
        self.calls = []
        self.dump_outputs = list(dump_outputs)
        self.file = None
        self.file_bytes = file_bytes
        self.logcat = logcat

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
