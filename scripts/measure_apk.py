#!/usr/bin/env python3
"""Measure stored and compressed APK bytes. No dependency-size estimates."""
import collections, hashlib, json, pathlib, sys, zipfile
apk=pathlib.Path(sys.argv[1])
out=pathlib.Path(sys.argv[2]); out.parent.mkdir(parents=True,exist_ok=True)
groups=collections.defaultdict(lambda: {"raw_bytes":0,"compressed_bytes":0,"entries":0})
abis=collections.defaultdict(lambda: {"raw_bytes":0,"compressed_bytes":0,"entries":0})
native=[]; assets=[]; entries=[]
with zipfile.ZipFile(apk) as z:
    for e in z.infolist():
        if e.is_dir(): continue
        name=e.filename
        kind=("native" if name.startswith("lib/") and name.endswith(".so") else
              "dex" if name.endswith(".dex") else "assets" if name.startswith("assets/") else
              "resources" if name.startswith("res/") or name=="resources.arsc" else "other")
        row={"path":name,"raw_bytes":e.file_size,"compressed_bytes":e.compress_size}
        entries.append(row)
        for target in [groups[kind]]:
            target["raw_bytes"]+=e.file_size;target["compressed_bytes"]+=e.compress_size;target["entries"]+=1
        if kind=="native":
            abi=name.split("/")[1]
            abis[abi]["raw_bytes"]+=e.file_size;abis[abi]["compressed_bytes"]+=e.compress_size;abis[abi]["entries"]+=1
            row["component"]="ONNX Runtime" if "onnx" in name.lower() else "ML Kit/other JNI"
            native.append(row)
        if kind=="assets":
            row["sha256"]=hashlib.sha256(z.read(name)).hexdigest()
            assets.append(row)
report={"apk":apk.name,"bytes":apk.stat().st_size,"MiB":apk.stat().st_size/1048576,
        "sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"groups":dict(groups),"abis":dict(abis),
        "native":native,"assets":assets,"largest_entries":sorted(entries,key=lambda e:e["raw_bytes"],reverse=True)[:40],
        "zip_headers_signing_padding_bytes":apk.stat().st_size-sum(e["compressed_bytes"] for e in entries),
        "note":"Raw and compressed entry sizes are measured from the ZIP directory. DEX dependency packages share metadata; see apkanalyzer output for defined classes."}
out.write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
