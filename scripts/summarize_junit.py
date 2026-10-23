#!/usr/bin/env python3
"""Read executed JUnit XML, fail on empty/failing suites, and print actual totals."""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

directory = pathlib.Path(sys.argv[1])
paths = sorted(directory.rglob("TEST-*.xml"))
assert paths, "No executed JUnit XML results found: " + str(directory)
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in paths:
    for suite in ET.parse(path).getroot().iter("testsuite"):
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
assert totals["tests"] > 0, "No tests executed"
assert totals["failures"] == 0 and totals["errors"] == 0, totals
result = {"results_directory": str(directory), "xml_files": len(paths), **totals}
if len(sys.argv) > 2:
    output = pathlib.Path(sys.argv[2])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2))
print(json.dumps(result))
