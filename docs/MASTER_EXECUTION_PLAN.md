# Master execution plan

The one engineering plan for BMD: what is verified today, what is wrong, and the order to fix and
finish it, for the desktop and Android apps together. Written 2026-11-27 from the audit in
[AUDIT_REPORT.md](AUDIT_REPORT.md) (finding IDs below point there) and the checks in
[VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

How the documents divide the work, so there is never a second plan:

| Document | Holds | Changes when |
|---|---|---|
| **This file** | Engineering order: work packages, dependencies, acceptance, gates | A package starts, finishes or is re-planned |
| [ROADMAP.md](../ROADMAP.md) | The public, per-version product status (✅ 🔄 📋 🔍), as CLAUDE.md rule 5 requires | Every PR |
| [CHANGELOG.md](../CHANGELOG.md) | What each release changed | Every release |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the pieces fit | The structure changes |
| [AUDIT_REPORT.md](AUDIT_REPORT.md), [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md), [BRANCH_RECONCILIATION.md](BRANCH_RECONCILIATION.md) | The 2026-11-27 audit's record | Not edited later except to mark a finding fixed |
| `docs/android/*` | Android's contract, build, privacy and store kit | With the Android code |

A work package here that ships something user-visible gets its ROADMAP line in the same PR.

## 1. Executive summary

BMD is a mature desktop download manager (Rust, egui; Windows first, Linux, experimental macOS)
with a newly added native Android app over the same engine. The desktop engine, resume and
persistence are verified sound; 1,608 tests pass. The repository is now one `main` with no open
stale work. What stands between this and the next release is mostly **release trust** (SEC-01,
P1) and, for Android, **one lifecycle defect, translations and real-device qualification**.
Order: stabilize the repository's rules (WP-00), fix the release blockers (WP-01), then the
narrow reliability windows (WP-02), then each app's completion in parallel (WP-03, WP-04), then
the cross-cutting work (WP-05…WP-09), and only then a release (WP-10).

## 2. Verified current status

| Area | Status | Evidence |
|---|---|---|
| Desktop build, fmt, clippy (`-D warnings`) | PASS on Windows here; PASS on Windows, Linux, macOS in CI | VERIFICATION_REPORT §2 |
| Desktop tests | 1,608 pass, 0 fail, 32 ignored; 3 targets flaky under full parallel load (pass alone) | VERIFICATION_REPORT §2 |
| Extension logic tests | 109 checks pass | `node tools/extension/test.mjs` |
| Help pictures | PASS after consolidation (failed on `main` before) | `python tools/shots/make.py check` |
| Dependency advisories | PASS with one documented exception | `cargo audit` |
| Android build, lint, 55 JVM tests, emulator API 26 and 36 (4/16 KiB) | PASS in CI at `aef830b`; later runs failed only on Maven Central's rate limit | Android run 45885055170; AND-05 |
| Android on real devices, Android 12–15, TalkBack, network switching, process death | NOT VERIFIED | AND-04 |
| Release pipeline (`release.yml`) | NOT VERIFIED here (runs on a release only) | WP-09.1 |

## 3. Canonical architecture

See [ARCHITECTURE.md](ARCHITECTURE.md). In one paragraph: `bmd-engine` downloads one file and
knows no UI. The desktop app (`bmd-app`) and its satellites (`cli`, `remote`, `mcp`, `nmhost`,
`ipc`, `tools`, `torrent`, `debrid`, `extract`, `cnl`, `geo`, `crash`) sit around it; the
Android app is a separate Kotlin front end whose only Rust is `bmd-android-bridge` → `bmd-engine`.
Dependencies point inward; nothing depends on the bridge. Platform calls live in named platform
modules. One product, two front ends, one engine. No shared cross-platform UI framework is
planned: the two UIs follow their platforms (IDM's layout on the desktop, Material on Android),
and what they share already lives in the engine.

## 4. Desktop readiness

Ready for daily use. Next release blocked by SEC-01. Subsystems:

| Subsystem | Complete | Tests | Open |
|---|---:|---|---|
| HTTP engine (probe, planner, workers) | ~95% | 358 unit + 16 integration files, fault injection | — |
| Resume and crash safety | ~95% | state round trips, e2e | DESK-02 |
| Failures, retries, validators | ~95% | `fault.rs`, labserver faults | — |
| FTP/FTPS | ~90% | integration | — |
| Queues, scheduler, limiter | ~90% | unit | — |
| Names and overwrite | ~95% | naming tests | DESK-03 |
| List and settings persistence, migration | ~95% | corruption, generations, migration | DESK-04, DESK-05 |
| Lifecycle (single instance, exit, session end) | ~90% | app integration | DESK-04 |
| UI responsiveness | ~80% | freeze report test | DESK-01 |
| Self-update | ~70% (trust) | checksum tests | SEC-01 |
| Installer and release | ~75% | smoke in CI | SEC-01, SEC-07, WP-09.1 |

## 5. Android readiness

Source-ready, not release-ready.

| Area | Verified (code, CI) | Not verified (needs devices) | Open |
|---|---|---|---|
| Build and packaging | lint (0 errors), JVM tests, APK, AAB, 16 KiB alignment | — | AND-05, AND-09 |
| Lifecycle | state restore, rotation, `singleTop` | process death mid-picker | — |
| Background work | user-initiated jobs (14+), WorkManager (older) | Android 12–13, Doze, standby buckets | AND-01 |
| Storage | MediaStore and SAF publication byte-checked (API 26, 36) | full storage mid-save, SD cards | AND-06 |
| Permissions | minimal set | notifications denied | — |
| Network | sockets bound to Android's network, no fallback | Wi-Fi ↔ mobile mid-download, VPN, captive portal | AND-07 |
| Recovery | start-up reconciliation, writer acknowledgement | force-stop, reboot, app update | — |
| UI and accessibility | Compose smoke tests | TalkBack, font scale, RTL | AND-03, AND-08 |
| Play readiness | minified release, no keys committed | data-safety form, pre-launch report | AND-09, REPO-12 |

## 6. Feature parity matrix

✅ works · ➖ out of scope on purpose · ⚠️ works with an open defect · ❌ missing. "Verified" is how
it was last checked: T (tests), CI, E (emulator), R (runtime by hand), — (not verified).

| Feature | Desktop | Android | Expected behaviour (both) | Defects / work | Verified |
|---|---|---|---|---|---|
| Direct HTTP(S) download | ✅ | ✅ | Probe, download, verify size, save under a safe name | — | T, CI / T, E |
| Multi-connection segmenting | ✅ | ✅ (engine, ≤4 active tasks) | Dynamic split, no overlap | — | T / T |
| Pause, resume | ✅ | ✅ | Resume from the last flushed byte, only if the server's file is unchanged | AND-01 | T / E |
| Resume after restart or crash | ✅ | ✅ (manual resume) | Never lose or mis-claim bytes | DESK-02 | T / T |
| Retries and backoff | ✅ | ✅ | Doubling to 30 s, Retry-After honoured | — | T / T |
| Changed file detection | ✅ | ✅ (strict) | Restart, never splice two versions | — | T / T |
| Checksum verification | ✅ (MD5…SHA-512, CRC32) | ✅ (SHA-256) | Optional expected hash, cancellable | — | T / T |
| Safe file names | ✅ | ⚠️ different rules | One sanitizing rule set | REPO-08 → WP-05.1 | T / T |
| Overwrite policy | ⚠️ | ✅ (app-owned publication) | Never replace unless asked | DESK-03 | T / E |
| Queues, scheduler | ✅ | ➖ | — | — | T / — |
| Speed limiter | ✅ | ➖ | — | — | T / — |
| Network selection | ✅ (adapter choice) | ✅ (Android's network, metered rules) | Downloads leave from the chosen network only | — | T / T |
| Proxy, PAC | ✅ | ➖ (one explicit proxy, no PAC) | — | REPO-09 | T / — |
| Source credentials | ✅ (OS keychain) | ✅ (Keystore, exact HTTPS origin) | Never sent to another origin, never logged | — | T / T |
| FTP/FTPS | ✅ | ➖ | — | — | T / — |
| Torrents, magnets | ✅ | ➖ | — | — | T / — |
| Video pages, galleries | ✅ (yt-dlp, gallery-dl) | ➖ | — | — | T / — |
| Browser capture | ✅ (extension, native host) | ➖ (Android share sheet instead) | — | AND-02 | T, CI / E |
| Clipboard / paste | ✅ | ✅ (explicit Paste) | — | AND-08 | T / E |
| Notifications | ✅ | ✅ | Progress and completion; Android 13+ asks permission | — | — / E |
| Remote control, automation API, MCP | ✅ | ➖ | — | SEC-03 | T / — |
| Self-update | ⚠️ | ➖ (store-managed) | Authentic updates only | SEC-01 | T / — |
| Translations | ✅ (21 languages) | ❌ (English only) | Every visible text translatable | AND-03 → WP-07.1 | CI / — |
| Accessibility | ⚠️ (Linux screen readers in progress) | — | Screen reader names every control | WP-07.2 | — / — |
| Dark mode, themes | ✅ | ✅ | — | — | R / E |

## 7. Defect inventory

All defects, with priority, are the findings in [AUDIT_REPORT.md](AUDIT_REPORT.md):
REPO-01…13, DESK-01…07, AND-01…09, SEC-01…12. Each open one is assigned to exactly one work
package below; the package's status is the defect's status.

## 8. Security findings

SEC-01 (P1) and SEC-02, SEC-03 (P2) are release blockers; SEC-06…12 (P3) are hardening.
Details are kept with the maintainer until fixed (SECURITY.md). Their work packages are WP-01.x.

## 9. Technical debt

| Debt | Where | Package |
|---|---|---|
| Platform `unsafe` outside the two named platform files, not recorded as exceptions | engine `disk.rs`, `sparse.rs`; `nmhost/win32.rs`; `android-bridge` | WP-00.3 |
| URL and name rules in three places | Kotlin `DownloadPolicy`, bridge `transfer.rs`, engine | WP-05.1 |
| Lock poisoning not isolated in shared engine statics | `agent.rs`, `dns.rs`, `naming.rs` | WP-02.4 |
| Two README tool trees; stale contributor-photo text; citations of a private file | `tools/readme`, `scripts/readme`, `ARCHITECTURE.md`, engine headers | WP-00.4 |
| Placeholder site domain in app and extension | `help.rs`, manifests, website | WP-10.2 |
| Generated telemetry committed to `main` | `readme-analytics.yml` | WP-09.2 |

## 10. Missing functionality

- Android: translations (AND-03); its user help on the website (WP-07.3); Android 12–15 device
  qualification (AND-04).
- Desktop: Linux screen-reader support and the other ROADMAP 🔄 items, which stay on ROADMAP and
  are not duplicated here.

## 11. Testing gaps

| Gap | Package |
|---|---|
| Tests sharing files or ports when the whole workspace runs at once | WP-08.1 |
| No test of the update path against a tampered checksum list | WP-01.1 |
| No test that the automation interfaces' save folders are restricted | WP-01.3 |
| No desktop proxy end-to-end test for the manual-proxy change PR #16 made | WP-08.2 |
| Android: no API 31–35 or real-device runs; no process-death, network-switch or TalkBack tests | WP-04.3 |
| A shared fixture set proving the three URL-rule copies agree | WP-05.1 |

## 12. Dependencies and infrastructure

- Rust stable (1.99.1 here; the Android build pins 1.98.0), on this Windows machine the GNU
  toolchain with `CMAKE_GENERATOR=Ninja` and a bindgen include path (local note).
- Android: JDK 17, SDK 36, Build Tools 36.0.0, NDK 29.0.14206865, Rust targets
  `aarch64-linux-android`, `x86_64-linux-android`. Not installed on the audit machine (NDK 28 and
  no Rust Android targets), so Android builds were verified in CI only.
- CI: GitHub Actions (Windows, Linux, macOS, Android emulators, CodeQL, ClusterFuzzLite,
  Scorecard, Pages). Secrets needed for a signed release: the Windows code-signing certificate,
  Android upload key, the future release-signing key (WP-01.1).

## 13. Work packages

Status: **TODO**, **IN PROGRESS**, **DONE**, **BLOCKED**. Complexity: S (≤1 day), M (a few
days), L (a week or more). Every package ends with its own PR, updating ROADMAP (rule 5) and
showing its proof (rule 6). "Independent verification" means a reviewer that did not write the
change, in a fresh context, checks the acceptance criteria against the code and the check output.

### WP-00 Repository stabilization

**WP-00.1 Consolidate branches and PRs** — P1 · all · DONE
- Problem: four open PRs, stale branches, a failing check on `main`. Root cause: work landed on
  long-lived branches without a merge path.
- Done in this consolidation ([BRANCH_RECONCILIATION.md](BRANCH_RECONCILIATION.md)).
- Acceptance: one long-lived branch; every open PR merged or closed with a reason; checks green.

**WP-00.2 Apply the PR rules to the Android work** — P1 · Android · IN PROGRESS
- Problem: REPO-01, REPO-02. ROADMAP and CHANGELOG lines added in the consolidation (DONE);
  screenshots (light, dark, de, ar) wait on WP-07.1, which makes de and ar possible.
- Files: `ROADMAP.md`, `CHANGELOG.md`, `pr-shots` branch.
- Acceptance: the Android item's ROADMAP status is true; a PR shows each Android screen in both
  themes and two languages.
- Depends on: WP-07.1 for the shots. Complexity: S.

**WP-00.3 Record the platform-code exceptions** — P2 · all · TODO
- Problem: REPO-07. Root cause: rule 3 and D-008 name only `app/src/win32.rs` and `unix.rs`.
- Proposal: name every platform module that may opt in to `unsafe` (engine `disk.rs` and
  `sparse.rs`, `nmhost/src/win32.rs`, `android-bridge` `native.rs` and `network::platform`) in
  CLAUDE.md rule 3 and the `Cargo.toml` lint comment, or move them into such modules. No behaviour
  change.
- Acceptance: `grep -rn "allow(unsafe_code)" crates` lists only named modules.
- Tests: build and clippy on all systems. Complexity: S.

**WP-00.4 Small documentation debt** — P3 · all · TODO
- REPO-11: merge `tools/readme` and `scripts/readme` into one tree (update the workflows that call
  them); fix ARCHITECTURE's contributors paragraph to what `tools/contributors.py readme` does now;
  replace citations of the private `notes/ENGINE.md` with public text or remove them.
- Acceptance: one README tool tree; `grep -rn "notes/ENGINE.md" crates` empty. Complexity: S.

### WP-01 Critical security and correctness

Details of each finding are with the maintainer. Public text for all of these: CHANGELOG wording.

**WP-01.1 Update authenticity** — P1 · desktop · TODO · release blocker
- Problem: SEC-01. Affected: `crates/tools/src/update.rs`, `.github/workflows/release.yml`,
  `crates/app/src/help.rs`.
- Proposal: an offline release-signing key whose public half is built into the app; the release
  signs its checksum list; the app refuses an update whose signature does not verify (the OpenPGP
  verifier already used for helpers can be reused). Update-source overrides only in test builds.
- Acceptance: an update with a valid checksum list but no or a wrong signature is refused with a
  clear message; a correctly signed one installs.
- Tests: unit tests for valid, missing, wrong-key and tampered signatures; an end-to-end update
  against `bmd-labserver`.
- Independent verification: a security reviewer reproduces each refusal. Complexity: M.

**WP-01.2 CI least privilege and signing hygiene** — P2 · CI · TODO
- Problem: SEC-02, SEC-07. Affected: `readme-analytics.yml`, `release.yml`.
- Proposal: job-level permissions, no persisted checkout token, signing material confined to the
  step that uses it and removed afterwards. Ties in with WP-09.2.
- Acceptance: no workflow job holds write permission it does not use; Scorecard's token-permission
  check passes. Complexity: S.

**WP-01.3 Save folders from automation interfaces** — P2 · desktop · TODO · release blocker
- Problem: SEC-03. Affected: `crates/mcp/src/lib.rs`, `crates/app/src/app.rs` (automation API).
- Proposal: a folder given through the automation interfaces must be inside a configured download
  folder, or the download waits in Download File Info for the user; some system folders are
  always refused.
- Acceptance and tests: requests inside, outside and into refused folders behave as specified;
  a test for each. Complexity: S–M.

**WP-01.4 Advisory exception review** — P2 · desktop · TODO (recurring)
- SEC-05: drop the `.cargo/audit.toml` exception when the crash-report crates move to a fixed
  version; until then the shipped app is unaffected (`cargo tree -p bmd-app -i ruzstd` is empty).
- Acceptance: `cargo audit` passes with no exception. Complexity: S.

**WP-01.5 Forked dependencies** — P3 · all · TODO
- SEC-06: host the forks (`egui`, `wgpu`, `winit`, `rqbit`) under the project's organization,
  record each one's upstream base and diff, and add a source allow-list (cargo-deny).
- Complexity: M.

**WP-01.6 Local interface hardening** — P3 · desktop · TODO
- SEC-08…12, as listed for the maintainer. Complexity: M.

### WP-02 Core download reliability

**WP-02.1 Final rename and resume state** — P3 · desktop, Android · TODO
- Problem: DESK-02. Root cause: the final name is chosen at rename time and not recorded in the
  resume state first. File: `crates/engine/src/download.rs`.
- Proposal: write the final path into the state before renaming; on resume, accept a complete
  file only at that path.
- Acceptance: a crash injected between rename and state removal resumes to the right file.
- Tests: an engine test that stops at that point. Complexity: S.

**WP-02.2 No-replace rename** — P3 · desktop · TODO
- Problem: DESK-03. Proposal: an atomic no-replace rename (Windows `MoveFileExW` without
  replace; Linux `renameat2(RENAME_NOREPLACE)`; macOS `renamex_np(RENAME_EXCL)`), in the platform
  modules, falling back to the current check where unsupported (say so in a `cfg` comment).
- Acceptance: a file created between check and rename is never replaced. Depends on WP-00.3.
  Complexity: S.

**WP-02.3 List saver robustness** — P3 · desktop · TODO
- Problems: DESK-04, DESK-05. File: `crates/app/src/store.rs`.
- Proposal: if the saver thread can't start, save on the calling thread and report it; write the
  backup generations with the same atomic, owner-only write as the main file.
- Acceptance and tests: a forced spawn failure still saves and exits. Complexity: S.

**WP-02.4 Lock poisoning** — P3 · engine · TODO
- Problem: DESK-06. Proposal: recover poisoned shared state (`PoisonError::into_inner`, as
  `store.rs` does) where the data stays valid; keep the panic report.
- Acceptance: a test panics while holding each shared lock, and the next download still works.
  Complexity: S.

### WP-03 Desktop completion

**WP-03.1 No disk waits on the window thread** — P2 · desktop · TODO
- Problem: DESK-01 (ROADMAP 🔍 item). Proposal: move the remaining start/resume, finish-rename
  and add-links disk calls onto workers, as list saving and resume reading already are.
- Acceptance: with a test drive that answers in 5 s, the window keeps drawing (freeze report test
  extended). Complexity: M.

**WP-03.2 The ROADMAP's 🔄 and 🔍 desktop items** — P2/P3 · desktop · IN PROGRESS
- Tracked on ROADMAP (social posts, Add torrent rename, Linux remainder, Safari, Wayland,
  look-up wait, bot checks, wake freeze). Not duplicated here; each lands with its own PR.

### WP-04 Android completion

**WP-04.1 Background resume on Android 12–13** — P1 · Android · TODO · release blocker
- Problem: AND-01. Root cause: a refused foreground start is classified as a transfer failure.
  Files: `platform/TransferExecutors.kt`, `TaskScheduler.kt`, `TransferCoordinator.kt`.
- Proposal: treat a refused foreground start as "waiting for Android" (retry when visible or
  when the system allows), and schedule such resumes as expedited work.
- Acceptance: on API 31 and 33 emulators, a download waiting for Wi-Fi that resumes in the
  background ends complete or waiting, never *Failed*.
- Tests: JVM test of the classification; a device-smoke case on API 31 and 33.
- Depends on: WP-04.3 (matrix). Complexity: M.

**WP-04.2 Bound shared text at intake** — P2 · Android · TODO
- Problem: AND-02. Files: `MainActivity.kt`, `ui/BmdViewModel.kt`, `ui/BmdPresentation.kt`.
- Proposal: cut or refuse shared text above 16 KiB before storing or scanning it; scan off the
  main thread.
- Acceptance: a 1 MB share neither freezes nor crashes; a test proves it. Complexity: S.

**WP-04.3 Device matrix** — P2 · Android · TODO
- Problem: AND-04. Proposal: add API 31, 33 and 35 emulators to `android.yml`'s device smoke;
  write the manual real-device checklist (ARM64 phone, network switch, force-stop, reboot,
  notifications denied, full storage, TalkBack, large font) and record results per release.
- Acceptance: CI green on five API levels; one recorded real-device pass. Depends on WP-09.3.
  Complexity: M.

**WP-04.4 Android hardening** — P3 · Android · TODO
- AND-06 (release folder permissions no longer used), AND-07 (network security config: cleartext
  only where the user consented, with a comment), AND-08 (ask before a share replaces a draft).
  Complexity: S each.

### WP-05 Cross-platform consistency

**WP-05.1 One set of URL and name rules** — P2 · both · TODO
- Problem: REPO-08. Proposal: the engine's rules are the authority; Kotlin keeps only a fast
  pre-check, and a shared fixture file (URLs and names with expected results) runs in the engine,
  bridge and Kotlin tests. File names on Android come from `bmd_engine::naming` through the
  bridge.
- Acceptance: one fixture file; all three suites pass it. Complexity: M.

**WP-05.2 Android `cfg` completeness** — P3 · engine · TODO
- REPO-09: gate `browserlike.rs`'s PAC call for Android, or refuse the feature combination at
  compile time. Acceptance: `cargo check --target aarch64-linux-android -p bmd-engine
  --features browser-connection` fails with a clear message or builds. Complexity: S.

### WP-06 Performance and resources

**WP-06.1 Benchmarks** — P3 · desktop · TODO (ROADMAP 📋)
- Publish the speed comparison (`tools/bench`) and keep it reproducible.

**WP-06.2 Android battery and large files** — P3 · Android · TODO
- Measure a 4 GB download's battery use and memory on a real device; set limits if needed.
  Depends on WP-04.3.

### WP-07 UI, UX and accessibility

**WP-07.1 Translate the Android app** — P2 · Android · TODO
- Problem: AND-03. Proposal: move every interface text into `strings.xml`, then generate the 21
  languages the desktop has, from the same i18n process (`tools/i18n`), so translators keep one
  workflow. Plurals through `plurals.xml`. RTL checked in Arabic.
- Acceptance: no `Text("…")` literal in `android/app/src/main` (a lint check enforces it);
  German and Arabic screenshots (completes WP-00.2). Complexity: M.

**WP-07.2 Accessibility** — P2 · both · TODO
- Desktop: the Linux screen-reader work on ROADMAP. Android: TalkBack labels and font scale on
  every screen. Acceptance: a recorded TalkBack and 200 % font pass; desktop as ROADMAP states.

**WP-07.3 Android user help** — P3 · Android · TODO
- An Android page in `docs/` (and so on the website, in 21 languages), with pictures.

### WP-08 Testing and regression coverage

**WP-08.1 Test isolation** — P3 · desktop · TODO
- Problem: DESK-07. `bmd-engine --test own_name`, `bmd-tools --test fake_ytdlp` and
  `--test system_helpers` fail under the full parallel run on Windows and pass alone. Root
  cause to confirm: a shared fake-helper file copied while another test runs it, and a shared
  port or child process.
- Proposal: per-test temporary copies and ports. Acceptance: three consecutive full
  `cargo test --workspace` runs pass on Windows. Complexity: S.

**WP-08.2 Missing tests** — P2 · both · TODO
- The ones in §11 that belong to no other package: the desktop manual-proxy end-to-end test.

### WP-09 Packaging, distribution, CI/CD

**WP-09.1 First release through the consolidated pipeline** — P2 · desktop · TODO
- Check `release.yml` with download-artifact v8 and `pages.yml` with deploy-pages v5 on a
  pre-release (workflow dispatch), before a real one.

**WP-09.2 Telemetry off `main`** — P2 · CI · TODO
- Problem: REPO-06. Proposal: publish the generated README telemetry to a separate branch or to
  Pages and reference it from the README, so `main` only moves by pull request.
- Acceptance: no bot commit on `main` for a week; README still shows the telemetry.

**WP-09.3 Android CI resilience** — P2 · CI · TODO
- Problem: AND-05. Proposal: let the dependency cache be created on `main` (it is per-branch
  today, and Android CI had never run on `main`); put Google's Maven first and retry on HTTP 429
  with backoff; keep the dependency preflight's "transport failure" distinct from a product
  failure, as it already is.
- Acceptance: ten consecutive Android runs without a transport failure.

### WP-10 Production readiness and release

**WP-10.1 Release gates** — P1 · all · TODO
- Run the gates in §17 on the release candidate; record results in a release checklist issue.

**WP-10.2 Real site domain** — P2 · all · BLOCKED (needs the maintainer's domain)
- REPO-12: replace `bmd.example.com` in the app, extension manifests and website build.

**WP-10.3 Android store identity** — P2 · Android · BLOCKED (needs the publisher account)
- AND-09: version code from the build; upload key; Play data-safety form from
  `docs/android/PRIVACY.md`; pre-launch report reviewed.

## 14. Dependencies between packages

```
WP-00.1 ──► everything
WP-00.3 ──► WP-02.2
WP-01.* ──► WP-10.1           (release blockers)
WP-09.3 ──► WP-04.3 ──► WP-04.1, WP-06.2, WP-07.2 (Android)
WP-07.1 ──► WP-00.2 (screenshots in de, ar), WP-07.3
WP-05.1 ──► WP-04.2           (intake uses the shared rules)
WP-02.*, WP-03.1, WP-04.1, WP-04.2 ──► WP-10.1
WP-09.1 ──► WP-10.1
WP-10.2, WP-10.3 (maintainer inputs) ──► store releases
```

Parallel tracks once WP-00 and WP-01 are under way: desktop (WP-02, WP-03, WP-08.1) and Android
(WP-09.3 → WP-04, WP-07.1) touch different files.

## 15. Acceptance criteria

Each package lists its own. Common to all: the change builds and passes clippy with `-D warnings`
on Windows, Linux and macOS (and Android where it touches it); `cargo test --workspace` passes;
user-visible text goes through i18n; ROADMAP is updated; the PR shows its proof.

## 16. Verification procedures

| What | Command |
|---|---|
| Format | `cargo fmt --all --check` |
| Lint | `cargo clippy --workspace --all-targets -- -D warnings`; `tools/dev/check-cross.ps1` for Linux and macOS from Windows |
| Tests | `cargo test --workspace` |
| Engine and bridge, host side | `cargo test --locked -p bmd-engine -p bmd-android-bridge --no-default-features --lib --test mobile` |
| Advisories | `cargo audit` |
| Extension | `node tools/extension/test.mjs`; `node tools/extension/e2e.mjs` |
| Help pictures | `python tools/shots/make.py check` |
| English catalog | `python tools/i18n/extract.py` then `git diff --exit-code -- i18n/en.tsv` |
| Android | see [android/BUILD_AND_RELEASE.md](android/BUILD_AND_RELEASE.md) (lint, JVM tests, APK/AAB, `verify_native.py`, device smoke) |
| Headless download | `cargo run -p bmd-labserver -- --faults "rate=2M"` and `cargo run -p bmd-app -- --cli get <url> -o <dir>` |

## 17. Release readiness gates

| Gate | Passes when |
|---|---|
| R1 Build | All CI jobs green on the release commit, including Android and CodeQL |
| R2 Tests | Full suite green three times in a row on Windows (WP-08.1) |
| R3 Security | No open P0/P1; P2 release blockers (WP-01.1, WP-01.3) done; `cargo audit` clean or every exception reasoned |
| R4 Release trust | Signed checksum list and code signature verified by the previous release's updater (WP-01.1) |
| R5 Android (store) | WP-04.1, WP-04.2, WP-04.3, WP-07.1, WP-10.3 done; real-device checklist recorded |
| R6 Docs | ROADMAP and CHANGELOG true; help pages for new features |
| R7 Independent review | A reviewer who did not write the release checks R1–R6 |

## 18. Definition of done

A work package is done when its acceptance criteria are met, its tests are in the suite and pass
in CI, an independent reviewer has checked it, ROADMAP shows its true status, and this file marks
it DONE with the PR that did it. A release is done when R1–R7 pass. Nothing is marked done on the
strength of code existing: only on a check that ran.
