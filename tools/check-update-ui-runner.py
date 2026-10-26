#!/usr/bin/env python3
"""Reproduce adb zero-exit failures, exact launcher recovery and one-retry boundary."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
ADB='''#!/usr/bin/env python3
import os,sys,pathlib,json
a=sys.argv[1:]; mode=os.environ['CASE']; state=pathlib.Path(os.environ['STATE'])
with open(str(state)+'.calls','a') as out:out.write(json.dumps(a)+'\\n')
if a[:3]==['shell','am','instrument']:
 n=int(state.read_text())+1 if state.exists() else 1;state.write_text(str(n))
 if mode=='transport':sys.exit(1)
 good=mode=='success' or (mode=='launcher' and n==2)
 print('CAPT_UPDATE_UI_SMOKE_PASSED' if good else 'CAPT_UPDATE_UI_SMOKE_FAILED')
elif a[:2]==['exec-out','cat']:
 text="Quickstep isn't responding" if mode in ['launcher','twice'] else "CAPT USB Print isn't responding"
 print('<hierarchy><node package="android" text="'+text+'"/></hierarchy>')
elif a[:3]==['shell','cmd','package']:
 print('com.android.launcher3/.uioverrides.QuickstepLauncher')
'''
with tempfile.TemporaryDirectory(prefix='capt-ui-runner-') as td:
    root=Path(td); bin_=root/'bin';bin_.mkdir();adb=bin_/'adb';adb.write_text(ADB);adb.chmod(0o755)
    for case in ['success','launcher','app-anr','twice','transport']:
        work=root/case;work.mkdir();state=work/'count'
        r=subprocess.run(['bash',str(ROOT/'tools/run-update-ui-smoke.sh')],cwd=work,
            env={**os.environ,'PATH':str(bin_)+os.pathsep+os.environ['PATH'],'CASE':case,'STATE':str(state)},
            capture_output=True,text=True,timeout=10)
        assert (r.returncode==0)==(case in ['success','launcher']), (case,r.stdout,r.stderr)
        assert int(state.read_text())==(2 if case in ['launcher','twice'] else 1)
        calls=Path(str(state)+'.calls').read_text()
        assert ('force-stop' in calls)==(case in ['launcher','twice'])
print('CAPT_UPDATE_UI_RUNNER_PASSED cases=5 (stock launcher only, assertions preserved)')
