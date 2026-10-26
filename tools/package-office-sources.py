#!/usr/bin/env python3
"""Build a corresponding-source evidence bundle for the three exact native packages. CI only."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.request


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('packages',type=Path); p.add_argument('core',type=Path)
    p.add_argument('narrow',type=Path); p.add_argument('output',type=Path)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root/'config/office/libreoffice.json').read_text())
    assert subprocess.check_output(['git','-C',str(args.core),'rev-parse','HEAD'],text=True).strip()==lock['commit']
    manifests = {}; archives = {}
    for package in sorted(args.packages.iterdir()):
        if not package.is_dir(): continue
        inventory = json.loads((package/'inventory.json').read_text())
        source = json.loads((package/'licenses/source-inputs.json').read_text())
        assert source['upstream']==lock
        manifests[inventory['abi']] = {'nativeInventory':inventory,'sourceInputs':source}
        for archive in source['externalArchives']:
            assert Path(archive['file']).name == archive['file']
            previous=archives.setdefault(archive['file'],archive)
            assert previous==archive
    assert set(manifests)=={'arm64-v8a','armeabi-v7a','x86_64'}
    with tempfile.TemporaryDirectory(prefix='capt-source-bundle-') as tmp:
        directory=Path(tmp); downloads=directory/'external-tarballs';downloads.mkdir()
        for name,entry in sorted(archives.items()):
            target=downloads/name
            # Exact content digests from the real build, never mutable package-manager versions.
            last=None
            for base in ('https://dev-www.libreoffice.org/src/','https://dev-www.libreoffice.org/src/'+entry['sha256']+'/'):
                try:
                    with urllib.request.urlopen(base+name,timeout=60) as src,target.open('wb') as dst:
                        total=0
                        while block:=src.read(1024*1024):
                            total+=len(block); assert total<=entry['bytes'];dst.write(block)
                    assert target.stat().st_size==entry['bytes'] and sha(target)==entry['sha256']
                    last=None;break
                except Exception as error: last=error;target.unlink(missing_ok=True)
            if last is not None: raise RuntimeError('Exact source input unavailable: '+name) from last
        for repo,name in ((args.core,'libreoffice-core.tar'),(root,'capt-integration.tar'),(args.narrow,'liberation-narrow-source.tar')):
            subprocess.run(['git','-C',str(repo),'archive','--format=tar','-o',str(directory/name),'HEAD'],check=True)
        narrow_commit=subprocess.check_output(['git','-C',str(args.narrow),'rev-parse','HEAD'],text=True).strip()
        (directory/'build-manifests.json').write_text(json.dumps({'abis':manifests,
            'narrowEditableSourceCommit':narrow_commit,'coreCommit':lock['commit']},indent=2)+'\n')
        (directory/'REBUILD.txt').write_text(
            'CAPT Office corresponding-source evidence\n\n'
            'Extract libreoffice-core.tar and capt-integration.tar into sibling directories. '
            'The exact external/tarballs inputs are in external-tarballs; copy them into the core checkout. '
            'Install the SDK/NDK/tool versions in config/office/libreoffice.json and .github/workflows/office-native.yml. '
            'Run tools/office/build-libreoffice.sh CORE ABI for each ABI. It applies the CAPT policy/Calc patches. '
            'config_host.mk and per-ABI inventories in build-manifests.json record the accepted build. '
            'The editable Liberation Sans Narrow source (GPLv2 plus Liberation exceptions) is included separately; '
            'OpenSymbol SFD is in the core source. Preserve all original copyright/license notices. '
            'App: build with -PofficeEngineDir=PACKAGE, as in the workflow. Modified APKs can be installed '
            'with a user-controlled signing key after uninstalling a differently signed copy; save needed data first. '
            'This source bundle does not authorize publishing a release and is not a hardware print acceptance.\n')
        with tarfile.open(args.output,'w:gz') as bundle:
            for item in sorted(directory.iterdir()):bundle.add(item,arcname=item.name,recursive=True)
    assert args.output.stat().st_size>0
    args.output.with_suffix(args.output.suffix+'.sha256').write_text(sha(args.output)+'  '+args.output.name+'\n')
    print(f'CAPT_OFFICE_CORRESPONDING_SOURCES_PACKAGED archives={len(archives)} abis=3')


if __name__=='__main__': main()
