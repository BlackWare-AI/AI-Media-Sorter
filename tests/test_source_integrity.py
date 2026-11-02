"""Isolated source fixtures; guard output must never include source/secret contents."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.check_source_integrity import check_tree


class SourceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_extracted_source_without_git_validates_manifests_and_resources(self):
        self.write("app/src/main/AndroidManifest.xml", '<manifest package="example"><application /></manifest>')
        self.write("app/src/main/res/values-tr/strings.xml", '<resources><string name="sample">Türkçe</string></resources>')
        self.write("app/src/androidTest/AndroidManifest.xml", '<manifest package="example.test" />')
        self.write("app/src/main/Main.kt", 'val text = "<<<<<<< quoted source data"\n')
        result = check_tree(self.root)
        self.assertEqual("extracted_source", result["mode"])
        self.assertEqual("PASS", result["status"])
        self.assertEqual(3, result["xml_files_checked"])
        self.assertEqual([], result["issues"])

    def test_exact_nested_markers_are_rejected_without_logging_labels_or_content(self):
        secret = "PRIVATE_PROVIDER_OR_KEY_TEXT"
        self.write(".github/workflows/android.yml", "name: CI\n<<<<<<< " + secret + "\n=======\n>>>>>>> other\n")
        self.write("app/src/main/Main.kt", "<<<<<<< first\n<<<<<<< second\n=======\n>>>>>>> nested\n=======\n>>>>>>> outer\n")
        result = check_tree(self.root)
        self.assertEqual("FAIL", result["status"])
        markers = [item for item in result["issues"] if item["code"] == "UNRESOLVED_MERGE_MARKER"]
        self.assertEqual(9, len(markers))
        self.assertEqual([2, 3, 4], [item["line"] for item in markers if item["path"].startswith(".github/")])
        self.assertNotIn(secret, json.dumps(result))
        self.assertTrue(all(set(item) == {"path", "line", "code"} for item in result["issues"]))

    def test_malformed_xml_reports_only_path_line_and_static_code(self):
        secret = "PRIVATE_XML_VALUE"
        self.write("app/src/main/AndroidManifest.xml", '<manifest>\n<application name="' + secret + '"></manifest>')
        result = check_tree(self.root)
        self.assertEqual([{"path": "app/src/main/AndroidManifest.xml", "line": 2, "code": "INVALID_XML"}], result["issues"])
        self.assertNotIn(secret, json.dumps(result))

    def test_excludes_build_sdk_git_caches_and_secret_files(self):
        self.write("app/src/main/Main.kt", "val ok = true\n")
        for name in ("app/build/generated/Bad.kt", "android-sdk/platforms/Bad.xml", "jdk17/Bad.java",
                     ".gradle/Bad.kt", ".kotlin/Bad.kt", "node_modules/Bad.xml", ".git/Bad.kt",
                     ".secrets/private.json", ".aws/config.json", "credentials/auth.json",
                     ".env", ".env.private.json", "credentials.json", "keystore.properties", "local.properties"):
            self.write(name, "<<<<<<< PRIVATE_SECRET_CONTENT\n")
        result = check_tree(self.root)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, result["source_files_checked"])
        self.assertNotIn("PRIVATE_SECRET_CONTENT", json.dumps(result))

    @unittest.skipUnless(shutil.which("git"), "Git required for tracked-source fixture")
    def test_git_mode_ignores_untracked_files_and_handles_a_real_worktree_root(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True, capture_output=True)
        self.write("app/src/main/Main.kt", "val ok = true\n")
        subprocess.run(["git", "-C", str(self.root), "add", "app/src/main/Main.kt"], check=True, capture_output=True)
        self.write("untracked.kt", "<<<<<<< not committed\n")
        result = check_tree(self.root)
        self.assertEqual("git_tracked", result["mode"])
        self.assertEqual("PASS", result["status"])
        # Inspect working-tree changes to a tracked file before CI commits/builds.
        self.write("app/src/main/Main.kt", "<<<<<<< HEAD\n=======\n>>>>>>> restored\n")
        self.assertEqual(3, len(check_tree(self.root)["issues"]))

    @unittest.skipUnless(shutil.which("git"), "Git required for nested-archive fixture")
    def test_extracted_archive_inside_another_git_tree_uses_its_own_source(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True, capture_output=True)
        self.write("parent.kt", "<<<<<<< parent only\n")
        self.write("extracted/app/src/main/Main.kt", "val ok = true\n")
        result = check_tree(self.root / "extracted")
        self.assertEqual("extracted_source", result["mode"])
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, result["source_files_checked"])

    def test_symlink_does_not_read_outside_source_tree(self):
        with tempfile.TemporaryDirectory() as elsewhere:
            secret = Path(elsewhere) / "secret.kt"
            secret.write_text("<<<<<<< PRIVATE_OUTSIDE_SECRET\n")
            (self.root / "linked.kt").symlink_to(secret)
            result = check_tree(self.root)
            self.assertEqual("FAIL", result["status"])
            self.assertIn("UNSAFE_SOURCE_PATH", [item["code"] for item in result["issues"]])
            self.assertNotIn(elsewhere, json.dumps(result))
            self.assertNotIn("PRIVATE_OUTSIDE_SECRET", json.dumps(result))

    def test_empty_or_missing_source_is_not_a_success(self):
        self.assertEqual("FAIL", check_tree(self.root)["status"])
        self.assertEqual("SOURCE_ROOT_UNAVAILABLE", check_tree(self.root / "missing")["issues"][0]["code"])


if __name__ == "__main__":
    unittest.main()
