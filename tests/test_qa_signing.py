"""Isolated signing-config contracts; fake keytool results, never real credentials."""
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.configure_qa_signing import configure, main, SigningError


class QaSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.envfile = self.root / "github-env"
        self.envfile.touch()
        java_bin = self.root / "jdk" / "bin"
        java_bin.mkdir(parents=True)
        (java_bin / "keytool").touch()
        self.env = {"RUNNER_TEMP": str(self.root), "GITHUB_ENV": str(self.envfile),
                    "JAVA_HOME": str(java_bin.parent)}
        self.secret = base64.b64encode(b"SYNTHETIC_PRIVATE_KEY_MATERIAL").decode()

    def tearDown(self):
        self.temp.cleanup()

    def supplied(self):
        return dict(self.env, QA_DEBUG_KEYSTORE_BASE64=self.secret)

    def fake_keytool(self, args, **kwargs):
        self.assertEqual(0o077, kwargs["umask"])
        if "-importkeystore" in args:
            source = Path(args[args.index("-srckeystore") + 1])
            self.assertEqual(0o700, stat.S_IMODE(source.parent.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(source.stat().st_mode))
            Path(args[args.index("-destkeystore") + 1]).write_bytes(b"SYNTHETIC_IMPORTED_KEY")
            output = b""
        elif "-list" in args:
            output = b"Alias name: androiddebugkey\nEntry type: PrivateKeyEntry\n"
        else:
            self.assertIn("-exportcert", args)
            Path(args[args.index("-file") + 1]).write_bytes(b"SYNTHETIC_PUBLIC_CERT_DER")
            output = b""
        return subprocess.CompletedProcess(args, 0, stdout=output, stderr=b"UNTRUSTED_PROVIDER_NOTICE")

    def test_absent_secret_is_honestly_ephemeral_and_clears_stale_path(self):
        with patch("scripts.configure_qa_signing.subprocess.run") as run:
            report = configure(dict(self.env, QA_KEYSTORE_PATH="stale-private-file"))
        run.assert_not_called()
        self.assertEqual("ephemeral_runner_debug", report["mode"])
        self.assertIsNone(report["certificate_sha256"])
        self.assertIn("QA_KEYSTORE_PATH=\n", self.envfile.read_text())
        self.assertFalse(list(self.root.glob("aims-qa-signing-*")))

    def test_invalid_supplied_secret_fails_without_falling_back_or_echoing_input(self):
        bad = "AQ.PRIVATE_TEST_MARKER!!not-base64"
        with self.assertRaises(SigningError) as error:
            configure(dict(self.env, QA_DEBUG_KEYSTORE_BASE64=bad))
        self.assertEqual("KEYSTORE_INPUT_INVALID", str(error.exception))
        self.assertNotIn(bad, str(error.exception))
        self.assertEqual("", self.envfile.read_text())

    def test_supplied_key_uses_private_temp_path_and_exports_public_fingerprint(self):
        with patch("scripts.configure_qa_signing.subprocess.run", side_effect=self.fake_keytool):
            report = configure(self.supplied())
        self.assertEqual("maintainer_qa_keystore", report["mode"])
        self.assertEqual(hashlib.sha256(b"SYNTHETIC_PUBLIC_CERT_DER").hexdigest(), report["certificate_sha256"])
        exported = dict(line.split("=", 1) for line in self.envfile.read_text().splitlines())
        key = Path(exported["QA_KEYSTORE_PATH"])
        self.assertTrue(key.is_file())
        self.assertEqual(0o600, stat.S_IMODE(key.stat().st_mode))
        self.assertEqual([key], list(key.parent.iterdir()))
        self.assertNotIn(self.secret, json.dumps(report) + self.envfile.read_text())
        self.assertNotIn("SYNTHETIC_PRIVATE_KEY_MATERIAL", json.dumps(report))

    def test_cli_redacts_provider_failures_and_reports_finite_code(self):
        report = self.root / "report.json"
        output = io.StringIO()
        provider = b"PRIVATE_PROVIDER_MESSAGE " + self.secret.encode()
        with patch.dict(os.environ, self.supplied(), clear=True), \
             patch("scripts.configure_qa_signing.subprocess.run", return_value=subprocess.CompletedProcess([], 1, provider, provider)), \
             patch("sys.argv", ["configure_qa_signing.py", "--report", str(report)]), contextlib.redirect_stdout(output):
            code = main()
        self.assertEqual(2, code)
        self.assertEqual("KEYSTORE_VALIDATION_FAILED", json.loads(report.read_text())["error_code"])
        self.assertNotIn(self.secret, output.getvalue() + report.read_text())
        self.assertNotIn("PRIVATE_PROVIDER_MESSAGE", output.getvalue() + report.read_text())
        self.assertFalse(list(self.root.glob("aims-qa-signing-*")))
        self.assertEqual("", self.envfile.read_text())

    def test_public_certificate_without_private_key_is_rejected(self):
        def certificate_only(args, **kwargs):
            if "-list" in args:
                return subprocess.CompletedProcess(args, 0, b"Entry type: trustedCertEntry\n", b"")
            return self.fake_keytool(args, **kwargs)
        with patch("scripts.configure_qa_signing.subprocess.run", side_effect=certificate_only):
            with self.assertRaises(SigningError) as error:
                configure(self.supplied())
        self.assertEqual("PRIVATE_KEY_REQUIRED", str(error.exception))
        self.assertFalse(list(self.root.glob("aims-qa-signing-*")))

    def test_missing_runner_temp_fails_closed(self):
        env = self.supplied()
        env.pop("RUNNER_TEMP")
        with self.assertRaises(SigningError) as error:
            configure(env)
        self.assertEqual("RUNNER_TEMP_UNAVAILABLE", str(error.exception))

    def test_env_file_symlink_is_not_followed_and_private_files_are_removed(self):
        protected = self.root / "protected.txt"
        protected.write_text("unchanged")
        self.envfile.unlink()
        self.envfile.symlink_to(protected)
        with patch("scripts.configure_qa_signing.subprocess.run", side_effect=self.fake_keytool):
            with self.assertRaises(SigningError) as error:
                configure(self.supplied())
        self.assertEqual("ENV_EXPORT_FAILED", str(error.exception))
        self.assertEqual("unchanged", protected.read_text())
        self.assertFalse(list(self.root.glob("aims-qa-signing-*")))

    def test_newline_in_private_path_cannot_inject_environment_commands(self):
        directory = self.root / "injected\nUNEXPECTED=value"
        directory.mkdir()
        with patch("scripts.configure_qa_signing.subprocess.run", side_effect=self.fake_keytool):
            with self.assertRaises(SigningError) as error:
                configure(dict(self.supplied(), RUNNER_TEMP=str(directory)))
        self.assertEqual("ENV_EXPORT_INVALID", str(error.exception))
        self.assertEqual("", self.envfile.read_text())
        self.assertFalse(list(directory.iterdir()))
