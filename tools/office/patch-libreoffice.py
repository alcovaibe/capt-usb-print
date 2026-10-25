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
    # Include a synchronous Android-only UNO adapter in the existing LOK translation unit.
    adapter = Path(__file__).with_name('capt-calc-layout.hxx')
    (path.parent / adapter.name).write_bytes(adapter.read_bytes())
    text += '\n#if defined(ANDROID) && HAVE_FEATURE_ANDROID_LOK\n#include "capt-calc-layout.hxx"\n#endif\n'
    path.write_text(text)
    # The same explicit servlet classpath is used by F-Droid for this pinned upstream.
    hsqldb = args.source / 'external/hsqldb/ExternalProject_hsqldb.mk'
    build = hsqldb.read_text()
    hsqldb.write_text(replace_once(build, '\t\t$(ICECREAM_RUN) "$(ANT)" \\\n',
        '\t\tCLASSPATH=/usr/share/java/servlet-api.jar \\\n'
        '\t\t$(ICECREAM_RUN) "$(ANT)" \\\n'))
    # These source hashes identify the precise local adaptation, not user documents.
    (args.source / 'capt-office-patch.json').write_text(json.dumps({
        'path': 'desktop/source/lib/init.cxx',
        'before': hashlib.sha256(original.encode()).hexdigest(),
        'after': hashlib.sha256(text.encode()).hexdigest(),
        'macros': 'NEVER_EXECUTE', 'documentUpdates': 'NO_UPDATE',
        'externalResourcesSandbox': 'NOT_IMPLEMENTED',
        'calcLayoutAdapterSha256': hashlib.sha256(adapter.read_bytes()).hexdigest(),
        'calcFitWidth': 'ScaleToPagesX=1,ScaleToPagesY=0,A4'
    }, indent=2) + '\n')
    print('Pinned LibreOffice load-policy patch applied; resource sandbox still required')


if __name__ == '__main__':
    main()
