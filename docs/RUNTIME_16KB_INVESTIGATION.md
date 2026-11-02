# API35 / 16KB runtime investigation

Date: 2026-10-24 UTC. This records evidence, not a verified production fix.

## Historical failures inspected

The exact GitHub job logs were fetched again after the repository moved to `BlackWare-AI/AI-Media-Sorter`.

| Job | Configuration | Observed failure |
|---|---|---|
| [117146510750](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/38952325321/job/117146510750) | API35 / 16KB / R8 release | DataStore `getCoordinator` reported a null `kotlin.Lazy`, followed by SIGSEGV in ART `Class::FindInterfaceMethod`. XML reported 30 tests, 1 failure, 1 live-test skip. |
| [117211788287](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/38971830056/job/117211788287) | API35 / 16KB / debug | SIGSEGV in libart `NterpGetStaticField+294`, while resolving `AnalysisCode.Companion.forKind`. XML: 42 tests, 1 failure, 1 skip. |
| [117216749394](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/38973345810/job/117216749394) | API35 / 16KB / R8 release | SIGSEGV at PC zero through JIT `AndroidComposeView` construction and `Wrapper_androidKt.setContent`. XML: 67 tests, 1 failure, 1 skip. |
| [117222540875](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/38975103683/job/117222540875) | API35 / 16KB / debug | `ReferenceQueueDaemon` null dereference in `ReferenceQueue.enqueuePending`; process terminated during vault concurrency coverage. XML: 6 tests, 1 failure. |

The captured native fingerprints identify the same system image:

`google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`

The ART native traces identify libart BuildId `e4a3c6c284a8b17d09fd84d661cf181b`. All four jobs used emulator **37.2.12.0**, host build **16428233**. This does not establish an unmet minimum emulator requirement. The first release job also captured a Google Play Services `ClassCastException` (`ethf cannot be cast to ethf`) before the application's suite failed. That separate process failure supports investigating the runtime/image; it does not exonerate application or library code.

## What source inspection establishes

- Debug crashes mean an R8-only attribution is insufficient. No broad keep-rule or dependency change is justified as a proven remedy.
- Current shrinker rules already retain application data/domain classes, Kotlin, DataStore and the existing cross-APK test boundary.
- `GeminiKeyVault` serializes all same-process vault instances with a shared canonical-file lock, including AtomicFile read/write/revision access. The concurrency test intentionally adds contention; its failure does not itself prove a missing file lock.
- Application/MediaRepository startup no longer constructs the offline classifier. There is no evidence here that ONNX inference caused the early ReferenceQueue failure. This is not proof that all native/library involvement is excluded.
- The failing sites span framework references, class/method resolution and Compose JIT execution. Existing logs do not isolate one reproducible application-level root cause.

## Supported comparative runtime

Official SDK metadata was inspected without installing system images:

https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml

| Package | Available revision | Declared minimum emulator |
|---|---:|---|
| `system-images;android-35;google_apis_ps16k;x86_64` | 5 | 33.1.24 |
| `system-images;android-36;google_apis_ps16k;x86_64` | 7 | 35.4.9 |

API36 uses the `google_apis_ps16k` target, not an assumed 16KB default target. Adding an API36/16KB diagnostic comparison is supported by that metadata. The API34/35 profiles remain required; API36 success cannot replace or excuse API35 failure. Runtime testing on API36 does not require changing compileSdk/targetSdk35 or minSdk34.

## Diagnostic additions

`scripts/capture_runtime_diagnostics.sh` collects bounded technical snapshots before device teardown: SDK/release/ABI/build fingerprint, actual page size, emulator version/build, active ART APEX/version and a fixed list of VM properties. It changes no VM setting.

Crash-buffer handling extracts allowlisted exception types, recognized thread/process names, Java method frames, native library/symbol identifiers and signals. It does **not** retain raw logcat, exception messages, arbitrary getprop values, serials, account data, personal filenames/full paths, images or provider responses. This is isolated CI instrumentation, not production logging or telemetry. Output is UTF-8 JSON; commands have timeouts and bounded output. Missing metadata is recorded, not used to change a test result.

Without a path argument the script emits a JSON snapshot to stdout, so the runner can retain chronological before/failure snapshots in `android-runtime-diagnostics.txt`. With a path it writes a single JSON document. Metadata has a 15-second total command budget and two-second per-command limit. Five fake-adb/emulator Python tests verify useful technical frames, credential/path/body omission, unavailable-device behavior, output bounds and the runner's stdout interface; none accesses a real device or network.

The native runner must preserve the original Gradle exit status while collecting diagnostics. No failed test is turned into a successful result, no test is disabled, and no retry-to-green is a remedy.

## Recovery-run recurrence

Recovery commit `b93dc93` was tested in push run [40136971532](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40136971532) and PR run [40137030466](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40137030466). Push job [121000436614](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40136971532/job/121000436614) reproduced SIGSEGV at PC zero through Compose JIT placement/AndroidComposeView during `VirtualGalleryUiTest.virtualFolderOpensRealThumbnailAndFullScreenPreview` (68 tests, 1 failure, 1 explicitly unconfigured live-test skip). The matching PR debug profile passed. This recurrence is retained as a failure, not erased by the later fixture correction.

The new snapshot confirms the same API35 fingerprint, libart BuildId and emulator build above, active ART APEX `com.google.android.art` version `352090000`, actual 16384-byte pages and JIT enabled. Google Play Services also crashed in ART ResolveMethod/OptimizingCompiler/JitCompile at 22:08:56 UTC, before the application's crash at 22:12:12 UTC. This cross-process evidence strengthens the runtime/image investigation but does not establish an application-independent root cause or an ARM64 device fix. API36 debug comparisons passed; they do not replace API35 acceptance.

All first-run release jobs passed their instrumentation suites but subsequently failed the newly added co-install check because the instrumentation target was removed at teardown. The next commit corrects that fixture by installing QA explicitly after instrumentation; it changes no application class, library, JIT setting, model or transfer algorithm. New CI after that code change is required and is not presented as a remedy for the unrelated intermittent crash.

## Second recovery run (709ff80)

Push [40139943594](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40139943594) and PR [40139947359](https://github.com/BlackWare-AI/AI-Media-Sorter/actions/runs/40139947359) both completed their build and all three R8-release profiles successfully, including the corrected co-install/private-data smoke. API34 debug and API35/4KB debug passed in both. No failed job was retried.

Both API35/16KB debug jobs failed again. Push job121009366476 reported 40 tests/1 failure/1 skip: SIGSEGV in JIT `CoroutineContext.DefaultImpls.plus` / `CombinedContext.plus` / `withContext`, during the 36-real-JPEG **mock-response** regression; Google Play Services had already aborted in ART ProfileSaver. PR job121009379522 reported 23 tests/1 failure/1 skip: `ReferenceQueueDaemon` failed while `DiagnosticsUiTest.clearRequiresExplicitConfirmationAndCanBeCancelled` ran. These are process failures, not failing category assertions or live Gemini calls.

API36/16KB debug passed in the PR run but failed in push job121009366586 (68 tests/1 failure/1 skip). This was a distinct 10-second `GeminiSettingsUiTest` timeout awaiting replacement-key readback after Activity restart, without a captured process crash. Its input-method log also recorded an asynchronous InputConnection timeout. That does not prove the timeout's root cause. The installed-control test now explicitly hides the IME through framework APIs and waits for hidden insets before scrolling/touch-clicking Save; persistence/feedback/restart/replace/delete assertions remain intact. A future persistence timeout reports only fixed boolean control/feedback state, never key/semantics contents. Neither a longer persistence deadline, direct callback invocation nor test disabling is used. Passing revised tests cannot retrospectively establish why the old click/readback timed out.

## Open evidence gate

The historical issue remains **HIGH / root cause unverified** until it is reproduced and isolated or supported by a demonstrated platform fix. A new passing matrix improves current verification evidence but does not retroactively determine why those failures occurred. Physical ARM64/16KB acceptance remains distinct from emulator ELF-alignment checks.
