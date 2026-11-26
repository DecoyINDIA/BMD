# Native Android build and release

The Android application lives in `android/`. Its Compose interface and Android scheduling, Room database, source-credential encryption and URI publication use the existing `bmd-engine` through `crates/android-bridge`. The native library is an actual `cdylib`, not a mocked transfer adapter. The bridge handles direct HTTP(S); Android does not load the desktop UI, PAC JavaScript, browser-cookie capture, video extractors, torrent engines or external downloader binaries.

## Reproducible toolchain

Use Python 3.11 or newer, JDK 17, Rust **1.98.0**, Android SDK platform **36**, Build Tools **36.0.0**, and NDK **29.0.14206865**. The Gradle wrapper is version 8.13 and verifies its distribution SHA-256. The application targets SDK 36, requires API 26, and packages `arm64-v8a` and `x86_64`. Java/Kotlin compile to JVM 17.

```sh
rustup toolchain install 1.98.0 --profile minimal --component rustfmt \
  --target aarch64-linux-android --target x86_64-linux-android
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865"
export RUSTUP_TOOLCHAIN=1.98.0
export CARGO_INCREMENTAL=0
python3 tools/android/build_native.py
cd android
./gradlew --no-daemon -PskipNativeBuild=true lintDebug lintRelease \
  testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease
```

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK. `ANDROID_NDK_HOME` may explicitly select the pinned NDK. The native build script sets the correct API-26 compiler, archiver and linker for each target, uses `cargo build --locked --profile android`, and requests 16 KiB ELF alignment. The `android` profile uses thin LTO, eight code-generation units, stripped debug information and panic unwinding so the JNI boundary can contain panics. Do not change it to `panic=abort`.

No source file uses a developer machine's absolute toolchain path. Do not commit `local.properties`, build output, native libraries or signing keys.

For the pinned AGP 8.13.2, `android.lint.useK2Uast=false` selects its supported legacy UAST frontend. Its K2 frontend crashed inside Gradle-script analysis (`KaFirScriptSymbol` / `findFirCompiledSymbol`), before producing application findings. The option is declared and honored in the [exact AGP 8.13.2 sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2-sources.jar). Lint remains enabled for both variants, with `abortOnError=true`; no detector is disabled and no baseline hides findings. Revisit this frontend selection when upgrading AGP.

## Artifact and source identity

`tools/android/build_native.py` writes these generated build inputs:

- `android/app/build/generated/native/jniLibs/arm64-v8a/libbmd_android_bridge.so`
- `android/app/build/generated/native/jniLibs/x86_64/libbmd_android_bridge.so`
- `android/app/build/generated/native/assets/native-build.json`
- `android/app/build/generated/native/assets/THIRD_PARTY_RUST_NOTICES.txt`

The native manifest records the real Git commit, working-tree state, a SHA-256 over the exact native source/build inputs, locked toolchain information, each library's SHA-256, ELF architecture, LOAD alignments, JNI exports and dependency notices. The build runs the pinned NDK's `llvm-strip --strip-unneeded` before inspecting and hashing each packaged library. Gradle preserves those verified bytes instead of stripping again. Diagnostics use the real source revision generated at build time. A dirty build is visibly labelled; it is never identified as an unchanged upstream engine commit.

The normal Gradle build creates the libraries. `-PskipNativeBuild=true` skips redundant compilation for an already built tree. Packaging always executes `verifyNative`, which rejects absent libraries, stale source fingerprints, mismatched manifests, wrong architectures, missing JNI methods, non-system dynamic dependencies, incomplete license text, or LOAD alignment below 16 KiB. It is not a mechanism to ship an application without a backend.

After changing or committing native sources, rerun the native build. Cargo reuses unchanged compiled objects while the manifest is refreshed to the actual source revision.

## Verification

Run Rust's platform-independent ownership, cancellation, recovery and HTTP/TLS tests:

```sh
cargo test --locked -p bmd-engine -p bmd-android-bridge \
  --no-default-features --lib --test mobile
```

They cover exclusive task ownership, acknowledgment before handle release, cancellation-safe gateway joins, no direct-socket fallback, exact source/credential origins, plaintext consent, HTTPS downgrade refusal, credentials across nondefault ports, changed validators on a misleading 206 response, durable completion-proof ordering, interrupted checkpoints, restart-only replacement URLs, and recovery after a rename interrupted before its verification marker. Host tests deliberately cannot bind an Android Network; the non-Android network adapter fails closed.

Inspect the actual packaged native libraries and ZIP alignment:

```sh
python3 tools/android/verify_native.py \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --apk android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --bundle android/app/build/outputs/bundle/release/app-release.aab
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 \
  android/app/build/outputs/apk/debug/app-debug.apk
```

The ELF parser checks every LOAD segment, and the NDK tools check architecture, the exact Java exports and dynamic library requirements. The archive check confirms that both packaged libraries and packaged provenance match the verified build. ELF and ZIP checks do **not** claim a successful 16 KiB device run. Test on an actual 16 KiB device/emulator before release. Android's official guidance distinguishes [ELF and ZIP alignment from runtime compatibility](https://developer.android.com/guide/practices/page-sizes); the [`zipalign` tool documents `-P 16`](https://developer.android.com/tools/zipalign).

`.github/workflows/android.yml` runs the Rust contracts, both native cross-builds, Android lint/JVM tests, debug and release APK/AAB builds, package verification, and actual instrumentation on API 26 and 36. It uploads the real test reports and artifacts. A workflow definition is not a report that CI or device tests have passed: inspect the run for the exact commit. The instrumentation step requires a real `OK (N tests)` result and named per-test pass records, and rejects failure/abort markers. It starts a deterministic 64 KiB HTTP server supporting HEAD, ranges and a strong ETag. Both API-26 and API-36 gates require the real system SAF picker grant, Rust download, publication and content-URI SHA-256 test to pass. API 36 additionally requires the actual share editor, HTTP consent, Rust transfer, expected SHA-256 verification and MediaStore publication test. An absent fixture or assumption skip cannot satisfy either named download gate.

Both device API gates also require `pinnedHttpsSourceUsesAndroidPlatformTrust`: a bounded native probe of the [35,149-byte LICENSE at pinned revision 6b4038a](https://raw.githubusercontent.com/DecoyINDIA/BMD/6b4038a7691bea096d7f035475ebc277db23c11a/LICENSE) using the actual JNI/Android platform verifier, with no certificate or CA override. It requires the expected HTTPS origin and positive length, and rejects offline, authentication and HTML fallbacks. This establishes an HTTPS metadata-probe path only; the separate deterministic download tests provide full saved-body checksum evidence.

After instrumentation, a bounded diagnostic step attempts to launch the real `MainActivity`, dump its accessibility hierarchy and capture the device display. `native-ui-evidence/capture.json` records whether the activity was observed resumed and whether a PNG was captured. An unconfirmed launch produces diagnostic device evidence, not a verified app screenshot. These files support independent visual inspection; they cannot replace or satisfy the named instrumentation pass requirements.

API 36 also runs a disposable `release-check` instrumentation APK against the actual minified production package. The harness uses `com.android.application` with `android:testOnly=true` and an explicit instrumentation target. It contains only a UIAutomator test and its own normal AndroidX runner dependencies; it imports no application classes and contributes no keep rules or mapping to the application. This avoids AGP 8.13.2's separate-test dependency subtraction, which removed libraries needed by the runner after the target had optimized away their unused classes. Harness R8 uses a private package for renamed classes. CI parses actual DEX `class_defs`, rejects any class definition shared with the target, requires the real runner/Trace/test definitions, and verifies that all method/field owner and superclass/interface references resolve within the harness or the selected SDK's `android.jar` and explicitly required `android.test.base` / `android.test.runner` libraries. These platform test libraries are declared in the harness manifest and build configuration, and their SDK JAR hashes are recorded; installation must provide them. Executable field accesses are read with the SDK DEX disassembler so annotation enum metadata does not masquerade as code. Annotation-only references and dynamic reflection remain outside this static check; the real device test is mandatory. CI records the verified release APK's complete hash before building the harness, requires it unchanged afterward, and verifies its packaged native libraries and notices again.

After boot, runtime observation and test-APK installation succeed, both API-36 rows attempt the minified gate even if a debug test fails. This preserves independent release diagnostics in the same run. A debug failure still fails the job; neither acceptance gate uses `continue-on-error`, and a failed signing or readiness step cannot be treated as an executed release test.

The device matrix retains API 26 and ordinary API 36, and adds API 36's official `google_apis_ps16k` system image. Each job records the actual SDK level, CPU ABI, kernel page size, observation method and guest memory before installation. Both API-36 rows require a successful `getconf PAGE_SIZE`; the 16 KiB row requires `16384` and runs both the complete debug acceptance set and the minified release test. Only the API-26 image, which lacks `getconf`, may use consistent `KernelPageSize` observations from `/proc/self/smaps`. Missing, malformed or conflicting observations fail the gate; there is no assumed page-size default. API 26 receives 2048 MiB of guest RAM, and the modern Google images receive 4096 MiB to accommodate their first-boot services without killing the foreground instrumentation. The host free-memory report and exact AVD configuration are retained with device evidence. The application's two lint gates remain mandatory, and the harness is compiled, independently shrunk, checked for a complete runtime, and executed as an additional gate. Bounded current-run fatal logcat context, primary failure files, and Android process exit information are printed and retained as diagnostics; they never change test acceptance.

For that device run only, `prepare_release_runtime.py` signs copies of the unchanged release APK and harness with one newly generated ephemeral certificate. It repeats the DEX closure and no-overlap check, then checks the production application ID, nondebuggable flag, signer agreement, ZIP alignment, and equality of **every non-signature ZIP entry**, including DEX, native libraries, resources, manifest and assets. The test independently compares its installed `sourceDir` APK hash with the signed-copy hash. It must complete a visible HTTPS Review using Android platform trust and a full HTTP fixture download, publication and owner-context content-URI SHA-256 comparison. The private key is deleted immediately after signing; signed test copies live outside the checkout and are removed before artifact upload. This is runtime qualification of the optimized payload, not production signing. Only evidence, hashes and the unsigned release candidates are retained for distribution review.

Android CI runs for `main` pushes and for pull requests, avoiding duplicate branch and PR builds. To validate a branch without a pull request, use **Actions → Android → Run workflow** and select that branch. The same mandatory build and device gates apply to a manual run.

Maven transport failures are isolated in `resolveAndroidDependencies`, which resolves external module artifacts without building project outputs. Its wrapper permits at most three attempts with 30- and 60-second backoff only for explicit transient dependency HTTP/connection failures. Every attempt and its classification is preserved under `reports/dependency-resolution`. Test/assertion, compiler, R8, lint and integrity failures take precedence and cannot trigger a retry, even when a transport error appears in the same log. The actual compile, lint, JVM test, packaging and runtime gates execute separately, once. Robolectric's pinned Android 14 instrumented SDK is staged through a declared Gradle configuration, and JVM tests run against that local SDK with Robolectric offline mode.

For an unsigned local release, the APK is `app-release-unsigned.apk`. A production signing configuration changes that filename to `app-release.apk`; pass the actual file to verification.

## Network ownership and native performance

Every admitted network executor has an authenticated loopback SOCKS5h gateway. Loopback carries data only within the app. Upstream DNS uses `android_getaddrinfofornetwork`, and every IPv4/IPv6 socket is bound before connect with `android_setsocknetwork`. A failed/lost route has no default-network fallback. Source credentials remain on the exact HTTPS origin, and no Referer is synthesized. These NDK APIs are available before the application's API-26 minimum ([NDK networking reference](https://developer.android.com/ndk/reference/group/networking)).

The native runtime has two async worker threads and at most four blocking workers. At most four executors and 64 retained handles are admitted. The native DTO defaults to four connections and caps requests at eight; the Android application's current preferences explicitly request two connections per transfer. Each transfer has an 8 MiB receive/write backlog budget plus bounded reusable buffers. Each gateway caps concurrent handshakes/relays, limits authentication time, and uses bounded copy buffers. A paused or invalidated executor closes admission, stops current relays and joins pending work before releasing the writer. API-26 platform DNS is noninterruptible; the native code waits for that worker to return rather than falsely acknowledging that it has stopped.

The proxy introduces an extra in-process copy and context-switch path. Benchmark throughput, CPU, energy and memory on representative devices; no throughput or battery improvement is claimed without those measurements. Reusing a completed private file for publication retry avoids a second network download. Private staging and publication may require space for two copies.

Provider I/O has an explicit older-Android boundary. API 26–29 accepts regular local-file descriptors. It rejects and closes streaming/nonregular descriptors before a read or write because the public `Os.fcntlInt` used for nonblocking I/O requires API 30. A rejected save retains its known URI and private bytes, with recovery directing the user to Save a separate copy in a local device folder. API 30+ uses nonblocking reads/writes for supported streams. The descriptor instrumentation tests exercise a real regular-file roundtrip on both CI API levels and require both pipe ends to be rejected and closed on API 26; the separate system-picker/native-download SAF gate remains required.

Both API-36 device rows also require `mediaStoreFreshAndRecoveredRowsPublishWithoutTruncatingUnprovenBytes`. This uses real MediaStore descriptors and the Room save journal to check fresh pending rows and interrupted save phases, including rejecting a nonempty unproven first-write document without truncating it. Its local fixture isolates the provider boundary; it supplements the required real Rust HTTP download, checksum and publication tests.

## Android TLS dependency

The locked native verifier is `rustls-platform-verifier` 0.7.1 and its Android support component 0.2.0. The **actual Maven archive** uses `org.rustls:rustls-platform-verifier:0.2.0` under `https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/`. Some upstream README examples use an obsolete group. Keep the Java component synchronized with the Cargo lockfile, and keep the JNI/verifier classes in R8 rules. The application Context initializes the verifier before the first request; certificate verification is never disabled.

## Licensing and corresponding source

BMD remains GPL-3.0-or-later. `android/app/src/main/assets/GPL-3.0.txt` contains the repository's full license. Native notices are generated from the actual Cargo compiler-artifact manifests, including build/procedural-macro dependencies for transparency. Workspace-inherited package versions/licenses are resolved from their owning workspace. Where a published crate omits license text, `android/third_party/licenses/` contains the upstream license at the commit identified by that crate's published VCS metadata; `index.json` records the provenance. Dual MIT/Apache crates with a vendored MIT license use the MIT option.

The Gradle `generateAndroidNotices` task separately resolves the actual release and debug runtime artifacts, records artifact SHA-256 values, reads Maven POM license declarations (including inherited parent licenses), and preserves embedded LICENSE/NOTICE text from JAR/AAR files and their `classes.jar`. The resulting `THIRD_PARTY_ANDROID_NOTICES.txt` and `android-dependencies.json` are bundled as assets. Apache-2.0 text is included once; Rustls's Android support uses its MIT option. The task fails if a component has no license evidence. The package verifier checks that the archived reports and notices exactly match the generated files and their hashes.

Publish corresponding source for the exact binary revision, the build scripts, Cargo lockfile and Android Gradle files alongside any binary distribution. Preserve dependency copyright and NOTICE text. Generated reports provide concrete dependency evidence; review their terms and the complete distribution before release. They are not a legal conclusion about every distribution obligation.

## Signing and publication

No production private key is stored in the repository. To make a signed release, provide these environment variables through a local secure environment or protected CI secrets:

- `BMD_ANDROID_KEYSTORE`
- `BMD_ANDROID_STORE_PASSWORD`
- `BMD_ANDROID_KEY_ALIAS`
- `BMD_ANDROID_KEY_PASSWORD`

The ordinary pull-request workflow builds unsigned release candidates and a debug-signed test APK. A successful AAB build does not mean the application is published to Google Play. Store publication additionally needs the user's publisher account, app signing/upload key, current store declarations and privacy-policy URL, real screenshots, device acceptance evidence and release authorization. Never ship the debug certificate as a production signing key.

Before marking a build production-ready, verify process death, Android user-stop/reboot holds, assigned-network loss and metering changes, denied notifications, bounded retry, low storage, revoked SAF access, interrupted publication and ambiguous provider completion on real Android executions. Retain those test reports with the native manifest and artifact hashes. Design mockups and planning approval are not substitutes for this execution evidence.
