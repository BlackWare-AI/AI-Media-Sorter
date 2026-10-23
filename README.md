# AI Media Sorter

Requires **Android 14 (API 34) or later**.

Native Android application using Kotlin, Jetpack Compose, Material 3, Room and WorkManager. The UI uses an AMOLED black background and purple accents, with English and Turkish resources.

## Current behavior

- Discover supported photos and videos in a user-selected SAF directory, optionally including subdirectories.
- Run the **bundled Google ML Kit image-labeling model locally**. No paid API, media upload or INTERNET permission is required.
- Map actual SDK labels to People, Food, Vehicles, Animals, Nature, Buildings, Technology, Text and Other.
- Flag unknown, weak or competing classifications for review. Scores are model outputs, **not measured classification accuracy**.
- Decode several bounded video frames and aggregate their predictions. Repeated decoded frames count once; insufficient samples and ambiguous scenes require review. Recorded times are requested sampling times, not measured frame presentation timestamps.
- Keep manual category corrections and exclusion choices for unchanged original bytes.
- Organize verified files or export CSV/JSON and standalone Termux scripts through a user-selected ZIP destination.

**Custom category descriptions match supported label keywords. They are not open-vocabulary CLIP text prompts.** MobileCLIP import is unavailable until matching encoders, tokenizer, model weights and licensing are supplied. The bundled small ONNX graph is a handcrafted diagnostic projection; it is clearly labeled and cannot approve automatic transfers.

## File preservation

The default is **Analysis Mode**, **COPY**, and **keep the source when filesystem modification time cannot be verified**. Enable Organize Mode explicitly before an in-app transfer.

The production transfer protocol copies to an exclusively created staging document, verifies SHA-256 by reading the staging bytes, creates a new collision-safe final document and reads the final bytes back. It records durable checkpoints and checks the source again before deletion. A requested MOVE becomes a verified COPY with the source retained when stable file identity, durable publication or required timestamp preservation cannot be confirmed. Deletion failure is reported as a retained source.

The app requests original media bytes rather than Android compatibility-transcoded or metadata-redacted versions. ACCESS_MEDIA_LOCATION may be necessary to retain embedded metadata. If original access is unavailable, classification may still run, but the item is marked for review and cannot be transferred.

Filesystem modification time, EXIF capture date and video metadata are separate fields. A provider date is never substituted for a verified filesystem modification time. Unsupported timestamp restoration is reported. Android document providers do not all expose the identity, durability or timestamp controls needed for MOVE; retaining the source is intentional.

Interrupted operations are reconciled for review. Recovery never automatically deletes sources. Analysis restarts can reuse unchanged checksummed classifications; this is not a byte-offset transfer resume.

## Exported operations

Use **Save export ZIP** to make generated files accessible outside the app. The Termux executor uses Python 3 and hashlib SHA-256, with filenames encoded as JSON data rather than shell commands.

Scripts default to dry run. Use --execute for a confirmation prompt or --execute --yes for an explicitly authorized batch. Review items remain skipped unless --include-review is also supplied. URI-only paths are marked unresolved rather than guessed. Android SAF permission does not grant Termux access; Termux needs its own storage access.

## Build and verification

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0 and the checked-in Gradle 8.9 wrapper.

    ./gradlew clean testDebugUnitTest lintDebug assembleDebug
    python3 -m unittest discover -s tests -v

For native instrumentation, install FFmpeg and start an x86_64 API 34+ emulator:

    python3 scripts/generate_test_media.py
    ./gradlew connectedDebugAndroidTest -PtargetAbi=x86_64

CI runs JVM/Python tests, a clean Android build, lint, APK manifest/signature/alignment inspection, and debug/release native tests on Android 14 (API 34) and Android 15 (API 35), including 4 KB and 16 KB pages. Results and debug APKs are downloadable from Actions. A successful build alone does not establish classification accuracy or suitability for a real archive.

See [Independent review and fix record](docs/INDEPENDENT_REVIEW.md) for evidence, resolved defects and remaining validation.

## Dependencies and release status

ML Kit uses the bundled image-labeling SDK; its base weights are packaged by the dependency rather than a separate project model file. ONNX Runtime is used only for the diagnostic model. Dependency/model terms and the diagnostic asset's provenance must be reviewed before distributing a release; this repository does not establish a license grant for third-party weights.

Representative labeled photographs/videos and physical-device measurements are still needed to establish accuracy, RAM, battery and throughput. Begin device acceptance with copied sample media in isolated folders.


APK selection: default builds target arm64-v8a (64-bit ARM). Use `-PtargetAbi=armeabi-v7a` for 32-bit ARMv7. No universal APK is generated by current CI; x86_64 is used only for emulator verification. Release CI APKs use a debug/QA certificate and require physical-device validation before distribution.

Physical test devices require the matching `-PtargetAbi=arm64-v8a` or `-PtargetAbi=armeabi-v7a`. Use copied media in isolated test folders.

See [measured Android 14/ARM optimization and QA results](docs/ANDROID14_OPTIMIZATION.md) for exact APK sizes, dependency breakdown and release limits.
