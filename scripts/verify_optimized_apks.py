#!/usr/bin/env python3
"""Assert model bytes and per-ABI contents using actual APK size reports."""
import json
import pathlib

def verify(baseline, reports):
    required = {
        "optimized-arm64-v8a-debug.apk": "arm64-v8a",
        "optimized-arm64-v8a-release-QA.apk": "arm64-v8a",
        "optimized-armeabi-v7a-debug.apk": "armeabi-v7a",
        "optimized-armeabi-v7a-release-QA.apk": "armeabi-v7a",
    }
    by_name = {r["apk"]: r for r in reports}
    assert by_name.keys() == required.keys(), "Expected only four ARM-specific APKs"
    models = {a["path"]: a["sha256"] for a in baseline["assets"]
              if "mlkit_label_default_model/" in a["path"] or a["path"].startswith("assets/models/")}
    assert len(models) == 3, "Offline ML Kit and diagnostic assets must be present"
    rows = []
    for name, abi in required.items():
        report = by_name[name]
        assert set(report["abis"]) == {abi}, (name, report["abis"])
        current = {a["path"]: a["sha256"] for a in report["assets"]}
        for path, sha in models.items():
            assert current.get(path) == sha, "Offline asset changed: " + path
        names = {pathlib.PurePosixPath(a["path"]).name for a in report["native"]}
        assert {"libonnxruntime.so", "libonnxruntime4j_jni.so", "libmlkitcommonpipeline.so"} <= names
        rows.append({"apk": name, "bytes": report["bytes"], "MiB": report["MiB"],
                     "savings_percent_vs_baseline": (1 - report["bytes"] / baseline["bytes"]) * 100,
                     "sha256": report["sha256"], "abi": abi})
    return {"baseline_bytes": baseline["bytes"], "unchanged_offline_model_sha256": models, "APKs": rows}

def main():
    baseline = json.loads(pathlib.Path("size-reports/baseline-size.json").read_text())
    paths = pathlib.Path("app/build/reports").glob("optimized-*-size.json")
    reports = [json.loads(p.read_text()) for p in paths]
    result = verify(baseline, reports)
    pathlib.Path("size-reports/comparison.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
