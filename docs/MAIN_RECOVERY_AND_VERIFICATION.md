# Main recovery and independent application verification

Date: 2026-10-24 UTC. Base: main `7a0a0b082073b568d1671b30b37584ba3ecf71d9`.
Branch: `codex/main-recovery-and-verification`. Main is not automatically merged or overwritten.

## Confirmed blockers repaired

Main contained 51 unresolved merge-marker lines in eight tracked files. Its Actions run 40120751191 failed without executing jobs; independent YAML/XML parsing failed, and a local Gradle attempt stopped at `processDebugMainManifest` before JVM tests.

The workflow, manifest, README, storage scanner, report/Termux exporters, SafeFileMover and acceptance tests are restored to the reviewed `91ab2dff` implementations, preserving later valid documentation. This removes the non-compilable legacy alternatives, including pre-verification atomic MOVE, byte-count-as-hash fallback, force overwrite and incorrect filename quoting. `SafeFileMover` again delegates to `VerifiedTransferEngine`; explicit approvals, original SHA-256 readback, publication/source checks, checkpoints and conservative timestamp/source-retention behavior remain intact. Gemini v3, galleries, manual correction, app icon, private diagnostics, API34 minimum, compile/target35 and offline diagnostic model assets are retained.

`scripts/check_source_integrity.py` rejects merge markers and invalid app/src XML before builds, including extracted source archives. Tests cover detection, malformed XML, source confinement and credential/cache exclusion. CI uses the canonical current repository for historical size-baseline retrieval rather than the old owner name.

## Safe QA installation and signing

QA release builds have distinct package `com.aimediasorter.app.qa`, label **AI Media Sorter QA**, and `-qa` version suffix. Production package/signing is unchanged; debug remains `.debug`. The official icon is retained. FileProvider authority follows the actual package. Existing app data/key/grants are neither migrated nor erased by installing the separate QA app. QA requires its own secure key entry, model selection and source/destination grants.

Release emulator jobs run the full release suite against QA first. After instrumentation teardown they install an independent debug fixture app, seed a fixed private sentinel, explicitly install QA, then verify different UIDs and retention of the debug app's private sentinel. The first CI attempt incorrectly ran this check after AGP had removed the instrumentation target; all release jobs failed the additional check despite passing their native suites. The corrected order tests the separate install operation directly without weakening any UID or data-retention assertion. This is restricted to temporary emulators and is not a physical-device upgrade/data-migration test.

Optional `QA_DEBUG_KEYSTORE_BASE64` is used only on trusted push/manual CI events; PR runs deliberately receive no signing secret. `configure_qa_signing.py` validates a private androiddebugkey entry using JDK keytool, restricts runner files to700/600, exports only the temporary path and public certificate fingerprint, and fails closed on invalid supplied input. No private key is committed, cached or uploaded. Without a configured secret, mode is explicitly `ephemeral_runner_debug`; installed APK inspection records the actual certificate SHA-256 and package/version.

GitHub integration could not read Actions-secret metadata (HTTP403). A stable QA key is therefore not claimed configured. Original-package in-place upgrades still require its original signing key; no code change can recover that key or guarantee compatibility with the user's installation. Co-installation fixes the immediate need to uninstall the old app, but not historical private-state migration or production signing.

## Runtime stability investigation

All existing API34/35 profiles remain required. API36/16KB debug and release comparisons are added using the officially available `google_apis_ps16k` image; compile/targetSdk stay35. The runner records bounded, sanitized runtime fingerprints, page sizes, ART/APEX/emulator/VM metadata and allowlisted crash details before teardown without changing VM behavior or masking the original test failure.

The earlier crashes span debug and release (ReferenceQueue, ART field/method resolution and Compose JIT). Existing evidence does not justify an R8-only fix, a speculative dependency change, or dismissal as environment-only. See [exact evidence and diagnostic contract](RUNTIME_16KB_INVESTIGATION.md). A passing new matrix does not retrospectively establish the root cause; that historical issue remains HIGH/unverified until isolated or supported by a demonstrated platform remedy.

## Functional scope and evidence limits

Source review found no additional proven Gemini/settings/gallery blocker requiring a rewrite. Existing native tests exercise AQ key persistence/settings, model catalog/protocol mocks, actual JPEG and video decoding, per-file errors and quotas, Room virtual categories/review, manual correction, explicit plan approval and real verified transfers in isolated temporary directories. Context-only strings cannot add destination categories; multiple distinct Gemini category proposals require manual review.

No live Gemini batch or actual user media is used here. The 17 mock composition cases verify the application contract, not model visual accuracy. Real subject-intent correctness, new prompt live-provider compatibility, representative video accuracy, OEM original-byte/timestamp behavior and physical ARM64 acceptance remain separate evidence gates. The local comparison tool is available, but no before/after accuracy percentage is fabricated.

## Executed verification

Local JDK17/SDK35 debug build, lint, JVM tests and debug instrumentation compilation passed (3m02s); 68 JVM tests reported zero failures/errors/skips. ARM64 R8 QA release and release instrumentation assembly passed (3m29s). After the co-install fixture correction, 56 Python tests passed. A real temporary JDK-keytool fixture retained its public certificate across two imports and was removed; this does not establish configured CI signing. Local lint reported zero error/fatal findings and 125 existing warnings, including dependency updates and optional resource/icon guidance.

For tested commit `709ff80`, [push40139943594](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40139943594) and [PR40139947359](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40139947359) both passed APK builds and all API34/35/36 R8-release profiles, including the independent co-install/private-data smoke. API34 debug and API35/4KB debug passed in both. Each passing native suite reports 68 tests, zero failures/errors and one explicitly unconfigured live-test skip. Both overall workflows failed: API35/16KB debug crashed in each; API36 debug additionally timed out during key replacement in the push run, while passing in the PR run. Exact sites and the subsequent IME test synchronization change are recorded in the runtime investigation. No failure was retried into a success.

Actual push APK sizes at `709ff80`: ARM64 debug 36,102,217 bytes; ARM64 R8 QA 19,616,741 bytes; ARMv7 debug 34,458,035 bytes; ARMv7 R8 QA 18,272,519 bytes. CI inspected minSdk34/targetSdk35, signatures, ARM64 16KB ELF alignment and unchanged bundled model hashes. The next test-only synchronization commit changes no production application code, model or transfer algorithm. Its executed native results and current downloadable artifacts are reported in PR10; earlier failures above remain part of the record.

A compilation or passing release suite alone is not acceptance of live semantic accuracy, full 16KB runtime stability, production signing or destructive operations on a real archive.
