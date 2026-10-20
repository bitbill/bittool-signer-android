#!/usr/bin/env python3
"""Tests of the translation overrides of import_ios_strings.py (QA round 1 sign-03), and that the committed
resources are what the importer generates:

    python3 -m unittest discover -s tools/l10n -p 'test_*.py'
"""
import contextlib
import io
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import import_ios_strings as importer  # noqa: E402

KEYS = ['Return', 'Step %lld of %lld', 'Back']
TRANSLATIONS = {
    'ja': {'Return': '改行', 'Step %lld of %lld': '%lld / %lld 手順', 'Back': '戻る'},
    'de': {'Return': 'Zurückgeben', 'Step %lld of %lld': 'Schritt %lld von %lld', 'Back': 'Zurück'},
}
CONTEXT = {'Return': {'where': 'Scan, step 4', 'meaning': 'send the signature back'}}


class OverridesTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.write('context.json', CONTEXT)

    def write(self, name, data):
        (self.dir / name).write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')

    def override(self, lang='ja', key='Return', **entry):
        fields = {'ios': '改行', 'value': '返送', 'reason': 'the keyboard key'}
        fields.update(entry)
        self.write(f'{lang}.json', {'language': lang, 'overrides': {key: fields}})

    def load(self):
        return importer.load_overrides(self.dir, KEYS, TRANSLATIONS)

    def assertRefused(self, fragment):
        with self.assertRaises(importer.CatalogueError) as error:
            self.load()
        self.assertIn(fragment, str(error.exception))

    def test_an_override_replaces_the_ios_translation_and_is_documented(self):
        self.override()
        applied, rows = self.load()
        self.assertEqual({'ja': {'Return': '返送'}}, applied)
        doc = importer.fixes_document(rows, {'Return': 'return_text'}, KEYS)
        self.assertIn('## “Return” (`return_text`)', doc)
        self.assertIn('| ja | 改行 | 返送 | the keyboard key |', doc)
        self.assertIn('- Meaning: send the signature back', doc)

    def test_no_overrides_directory_means_none(self):
        applied, rows = importer.load_overrides(self.dir / 'missing', KEYS, TRANSLATIONS)
        self.assertEqual(({}, []), (applied, rows))
        self.assertIn('No overrides at the moment.', importer.fixes_document(rows, {}, KEYS))

    def test_an_override_whose_ios_text_changed_must_be_reviewed(self):
        # iOS adopted a fix (or changed the text): the override no longer says what it replaces.
        self.override(ios='Enter')
        self.assertRefused('the iOS translation of \'Return\' is now \'改行\'')

    def test_an_override_equal_to_ios_is_redundant(self):
        self.override(ios='改行', value='改行')
        self.assertRefused('iOS already says')

    def test_an_unknown_key_or_language_is_refused(self):
        self.override(key='Not a key')
        self.assertRefused('is not an interface string')
        (self.dir / 'ja.json').unlink()
        self.override(lang='xx')
        self.assertRefused("'xx' is not a translated language")

    def test_the_file_must_name_its_language(self):
        self.write('ja.json', {'language': 'de', 'overrides': {'Return': {'ios': '改行', 'value': '返送', 'reason': 'r'}}})
        self.assertRefused('"language" must be \'ja\'')

    def test_every_field_is_required(self):
        self.override(reason=' ')
        self.assertRefused('needs non-empty "ios", "value" and "reason"')

    def test_specifiers_must_survive(self):
        self.write('de.json', {'language': 'de', 'overrides': {'Step %lld of %lld': {
            'ios': 'Schritt %lld von %lld', 'value': 'Schritt %lld', 'reason': 'r'}}})
        self.write('context.json', {'Step %lld of %lld': {'where': 'w', 'meaning': 'm'}})
        self.assertRefused('specifier mismatch')

    def test_context_is_required_and_must_not_go_stale(self):
        self.write('context.json', {})
        self.override()
        self.assertRefused("describe 'Return'")
        self.write('context.json', {**CONTEXT, 'Back': {'where': 'w', 'meaning': 'm'}})
        self.assertRefused("no override uses 'Back'")


class CommittedResourcesTest(unittest.TestCase):
    def test_the_committed_resources_are_up_to_date(self):
        importer.OUTPUTS.clear()
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            importer.generate(check=True)
        self.assertIn('up to date', out.getvalue())

    def test_the_return_step_is_corrected_in_every_affected_language(self):
        overrides = importer.OVERRIDES
        corrected = {p.stem for p in overrides.glob('*.json') if p.name != importer.CONTEXT_FILE}
        self.assertTrue({'ja', 'ko', 'es', 'es-419', 'it', 'pl', 'pt-BR', 'pt-PT', 'ru', 'tr', 'uk'} <= corrected)


if __name__ == '__main__':
    unittest.main()
