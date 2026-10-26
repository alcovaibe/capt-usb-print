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
        entry = a[-1].rsplit('.', 1)[-1]
        markers = {'OfficeEngineSmoke': 'CAPT_OFFICE_ENGINE_CONVERSION_SMOKE_PASSED',
                   'OfficeUriSmoke': 'CAPT_OFFICE_URI_LIFECYCLE_PASSED',
                   'OfficeSandboxSmoke': 'CAPT_OFFICE_SANDBOX_ANDROID_PASSED',
                   'OfficeWorkerSmoke': 'CAPT_OFFICE_WORKER_CONVERSION_PASSED',
                   'OfficeWorkerLifecycleSmoke': 'CAPT_OFFICE_WORKER_LIFECYCLE_PASSED',
                   'OfficeKernelLimitsSmoke': 'CAPT_OFFICE_KERNEL_LIMITS_PASSED',
                   'OfficeResourceStressSmoke': 'CAPT_OFFICE_RESOURCE_STRESS_PASSED',
                   'OfficePreviewUiSmoke': 'CAPT_OFFICE_PREVIEW_UI_PASSED',
                   'OfficeNativeUiSmoke': 'CAPT_OFFICE_NATIVE_UI_PASSED',
                   'OfficeExtendedSmoke': 'CAPT_OFFICE_EXTENDED_PASSED'}
        print('INSTRUMENTATION_RESULT: stream=' + markers[entry] + ' (synthetic only)')
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
    elif a[-1].endswith('office-worker-results.json'):
        print('{"syntheticOnly": true, "probe": "conversion"}')
    else:
        sys.exit(1)
'''
FAKE_TIMEOUT = '''#!/usr/bin/env python3
import os, sys
if sys.argv[1] in ('300', '360') and os.environ['PROBE_TEST_MODE'] == 'timeout':
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
        runners = [('engine', 'run-office-engine-smoke.sh', [], 'office-engine')]
        runners += [(mode, 'run-office-worker-smoke.sh', [mode], prefix) for mode, prefix in [
            ('resources', 'office-resources'), ('extended', 'office-extended'), ('uri', 'office-uri'),
            ('sandbox', 'office-sandbox'),
            ('conversion', 'office-worker'), ('lifecycle', 'office-worker-lifecycle'),
            ('kernel', 'office-kernel'), ('ui', 'office-ui'), ('native-ui', 'office-native-ui')]]
        scenarios = 0
        for name, script, arguments, prefix in runners:
            for mode in ['success', 'crash', 'transport', 'timeout', 'no-marker']:
                work = root / (name + '-' + mode)
                work.mkdir()
                previous_report = b'{"syntheticOnly":true,"previousEvidence":true}'
                (work / 'office-worker-results.json').write_bytes(previous_report)
                result = subprocess.run(['bash', str(ROOT / 'tools' / script), *arguments],
                    cwd=work, env={**os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                                   'PROBE_TEST_MODE': mode, 'PROBE_TEST_CALLS': str(work / 'calls.jsonl')}, capture_output=True, text=True, timeout=10)
                assert (result.returncode == 0) == (mode == 'success'), (name, mode, result.stdout, result.stderr)
                calls = [json.loads(line) for line in (work / 'calls.jsonl').read_text().splitlines()]
                assert calls[-1] == ['shell', 'am', 'force-stop', 'com.cryonum.captusbprint.debug']
                deletes_report = any(a[:2] == ['shell', 'run-as'] and 'rm' in a for a in calls)
                assert deletes_report == (name in ('engine', 'conversion', 'extended'))
                if name == 'conversion':
                    assert json.loads((work / 'office-worker-results.json').read_text())['probe'] == 'conversion'
                else:
                    assert (work / 'office-worker-results.json').read_bytes() == previous_report
                assert (work / (prefix + '-logcat.txt')).read_text().strip() == 'synthetic native crash diagnostic'
                assert (work / (prefix + '-crash.txt')).read_text().strip() == 'synthetic native crash diagnostic'
                if name == 'engine':
                    assert (work / 'office-probe-stage.txt').read_text().strip() == 'jni-init'
                scenarios += 1
    print(f'CAPT_OFFICE_PROBE_RUNNER_PASSED scenarios={scenarios} (not an engine test)')


if __name__ == '__main__':
    main()
