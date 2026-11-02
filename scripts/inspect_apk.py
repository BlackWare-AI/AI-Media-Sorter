#!/usr/bin/env python3
"""Inspect the built APK itself, including its merged permissions and ELF segments."""
import hashlib
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
sdk = pathlib.Path(os.environ["ANDROID_HOME"])
analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
def manifest(field):
    return subprocess.check_output([str(analyzer), "manifest", field, str(apk)], text=True).strip()
permissions = manifest("permissions").splitlines()
assert "com.aimediasorter.app.qa.documents" not in manifest("print"), "Test provider leaked into app APK"
assert any("android.permission.INTERNET" in p for p in permissions), "Cloud-capable APK must declare Internet; upload consent is enforced in code."
assert not any("MANAGE_EXTERNAL_STORAGE" in p for p in permissions), permissions
assert manifest("min-sdk") == "34"
assert manifest("target-sdk") == "35"
subprocess.run([str(sdk / "build-tools/35.0.0/zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
signing = subprocess.check_output([str(sdk / "build-tools/35.0.0/apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signing)
assert fingerprints, "APK signing certificate fingerprint was not reported"
package = manifest("application-id")
if apk.name.endswith("-release-QA.apk"):
    assert package == "com.aimediasorter.app.qa", "QA APK must coexist with existing production/debug installations"
native = []
with zipfile.ZipFile(apk) as archive:
    assert "assets/models/media_visual_embedder.onnx" in archive.namelist()
    assert "assets/termux/manifest_executor.py" in archive.namelist()
    for name in archive.namelist():
        if not name.endswith(".so"):
            continue
        data = archive.read(name)
        assert data[:4] == b"\x7fELF", name
        endian = "<" if data[5] == 1 else ">"
        is64 = data[4] == 2
        if is64:
            offset = struct.unpack_from(endian + "Q", data, 32)[0]
            entry_size, count = struct.unpack_from(endian + "HH", data, 54)
        else:
            offset = struct.unpack_from(endian + "I", data, 28)[0]
            entry_size, count = struct.unpack_from(endian + "HH", data, 42)
        alignments = []
        for i in range(count):
            entry = offset + i * entry_size
            kind = struct.unpack_from(endian + "I", data, entry)[0]
            if kind == 1:
                alignments.append(struct.unpack_from(endian + ("Q" if is64 else "I"),
                    data, entry + (48 if is64 else 28))[0])
        native.append({"path": name, "load_segment_alignment": alignments,
            "supports_16kb_alignment": all(a >= 16384 for a in alignments)})
report = {"apk": apk.name, "bytes": apk.stat().st_size,
    "package": package, "version_code": manifest("version-code"), "version_name": manifest("version-name"),
    "signing_certificate_sha256": fingerprints,
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "permissions": permissions, "min_sdk": 34, "target_sdk": 35, "native_libraries": native}
directory = pathlib.Path("app/build/reports")
directory.mkdir(parents=True, exist_ok=True)
(directory / (apk.stem + "-inspection.json")).write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))

assert all(lib['supports_16kb_alignment'] for lib in native if '/arm64-v8a/' in lib['path'] or '/x86_64/' in lib['path']), '64-bit native libraries must support 16 KB load alignment'
