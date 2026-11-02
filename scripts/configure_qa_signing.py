#!/usr/bin/env python3
"""Prepare optional private QA signing, exposing only mode and public certificate SHA-256.

QA_DEBUG_KEYSTORE_BASE64 is a CI secret, never a command argument or repository file.
It must contain a private androiddebugkey entry with standard debug passwords android.
No production release signing is configured by this helper.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


class SigningError(Exception):
    pass


def _export_env(env, values):
    destination = env.get("GITHUB_ENV")
    if not destination:
        if values.get("QA_KEYSTORE_PATH"):
            raise SigningError("ENV_EXPORT_UNAVAILABLE")
        return
    if any("\n" in value or "\r" in value or "\0" in value for value in values.values()):
        raise SigningError("ENV_EXPORT_INVALID")
    try:
        flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(destination, flags, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as output:
            for name, value in values.items():
                output.write(name + "=" + value + "\n")
    except OSError:
        raise SigningError("ENV_EXPORT_FAILED") from None


def _keytool(env, args):
    java_home = env.get("JAVA_HOME")
    executable = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
    if not executable or not Path(executable).is_file():
        raise SigningError("KEYTOOL_UNAVAILABLE")
    try:
        result = subprocess.run([executable, "-J-Duser.language=en", "-J-Duser.country=US", *args],
                                capture_output=True, timeout=30, env=env, umask=0o077)
    except (OSError, subprocess.SubprocessError):
        raise SigningError("KEYSTORE_VALIDATION_FAILED") from None
    if result.returncode:
        # Never expose provider messages, keytool output, key contents or paths.
        raise SigningError("KEYSTORE_VALIDATION_FAILED")
    return result.stdout


def configure(env=None):
    env = dict(os.environ if env is None else env)
    encoded = env.get("QA_DEBUG_KEYSTORE_BASE64", "")
    if not encoded:
        _export_env(env, {"QA_KEYSTORE_PATH": "", "QA_SIGNING_MODE": "ephemeral_runner_debug"})
        return {"schema_version": 1, "mode": "ephemeral_runner_debug", "certificate_sha256": None}
    if len(encoded) > 2_000_000:
        raise SigningError("KEYSTORE_INPUT_INVALID")
    try:
        data = base64.b64decode("".join(encoded.split()), validate=True)
        if not 1 <= len(data) <= 1_000_000:
            raise ValueError()
    except (ValueError, TypeError):
        raise SigningError("KEYSTORE_INPUT_INVALID") from None
    runner_temp = env.get("RUNNER_TEMP")
    if not runner_temp or not Path(runner_temp).is_dir():
        raise SigningError("RUNNER_TEMP_UNAVAILABLE")
    directory = None
    success = False
    try:
        directory = Path(tempfile.mkdtemp(prefix="aims-qa-signing-", dir=runner_temp))
        directory.chmod(0o700)
        source = directory / "input.keystore"
        fd = os.open(source, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(data)
        target = directory / "qa-debug.jks"
        # Importing verifies access to the private key with the expected debug
        # key password, and keeps one predictable alias/type for Gradle.
        _keytool(env, ["-importkeystore", "-srckeystore", str(source), "-srcalias", "androiddebugkey",
                       "-srcstorepass", "android", "-srckeypass", "android", "-destkeystore", str(target),
                       "-deststoretype", "JKS", "-destalias", "androiddebugkey", "-deststorepass", "android",
                       "-destkeypass", "android", "-noprompt"])
        target.chmod(0o600)
        listing = _keytool(env, ["-list", "-v", "-keystore", str(target), "-alias", "androiddebugkey", "-storepass", "android"])
        if not re.search(rb"(?m)^Entry type: PrivateKeyEntry\s*$", listing):
            raise SigningError("PRIVATE_KEY_REQUIRED")
        certificate = directory / "certificate.der"
        _keytool(env, ["-exportcert", "-keystore", str(target), "-alias", "androiddebugkey", "-storepass", "android", "-file", str(certificate)])
        der = certificate.read_bytes()
        if not 1 <= len(der) <= 65_536:
            raise SigningError("CERTIFICATE_INVALID")
        fingerprint = hashlib.sha256(der).hexdigest()
        source.unlink()
        certificate.unlink()
        _export_env(env, {"QA_KEYSTORE_PATH": str(target), "QA_SIGNING_MODE": "maintainer_qa_keystore"})
        success = True
        return {"schema_version": 1, "mode": "maintainer_qa_keystore", "certificate_sha256": fingerprint}
    except SigningError:
        raise
    except (OSError, ValueError):
        raise SigningError("SIGNING_PREPARATION_FAILED") from None
    finally:
        if directory is not None and not success:
            shutil.rmtree(directory, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path("qa-signing.json"))
    args = parser.parse_args()
    try:
        report = configure()
        result = 0
    except SigningError as error:
        report = {"schema_version": 1, "mode": "configuration_failed", "error_code": str(error)}
        result = 2
    text = json.dumps(report, indent=2) + "\n"
    try:
        args.report.write_text(text, encoding="utf-8")
    except OSError:
        print('{"mode":"configuration_failed","error_code":"REPORT_WRITE_FAILED"}')
        return 2
    print(text, end="")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
