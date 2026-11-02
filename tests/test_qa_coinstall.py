"""Isolated fake-adb tests: no real phone, media or package changes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEBUG = 'com.aimediasorter.app.debug'
QA = 'com.aimediasorter.app.qa'


class QaCoinstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        for script in ('run_native_tests.sh', 'verify_qa_coinstall.py', 'summarize_junit.py'):
            shutil.copy(ROOT / 'scripts' / script, self.root / 'scripts' / script)
        (self.root / 'scripts/capture_runtime_diagnostics.sh').write_text('echo "{}"\n')
        self.state = {'emulator': '1', 'packages': {DEBUG: 10001, QA: 10002},
                      'sentinel': 'AIMS_QA_CO_INSTALL_SENTINEL', 'calls': []}
        self.state_path = self.root / 'state.json'
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        AIMS_COINSTALL_TEST_STATE=str(self.state_path))
        adb = self.root / 'adb'
        adb.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p=Path(os.environ['AIMS_COINSTALL_TEST_STATE']); d=json.loads(p.read_text()); a=sys.argv[1:]
d['calls'].append('adb ' + ' '.join(a)); result=''; status=0
if a[:3] == ['shell','getprop','ro.kernel.qemu']: result=d['emulator']
elif a[:6] == ['shell','cmd','package','list','packages','-U']:
    pkg=a[6]
    if pkg in d['packages']: result='package:'+pkg+' uid:'+str(d['packages'][pkg])
elif a[:3] == ['shell','run-as','com.aimediasorter.app.debug']:
    if 'cat' in a: result=d['sentinel']
elif a[:1] == ['shell'] and 'AIMS_QA_CO_INSTALL_SENTINEL' in ' '.join(a):
    d['sentinel']='AIMS_QA_CO_INSTALL_SENTINEL'
elif a[:2] == ['install','-r']:
    pkg='com.aimediasorter.app.qa' if '/release/' in a[2] else 'com.aimediasorter.app.debug'
    d['packages'][pkg]=10002 if pkg.endswith('.qa') else 10001
    result='Success'
elif a[:1] != ['logcat']: status=9
p.write_text(json.dumps(d)); print(result); sys.exit(status)
''')
        adb.chmod(0o755)
        gradle = self.root / 'gradlew'
        gradle.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p=Path(os.environ['AIMS_COINSTALL_TEST_STATE']); d=json.loads(p.read_text())
d['calls'].append('gradle '+sys.argv[1])
if sys.argv[1]=='connectedReleaseAndroidTest':
    d['packages'].pop('com.aimediasorter.app.qa',None)
    result=Path('app/build/outputs/androidTest-results/TEST-fixture.xml')
    result.parent.mkdir(parents=True,exist_ok=True)
    result.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
p.write_text(json.dumps(d)); sys.exit(int(os.environ.get('AIMS_TEST_GRADLE_EXIT','0')))
''')
        gradle.chmod(0o755)

    def run_script(self, runner=False):
        self.state_path.write_text(json.dumps(self.state))
        args = ['bash', 'scripts/run_native_tests.sh', 'release', '16384'] if runner else [
            'python3', 'scripts/verify_qa_coinstall.py']
        return subprocess.run(args, cwd=self.root, env=self.env, text=True,
                              capture_output=True, timeout=20)

    def test_separate_uids_and_existing_private_data_pass(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report['existing_private_sentinel_retained'])
        self.assertTrue(report['separate_uids'])

    def test_missing_qa_reports_static_fixture_package(self):
        del self.state['packages'][QA]
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('fixture package is missing: ' + QA, result.stderr)

    def test_same_uid_is_rejected(self):
        self.state['packages'][QA] = self.state['packages'][DEBUG]
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('separate application UIDs', result.stderr)

    def test_lost_private_data_is_rejected(self):
        self.state['sentinel'] = 'different'
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('private data was not retained', result.stderr)

    def test_runner_refuses_physical_device(self):
        self.state['emulator'] = '0'
        result = self.run_script(runner=True)
        self.assertNotEqual(0, result.returncode)
        calls = json.loads(self.state_path.read_text())['calls']
        self.assertFalse(any(call.startswith(('gradle ', 'adb install')) for call in calls))

    def test_runner_checks_explicit_install_after_instrumentation_teardown(self):
        self.state['packages'] = {}
        self.state['sentinel'] = ''
        result = self.run_script(runner=True)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads(self.state_path.read_text())['calls']
        self.assertLess(calls.index('gradle connectedReleaseAndroidTest'),
                        calls.index('adb install -r app/build/outputs/apk/debug/app-debug.apk'))
        self.assertLess(calls.index('adb install -r app/build/outputs/apk/debug/app-debug.apk'),
                        calls.index('adb install -r app/build/outputs/apk/release/app-release.apk'))
        self.assertEqual('PASS', json.loads((self.root / 'qa-coinstall-smoke.json').read_text())['status'])

    def test_runner_preserves_gradle_failure_without_installing_or_reporting_pass(self):
        self.env['AIMS_TEST_GRADLE_EXIT'] = '42'
        result = self.run_script(runner=True)
        self.assertEqual(42, result.returncode)
        calls = json.loads(self.state_path.read_text())['calls']
        self.assertFalse(any(call.startswith('adb install') for call in calls))
        self.assertFalse((self.root / 'qa-coinstall-smoke.json').exists())
