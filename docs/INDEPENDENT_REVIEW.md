# Independent Technical Review and Fix Record

Current branch minimum: **Android 14 / API 34**. The dated Android 15 baseline verification below is historical. See [current ARM optimization and verification](ANDROID14_OPTIMIZATION.md) for the superseding platform, packaging and test results.

Review baseline: main commit b32444218f4a5e262803199abbd70e98df786ffe.
Proposed fixes: codex/audit-safety-and-build-fixes. Main was not overwritten.
Source of truth: the GitHub repository. The supplied ZIP is excluded from the fix review.

## Project Status

The public repository is accessible. It contains a single native Android application module, Kotlin/Compose/Material 3 screens, a repository layer, Room persistence, DataStore settings and WorkManager workers. Existing functional architecture and screens were retained.

The build uses AGP 8.7.2, Kotlin 2.0.21, JDK 17, Gradle 8.9, compile/target SDK 35 and minimum SDK 35 (Android 15). The official wrapper scripts/JAR and distribution checksum are checked in. The wrapper JAR's SHA-256 was independently compared with Gradle's release checksum.

Before fixes, TermuxOperationExporter contained invalid mixed Python/Kotlin source; backup-rule validation also failed during independent CI. The repaired branch passed a clean debug build, lint, 21 JVM tests and 14 Python tests at bf01a9752def0c258b92e1b65ffc72efb77ca6a9:
https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38661853545

Native inference/storage tests and APK inspection have since been added. Their final results must be read from the final verification record below; the earlier green run did not execute Android instrumentation.

## AI System

Default runtime: bundled Google ML Kit Image Labeling, com.google.mlkit:image-labeling:17.0.9. The model's architecture/weight revision is controlled by that SDK; this review does not assign an unsupported exact backbone name. SDK output labels and their actual confidences are mapped by LabelCategoryMapper. Automatic scores contain no demonstration ONNX mixing or invented calibration boost.

The project asset models/media_visual_embedder.onnx is a small handcrafted 64-feature projection, not a trained semantic image/text encoder. It is retained only for diagnostic execution with mandatory review. Tensor input/output sizes are checked and sessions/options/tensors are closed.

Custom MobileCLIP is unavailable: importing an image encoder alone does not supply compatible trained text embeddings or a tokenizer. Saved unsupported custom-engine settings fall back to the real bundled default. Custom category descriptions are keyword matches against SDK label evidence.

Videos are decoded at several requested times with scaled native frames. Distinct frame content is SHA-256 deduplicated. Frame uncertainty, too few distinct samples and competing scene categories force review. Aggregation uses mean model score and vote share, without an artificial confidence floor.

No representative labeled evaluation set is present. People, food, vehicle, animal and nature accuracy, primary-subject behavior, custom-category coverage, unknown rejection and threshold calibration are **unestablished**. Synthetic native tests prove execution and plumbing, not accuracy.

References:
- https://developers.google.com/ml-kit/vision/image-labeling/android
- https://developers.google.com/ml-kit/terms
- https://github.com/microsoft/onnxruntime/blob/main/LICENSE

ML Kit SDK/model terms and provenance of the diagnostic weights need maintainer review before distribution. Open-source runtime licensing does not automatically license every model asset.

## File Safety

SafeFileMover delegates to VerifiedTransferEngine for both local and SAF operations. The engine exclusively stages and publishes, reads back SHA-256 and byte count, verifies source identity/content repeatedly, restores modification time separately and records checkpoints. Existing destination files are never opened with replace semantics.

MOVE requires checked source deletion, stable identities, durable destination publication and the selected timestamp policy. An unsupported provider produces a retained original and an actual COPY result. A failed deletion is not reported as a completed MOVE.

OriginalMediaAccess requests original MediaStore bytes and original-format typed streams. Android's platform FileSystemProvider can otherwise route SAF reads through compatibility transcoding. Unavailable original access blocks automatic transfer. Non-platform providers remain subject to their original-document stream contract.

Room records per-source scan sessions and transfer journals. Startup reconciliation flags interrupted destinations for review, verifying bytes where accessible, and never resumes deletion. Schema 1→2 migration is non-destructive; old unscoped rows are retained but require a new scan before transfer.

Filesystem mtime is obtained from an actual local file or regular-file descriptor where exposed. EXIF/video capture dates and provider dates are kept distinct. Restoration success requires readback. A provider without reliable mtime reports the limitation rather than claiming preservation.

Safety depends on a conforming provider and storage behaving according to its reported identity/durability contract. No application can make an arbitrary third-party provider or sudden hardware failure fully transactional. Destructive acceptance on a physical device and its actual provider remains a separate release gate.

## Privacy and Security

The reviewed application source contains no media-upload endpoint, embedded paid-API secret or advertising tracker. The inspected merged debug APK has no INTERNET or MANAGE_EXTERNAL_STORAGE permission; normal WorkManager permissions include boot handling, wake locks and network-state observation. Media access uses explicitly selected SAF grants, with metadata access requested only for original-byte preservation. Backups are disabled and exports remain in private cache until the user saves them.

Google's ML Kit terms document SDK usage/performance metrics. The dependency therefore must not be described as telemetry-free. This APK denies app-owned Internet access, and Google's published terms state media inputs and inference outputs are processed on-device. Privacy disclosures and dependency behavior still require review for a distributed release; absence of application upload code is not a source-level audit of closed-source SDK internals.

## Defects and Targeted Fixes

| Severity | Baseline defect / evidence | Changed files and implementation | Expected/current behavior |
| --- | --- | --- | --- |
| CRITICAL | Source move/delete could precede destination readback; source/destination races and replacement risk | SafeFileMover.kt, VerifiedTransferEngine.kt, LocalTransferStorage.kt | Verify staging/final bytes; exclusive creation; recheck both after deletion checkpoint; keep originals on uncertainty |
| CRITICAL | Transfers were not consistently gated by Analysis Mode and current source | MediaRepository.kt, MainViewModel.kt, Daos.kt, FileTransferScreen.kt | Authoritative mode/source/signature checks, scoped latest scan and live deletion guard |
| CRITICAL | Shell interpolation could execute filenames as code; fallback checks could masquerade as SHA-256 | TermuxOperationExporter.kt, TermuxScriptBuilder.kt, assets/termux/manifest_executor.py, scripts/termux_manifest_executor.py | Embedded JSON data, mandatory Python hashlib and verified publication |
| CRITICAL | Heuristic ONNX outputs contributed to automatic semantic claims | OnDeviceAiClassifier.kt, LabelCategoryMapper.kt, SettingsScreen.kt | Real SDK inference by default; diagnostic ONNX always requires review |
| HIGH | Mixed Python/Kotlin source prevented compilation; wrapper missing | TermuxOperationExporter.kt, gradlew, gradlew.bat, gradle/wrapper | Real clean build; official checksummed wrapper |
| HIGH | Imported CLIP output dimensions/text representation did not match | OnDeviceAiClassifier.kt, SettingsRepository.kt | Unsupported import removed from active UI; honest fallback |
| HIGH | Metadata/deletion failure could be reported as successful; SAF reads can transcode | ExifAndTimestampManager.kt, OriginalMediaAccess.kt, SafeFileMover.kt, AndroidManifest.xml | Separate filesystem readback, checked deletion result, original-byte requirement |
| HIGH | Filename/size/time fingerprints could reuse another file's prediction | MediaRepository.kt, Entities.kt, Daos.kt | URI, original SHA-256, settings/category signature and source session scope |
| HIGH | APK ELF inspection found 4 KB-aligned 64-bit ONNX Runtime libraries | gradle/libs.versions.toml, scripts/inspect_apk.py | Upgrade runtime to 1.22.0 and require 16 KB alignment for packaged 64-bit libraries |
| MEDIUM | Thumbnail-like sampling, repeated frames, score inflation, resource cleanup | VideoFrameAnalyzer.kt, VideoPredictionAggregator.kt, VisualFeatureExtractor.kt | Bounded distinct native frames, conservative aggregation, coroutine cancellation and recycle |
| MEDIUM | UI-driven long jobs and incomplete checkpoints/cancellation | MediaAnalysisWorker.kt, MediaTransferWorker.kt, WorkerNotifications.kt, MainViewModel.kt | Unique foreground work, serialized settings snapshots, persistent pause and per-item/session checkpoints |
| MEDIUM | Dead mode/export controls, hidden cache outputs, misleading score/error text | Compose screens, MainViewModel.kt, localized resources | Functional mode controls/cancel/save ZIP, errors shown, honest model labels and bilingual defaults |
| MEDIUM | Provider revocation/listing failures silently looked like an empty folder | StorageScanner.kt | Stream traversal and explicit access failures; cancellation; bounded buffering |
| LOW | Stale README claims and missing executable QA | README.md, tests/, androidTest/, workflow | Actual supported behavior, isolated fault tests and repeatable verification |

All Kotlin paths above are under app/src/main/java/com/aimediasorter/app, except files explicitly identified as build/assets/tests.

## Test Strategy and Limitations

JVM tests exercise the production transfer engine and real temporary filesystem files: integrity, historical mtime, safe move, concurrent filename collision, same-size corruption, changed source, destination mutation after checkpoint, missing identities, deletion failure, cancellation, path traversal and a provider returning the source as staging.

Python tests exercise the production executor in isolated temporary folders: dry run, Unicode/quotes/metacharacters, actual move/copy, collision policies, checksum mismatch, review restrictions, path confinement and timestamp fallback. The generated shell is syntax checked and executed by the JVM test against its embedded production Python.

Android instrumentation runs the actual bundled ML Kit and diagnostic ONNX runtimes, native multiframe/corrupt-video decoding, panorama/mirror preprocessing, 2,002-file discovery, Room source scoping and migration, original MediaStore stream equality and a test-only DocumentsProvider with collision/corruption/revocation/timestamp faults.

The test provider is packaged only in the instrumentation APK. No destructive test uses a real user archive. Tests with an owned MediaStore sample do not prove every OEM SAF grant/redaction combination.

Remaining release validation:
1. Representative labeled photos/videos, multi-subject annotations and threshold/unknown evaluation.
2. Physical mid-range devices: peak RAM, thermal throttling, throughput, battery, provider behavior and very large collections.
3. OEM/SD-card providers, original-byte grants, HEVC/HDR passthrough and 16 KB page-size device execution.
4. Interrupted process/device power loss across every transfer checkpoint; recovery is conservative but not a full transactional filesystem.
5. Model/asset provenance, dependency terms and release-signing/distribution policy.
6. Custom open-vocabulary models are not implemented; no accuracy or model capability is fabricated.

## Recommended Fixes and Final Assessment

The proposed branch addresses the recorded defects through these production entry points:

- SafeFileMover.transferMediaItem and VerifiedTransferEngine.transfer now verify new destinations before any source deletion, retain originals when checks fail, and record actual COPY/MOVE outcomes.
- OriginalMediaAccess.originalUri/input and ExifAndTimestampManager.restoreAndVerifyTimestamp request unchanged original streams and verify filesystem mtime independently.
- MediaRepository.executeVerifiedTransfers checks source scope, confirmed settings, active categories and live deletion guards. reconcileLocked examines every new unfinished operation, rechecks destination bytes and mtime, and never resumes source deletion.
- OnDeviceAiClassifier.classifyBitmap, LabelCategoryMapper.classify and VideoFrameAnalyzer.analyzeVideo execute real local inference with conservative review rules.
- TermuxScriptBuilder.build and the production manifest executor publish verified files without interpreting media names as shell code.

Working behavior is defined by the executed tests below. Semantic accuracy, physical-device performance and general OEM-provider acceptance remain unverified. Custom descriptions and recovery are partial capabilities: descriptions match SDK labels rather than learned text embeddings, and recovery flags interrupted operations for review rather than resuming byte-offset transfers.

Before testing with a real archive, verify the phone/provider using copied representative samples and retain a separate backup. Begin with the default COPY operation and inspect modification-time reports. MOVE is not approved for a provider that cannot establish the required identity, publication durability and timestamp policy.

## Historical Verification Before the Android 15 Minimum

Verified implementation commit: b00bb10fc178c28c00d6a171699b5523f7b74a12.
Verification date: 2026-10-15 UTC.
Full workflow result: SUCCESS.
https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38671112461

| Check | Executed result |
| --- | --- |
| Clean debug build, JVM unit tests, lint and instrumentation compilation | PASS; Gradle BUILD SUCCESSFUL in 5m 16s |
| Production transfer/classification/shell JVM tests | 22 passed |
| Production Python executor tests | 14 passed |
| Native API 29, 4 KB pages | 18 passed; BUILD SUCCESSFUL |
| Native API 35, 4 KB pages | 18 passed; BUILD SUCCESSFUL |
| Native API 35, 16 KB pages | 18 passed; page size asserted as 16384; BUILD SUCCESSFUL |
| Combined executed test invocations | 90 passed; no ignored tests used |
| APK manifest, signature and alignment inspection | PASS |

The native suite executes actual ML Kit and ONNX inference, distinct-frame video inference, corrupted-video review, image bounds/mirroring, 2,002-file discovery, source-scoped Room queries and non-destructive migration. Its real ContentResolver/DocumentsProvider tests verify collision-safe readback, source retention on corruption/revocation and unsupported mtime. Additional tests verify later recovery in the same process, fresh recovery mtime readback and updated filesystem timestamps when inference is reused for identical bytes.

The initial native runs exposed defects in the test fixture itself (an API-29-incompatible assertion, cross-APK provider permission/ownership, missing Kotlin libraries in the test APK's standalone process, and a private-directory URI alias assumption). Those harness defects were corrected and the entire suite was rerun successfully. They were not hidden with skipped or ignored tests.

Generated debug APK:
https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38671112461/artifacts/11944548455

APK SHA-256: 627f62c44de8d64fb2ea911becec143cebe939f3aacaf3aec323df4b348d7424
APK size: 188,020,982 bytes (universal debug APK; artifact ZIP is approximately 67.9 MB).
Minimum SDK 29; target SDK 35. APK Signature Scheme v2 verified. zipalign -P 16 passed and all packaged 64-bit native PT_LOAD segments meet 16 KB alignment. INTERNET and MANAGE_EXTERNAL_STORAGE are absent; the test provider/activity are absent from the application manifest.

Reports:
- JVM/lint/APK: artifact 11943788794.
- Native API 29: artifact 11944293120.
- Native API 35: artifact 11944104871.
- Native API 35 with 16 KB pages: artifact 11943979784.

This verification establishes buildability and the tested synthetic/isolated behavior. It does not establish a semantic accuracy percentage, physical-phone throughput/battery/RAM, release R8/signing behavior, every OEM/provider contract, or safe destructive acceptance on a real archive. Main remains unchanged at b32444218f4a5e262803199abbd70e98df786ffe. These results and the linked API-29-compatible APK describe that earlier implementation. The current platform requirement and its verification follow below.


## Android 15+ Requirement and Verification

The minimum supported OS is now **Android 15 (API 35)**. Compile/target SDK remain 35; no maximum OS version is declared. The API 29 emulator entry was removed from CI; API 35 with 4 KB and 16 KB pages remains. README and built-APK inspection now use the same minimum. This platform update changes configuration and documentation only; AI and transfer source files are unchanged.

Verified application/configuration commit: `1f8eb051199c48021e1291af7e8db13cd5a18912`.
Full workflow: **SUCCESS** on 2026-10-15 UTC.
https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38682222910

| Check | Executed result |
| --- | --- |
| Clean debug build, JVM tests, lint and instrumentation compilation | PASS |
| Python executor tests | 14 passed |
| Android 15 / API 35, 4 KB pages | 18 tests passed |
| Android 15 / API 35, 16 KB pages | 18 tests passed |
| APK minimum/target SDK | 35 / 35, inspected from the actual APK |
| APK v2 signature, ZIP alignment and 64-bit ELF 16 KB alignment | PASS |

[Download the Android 15+ debug APK](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38682222910/artifacts/11948872046).
The artifact download API successfully returned the archive bytes.
APK size: 187,562,102 bytes.
APK SHA-256: `5a4e9b4737d5747d6cf5c637832d09d8ff92a1d39876e7499c85b90ebc37e577`.
Artifact ZIP SHA-256: `0c59fbfe56281b0edab959165ce5108f3eef2b90c2ec145852a29616e66fc2e5` (separate from the APK digest).

Reports: verification/lint/APK artifact `11948922123`; API 35 artifact `11948827771`; API 35 with 16 KB pages artifact `11949500421`.

This report-only follow-up leaves the verified application, tests and build configuration unchanged. Physical-device/OEM acceptance and semantic accuracy remain subject to the limitations above.
