"""CI metadata parser tests with fake adb/emulator; no actual device or network."""
import json
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'scripts' / 'capture_runtime_diagnostics.sh'


class RuntimeDiagnosticsTest(unittest.TestCase):
    def capture(self, fixture, stdout=False):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            (root / 'fixture.json').write_text(json.dumps(fixture))
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
d=json.loads(Path(os.environ['RUNTIME_DIAGNOSTIC_TEST_FIXTURE']).read_text())
key=' '.join(sys.argv[1:])
if key in d: print(d[key])
else: sys.exit(1)
''')
            adb.chmod(0o755)
            emulator = root / 'emulator'
            emulator.write_text('#!/bin/sh\necho "Android emulator version 37.2.12.0 (build_id 16428233) (CL:N/A)"\n')
            emulator.chmod(0o755)
            output = root / 'report.json'
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       RUNTIME_DIAGNOSTIC_TEST_FIXTURE=str(root / 'fixture.json'))
            args = ['bash', str(SCRIPT)] + ([] if stdout else [str(output)])
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode)
            return json.loads(result.stdout if stdout else output.read_text()), result.stdout + result.stderr

    def test_crash_extracts_useful_frames_without_sensitive_messages_or_paths(self):
        secret = 'AQ.SyntheticSecretNeverExport'
        raw = '''E/AndroidRuntime: FATAL EXCEPTION: ReferenceQueueDaemon
E/AndroidRuntime: Process: com.aimediasorter.app.debug, PID: 123
E/AndroidRuntime: java.lang.NullPointerException: PRIVATE_PHOTO.jpg /storage/private/PRIVATE_PHOTO.jpg API_KEY_HERE
E/AndroidRuntime:     at java.lang.ref.ReferenceQueue.enqueuePending(ReferenceQueue.java:239)
E/AndroidRuntime:     at com.aimediasorter.app.domain.cloud.GeminiClient.classify(CloudProtocol.kt:1)
E/AndroidRuntime: Authorization: Bearer API_KEY_HERE
F/libc: Fatal signal 11 (SIGSEGV), code 1
F/DEBUG: #00 pc 0000000000a885a6 /apex/com.android.art/lib64/libart.so (NterpGetStaticField+294)
F/DEBUG: #01 pc 0000000000000000 <unknown>
E/AndroidRuntime: arbitrary raw provider body PRIVATE_BODY
'''.replace('API_KEY_HERE', secret)
        report, console = self.capture({
            'shell getprop ro.build.fingerprint': 'google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys',
            'shell getprop ro.build.version.sdk': '35',
            'shell getprop dalvik.vm.usejit': 'true',
            'shell getconf PAGESIZE': '16384',
            'shell pm list packages --apex-only --show-versioncode': 'package:com.google.android.art versionCode:12345\npackage:unrelated.private.package versionCode:99',
            'logcat -b crash -d -t 300 -v brief': raw,
        })
        encoded = json.dumps(report) + console
        for marker in (secret, 'PRIVATE_PHOTO', '/storage/', '/apex/', 'PRIVATE_BODY', 'Bearer', 'unrelated.private'):
            self.assertNotIn(marker, encoded)
        self.assertEqual(16384, report['page_size_bytes'])
        self.assertEqual('37.2.12.0', report['emulator_version'])
        self.assertEqual([{'package': 'com.google.android.art', 'version_code': 12345}], report['active_art_apex'])
        crash = report['crash_buffer']
        self.assertIn('java.lang.NullPointerException', crash['exception_types'])
        self.assertIn('java.lang.ref.ReferenceQueue.enqueuePending', crash['java_frames'])
        self.assertIn('ReferenceQueueDaemon', crash['threads'])
        self.assertEqual('libart.so', crash['native_frames'][0]['library'])
        self.assertEqual('NterpGetStaticField', crash['native_frames'][0]['symbol'])
        self.assertFalse(report['raw_logcat_retained'])
        self.assertEqual('not_determined_by_diagnostics', report['test_result'])

    def test_unavailable_metadata_does_not_fail_or_invent_test_success(self):
        report, _ = self.capture({})
        self.assertIsNone(report['page_size_bytes'])
        self.assertEqual([], report['active_art_apex'])
        self.assertIn('page_size', report['unavailable_commands'])
        self.assertNotEqual('success', report['test_result'])

    def test_arbitrary_getprop_values_and_excessive_crash_events_are_rejected_or_bounded(self):
        raw = '\n'.join('E/AndroidRuntime: at com.aimediasorter.app.Class.method' + str(i) + '(File.kt:1)' for i in range(500))
        report, console = self.capture({
            'shell getprop ro.build.fingerprint': 'Authorization: Bearer AQ.private',
            'shell getprop ro.product.cpu.abi': '/storage/PRIVATE_PHOTO.jpg',
            'logcat -b crash -d -t 300 -v brief': raw,
        })
        self.assertNotIn('ro.build.fingerprint', report['properties'])
        self.assertNotIn('ro.product.cpu.abi', report['properties'])
        self.assertLessEqual(len(report['crash_buffer']['java_frames']), 48)
        self.assertLess(len(json.dumps(report)), 16000)
        self.assertNotIn('PRIVATE_PHOTO', json.dumps(report) + console)

    def test_key_like_identifiers_are_never_treated_as_safe_stack_metadata(self):
        report, console = self.capture({'logcat -b crash -d -t 300 -v brief':
            'E/AndroidRuntime: at com.aimediasorter.app.AQ.SyntheticKey.method(File.kt:1)\n'
            'E/AndroidRuntime: at com.aimediasorter.app.AIzaSyntheticKey.method(File.kt:1)'})
        self.assertEqual([], report['crash_buffer']['java_frames'])
        self.assertNotIn('SyntheticKey', json.dumps(report) + console)

    def test_runner_stdout_mode_is_a_valid_standalone_json_snapshot(self):
        report, console = self.capture({'shell getconf PAGESIZE': '16384'}, stdout=True)
        self.assertEqual(16384, report['page_size_bytes'])
        self.assertEqual(report, json.loads(console))
