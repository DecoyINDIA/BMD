# Engineering audit, 2026-11-27

An audit of the whole repository (desktop app, native Android app, browser extension, tooling,
CI and documentation) at `main` `05bdca2` and open PR #16, made before consolidating them. It says
what is true now and why; what to do about it is in [MASTER_EXECUTION_PLAN.md](MASTER_EXECUTION_PLAN.md)
(one work package per finding), and how each claim was checked is in
[VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

Security findings are listed here by priority and area only. Their details stay with the
maintainer until they are fixed, as [SECURITY.md](../SECURITY.md) asks; this file uses the
CHANGELOG's wording.

## Method

- Repository: every branch, PR, tag and local ref inventoried and compared against `main`
  ([BRANCH_RECONCILIATION.md](BRANCH_RECONCILIATION.md)).
- Builds and tests run on this machine before and after the consolidation (Windows 11, Rust 1.99.1
  `x86_64-pc-windows-gnu`), plus the project's own checks (extension logic, help pictures, English
  catalog, `cargo audit`). GitHub Actions results were read for Linux, macOS, Android and CodeQL.
- Four independent reviewers, each in a fresh context, read the code with separate briefs
  (repository and architecture, desktop, Android, security); a fifth reviewed the result. They
  were the same model as the author (Claude Opus 5.5): no other model was available here. Their
  verdicts are in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).
- Claims in README and earlier reports were checked against source, not taken as given. That
  includes PR #16's own "independent verification: PASS", which holds only for what its CI ran on
  emulators.

## What the product is

| Part | Size | What it does |
|---|---|---|
| `crates/engine` | ~27k lines Rust | One file over HTTP(S) or FTP(S): probe, dynamic segment planner, workers, crash-safe resume, validators, limiter, checksums, PAC |
| `crates/app` | ~120k lines Rust (egui) | The desktop app laid out as IDM users expect: list, categories, queues, scheduler, dialogs, tray, settings, themes, 21 languages |
| `crates/tools`, `torrent`, `debrid`, `extract`, `cnl`, `geo` | ~41k | Video pages (yt-dlp, ffmpeg), galleries, BitTorrent, debrid services, archives, Click'n'Load, server country |
| `crates/ipc`, `nmhost`, `remote`, `mcp`, `cli`, `crash` | ~19k | Single instance and command channel, browser native messaging host, remote control server, MCP server, command line, crash reports |
| `extension/` | JS | Chromium (MV3), Firefox and Safari extension |
| `android/` + `crates/android-bridge` | ~7.4k Kotlin + 2.2k Rust | Native Android app over the same engine through JNI (PR #16) |
| `crates/labserver`, `fuzz/`, `tools/` | — | Fault-injecting test servers, 8 fuzz targets, build, release, website and screenshot tooling |

## Overall

- **The desktop download engine is solid.** No P0 or P1 defect was found in downloading itself.
  Resume state is written atomically and never claims unflushed bytes; a file that changed on the
  server is caught (If-Range, a 200 to a ranged request, byte overlap, FTP size and date); names
  from servers are sanitized (separators, control and bidi characters, reserved Windows names);
  the list is saved atomically with three backup generations. On this machine `main` passed 1,608
  tests with 3 targets aborting under full parallel load; the consolidated tree passes 1,642 with 1
  such target (DESK-07), each passing alone.
- **The Android app is real, not a mock**, and well guarded at its boundaries (JNI panics
  contained, sockets bound to Android's network with no fallback, credentials sealed by the
  Keystore, backup disabled). It is tested on emulators for API 26 and 36 only, has one P1
  lifecycle defect, and is not localized. Source-ready, not release-ready.
- **The weakest area is release trust**: one P1 and several P2 findings about how releases and
  their automation are trusted. They block the next release, not the consolidation.
- **The repository had drifted**: four open PRs, two stale branches, a failing required check on
  `main`, documentation that did not mention Android, and process rules (ROADMAP, screenshots,
  i18n) not applied to PR #16.

## Findings

Priorities: **P0** critical, **P1** high, **P2** medium, **P3** low. Status as of the
consolidation's last commit. "Fixed" means fixed and checked; everything else is open, with its
work package.

### Repository and architecture

| ID | Pri | Finding | Status |
|---|---|---|---|
| REPO-01 | P1 | PR #16 did not update ROADMAP.md or CHANGELOG.md (CLAUDE.md rule 5) | **Fixed** in the consolidation |
| REPO-02 | P1 | PR #16 has no screenshots (rule 6); its UI has no German or Arabic to show | Open → WP-07.1 |
| REPO-03 | P2 | `main`'s *Help pictures* check failed (3 pictures under the wrong heading in `docs/video.md`) | **Fixed** by PR #16's move, checked with `tools/shots/make.py check` |
| REPO-04 | P2 | `docs/ARCHITECTURE.md` had no Android, no `bmd-android-bridge`, and a wrong dependency sentence (`mcp`, `nmhost` use only `ipc`) | **Fixed** |
| REPO-05 | P2 | `.gitignore` did not cover the private notes folder, env files, keys, keystores, APK/AAB | **Fixed** |
| REPO-06 | P2 | A scheduled job pushes generated README telemetry to `main` every 6 hours; every open PR falls behind | Open → WP-09.2 |
| REPO-07 | P2 | `unsafe` outside `win32.rs`/`unix.rs` (engine disk and sparse files, nmhost, android-bridge): each scoped and commented, but rule 3 and decision D-008 don't name them | **Fixed** (PR #20): CLAUDE.md rule 3 names every module allowed `unsafe` |
| REPO-08 | P2 | Mobile URL rules exist three times (Kotlin, bridge, engine), and Kotlin names files differently from the engine | Open → WP-05.1 |
| REPO-09 | P3 | `browserlike.rs` calls PAC without the Android `cfg`; it builds only because Android turns that feature off | **Fixed** (PR #20): the PAC call is gated for Android as in `download.rs` |
| REPO-10 | P3 | Gradle wrapper skipped its URL check | **Fixed** (`validateDistributionUrl=true`; its SHA-256 was already pinned) |
| REPO-11 | P3 | Two README tool trees (`tools/readme`, `scripts/readme`); a stale ARCHITECTURE line on contributor photos; code citing a private `notes/ENGINE.md` | Open → WP-00.4 |
| REPO-12 | P3 | The shipped app and extension manifests point at the placeholder `bmd.example.com` | Open → WP-10.2 (release-blocking for store builds) |
| REPO-13 | P3 | Two stale branches, one local branch, five local agent refs | **Fixed**: classified, backed up, merged and deleted; `main` is the only branch ([BRANCH_RECONCILIATION.md](BRANCH_RECONCILIATION.md)) |
| REPO-14 | P2 | PR #16 made `bmd-android-bridge` a shared library on every host; MinGW's linker refuses it ("export ordinal too large"), so `cargo test --workspace` failed on Windows with the GNU toolchain (CI uses MSVC and missed it) | **Fixed**: an ordinary library in the workspace, the Android build asks for the shared library; the workspace suite runs on GNU and the Android build passed in PR #17's CI |
| REPO-15 | P2 | The Android dependency preflight's classifier matched `PKIX` (and other rule words) inside artifact names: a Maven HTTP 429 on `org.bouncycastle:bcpkix-jdk18on` was taken for a certificate failure and not retried (run 45846587660) | **Fixed**: rules read the log with URLs and coordinates removed, certificates matched by their diagnostics; the archived case is a test |
| REPO-16 | P2 | The dependency preflight started `android/gradlew` on every system; on Windows that fails with WinError 193 | **Fixed**: `gradlew.bat` on Windows; tested. Gradle itself not run to the end here (its distribution is not cached on this machine) |

### Desktop

| ID | Pri | Finding | Status |
|---|---|---|---|
| DESK-01 | P2 | The window thread still waits on the disk when starting or resuming a download, renaming or moving a finished one, and adding links without Download File Info (already on ROADMAP as 🔍) | Open → WP-03.1 |
| DESK-02 | P3 | A crash between the final rename and the removal of the resume state can, on a numbered name, report the wrong file as complete | Open → WP-02.1 |
| DESK-03 | P3 | "Never replace" is checked before the rename, not by it: another program's file created in between could be replaced | Open → WP-02.2 |
| DESK-04 | P3 | If the list saver thread can't be started, Exit can wait for ever | Open → WP-02.3 |
| DESK-05 | P3 | The list's backup generations are written non-atomically | **Fixed** (PR #20): backups and both generations written atomically, owner-only |
| DESK-06 | P3 | A panic while a shared lock is held poisons it for every later download in the process | Open → WP-02.4 |
| DESK-07 | P3 | Three test targets fail when the whole workspace runs at once on Windows (a file held by another process, an abnormal exit) and pass alone: they share resources | Open → WP-08.1 |

### Android (PR #16)

| ID | Pri | Finding | Status |
|---|---|---|---|
| AND-01 | P1 | On Android 12–13 a download resumed in the background ends *Failed* instead of waiting, because the foreground start is refused and that error is treated as fatal | **Fixed in code** (PR #20): a refused foreground start waits (WAITING_ANDROID) instead of failing; JVM-tested on API 33; a device run on Android 12–13 is still WP-04.3 |
| AND-02 | P2 | Shared text is stored and scanned whole before its 8 KiB limit applies: a huge share can freeze or crash the app | **Fixed** (PR #20): shared text bounded at 16K characters and 50 links before it is stored or scanned, tested |
| AND-03 | P2 | All interface text is hard-coded English (102 texts outside resources): not translatable | Open → WP-07.1 |
| AND-04 | P2 | Devices tested: API 26 and 36 (4 and 16 KiB pages) emulators only; nothing on Android 12–15 or real hardware | Open → WP-04.3 |
| AND-05 | P2 | The Android CI depends on Maven Central at every run and failed twice on its rate limit (HTTP 429), with no code change | Open → WP-09.3 |
| AND-06 | P3 | Folder permissions taken with the picker are never released | Open → WP-04.4 |
| AND-07 | P3 | Cleartext is allowed app-wide (the engine asks per download), with no network security config | Open → WP-04.4 |
| AND-08 | P3 | A share replaces the link being typed without asking | Open → WP-04.4 |
| AND-09 | P3 | `versionCode = 1` is fixed in the build file | Open → WP-10.3 |
| AND-10 | P1 | The optimized-release acceptance test on the API 36 16 KiB-page emulator fails intermittently before the download starts (`main` at `72120d3`, run 45945391501; PR #17 attempt 1): the captured screenshot shows Advanced options scrolled into view while the captured accessibility tree still shows the unscrolled screen, so the test never sees the control. The app scrolled; UiAutomator read a stale accessibility cache. Release checksum and publication on that configuration are therefore not proven on every run | Fix in review: the test reads a fresh tree before every observation and logs each swipe; **open** until the 16 KiB release job passes on repeated runs → WP-09.3 |

### Security and supply chain

Details with the maintainer; see SECURITY.md to report one.

| ID | Pri | Area | Status |
|---|---|---|---|
| SEC-01 | P1 | Release trust hardening | Open → WP-01.1 (release blocker) |
| SEC-02 | P2 | CI least privilege | Open → WP-01.2 |
| SEC-03 | P2 | Automation interface hardening | **Fixed** (PR #20), tested |
| SEC-04 | P2 | Dependency advisories were never checked in CI | **Fixed in the tree**: a `cargo audit` job; `cargo audit` passes locally; first CI run with the consolidation PR |
| SEC-05 | P2 | One dependency advisory, only in the developers' symbolication tool, not in the shipped app; no upstream fix yet | **Accepted with reason** in `.cargo/audit.toml`; revisit → WP-01.4 |
| SEC-13 | P2 | A dependency advisory published 2026-11-20 against a URL-parsing crate used across the workspace | **Fixed**: patched version in both lockfiles; `cargo audit` passes |
| SEC-06 | P3 | Forked dependencies hosted on personal accounts (all pinned by commit) | Open → WP-01.5 |
| SEC-07 | P3 | CI least privilege (release job) | Open → WP-01.2 |
| SEC-08, 09, 11, 12 | P3 | Hardening of local interfaces and helper key pinning | Open → WP-01.6 |
| SEC-10 | P3 | Crash report URL and query/fragment scrubbing | **Fixed in PR #20** (`crates/crash/src/report.rs`), tested |

Checked and sound: no secret in any tracked file or in history; the command channel, remote
control server, native messaging host, extension messaging and Click'n'Load are bound, token- or
origin-checked and size-capped; archive extraction and torrent names refuse traversal; helpers run
with fixed argument lists and no shell; TLS uses the system verifier; PAC scripts run with memory
and time limits; every GitHub Action is pinned by SHA and no workflow uses `pull_request_target`.

## Readiness

| | Readiness | Why |
|---|---|---|
| Desktop (Windows) | **Ready for use; next release blocked by SEC-01** | Engine, resume, persistence and lifecycle verified in code and tests; one P1 in release trust |
| Desktop (Linux, macOS) | Ready as documented in ROADMAP (Linux) / experimental (macOS) | CI green on both; runtime not checked here |
| Android | **Source-ready, not release-ready** | APK and AAB build; API 26 and API 36 (4 KiB) pass; the API 36 16 KiB optimized-release test is intermittent (AND-10, P1); AND-01 fixed in code (device run pending), no translations, no device matrix, no store identity |
| Repository | **Consolidated** | One `main` (PR #17, `72120d3`), every reconciled branch deleted, 17 checks green |

## Quality score

Weighted as the audit brief asks. Each category is scored from the evidence above; an unverified
category is not given full marks. A total cannot override an open P1: the product is not
release-ready at either score. The "after" column was provisional until PR #17 merged with its CI green (Android included);
it now stands. Android's 4 credits real, CI-tested code on emulators, not store-readiness.

| Category | Weight | Before (main `05bdca2`) | After (consolidated) | Evidence |
|---|---:|---:|---:|---|
| Functional correctness | 20 | 16 | 16 | 1,608 → 1,642 tests pass (flaky targets DESK-07); DESK-02…06 open; Android AND-01 |
| Architecture and maintainability | 15 | 11 | 12 | Clear inward dependencies; docs now match; REPO-07 fixed, REPO-08 open |
| Security and privacy | 15 | 9 | 10 | No P0, sound local interfaces; SEC-01 (P1) open; advisories now checked |
| Testing and verification | 15 | 11 | 12 | Large unit and fault-injection suite, fuzzing, extension tests; a failing check on `main` (fixed); flaky targets; Android device matrix thin |
| Desktop readiness | 10 | 7 | 7 | Ready to use; release trust and UI-thread I/O open |
| Android readiness | 10 | 0 | 4 | Not on `main` before; after: real, tested on emulators, P1 and i18n open |
| Reliability and recovery | 5 | 4 | 4 | Crash-safe resume and list; narrow windows DESK-02…05 |
| Repository hygiene | 5 | 2 | 4 | Stale branches and open PRs resolved; telemetry pushes remain |
| Documentation and execution readiness | 5 | 3 | 4 | One plan, architecture updated; Android not yet in user help |
| **Total** | **100** | **63** | **73** | |
