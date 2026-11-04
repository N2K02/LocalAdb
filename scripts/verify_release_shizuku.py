#!/usr/bin/env python3
"""Verify both classpaths used by Shizuku's user-service launcher in the built APK."""
import hashlib
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

STARTER = "Lmoe/shizuku/starter/ServiceStarter;"
PAYLOAD_SHA256 = "f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149"


def has_public_main(output: str, descriptor: str) -> bool:
    match = re.search(r"Class descriptor\s*:\s*'" + re.escape(descriptor) + r"'", output)
    if not match:
        return False
    body = re.split(r"(?m)^Class #[0-9]+", output[match.end():], maxsplit=1)[0]
    return bool(re.search(
        r"name\s*:\s*'main'\s*\n\s*type\s*:\s*'\(\[Ljava/lang/String;\)V'\s*\n\s*"
        r"access\s*:\s*[^\n]*\bPUBLIC\b[^\n]*\bSTATIC\b", body))


def verify_starter(archive: zipfile.ZipFile, dexdump: Path, directory: Path) -> None:
    for name in archive.namelist():
        if not re.fullmatch(r"classes[0-9]*\.dex", name):
            continue
        dex = directory / name
        dex.write_bytes(archive.read(name))
        output = subprocess.run([str(dexdump), str(dex)], check=True, capture_output=True,
                                text=True, timeout=120).stdout
        if has_public_main(output, STARTER):
            return
    raise ValueError("Missing public static moe.shizuku.starter.ServiceStarter.main(String[])")


def main() -> None:
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    tools = sorted((sdk / "build-tools").glob("*/dexdump"))
    if not tools:
        raise ValueError("Android SDK dexdump is required")
    with zipfile.ZipFile(sys.argv[1]) as app, tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        verify_starter(app, tools[-1], directory)
        payload = app.read("assets/embedded-shizuku-server.apk")
        if hashlib.sha256(payload).hexdigest() != PAYLOAD_SHA256:
            raise ValueError("Bundled Shizuku payload checksum mismatch")
        with zipfile.ZipFile(io.BytesIO(payload)) as embedded:
            verify_starter(embedded, tools[-1], directory)
    print("PASS: installed manager and pinned payload both expose the user-service starter main")


if __name__ == "__main__":
    main()
