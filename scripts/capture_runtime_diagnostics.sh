#!/usr/bin/env bash
# CI emulator metadata only. Never changes VM settings, test assertions or test exit codes.
# No argument: emit JSON to stdout (supports the runner's bounded TXT capture).
# Optional argument: save one JSON snapshot to that file.
# Raw logcat/exception messages and arbitrary getprop output are never persisted.
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Runtime diagnostics unavailable: Python is missing.' >&2
  exit 0
fi
python3 - "${1:--}" <<'PY'
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

started = time.monotonic()
unavailable = []

def command(label, args):
    remaining = 15 - (time.monotonic() - started)
    if remaining <= 0:
        unavailable.append(label)
        return ''
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=min(2, remaining), errors='replace')
        if result.returncode == 0:
            return result.stdout[:65536]
    except (OSError, subprocess.TimeoutExpired):
        pass
    unavailable.append(label)
    return ''

def token(value, pattern=r'[A-Za-z0-9._:/-]{1,256}'):
    value = value.strip()
    if re.fullmatch(pattern, value) and 'AQ.' not in value and 'AIza' not in value:
        return value
    return None

properties = {}
property_patterns = {
    'ro.build.fingerprint': r'[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:[A-Za-z0-9._-]+/[A-Za-z0-9._-]+',
    'ro.build.version.sdk': r'[0-9]{1,3}',
    'ro.build.version.release': r'(?:[0-9]{1,2}(?:\.[0-9]{1,2})?|[A-Z][A-Za-z_]{1,25})',
    'ro.build.version.incremental': r'[A-Za-z0-9_-]{1,80}',
    'ro.product.cpu.abi': r'(?:arm64-v8a|armeabi-v7a|x86|x86_64|riscv64)',
    'dalvik.vm.usejit': r'(?:true|false|0|1)',
    'dalvik.vm.usejitprofiles': r'(?:true|false|0|1)',
    'dalvik.vm.dex2oat-filter': r'(?:verify|speed|speed-profile|everything|quicken|assume-verified|extract|space|space-profile|balanced)',
    'dalvik.vm.heapgrowthlimit': r'[0-9]{1,8}[kKmMgG]?',
    'dalvik.vm.heapsize': r'[0-9]{1,8}[kKmMgG]?',
    'dalvik.vm.gctype': r'[A-Za-z0-9_-]{1,40}',
    'dalvik.vm.enable_uffd_gc': r'(?:true|false|0|1)',
    'persist.sys.dalvik.vm.lib.2': r'libartd?\.so',
}
for name, pattern in property_patterns.items():
    value = token(command(name, ['adb', 'shell', 'getprop', name]), pattern)
    if value is not None:
        properties[name] = value

page = token(command('page_size', ['adb', 'shell', 'getconf', 'PAGESIZE']), r'[0-9]{4,6}')
emulator = shutil.which('emulator')
if emulator is None:
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    emulator = str(pathlib.Path(sdk) / 'emulator' / 'emulator') if sdk else 'emulator'
version_text = command('emulator_version', [emulator, '-version'])
version = re.search(r'Android emulator version ([0-9.]{1,40})(?: \(build_id ([0-9]{1,20})\))?', version_text)
apex_text = command('art_apex', ['adb', 'shell', 'pm', 'list', 'packages', '--apex-only', '--show-versioncode'])
apex = [{'package': match[0], 'version_code': int(match[1])} for match in
        re.findall(r'package:(com\.(?:google\.)?android\.art)\s+versionCode:([0-9]{1,20})', apex_text)][:4]

# These are allowlisted technical extracts, not a redacted copy of raw logcat.
# Do not include exception messages: they may contain filenames, URIs or response text.
raw = command('crash_buffer', ['adb', 'logcat', '-b', 'crash', '-d', '-t', '300', '-v', 'brief'])
crash = {'exception_types': [], 'threads': [], 'processes': [], 'signals': [], 'java_frames': [], 'native_frames': []}

def add(key, value, limit):
    encoded = json.dumps(value)
    if 'AQ.' in encoded or 'AIza' in encoded or 'authorization' in encoded.lower():
        return
    if value not in crash[key] and len(crash[key]) < limit:
        crash[key].append(value)

namespaces = ('java.', 'android.', 'androidx.', 'kotlin.', 'kotlinx.', 'com.aimediasorter.app.')
libraries = ('libart.so', 'libc.so', 'libc++.so', 'libc++_shared.so', 'libonnxruntime.so',
             'libonnxruntime4j_jni.so', 'libmlkitcommonpipeline.so',
             'libdatastore_shared_counter.so', 'libandroidx.graphics.path.so')
for line in raw.splitlines()[:300]:
    line = line[:1000]
    fatal = re.search(r'FATAL EXCEPTION: (ReferenceQueueDaemon|FinalizerDaemon|FinalizerWatchdogDaemon|main|DefaultDispatcher-worker-[0-9]+)(?:\s|$)', line)
    if fatal:
        add('threads', fatal[1], 8)
    process = re.search(r'Process: (com\.aimediasorter\.app(?:\.(?:debug|qa))?|com\.google\.android\.gms),', line)
    if process:
        add('processes', process[1], 8)
    exception = re.search(r'\b((?:java|android|androidx|kotlin|kotlinx|com\.aimediasorter\.app)\.[A-Za-z0-9_.$]*(?:Exception|Error))(?::|\s|$)', line)
    if exception:
        add('exception_types', exception[1], 12)
    signal = re.search(r'(?:Fatal signal|signal) ([0-9]{1,2}) \((SIG[A-Z]{1,10})\)', line)
    if signal:
        add('signals', {'number': int(signal[1]), 'name': signal[2]}, 8)
    frame = re.search(r'\bat ([A-Za-z_$][A-Za-z0-9_.$<>]+)\(', line)
    if frame and frame[1].startswith(namespaces):
        add('java_frames', frame[1], 48)
    native = re.search(r'#([0-9]{2}) pc ([0-9a-f]{1,16})\s', line)
    if native:
        item = {'frame': int(native[1]), 'pc': native[2]}
        for library in libraries:
            if re.search(r'/' + re.escape(library) + r'(?:\s|$)', line):
                item['library'] = library
                break
        symbol = re.search(r'\((Nterp[A-Za-z0-9_]+|art::[A-Za-z0-9_:~]+|(?:androidx\.|com\.aimediasorter\.app\.)[A-Za-z0-9_.$<>]+)(?:\+|\()', line)
        if symbol:
            item['symbol'] = symbol[1][:160]
        if 'library' in item or 'symbol' in item or int(native[2], 16) == 0:
            add('native_frames', item, 32)

report = {
    'schema_version': 1,
    'scope': 'isolated_ci_emulator_runtime',
    'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'capture_duration_ms': int((time.monotonic() - started) * 1000),
    'properties': properties,
    'page_size_bytes': int(page) if page else None,
    'emulator_version': version[1] if version else None,
    'emulator_build_id': version[2] if version else None,
    'active_art_apex': apex,
    'crash_buffer': crash,
    'unavailable_commands': unavailable,
    'raw_logcat_retained': False,
    'test_result': 'not_determined_by_diagnostics',
}
try:
    text = json.dumps(report, indent=2) + '\n'
    if sys.argv[1] == '-':
        print(text, end='')
    else:
        output = pathlib.Path(sys.argv[1])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding='utf-8')
        print('Runtime diagnostic technical snapshot captured.')
except OSError:
    print('Runtime diagnostics unavailable: snapshot could not be saved.', file=sys.stderr)
PY
# Metadata collection is best effort. The caller must retain the original Gradle/test status.
exit 0
