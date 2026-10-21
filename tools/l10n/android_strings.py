#!/usr/bin/env python3
"""Translations of the Android-only strings (the ones the iOS catalogue does not have).

    python3 tools/l10n/android_strings.py export             # write tools/l10n/android/source.json for translators
    python3 tools/l10n/android_strings.py generate [--lang L] # write values-<q>/strings_<area>.xml from the translations
    python3 tools/l10n/android_strings.py check              # fail if anything is missing, stale or out of date

Source (English, hand-written by each feature area, with translator comments):
  core/l10n/src/main/res/values/strings_<area>.xml   every file except the generated strings_ios.xml

Translations (owned here, one file per iOS language code, same codes as tools/l10n/translations/):
  tools/l10n/android/<lang>.json   {"language": "<lang>", "strings": {"<resource name>": {"source": "<the English it
                                   translates, verbatim>", "value": "<translation>"}}}
  A translation whose "source" no longer equals the English text is stale: check fails until it is re-translated.

Outputs (never edit by hand — rerun generate):
  core/l10n/src/main/res/values-<q>/strings_<area>.xml   for every qualifier of import_ios_strings.LANGUAGES

Rules: the same Android escaping as import_ios_strings.py; a translation must use exactly the specifiers of the
English (%s, %d, %1$s… — positional forms may reorder them); formatted="false" strings stay unformatted; product names
(BitTool Signer, BitTool Wallet, Google Play, APK, SHA-256, BIP39, …) stay as in English.
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import import_ios_strings as ios  # noqa: E402  (shared LANGUAGES table and escape())

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / 'core/l10n/src/main/res'
DIR = ROOT / 'tools/l10n/android'
SPEC = re.compile(r'%(?:(\d+)\$)?([sd])')


def unescape(raw):
    """The string an Android resource text stands for (after XML entity decoding)."""
    v = raw.strip()
    if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
        v = v[1:-1]
    else:
        v = re.sub(r'\s+', ' ', v)
    out, i = [], 0
    while i < len(v):
        c = v[i]
        if c == '\\' and i + 1 < len(v):
            n = v[i + 1]
            if n == 'n':
                out.append('\n'); i += 2; continue
            if n == 't':
                out.append('\t'); i += 2; continue
            if n == 'u' and i + 5 < len(v) + 0 and re.match(r'[0-9a-fA-F]{4}', v[i + 2:i + 6]):
                out.append(chr(int(v[i + 2:i + 6], 16))); i += 6; continue
            out.append(n); i += 2; continue
        out.append(c)
        i += 1
    return ''.join(out)


def source_strings():
    """[(area, name, english, formatted, context)] in file order."""
    rows = []
    for path in sorted(RES.glob('values/strings_*.xml')):
        if path.name == 'strings_ios.xml':
            continue
        area = path.stem[len('strings_'):]
        text = path.read_text(encoding='utf-8')
        # ElementTree drops comments by default; keep the last comment before each string as context.
        parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
        root = ET.fromstring(text, parser=parser)
        comment = ''
        for el in root:
            if el.tag is ET.Comment:
                comment = ' '.join((el.text or '').split())
                continue
            if el.tag != 'string':
                if el.tag in ('plurals', 'string-array'):
                    sys.exit(f'{path.name}: <{el.tag}> is not supported by android_strings.py; use <string>')
                continue
            if el.get('translatable') == 'false':
                continue
            raw = ''.join(el.itertext())
            rows.append((area, el.get('name'), unescape(raw), el.get('formatted') != 'false', comment))
            comment = ''
    return rows


def specs(text, formatted):
    if not formatted:
        return []
    found = []
    seq = 0
    for m in SPEC.finditer(text.replace('%%', '')):
        seq += 1
        found.append((int(m.group(1)) if m.group(1) else seq, m.group(2)))
    return sorted(found)


def load(lang):
    path = DIR / f'{lang}.json'
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding='utf-8'))
    if data.get('language') != lang:
        sys.exit(f'{path.name}: "language" must be "{lang}"')
    return data.get('strings', {})


def problems(lang, rows, strings):
    out = []
    names = {r[1] for r in rows}
    for area, name, english, formatted, _ in rows:
        entry = strings.get(name)
        if not isinstance(entry, dict) or not entry.get('value', '').strip():
            out.append(f'{lang}: {name} not translated')
            continue
        if entry.get('source') != english:
            out.append(f'{lang}: {name} is stale (English changed)')
            continue
        if specs(entry['value'], formatted) != specs(english, formatted):
            out.append(f'{lang}: {name} changes the format specifiers')
    for name in strings:
        if name not in names:
            out.append(f'{lang}: {name} is not an Android string any more (remove it)')
    return out


def render(lang, area, rows, strings):
    entries = [(name, strings[name]['value'], formatted) for a, name, _, formatted, _ in rows if a == area]
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- {lang}: Android-only strings of strings_{area}.xml. Generated by tools/l10n/android_strings.py from '
             f'tools/l10n/android/{lang}.json: do not edit. -->',
             '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation,'
             'UnusedResources,TypographyEllipsis,TypographyQuotes,TypographyDashes,Typos">']
    for name, value, formatted in entries:
        attr = '' if formatted else ' formatted="false"'
        lines.append(f'    <string name="{name}"{attr}>{ios.escape(value)}</string>')
    lines.append('</resources>')
    return '\n'.join(lines) + '\n'


def outputs(rows, langs):
    areas = sorted({r[0] for r in rows})
    files = {}
    for lang in langs:
        strings = load(lang)
        if problems(lang, rows, strings):
            continue
        for q in ios.LANGUAGES[lang]:
            for area in areas:
                files[RES / f'values-{q}/strings_{area}.xml'] = render(lang, area, rows, strings)
    return files


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'check'
    rows = source_strings()
    langs = list(ios.LANGUAGES)
    if '--lang' in sys.argv:
        langs = [sys.argv[sys.argv.index('--lang') + 1]]
    if cmd == 'export':
        DIR.mkdir(parents=True, exist_ok=True)
        source = {name: {'area': area, 'english': english, 'formatted': formatted, 'context': ctx}
                  for area, name, english, formatted, ctx in rows}
        (DIR / 'source.json').write_text(json.dumps({'strings': source}, ensure_ascii=False, indent=1) + '\n',
                                         encoding='utf-8')
        print(f'{len(rows)} Android-only strings exported to tools/l10n/android/source.json')
    elif cmd == 'generate':
        bad = [p for lang in langs for p in problems(lang, rows, load(lang))]
        files = outputs(rows, langs)
        for path, text in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        print(f'{len(files)} files written')
        if bad:
            print('\n'.join(bad[:40]))
            sys.exit(f'{len(bad)} problems: those languages were not generated')
    elif cmd == 'check':
        bad = [p for lang in langs for p in problems(lang, rows, load(lang))]
        stale = [str(p.relative_to(ROOT)) for p, t in outputs(rows, langs).items()
                 if not p.exists() or p.read_text(encoding='utf-8') != t]
        if bad or stale:
            print('\n'.join((bad + [f'out of date: {s}' for s in stale])[:60]))
            sys.exit(f'{len(bad)} translation problems, {len(stale)} out-of-date files')
        print(f'Android-only strings: {len(rows)} x {len(langs)} languages up to date')
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
