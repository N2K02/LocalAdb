#!/usr/bin/env bash
set -euo pipefail

# Pinned Shizuku-Next Apache-2.0 server, bundled and NOT installed separately.
tag='v14.0.31-next'
filename='shizuku-v14.0.31-next.apk'
expected='f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149'
dest='app/src/main/assets/embedded-shizuku-server.apk'

mkdir -p "$(dirname "$dest")"
actual=''
if [[ -f "$dest" ]]; then
  actual="$(sha256sum "$dest" | cut -d' ' -f1)"
fi
if [[ "$actual" != "$expected" ]]; then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  curl --fail --location --silent --show-error --retry 3 \
    "https://github.com/rushiranpise/Shizuku-Next/releases/download/$tag/$filename" \
    --output "$tmp"
  actual="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "::error::Embedded Shizuku payload digest mismatch: $actual" >&2
    exit 1
  fi
  mv "$tmp" "$dest"
fi

# JNI dependencies are required for a usable server, not merely a starter exit 0.
python3 - "$dest" <<'PY'
import sys
import zipfile

apk_path = sys.argv[1]
supported = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
with zipfile.ZipFile(apk_path) as apk:
    entries = set(apk.namelist())
    found = []
    for abi in supported:
        starter = f'lib/{abi}/libshizuku.so'
        rish = f'lib/{abi}/librish.so'
        if starter not in entries and rish not in entries:
            continue
        if starter not in entries or rish not in entries:
            sys.exit(f'::error::Shizuku JNI pair incomplete for {abi}: '
                     f'libshizuku={starter in entries}, librish={rish in entries}')
        for entry in (starter, rish):
            with apk.open(entry) as stream:
                if stream.read(4) != b'\x7fELF':
                    sys.exit(f'::error::Invalid ELF native library: {entry}')
        found.append(abi)
    if 'arm64-v8a' not in found:
        sys.exit('::error::Pinned Shizuku APK missing required ARM64 native starter/Rish JNI')
    print('Verified Shizuku JNI dependencies: ' + ', '.join(found))
PY
