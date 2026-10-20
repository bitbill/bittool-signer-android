#!/usr/bin/env python3
"""Generates the Android string resources of :core:l10n from the iOS translation catalogue.

    python3 tools/l10n/import_ios_strings.py           # regenerate
    python3 tools/l10n/import_ios_strings.py --check   # fail if the generated files are out of date

Inputs (copied verbatim from the iOS repository, which owns them):
  tools/l10n/interface-strings.json   the ordered key list: Xcode's extraction plus the static text the offline
                                      engine produces (field labels, display actions, review titles)
  tools/l10n/translations/<lang>.json one file per language, keyed by the English source text

Outputs (never edit by hand — rerun this script):
  core/l10n/src/main/res/values*/strings_ios.xml           one file per Android resource directory
  core/l10n/src/main/kotlin/.../IosStrings.kt              English source text -> R.string id, for engine text
  tools/l10n/strings-index.tsv                             resource name <TAB> English text (for developers)

Conversion rules:
  - Resource names are slugs of the English text, persisted in strings-index.tsv: a recorded name is reused, so adding,
    removing or reordering iOS keys never renames another resource.
  - "%@" -> "%s", "%lld" -> "%d". Strings with two or more specifiers are written with positional specifiers
    ("%1$s", "%2$d") in every language; a translation that already uses positions keeps its order.
  - XML/Android escaping: \\ ' " @ ? at start, & < >, newlines; a value whose whitespace Android would collapse or
    trim (runs, ends, any non-ASCII space) is wrapped in double quotes.
  - Specifiers follow the iOS rule (scripts/write-localizations.py): a translation repeats the English sequence
    exactly, or is fully positional over 1..n with the same kinds.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/l10n'
RES = ROOT / 'core/l10n/src/main/res'
KT = ROOT / 'core/l10n/src/main/kotlin/com/bittool/signer/core/l10n/IosStrings.kt'

# iOS language code -> Android resource qualifiers that must carry it.
# Indonesian is shipped under the legacy "in" code (what Android resolves) and BCP-47 "id".
# Filipino: "fil" plus "tl" (older devices report Tagalog).
LANGUAGES = {
    'ar': ['ar'],
    'de': ['de'],
    'es': ['es'],
    'es-419': ['b+es+419'],
    'fil': ['fil', 'tl'],
    'fr': ['fr'],
    'id': ['in', 'b+id'],
    'it': ['it'],
    'ja': ['ja'],
    'ko': ['ko'],
    'pl': ['pl'],
    'pt-BR': ['pt-rBR'],
    'pt-PT': ['pt-rPT'],
    'ru': ['ru'],
    'th': ['th'],
    'tr': ['tr'],
    'uk': ['uk'],
    'vi': ['vi'],
    # API 24+ infers the script: zh-CN -> Hans, zh-TW / zh-HK -> Hant.
    'zh-Hans': ['b+zh+Hans'],
    'zh-Hant': ['b+zh+Hant'],
}

SPEC = re.compile(r'%(\d+\$)?(@|lld)')
JAVA_KEYWORDS = set('''abstract assert boolean break byte case catch char class const continue default do double else enum
extends final finally float for goto if implements import instanceof int interface long native new package private
protected public return short static strictfp super switch synchronized this throw throws transient try void volatile
while true false null'''.split())


def slug(text, used):
    s = SPEC.sub(' x ', text).lower()
    s = re.sub(r"[’']", '', s)
    s = re.sub(r'[^a-z0-9]+', '_', s).strip('_') or 'text'
    if len(s) > 56:
        cut = s[:56]
        s = cut[: cut.rfind('_')] if '_' in cut[20:] else cut
    if s[0].isdigit():
        s = 'n_' + s
    if s in JAVA_KEYWORDS:
        s += '_text'
    base, n = s, 2
    while s in used:
        s = f'{base}_{n}'
        n += 1
    used.add(s)
    return s


def android_format(source, translation):
    """Converts iOS specifiers to Android ones; positional whenever the source has two or more."""
    count = len(SPEC.findall(source))
    out, seq = [], 0

    def repl(m):
        nonlocal seq
        pos, kind = m.group(1), m.group(2)
        seq += 1
        conv = 's' if kind == '@' else 'd'
        if count < 2:
            return '%' + conv
        index = pos[:-1] if pos else str(seq)
        return f'%{index}${conv}'

    return SPEC.sub(repl, translation)


WHITESPACE = re.compile(r'\s')


def escape(value):
    v = value.replace('\\', '\\\\')
    v = v.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    v = v.replace('"', '\\"')
    v = v.replace('\n', '\\n').replace('\t', '\\t')
    needs_quotes = (value != value.strip() or '  ' in value
                    or any(WHITESPACE.match(c) and c not in ' \n\t' for c in value))
    if needs_quotes:
        return '"' + v + '"'           # inside quotes an apostrophe needs no escape
    v = v.replace("'", "\\'")
    if v[:1] in ('@', '?'):
        v = '\\' + v
    return v


def check_specifiers(lang, key, value):
    """The iOS rule: the same sequence as the English, or fully positional over 1..n with the same kinds."""
    want = [m.group(2) for m in SPEC.finditer(key)]
    got = list(SPEC.finditer(value))
    if [m.group(2) for m in got] == want and not any(m.group(1) for m in got):
        if len(want) != len(set(want)) and len(want) > 1:
            pass  # same order as English: converted to positions 1..n below, so repeats stay unambiguous
        return
    positional = {int(m.group(1)[:-1]): m.group(2) for m in got if m.group(1)}
    if (len(positional) == len(got) == len(want)
            and sorted(positional) == list(range(1, len(want) + 1))
            and all(positional[i + 1] == want[i] for i in range(len(want)))):
        return
    sys.exit(f'{lang}: specifier mismatch for {key!r}: {value!r}')


def write_xml(path, entries, header):
    path.parent.mkdir(parents=True, exist_ok=True)
    ignore = 'MissingTranslation,UnusedResources,TypographyEllipsis,TypographyQuotes,TypographyDashes,Typos'
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- {header} Generated by tools/l10n/import_ios_strings.py from the iOS catalogue: do not edit. -->',
             f'<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="{ignore}">']
    for name, value, formatted in entries:
        attr = '' if formatted else ' formatted="false"'
        lines.append(f'    <string name="{name}"{attr}>{escape(value)}</string>')
    lines.append('</resources>')
    OUTPUTS[path] = '\n'.join(lines) + '\n'


OUTPUTS = {}


def recorded_names():
    index = TOOLS / 'strings-index.tsv'
    if not index.exists():
        return {}
    names = {}
    for line in index.read_text(encoding='utf-8').splitlines()[1:]:
        name, english = line.split('\t', 1)
        names[english] = name
    return names


def main():
    check = '--check' in sys.argv[1:]
    keys = json.loads((TOOLS / 'interface-strings.json').read_text(encoding='utf-8'))
    recorded = recorded_names()
    used = set(recorded.values())
    names = {k: recorded[k] if k in recorded else slug(k, used) for k in keys}
    formatted = {k: bool(SPEC.search(k)) or '%' not in k for k in keys}

    # English: the key is the text. The camera rationale comes from the iOS Info.plist entry.
    camera_en = ('BitTool Signer scans signing requests from your other wallet and recovery-phrase QR codes. '
                 'Camera images are processed on this device and never leave it.')
    en = [(names[k], android_format(k, k), formatted[k]) for k in keys]
    en.append(('camera_usage_description', camera_en, True))
    write_xml(RES / 'values/strings_ios.xml', en, 'English (base).')

    for lang, qualifiers in LANGUAGES.items():
        data = json.loads((TOOLS / f'translations/{lang}.json').read_text(encoding='utf-8'))
        strings = data['strings']
        missing = [k for k in keys if k not in strings]
        if missing:
            sys.exit(f'{lang}: {len(missing)} strings missing, first {missing[0]!r}')
        entries = []
        for k in keys:
            check_specifiers(lang, k, strings[k])
            entries.append((names[k], android_format(k, strings[k]), formatted[k]))
        camera = data.get('infoPlist', {}).get('NSCameraUsageDescription')
        if camera:
            entries.append(('camera_usage_description', camera, True))
        for q in qualifiers:
            write_xml(RES / f'values-{q}/strings_ios.xml', entries, f'{data.get("name", lang)} ({lang}).')

    index = ['resource\tenglish'] + [f'{names[k]}\t{k}' for k in keys] + [f'camera_usage_description\t{camera_en}']
    OUTPUTS[TOOLS / 'strings-index.tsv'] = '\n'.join(index) + '\n'

    body = ',\n'.join(f'        {json.dumps(k, ensure_ascii=False)} to R.string.{names[k]}' for k in keys)
    OUTPUTS[KT] = (f'''package com.bittool.signer.core.l10n

// Generated by tools/l10n/import_ios_strings.py from the iOS catalogue: do not edit.

/**
 * Every interface string of the iOS catalogue, keyed by its English source text. The offline engine returns
 * English field labels, display actions and review titles; [IosStrings.lookup] finds their translation the way
 * iOS `ReviewText` does. Interface code uses `R.string.*` directly.
 */
object IosStrings {{
    val byEnglish: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {{ build() }}

    /** The resource id for an English source string, or null when the text is not in the catalogue. */
    fun lookup(english: String): Int? = byEnglish[english]

    private fun build(): Map<String, Int> = hashMapOf(
{body},
    )
}}
''')
    stale_dirs = [d for d in RES.glob('values*/strings_ios.xml') if d not in OUTPUTS]
    if check:
        stale = [str(p.relative_to(ROOT)) for p, text in OUTPUTS.items()
                 if not p.exists() or p.read_text(encoding='utf-8') != text]
        stale += [str(p.relative_to(ROOT)) for p in stale_dirs]
        if stale:
            sys.exit('Out of date (run tools/l10n/import_ios_strings.py): ' + ', '.join(stale[:8]))
        print('l10n resources are up to date')
        return
    for p in stale_dirs:
        p.unlink()
        if not any(p.parent.iterdir()):
            p.parent.rmdir()
    for p, text in OUTPUTS.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf-8')
    print(f'{len(keys)} strings x {len(LANGUAGES) + 1} languages; '
          f'{sum(len(q) for q in LANGUAGES.values()) + 1} resource directories')


if __name__ == '__main__':
    main()
