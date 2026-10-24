#!/usr/bin/env bash
set -euo pipefail
source_dir="$(realpath "${1:?source checkout}")"
abi="${2:?ABI}"
capt_root="$(cd "$(dirname "$0")/../.." && pwd)"
lock="$capt_root/config/office/libreoffice.json"
expected="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$lock")"
distro="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["abis"][sys.argv[2]])' "$lock" "$abi")"
ndk_version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ndk"])' "$lock")"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$expected"
test -n "${ANDROID_HOME:?Android SDK required}"
test -d "$ANDROID_HOME/ndk/$ndk_version"
python3 "$capt_root/tools/office/patch-libreoffice.py" "$source_dir"
cd "$source_dir"
./autogen.sh --enable-release-build --with-vendor='CAPT build probe' \
  --with-android-package-name=org.documentfoundation.libreoffice \
  --with-android-sdk="$ANDROID_HOME" --with-android-ndk="$ANDROID_HOME/ndk/$ndk_version" \
  --with-distro="$distro" --with-parallelism=3 --without-junit
make build
test -s "android/jniLibs/$abi/liblo-native-code.so"
test -d android/source/assets
test -d android/source/assets_strippedUI
mkdir -p capt-office-package
cp -a android/jniLibs capt-office-package/
cp -a android/source/assets capt-office-package/
cp -a android/source/assets_strippedUI capt-office-package/
cp -a android/Bootstrap/src capt-office-package/java-bootstrap
cp capt-office-patch.json capt-office-package/
cp "$lock" capt-office-package/source-lock.json
mkdir -p capt-office-package/licenses
find . -maxdepth 1 -type f -name 'COPYING*' -exec cp '{}' capt-office-package/licenses/ \;
for notice_dir in instdir/share/readme instdir/share/license readlicense_oo/license; do
  if [[ -d "$notice_dir" ]]; then
    mkdir -p "capt-office-package/licenses/$notice_dir"
    cp -a "$notice_dir/." "capt-office-package/licenses/$notice_dir/"
  fi
done
git diff -- desktop/source/lib/init.cxx external/hsqldb/ExternalProject_hsqldb.mk > capt-office-package/load-policy.patch
python3 "$capt_root/tools/office/inspect-package.py" capt-office-package "$abi"
tar -czf "capt-office-$abi.tar.gz" -C capt-office-package .
echo "CAPT_OFFICE_NATIVE_SOURCE_BUILD_PASSED $abi (not integrated into app)"
