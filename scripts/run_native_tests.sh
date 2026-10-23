#!/usr/bin/env bash
set -euo pipefail
variant="$1"
page_size="$2"
if [ "$variant" = "release" ]; then
  ./gradlew connectedReleaseAndroidTest -PqaReleaseSigning=true -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
else
  ./gradlew connectedDebugAndroidTest -PtargetAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" --stacktrace
fi

python3 scripts/summarize_junit.py app/build/outputs/androidTest-results app/build/reports/native-summary.json
