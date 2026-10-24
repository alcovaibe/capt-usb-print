#!/usr/bin/env python3
"""Narrow, pinned-source patch. Not a complete external-resource sandbox."""
import argparse
import hashlib
import json
from pathlib import Path


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit('Pinned LibreOffice source changed; review patch before building')
    return text.replace(old, new, 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    path = args.source / 'desktop/source/lib/init.cxx'
    original = path.read_text()
    text = replace_once(original,
        '#include <com/sun/star/document/MacroExecMode.hpp>',
        '#include <com/sun/star/document/MacroExecMode.hpp>\n'
        '#include <com/sun/star/document/UpdateDocMode.hpp>')
    start = '#if defined(ANDROID) && HAVE_FEATURE_ANDROID_LOK\n        sal_Int16 nMacroExecMode'
    offset = text.find(start)
    if offset < 0:
        raise SystemExit('Expected macro policy block missing')
    end = text.find('#endif', offset)
    if end < 0:
        raise SystemExit('Unterminated macro policy block')
    block = text[offset:end + len('#endif')]
    text = replace_once(text, block,
        '// CAPT integration: macros are never executed, regardless of load options.\n'
        '        sal_Int16 nMacroExecMode = document::MacroExecMode::NEVER_EXECUTE;')
    text = replace_once(text,
        'comphelper::makePropertyValue(u"MacroExecutionMode"_ustr, nMacroExecMode),',
        'comphelper::makePropertyValue(u"MacroExecutionMode"_ustr, nMacroExecMode),\n'
        '            comphelper::makePropertyValue(u"UpdateDocMode"_ustr, '
        'sal_Int16(document::UpdateDocMode::NO_UPDATE)),')
    path.write_text(text)
    # These source hashes identify the precise local adaptation, not user documents.
    (args.source / 'capt-office-patch.json').write_text(json.dumps({
        'path': 'desktop/source/lib/init.cxx',
        'before': hashlib.sha256(original.encode()).hexdigest(),
        'after': hashlib.sha256(text.encode()).hexdigest(),
        'macros': 'NEVER_EXECUTE', 'documentUpdates': 'NO_UPDATE',
        'externalResourcesSandbox': 'NOT_IMPLEMENTED'
    }, indent=2) + '\n')
    print('Pinned LibreOffice load-policy patch applied; resource sandbox still required')


if __name__ == '__main__':
    main()
