# Branch and pull request reconciliation

The record of how every branch, pull request and tag of `DecoyINDIA/BMD` was reconciled into one
`main`, on 2026-11-27. What is left to build is in [MASTER_EXECUTION_PLAN.md](MASTER_EXECUTION_PLAN.md);
how it was checked, in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

## Before anything changed

- `main` (local) was one commit behind `origin/main` (`4b59d17` → `05bdca2`, a telemetry refresh) and
  was fast-forwarded.
- Every ref, local and remote, was saved first: a Git bundle of all refs and a list of their SHAs,
  kept outside the repository by the maintainer (`bmd-all-2026-11-27.bundle`,
  `refs-2026-11-27.txt`). Any branch deleted below can be restored from it with
  `git fetch <bundle> <ref>:<ref>`.
- The clone's fetch refspec only fetched `main`; every remote branch was fetched explicitly before
  comparing.

Recorded SHAs:

| Ref | SHA |
|---|---|
| `origin/main` | `05bdca2e2853eccd63e21607586eed2fbdba6867` |
| `origin/android/native-production` (#16) | `14ac304a124f82fd99140ee09a799d0fef9f1db8` |
| `origin/dependabot/cargo/crates-6f32c10d7a` (#14) | `19cbfd16c387997404832f74240c62cc690befa8` |
| `origin/dependabot/github_actions/actions/deploy-pages-5.0.1` (#12) | `5837551ad185b01bde5d54285797a07de7d3f502` |
| `origin/dependabot/github_actions/actions/download-artifact-8.0.2` (#13) | `2eb230574d7d37a7b28bc8dffff9fca6e74f7bd9` |
| `origin/readme-sales-pitch` | `df59211d76299ef588e187d94ae0cca4c5b9fe40` |
| `store-ready-and-owner-cleanup` (local only) | `f44a561bd7012665e4287cf310fce4ede108da3f` |
| tag `v1.0.0` | `b050ab681466850eda125a55094d1987080a4be7` (→ `5f4b55f`) |
| tag `backup/local-main-before-consolidation` (local only) | `5a05172165cad861ca82192c1711b11e1ce6e9bd` |
| `refs/codex/turn-diffs/checkpoints/*` (5, local only) | tree snapshots of an earlier agent's session |

## Decisions

| Ref / PR | Divergence from `main` | Class | Evidence and action |
|---|---|---|---|
| `android/native-production` — PR #16, *native Android downloader* | 22 ahead, 1 behind | **MERGE** | Merges cleanly. Adds `android/`, `crates/android-bridge`, `docs/android/`, `tools/android/`, `android.yml`; changes `bmd-engine` only behind `cfg(target_os = "android")` or options the desktop leaves off (`strict_http`, `network_completion`). Its `docs/video.md` move also fixes `main`'s failing *Help pictures* check (3 problems → 0). Reviewed independently (Agent C, CONDITIONAL PASS): safe as source, not production-ready (WP-04). Merged into the consolidation branch. |
| `dependabot/cargo/crates-6f32c10d7a` — PR #14 | 1 ahead, 12 behind | **MERGE** | `Cargo.lock` only: rmcp 3.9.0 → 3.11.0 (its description still says 3.10.0), brotli 9.1.1 → 9.1.3, brotli-decompressor 6.3.1 → 6.3.2, zip 9.5.1 → 9.6.0. Its own CI passed on every system; the workspace suite was rerun after merging. |
| `dependabot/github_actions/actions/deploy-pages-5.0.1` — PR #12 | 1 ahead, 12 behind | **MERGE** | `pages.yml`: deploy-pages v4.0.5 → v5.0.1, SHA-pinned. A major version: the first Pages deploy after the merge is to be watched (WP-09). |
| `dependabot/github_actions/actions/download-artifact-8.0.2` — PR #13 | 1 ahead, 12 behind | **MERGE** | `release.yml` ×4: v4.3.0 → v8.0.2, matching upload-artifact v7.0.2 already used. PR #16's `android.yml` added another v4.3.0 use; bumped by hand to the same pinned SHA. `release.yml` only runs on a release, so it is checked at the next one (WP-09). |
| `readme-sales-pitch` | 0 ahead, 18 behind | **ALREADY INCORPORATED → SAFE TO DELETE** | Its tip `df59211` is an ancestor of `main`. No PR. |
| `store-ready-and-owner-cleanup` (local) — PR #15, merged | 4 ahead (by SHA), squash-merged as `6b4038a` | **ALREADY INCORPORATED → SAFE TO DELETE** | `git cherry` shows every change on `main`. The seven files that still differ (CHANGELOG, README, ROADMAP, `settings.rs`, `videodlg.rs`, `pages.rs`, `i18n/en.tsv`) were all changed on `main` afterwards (for example `3b218fd`); `main`'s versions are the newer ones. No unique content. |
| tag `v1.0.0` | ancestor of `main` | **KEEP** | The released version. Not moved, not re-tagged. |
| tag `backup/local-main-before-consolidation` (local) | 2 commits not on `main` | **ARCHIVE (local only, never push)** | An earlier import and rebrand, superseded by `main`'s content. It still carries the previous project's identifiers, so it must not be published. Kept in the bundle. |
| `refs/codex/turn-diffs/*` (local) | tree objects, not commits | **SAFE TO DELETE** | An earlier agent's rollback points, never pushed; in the bundle. |
| PRs #7–#11 (Dependabot, closed) | — | **SUPERSEDED** | Closed by the maintainer on 2026-11-19; nothing to do. |
| PRs #1–#6, #15 | — | **MERGED** | Already in `main`. |

No branch was classified BLOCKED, REIMPLEMENT or SELECTIVE CHERRY-PICK: none held unique work that
could not be merged whole.

## How it lands

1. One consolidation branch, `integrate/consolidation-2026-11-27`, cut from `origin/main`
   `05bdca2`: PR #16 merged, then #14, #12, #13, then the consolidation's own commits (hygiene,
   these documents).
2. It goes to `main` as one pull request, through the normal checks; no force-push, no history
   rewrite, no bypassed protection.
3. Once it is merged, PRs #16, #14, #12 and #13 show as merged (their commits are in `main`), and
   their branches and `readme-sales-pitch` are deleted; the consolidation branch is deleted too.
   The local `store-ready-and-owner-cleanup` and `refs/codex/*` are removed from the maintainer's
   clone; the backup tag stays local.

The final state of each step, with its SHA, is in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

## Afterwards: one branch

- `main` is the only long-lived branch. Work happens on short branches that end in a pull
  request and are deleted when it merges.
- The README telemetry job pushes generated files to `main` every six hours, which puts every
  open pull request one commit behind; moving it off `main` is WP-09.
