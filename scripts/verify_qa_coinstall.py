#!/usr/bin/env python3
"""CI-emulator-only check: installing QA must retain a separate debug app's private sentinel."""
import json
import subprocess
import re

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=15).strip()

assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Co-install smoke is restricted to isolated emulators'
packages = ('com.aimediasorter.app.debug', 'com.aimediasorter.app.qa')
uids = []
for package in packages:
    rows = adb('shell', 'cmd', 'package', 'list', 'packages', '-U', package).splitlines()
    found = [re.fullmatch(r'package:' + re.escape(package) + r'\s+uid:(\d+)', row.strip()) for row in rows]
    found = [match for match in found if match]
    assert len(found) == 1, 'Required co-installed fixture package is missing: ' + package
    uids.append(int(found[0].group(1)))
assert uids[0] != uids[1], 'QA and debug must use separate application UIDs'
assert adb('shell', 'run-as', packages[0], 'cat', 'files/qa-coinstall-sentinel.txt') == 'AIMS_QA_CO_INSTALL_SENTINEL', 'Existing test app private data was not retained'
print(json.dumps({'schema_version':1, 'status':'PASS', 'packages':list(packages),
                  'separate_uids':True, 'existing_private_sentinel_retained':True,
                  'scope':'isolated emulator fixture; no production data migration tested'}))
