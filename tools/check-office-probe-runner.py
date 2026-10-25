#!/usr/bin/env python3
"""Reproduce adb's zero-exit process crash without an emulator or native engine."""
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
FAKE_ADB = '''#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
with open(os.environ["PROBE_TEST_CALLS"], "a") as calls:
    calls.write(json.dumps(a) + "\\n")
if a[:3] == ['shell', 'am', 'instrument']:
    mode = os.environ['PROBE_TEST_MODE']
    if mode == 'success':
        print('INSTRUMENTATION_RESULT: stream=CAPT_OFFICE_ENGINE_CONVERSION_SMOKE_PASSED (synthetic only)')
        print('INSTRUMENTATION_CODE: -1')
    elif mode == 'crash':
        print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.')
        print('INSTRUMENTATION_CODE: 0')
    elif mode == 'transport':
        sys.exit(1)
    else:
        print('INSTRUMENTATION_CODE: 0')
elif a[:1] == ['logcat'] and '-d' in a:
    print('synthetic native crash diagnostic')
elif a[:1] == ['exec-out']:
    if a[-1].endswith('stage.txt'):
        print('jni-init')
    else:
        sys.exit(1)
'''
FAKE_TIMEOUT = '''#!/usr/bin/env python3
import os, sys
if sys.argv[1] == '300' and os.environ['PROBE_TEST_MODE'] == 'timeout':
    sys.exit(124)
os.execvp(sys.argv[2], sys.argv[2:])
'''


def main():
    with tempfile.TemporaryDirectory(prefix='capt-probe-runner-') as temp:
        root = Path(temp)
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        for name, script in [('adb', FAKE_ADB), ('timeout', FAKE_TIMEOUT)]:
            path = bin_dir / name
            path.write_text(script)
            path.chmod(0o755)
        for mode in ['success', 'crash', 'transport', 'timeout', 'no-marker']:
            work = root / mode
            work.mkdir()
            result = subprocess.run(['bash', str(ROOT / 'tools/run-office-engine-smoke.sh')],
                cwd=work, env={**os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                               'PROBE_TEST_MODE': mode, 'PROBE_TEST_CALLS': str(work / 'calls.jsonl')}, capture_output=True, text=True, timeout=10)
            assert (result.returncode == 0) == (mode == 'success'), (mode, result.stdout, result.stderr)
            calls = [json.loads(line) for line in (work / 'calls.jsonl').read_text().splitlines()]
            assert calls[-1] == ['shell', 'am', 'force-stop', 'com.cryonum.captusbprint.debug']
            assert any(a[:2] == ['shell', 'run-as'] and 'rm' in a for a in calls)
            assert (work / 'office-engine-logcat.txt').read_text().strip() == 'synthetic native crash diagnostic'
            assert (work / 'office-probe-stage.txt').read_text().strip() == 'jni-init'
    print('CAPT_OFFICE_PROBE_RUNNER_PASSED scenarios=5 (not an engine test)')


if __name__ == '__main__':
    main()
