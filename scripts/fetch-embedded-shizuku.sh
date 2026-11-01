#!/usr/bin/env bash
set -euo pipefail
# Apache-2.0 Shizuku-Next server, bundled as a payload, NOT installed as another app.
tag='v14.0.31-next'
filename='shizuku-v14.0.31-next.apk'
expected='f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149'
dest='app/src/main/assets/embedded-shizuku-server.apk'
mkdir -p "$(dirname "$dest")"
if [[ -f "$dest" ]] && [[ "$(sha256sum "$dest" | cut -d' ' -f1)" == "$expected" ]]; then
  exit 0
fi
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl --fail --location --silent --show-error --retry 3 \
  "https://github.com/rushiranpise/Shizuku-Next/releases/download/${tag}/${filename}" \
  --output "$tmp"
actual="$(sha256sum "$tmp" | cut -d' ' -f1)"
if [[ "$actual" != "$expected" ]]; then
  echo "::error::Embedded Shizuku payload digest mismatch: $actual" >&2
  exit 1
fi
mv "$tmp" "$dest"
