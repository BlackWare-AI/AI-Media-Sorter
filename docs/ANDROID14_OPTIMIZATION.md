# Android 14 compatibility, ARM APK optimization and official icon

The current minimum is Android 14 (API 34); compileSdk/targetSdk remain 35. This supersedes the historical Android 15 minimum in INDEPENDENT_REVIEW.md. Changes are isolated on codex/android14-size-and-icon, stacked on the independently audited safety branch. Main is unchanged.

## Measured baseline

Baseline workflow: https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38682222910
APK SHA-256: 5a4e9b4737d5747d6cf5c637832d09d8ff92a1d39876e7499c85b90ebc37e577

The actual APK is 187,562,102 bytes (178.873159 MiB), distinct from its compressed artifact ZIP.

| APK ZIP group | Raw bytes | Compressed/stored bytes |
| --- | ---: | ---: |
| Native .so, four ABIs | 117,827,824 | 117,827,824 |
| 12 DEX files | 65,946,988 | 65,946,988 |
| Model/other assets | 3,116,435 | 3,108,359 |
| Resources | 443,309 | 346,624 |
| Other entries | 93,013 | 58,265 |

| ABI | Native bytes |
| --- | ---: |
| arm64-v8a | 29,321,168 |
| armeabi-v7a | 20,255,908 |
| x86 | 34,133,916 |
| x86_64 | 34,116,832 |

Across the four ABIs, ONNX Runtime native libraries account for 75,206,864 bytes; ML Kit common-pipeline libraries account for 42,560,668 bytes. Other JNI libraries account for 60,292 bytes.

Bundled ML Kit weights occupy 3,042,638 bytes, the diagnostic ONNX graph 10,041 bytes and its vocabulary table 51,613 bytes. The Termux executor occupies 12,143 raw bytes. Offline assets are retained, not replaced with network downloads.

APKAnalyzer defined-code attribution below is measured, but is not an additive breakdown of the complete DEX ZIP entries: packages share metadata, pools and references. Google Play services also contains shared code used by ML Kit.

| Package tree | Defined-code bytes |
| --- | ---: |
| androidx.compose | 20,728,703 |
| kotlin | 956,954 |
| kotlinx | 723,809 |
| androidx.room | 118,983 |
| androidx.work | 292,519 |
| com.google.mlkit | 81,263 |
| com.google.android.gms | 1,534,722 |
| ai.onnxruntime | 67,890 |

Material icons account for 14,207,252 defined-code bytes within the Compose total. Required icon usage remains; unreachable release classes/resources are removed by R8.

## Optimized APKs

Application/configuration commit: 7d63b9052822eb48e23998f5ad4cf8ebcd7e43d5.
Build, R8, lint, JVM/Python and APK inspections passed in workflow 38799135810.

| APK | Bytes | MiB | Saving vs baseline |
| --- | ---: | ---: | ---: |
| optimized-arm64-v8a-debug.apk | 35,654,051 | 34.002353 | 80.99% |
| optimized-arm64-v8a-release-QA.apk | 17,953,826 | 17.122103 | 90.43% |
| optimized-armeabi-v7a-debug.apk | 34,009,869 | 32.434339 | 81.87% |
| optimized-armeabi-v7a-release-QA.apk | 16,609,604 | 15.840153 | 91.14% |

The optimized ARM64 release contains 8,131,928 raw DEX bytes (3,155,949 compressed), 29,321,168 raw native bytes (11,050,325 compressed), 3,125,031 asset bytes (3,066,259 compressed), and 632,885 resource bytes (546,835 compressed).

[Download the four ARM debug/release QA APKs](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38799135810/artifacts/12004171456).
The separate ARM64/ARMv7 release artifact ZIP downloads were exercised successfully through the GitHub connector. The ZIP bundles four APKs and is 98,297,696 bytes; it is not the size of one APK.
[Verification reports and size JSON](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38799135810/artifacts/12004076924).

Universal packaging was measured during evaluation (73,890,941-byte debug and 52,053,714-byte release APKs), then removed from current CI at the user's request. Current deliverables contain exactly one ARM ABI each. x86_64 is built only for emulators.

## Exact changes and rationale

- app/build.gradle.kts: minSdk 34, explicit targetAbi (default arm64-v8a), native/DEX compression, release R8/resource shrinking, and opt-in QA debug-certificate signing. Normal release remains unsigned unless signing is configured externally.
- app/proguard-rules.pro: conservative ONNX JNI, ML Kit, Room/serialization contract and WorkManager constructor rules. Shared Kotlin/coroutine/Room and tracing APIs are retained because the separate instrumentation APK depends on their app-provided runtime contracts. Sizes above include these keep rules. app/test-proguard-rules.pro narrowly handles the test-only Error Prone annotation reference to javax.lang.model.element.Modifier.
- Launcher resources and artwork/: adaptive, legacy and round icons from the user-approved logo. The attachment was encoded as JPEG; decoded RGB pixels were losslessly converted to PNG without redrawing/recoloring. Provenance is in artwork/README.md.
- scripts/measure_apk.py, verify_optimized_apks.py and inspect_apk.py: actual ZIP sizes, per-ABI/native entries, model SHA-256 equality, manifest/signature/16 KB checks and four explicit ARM outputs.
- CI and isolated SAF fixtures: API 34 and 35 debug/release matrix, 4 KB/16 KB pages, and permission grants derived from the tested debug/release package. No fixture provider is present in application APKs. Resource smoke tests resolve entries through the real APK resource table, so they do not require generated R classes that R8 can remove.

No required declared dependency or model was removed. Compression reduces downloads but causes install-time native extraction; equal installed-storage, RAM or startup improvements are not established.

## Smaller ONNX Runtime investigation

Keep onnxruntime-android 1.22.0 for the existing ONNX loader and diagnostic/NNAPI behavior. Official documentation recommends a custom runtime built with the actual required operator set for further reductions. An old mobile package or ORT-only minimal build is not a verified drop-in replacement for this ONNX loader. No custom AAR or additional size saving is claimed. A future reduced runtime requires both ARM ABIs, graph/operator coverage, execution-provider support, provenance and executable 16 KB-compatible inference tests.

Official references:
- https://onnxruntime.ai/docs/build/custom.html
- https://onnxruntime.ai/docs/reference/operators/reduced-operator-config-file.html
- https://onnxruntime.ai/docs/tutorials/mobile/

## Android 14 and file safety

Source review and lint found no unguarded Android-15-only API. SAF grants/hierarchy calls, original MediaStore access, scaled video decoding, notifications and WorkManager foreground dataSync use APIs available on API 34. Existing original-format API 31 and notification API 33 guards remain. FOREGROUND_SERVICE_DATA_SYNC is declared for Android 14. Actual APK manifests declare minSdk 34 and targetSdk 35.

The optimization diff changes no AI classification or core transfer source. VerifiedTransferEngine, SafeFileMover, OriginalMediaAccess, ExifAndTimestampManager, LocalTransferStorage and the production Termux executor are unchanged from the audit branch. Original-byte SHA-256 staging/final readback, approval gates, separate filesystem/EXIF timestamp reporting and conservative MOVE-to-COPY fallback remain intact.

Separate testing downloads: [ARM64 release QA](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38799135810/artifacts/12003458363) and [ARMv7 release QA](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38799135810/artifacts/12004326337).

## Remaining release limits

QA release APKs use a debug certificate for testing and are not production releases. Physical ARMv7/ARM64 devices, OEM/provider behavior, production signing, representative labeled accuracy, peak RAM/battery/throughput and install/startup measurements remain unverified. ARMv7 also requires an OS supporting 32-bit apps. The 16 KB requirement applies to 64-bit Android ABIs.

## Native verification

Full workflow **SUCCESS**: https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38799135810

Clean Android build, lint, APK manifest/signature/alignment and exact-ABI/model-hash checks passed. JUnit XML records **22 JVM tests**, zero failures/errors/skips. Python records **14 passed**.

| API | Build | Page size | Executed result |
| --- | --- | ---: | --- |
| 34 | debug | 4096 | 18 passed; zero failures/errors/skips |
| 34 | release | 4096 | 18 passed; zero failures/errors/skips |
| 35 | debug | 16384 | 18 passed; zero failures/errors/skips |
| 35 | debug | 4096 | 18 passed; zero failures/errors/skips |
| 35 | release | 16384 | 18 passed; zero failures/errors/skips |

Native totals: 90 passed across five configurations; combined JVM/Python/native invocations: 126 passed. Real bundled ML Kit and diagnostic ONNX inference, actual video decoding, Room/migration, original-byte access and isolated collision/corruption/revocation/timestamp transfer cases executed on both debug and minified release.

These are x86_64 emulator results. They do not establish physical ARM behavior, semantic accuracy or every provider's deletion/durability contract.

## Original build failure

The exact initial run 38646098807 failed before Gradle: android-actions/setup-android invoked sdkmanager tools, which returned “Warning: Failed to find package 'tools'” and exit code 1. The obsolete SDK Tools package is no longer available. Current CI explicitly installs platform-tools, platforms;android-35 and build-tools;35.0.0. The isolated minimal build fix is PR #2; this optimization proposal remains stacked on the independently audited safety branch.
