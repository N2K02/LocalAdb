#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?ANDROID_DISTRIBUTION_TOKEN missing}"
PUBLIC_REPO="N2K02/n2k02.github.io"
SLUG="localadb"
test -s "$APK_NAME"
SHA=$(sha256sum "$APK_NAME" | cut -d' ' -f1)
SIZE=$(stat -c '%s' "$APK_NAME")
if [[ "$CHANNEL" == "experimental" ]]; then
  TAG="$SLUG-exp-pr-${PR_NUMBER:?}-run-$GITHUB_RUN_NUMBER"
  VERSION_NAME="$VERSION$VERSION_SUFFIX"
  TITLE="LocalAdb experimental · PR #$PR_NUMBER · build $GITHUB_RUN_NUMBER"
  FLAGS=(--prerelease --latest=false)
else
  TAG="$SLUG-v$VERSION-build-$GITHUB_RUN_NUMBER"
  VERSION_NAME="$VERSION"
  TITLE="LocalAdb $VERSION"
  FLAGS=(--latest)
fi
if gh release view "$TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$APK_NAME" --repo "$PUBLIC_REPO" --clobber
else
  gh release create "$TAG" "$APK_NAME" --repo "$PUBLIC_REPO" --target main \
    --title "$TITLE" --notes-file release-notes.md "${FLAGS[@]}"
fi
URL="https://github.com/$PUBLIC_REPO/releases/download/$TAG/$APK_NAME"
PAGE="https://github.com/$PUBLIC_REPO/releases/tag/$TAG"
jq -n --arg channel "$CHANNEL" --arg tag "$TAG" \
  --arg versionName "$VERSION_NAME" --argjson versionCode "$VERSION_CODE" \
  --arg apkName "$APK_NAME" --arg apkUrl "$URL" --argjson apkSize "$SIZE" \
  --arg sha256 "$SHA" --arg pageUrl "$PAGE" \
  '{channel:$channel,tag:$tag,versionName:$versionName,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:[]}' > update-manifest.json
PATH_IN_REPO="$SLUG/updates/$CHANNEL.json"
OLD=$(gh api "repos/$PUBLIC_REPO/contents/$PATH_IN_REPO" --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [[ -n "$OLD" ]]; then
  OLD_VERSION=$(jq -r '.versionCode // 0' <<< "$OLD")
  if [[ "$OLD_VERSION" =~ ^[0-9]+$ ]] && (( OLD_VERSION >= VERSION_CODE )); then
    echo "Newer or equal update manifest already published"
    exit 0
  fi
fi
for attempt in 1 2 3 4 5; do
  OLD_SHA=$(gh api "repos/$PUBLIC_REPO/contents/$PATH_IN_REPO" --jq '.sha' 2>/dev/null || true)
  PARAMS=(--method PUT "repos/$PUBLIC_REPO/contents/$PATH_IN_REPO"
    -f "message=Publish $CHANNEL LocalAdb APK $GITHUB_RUN_NUMBER"
    -f "content=$(base64 -w0 update-manifest.json)" -f "branch=main")
  [[ -z "$OLD_SHA" ]] || PARAMS+=(-f "sha=$OLD_SHA")
  if gh api "${PARAMS[@]}" >/dev/null; then exit 0; fi
  if (( attempt == 5 )); then echo "Failed to publish update manifest" >&2; exit 1; fi
  sleep "$attempt"
done
