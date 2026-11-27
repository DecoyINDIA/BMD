# Independent Android implementation review

**Independent engineering verdict: PASS for tested revision f5494de7.** All three Android rows passed. I verified 23 named debug cases and two optimized production-payload cases with zero skips. The current source CI, Fuzz, CodeQL and Help pictures jobs also completed successfully.

Reviewed by GPT-6.2 Sol over 26–27 November 2026 IST. The recorded CI run occurred on 26 November 2026 UTC. The application and test harness were authored independently of this review.

## Tested identity and deliverables

| Item | Exact identity |
| --- | --- |
| GitHub branch revision and tree | `aef830b8bf9965a22dfa898d2c18b327728347f4` · `470d5e4e6ae05a548bc59d7934aad5e944ec095c` |
| PR test checkout and tested tree | `f5494de70d30c070590879e396fb3a1470a1483d` · `470d5e4e6ae05a548bc59d7934aad5e944ec095c` |
| Android Actions run | [45885055170](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170) |
| GitHub build artifact and ZIP SHA-256 | [15149452306](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/artifacts/15149452306) · `e810318d55f1d7b3c716608aff7200cc02b75928d8538032eafb2fb0cea31912` |
| Debug APK SHA-256 | `5141b0cd31742ff54d06032891534190186258f597a69f33b44b47f89e8be61b` |
| Unsigned optimized release APK SHA-256 | `e6920c0dc4b029b79643c3968d44b9ca54ba02ed42f5aa37a4031669db31f5ff` |
| Unsigned release AAB SHA-256 | `51c3bc30113da7d9c54e955532dae1c0ff1873c5dd27886e19e228a85ecf5e41` |
| Native source fingerprint | `29d399ca2b00ca68f38513799c7a2e1f497058d3198ebf7a6dc019cc38007329` |

I fetched the GitHub merge commit and compared it with the reviewed branch revision. Its parents are main `4b59d174` and the branch revision above. The branch and tested Git trees match exactly. The integration preserved every upstream path and mode outside README.md, where it retained the three existing Android paragraphs within the updated documentation chapter. Relative to 665a48c9, the only changed file is the approved standalone harness interaction.

## Executed gates

| Gate | Actual evidence |
| --- | --- |
| Rust contracts and selected desktop regressions | PASS. Clippy with warnings treated as errors, 20 bridge tests, 182 engine unit tests, seven mobile HTTP/TLS integration tests and four selected shared desktop regression tests. Two engine tests were ignored by their existing annotations. [Rust job](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/job/140133577327) |
| App lint, JVM and distributable packaging | PASS. 55 JVM tests and zero failures, errors or skips. Both lint variants had zero errors, 47 warnings and four hints. I matched the archived product hashes, embedded clean revision, notices and both exact native libraries. [Build job](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/job/140134527323) |
| Standalone harness DEX and production-payload equality | PASS. My exact APK/SDK rerun produced a byte-identical DEX report. The 662 harness definitions and 3,421 app definitions are disjoint. The required Trace class, public delegate constructor and SDK test-library manifest declarations are present. The production APK hash matched before and after harness assembly. |
| Android 8.0, API 26, x86_64, 4 KiB | PASS. Seven debug cases, including real SAF picker and full checksum, Android TLS trust, JNI/Room initialization and legacy descriptor handling. I checked the measured `/proc/self/smaps` page size. [Device job](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/job/140139882285) · [Artifact 15149977006](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/artifacts/15149977006) |
| Android 16, API 36, x86_64, 4 KiB | PASS. Eight debug cases and the optimized HTTPS and HTTP fixture publication case. Page size measured by `getconf`. [Device job](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/job/140139882348) · [Artifact 15149234135](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/artifacts/15149234135) |
| Android 16, API 36, x86_64, 16 KiB | PASS. Eight debug cases and the optimized HTTPS and HTTP fixture publication case. Page size measured by `getconf`. [Device job](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/job/140139882227) · [Artifact 15150690797](https://github.com/DecoyINDIA/BMD/actions/runs/45885055170/artifacts/15150690797) |
| Native UI inspection | Both optimized HTTPS images show the LICENSE Review and 34.3 KiB metadata. The 16 KiB published image shows File details, 64.0 KiB, and the expected SHA-256 badge. The 4 KiB published PNG captures the Files index with its 64.0 KiB fixture. Its paired XML records the earlier File details and hash badge. |

Both physical APK archives carry uncompressed bridge entries whose data offsets are 16 KiB aligned. Both native ELF binaries have three 16 KiB-aligned load segments and the expected three JNI entry points. I decoded the exact AAB's bundle configuration and verified `PAGE_ALIGNMENT_16K` with native library uncompression enabled. These packaging checks cover both supported ABIs. Executed device evidence is recorded separately in the table.

I verified each device ZIP digest and parsed its original instrumentation transcript against the exact required method names and no-skip records. The modern debug rows cover real MediaStore creation and recovery, real SAF publication and the byte and private-cleanup assertions. Both optimized cases ran the exact nondebuggable `com.blackmarlin.downloader` payload after ephemeral signing. I compared the non-signature ZIP payload ledger against the delivered unsigned APK and matched each reported installed APK SHA-256. The pinned HTTPS review verified Android TLS trust and source metadata. The HTTP fixture separately verified the complete 65,536-byte ContentResolver stream, expected SHA-256 `0bca3b5b2577c1341e7c61a10d9b664333ab04ed8976c8b594a75353dc2fb0ef`, caller-owned MediaStore row, cleared pending status, and explicit file and transfer-history cleanup.

### Repository checks

I verified terminal success for Linux, macOS, Windows, browser extension and translations. I matched the tested checkout in the three desktop job logs. All eight fuzz targets finished. Actions, JavaScript/TypeScript and Rust CodeQL, and the Help pictures check, completed successfully. [Repository CI](https://github.com/DecoyINDIA/BMD/actions/runs/45885056432) · [Fuzz](https://github.com/DecoyINDIA/BMD/actions/runs/45885055126) · [CodeQL](https://github.com/DecoyINDIA/BMD/actions/runs/45885055078) · [Help pictures](https://github.com/DecoyINDIA/BMD/actions/runs/45885055641)

### Lint warning triage

The debug and release XML reports have the same issue counts. I found no release-scope blocker in these findings.

| Issue ID | Count | Assessment |
| --- | ---: | --- |
| `UseKtx` | 23 | Equivalent URI parsing and SharedPreferences extension syntax. |
| `UnusedResources` | 10 | Unused strings. The packaged application label and backup rules have their own active declarations. |
| `GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion` | 7, 3, 1 | Dependency and wrapper update advisories for the existing version pins. |
| `KaptUsageInsteadOfKsp` | 1 | Build tooling recommendation for Room annotation processing. |
| `OldTargetApi` | 1 | Android 16/API 36 is the chosen target and satisfies the current Google Play phone-app target minimum. [Target API requirement](https://developer.android.com/google/play/requirements/target-sdk) |
| `ApplySharedPref` | 1 | A synchronous commit removes an unreadable encrypted credential record on background repository/executor paths. |
| `AutoboxingStateCreation` | 4 hints | Optional primitive state storage in settings. |

## Re-review history

The initial implementation failed review. The source was revised across executor admission, durable ownership, publication and test infrastructure. Each later hosted artifact was interpreted against its own tested tree.

| Findings | Revision reviewed |
| --- | --- |
| A01–A02. JNI ownership and unacknowledged shutdown | Publish native handles and durable ownership together. Bound stop acknowledgement and retain ownership on timeout. |
| A03–A04. Late scheduler actions and lost durable stop | Bind scheduler effects to task UUID, Android job ID and epoch. Propagate the executor stop token before admission and consult durable Pause/Cancel intent. |
| A05–A06. Stranded local work and idempotent scheduling | Retry eligible local slot work and replay the same persisted submission without creating duplicates. |
| A07–A08. Changed staging, stale Locate and partial SAF | Check authoritative length and supplied SHA-256. Clear stale Locate when preparing a separate copy. Preserve a recovered generic SAF document until a safe recovery choice. |
| A09–A10. Unresolved private discard and premature picker delivery | Offer a confirmed private-only discard. Deliver the original task/draft URI and flags after the activity resumes. |
| A11–A12. Android compilation and post-hash native stripping | Correct coroutine/Job APIs. Strip with the pinned NDK first and verify the exact library bytes in the distributables. |
| A13–A14. Missing replacement choices and lost URI/PFD on cancellation | Pass the whole confirmed replacement, close the old epoch and record known provider identity before the copy. Hand off descriptor ownership safely. |
| A15–A16. API 26 compatibility and scheduler ID collisions | Version-check nonregular descriptors and retain recoverable data on older Android. Configure a disjoint WorkManager job-ID range while preserving other AndroidX initializers. |
| A17–A19. CI AVD, dependency transport and runtime observation | Share the exact AVD path, stage the pinned Robolectric jar, retry only dependency transport failures, measure actual page size, and allocate enough memory for modern guest images. |
| A20–A21. Real MediaStore failure and obscured test errors | Open a verified app-owned pending row in nontruncating read/write mode and check the same descriptor before the first copy. Check the closed file's size from its descriptor. Bound test diagnostics and preserve the primary exception before ActivityScenario teardown. |
| A22–A23. Premature SAF/FTP assertions and misleading active recovery UI | Await settled save results. Confirm actual share rejection and separate Review validation without creating a task. Present recovery choices only on an ownerless verified held save and retain Stop saving while active. |
| A24. Android data-directory alias blocked private cleanup | Resolve the trusted no-backup prefix, reject redirected transfer/task roots, and avoid following interior links. Retain real owner-release, CLEANED, empty source URL and absent-stage assertions before fixture cleanup. |
| A25. Android 8 DocumentsUI root discovery | Wait for the enabled actual roots list and observed device-storage label. Continue to require the real grant, exact tree identity and actual published bytes. |
| A26. Optimized test runner startup failed before the app test | Isolate the harness runtime from AGP target dependency subtraction and app mapping. Require actual Trace/runner definitions, disjoint target/harness classes, compiled-manifest legacy test-library declarations, and SDK-supported executable member-owner closure. |
| A27. An Android confirmation node went stale before the picker test read its text | Reacquire the actual positive button through a bounded package-scoped selector if Android invalidates it. Require the confirmed app return, exact persisted grant and full byte proof. |
| A28. The standalone optimized test harness lost a reflected public constructor | Keep the exact public one-Class constructor in the test APK. Confirm the actual encoded definition and access flags. The 05d DEX kept only the separate Class-plus-long overload. |
| A29. The optimized test's scroll helper missed Save defaults in a partially scrolled real Settings view | Use bounded swipes inside the observed app ScrollView with a fresh target lookup between overlapping gestures. Preserve the actual settings acknowledgement and subsequent TLS/publication checks. |
| A30. The optimized test read the editable filename before confirmation | Select the unique enabled production EditText by its direct label. Require two fresh exact-value observations and reattempt the same edit within the bounded field wait. Both optimized runs passed the filename edit. |
| A31. A completed checksum-field swipe overran the harness's local observation window | Shorten real swipes, observe the target after each gesture and reserve time for exact field confirmation. The ordinary 4 KiB successor passed the complete optimized test. |
| A32. A scroll-moving label tap left Advanced options collapsed | Acquire the observed enabled clickable parent and wait for fresh bounds to settle. Tap once and require the real checksum editor to be visible before entering the hash. The 665a 16 KiB run passed this stage and published the real fixture. |
| A33. Other optimized workflow buttons were tapped by their nonclickable labels after scrolling | Use the first visible app match and its nearest enabled clickable control. Reacquire both bounds until stable, then tap once and require each original postcondition. Both optimized page-size runs passed the original complete workflow on f5494de7. |

Other reviewed fixes preserve notification generation/epoch matching, durable CLEANED recording, storage reserve budgeting, signed-URL path identity and IPv6 origin normalization. Progress retains downloaded counts through verification and reports resumed rates from the current run baseline.

The historical `2c1a4f67` API 36 job completed the network stage but held MediaStore publication. The `135b456b` devices demonstrated the corrected MediaStore bytes on both modern page sizes, while premature SAF assertions still failed. At `24e133e3`, both API 36 debug rows passed all eight mandatory cases and the API 26 row passed six of seven. Both optimized harnesses installed before AndroidJUnitRunner failed to resolve `androidx.tracing.Trace`. I inspected both primary stacks and the exact archived DEX. The older production and harness DEX files supplied no class definition for the referenced Trace class. [Historical runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45859282531) · [Exact prior-artifact diagnostic job](https://github.com/DecoyINDIA/BMD/actions/runs/45863381978/job/140059895736)

At `05d16123`, API 26 passed all seven cases and ordinary API 36 passed all eight debug cases. The 16 KiB debug row passed seven cases before a stale picker node interrupted SAF. Both optimized harnesses reached AndroidJUnit4 initialization and failed to locate the public delegate constructor. I matched the original public signature in the runner AAR and its absence from that optimized DEX. The current compiled DEX contains the public constructor. [05d runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45865706936)

At `2d023778`, all 23 named debug cases across the three devices passed. Both optimized tests launched the application and selected Any network in Settings. Their scroll helper failed to reach Save defaults. I inspected the actual foreground PNG and hierarchy, which show the selected setting and an active ScrollView with part of the form visible. [2d runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45868324081)

At `44f8f0c1`, all 23 debug cases passed again. Both optimized APKs ran through Settings save and an actual pinned HTTPS Review showing 34.3 KiB and the expected origin. In the HTTP fixture Review, the harness called `setText` and asserted its stale node value 2 ms and 4 ms later. The ordinary API 36 hierarchy subsequently displayed the correct UUID filename. The 16 KiB hierarchy retained the suggested default. I matched both primary failure files and the original PNG/XML. The required optimized publication gate remained open. [44f runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45871289831)

At `3a9e6d45`, all 23 debug cases passed. The 16 KiB optimized case passed publication, checksum, UI and deletion on the verified, ephemeral-signed release payload. Ordinary API 36 passed the filename edit and failed while looking for the visible checksum field. Three 80-step swipes took about 3.4, 5.7 and 5.7 seconds in its loaded emulator. The last swipe returned after the 15-second local deadline. The failure PNG and hierarchy showed the checksum field newly visible, with no test lookup after the third swipe. [3a9 runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45874222584)

At `4ddd19da`, all 23 debug cases and the ordinary 4 KiB optimized runtime passed. The 16 KiB optimized test tapped a nonclickable Advanced label during post-swipe movement, then made six checksum-discovery strokes. Its original screenshot showed the downward chevron and collapsed section. The XML located the clickable direct parent and showed no checksum controls. The ordinary optimized result and earlier 16 KiB optimized result each proved actual 65,536-byte MediaStore publication on their own package identities. The required matrix still needed a single successful successor. [4ddd runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45877186875)

At `665a48c9`, all debug cases passed. Both optimized runs exposed misses in the shared harness click path. Ordinary API 36 tapped a moving Save defaults label. The 16 KiB case proved real HTTP fixture publication and deletion before a missed tap on the transfer-history removal label. I checked the original terminal failures and both exact hierarchies. Their enabled parent controls remained visible. Neither optimized harness completed every assertion. [665a runtime run](https://github.com/DecoyINDIA/BMD/actions/runs/45882080224)

I reran the four canonical cleanup tests directly from compiled Kotlin test classes, plus 16 Python tests for dependency failure classification, runtime observation and the DEX gate. The recovered 24e 16 KiB native Transfers image showed the clean empty state after successful debug fixture cleanup, with the prior storage-recovery warning absent.

## Remaining release qualification

The packaged ABI checks cover ARM64 and x86_64. The executable matrix uses x86_64 emulators. Physical ARM64, OEM/provider behavior, assigned-network and background transitions on device, large-file resource measurements, and TalkBack and large-font acceptance remain separate release checks. The unsigned release APK and AAB need the actual publisher's signing and Google Play workflow. Ephemeral certificates are confined to test copies. The separate publication kit prepares listing material, graphics and declaration inputs for that publisher.
