#!/usr/bin/env bash
set -Eeuo pipefail
dump_test_failures() {
  local failed_status=$?
  # The emulator runner terminates its device before subsequent workflow steps.
  # Capture the crash buffer here while the failing process/device is still available.
  adb logcat -b crash -d -v threadtime > android-crash-logcat.txt 2>&1 || true
  tail -n 200 android-crash-logcat.txt || true
  bash scripts/capture_runtime_diagnostics.sh >> android-runtime-diagnostics.txt 2>&1 || true
  python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
for path in pathlib.Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    root=ET.parse(path).getroot()
    print("Executed suite:",root.attrib)
    for case in root.iter("testcase"):
        for failure in list(case.findall("failure"))+list(case.findall("error")):
            print("FAILED:",case.attrib)
            print("Failure attributes:",failure.attrib)
            print(failure.text)
PY
  return "$failed_status"
}
trap dump_test_failures ERR
variant="$1"
page_size="$2"
bash scripts/capture_runtime_diagnostics.sh > android-runtime-diagnostics.txt 2>&1 || true
if [ "$variant" = "release" ]; then
  # Run instrumentation first: AGP removes its target app during teardown.
  # Test co-installation afterwards as an independent install operation.
  # This is restricted to the throwaway CI emulator, never a user's phone.
  test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
  ./gradlew assembleDebug -PtargetAbi=x86_64 --stacktrace
  ./gradlew connectedReleaseAndroidTest -PqaReleaseSigning=true -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell run-as com.aimediasorter.app.debug mkdir -p files
  adb shell "run-as com.aimediasorter.app.debug sh -c 'echo AIMS_QA_CO_INSTALL_SENTINEL > files/qa-coinstall-sentinel.txt'"
  adb install -r app/build/outputs/apk/release/app-release.apk
  python3 scripts/verify_qa_coinstall.py > qa-coinstall-smoke.json
else
  ./gradlew connectedDebugAndroidTest -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
fi

python3 scripts/summarize_junit.py app/build/outputs/androidTest-results app/build/reports/native-summary.json
