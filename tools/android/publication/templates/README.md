# Black Marlin Android publication kit

**Reconstructed publication-preparation draft — not submitted to Google Play.** The original draft's source review and the official-reference research were completed on 26 November 2026. This restored kit needs its own independent review. `source-snapshot.json` identifies the exact repository commit used for this reconstruction. Device acceptance, production signing, content rating and store approval are separate gates.

## Contents

| File | Purpose | Status |
| --- | --- | --- |
| `store-listing-en-US.json` and `listing-*.txt` | English title, short description and full description | Source-based draft; checked character counts |
| `PERMISSIONS_AND_DATA.md` | Permissions, retention, network recipients and declaration questions | Evidence map; no Data safety form submitted |
| `PUBLISHER_INPUTS.md` | Nine publisher-specific inputs for signing and submission | Unknown fields remain explicit |
| `STORE_ASSETS.md` | Official limits, artwork status and capture plan | Original graphics supplied; listing captures pending |
| `assets/` | Faithful launcher export and original feature graphic, with provenance | SVG and sRGB PNG; no third-party artwork |
| `verification-evidence/` | Evidence scope and capture intake | No diagnostic screenshot promoted to a listing asset |
| `sources.json` / `source-snapshot.json` | Official links and exact source-file hashes | Original research date distinguished from snapshot date |
| `INDEPENDENT_REVIEW.md` / `kit-validation.json` / `SHA256SUMS` | Review scope, checks and artifact identities | Reconstructed draft awaiting review |

The source build uses `com.blackmarlin.downloader`, version `1.0.0` / code `1`, minimum API 26 and target API 36. It packages **arm64-v8a and x86_64 only**; it does not support every Android 8 device. Google currently requires API 36 or later for new phone-app submissions. Meeting that target alone does not establish Play acceptance. [P08]

Read the repository's [build and release guide](https://github.com/DecoyINDIA/BMD/blob/android/native-production/docs/android/BUILD_AND_RELEASE.md) for artifact identity, signing and execution gates. Use the commit recorded in `source-snapshot.json` when comparing that guide with this kit. Tie eventual listing captures, declarations, test reports and signed artifact hashes to the actual selected release.

## Recreate metadata and artwork

Requirements: Python 3, Git, Pillow, Inkscape and DejaVu Sans. Use a local BMD checkout; the scripts read the selected Git commit and write outside that checkout. From an extracted kit directory:

```sh
python3 prepare_metadata.py --repo /path/to/BMD --ref EXACT_COMMIT --out ./regenerated
python3 build_assets.py --repo /path/to/BMD --ref EXACT_COMMIT --out ./regenerated/assets
```

The metadata command regenerates machine-readable files and listing text. Copy the reviewed prose documents separately when assembling another full kit. Neither script modifies application source. Both reject an output directory inside the checkout. All source-file reads use `git show` at the resolved commit, so unrelated uncommitted test changes cannot silently enter the snapshot.

If an actual release merged manifest is available, pass `--merged-manifest /path/to/AndroidManifest.xml` to the metadata command and independently verify its build provenance. This restored kit distinguishes the current source manifest from the previously inspected historical release merge; it does not pretend that a fresh merged artifact was inspected when none was supplied.

To include a verified CI capture, add both `--evidence-image /path/to/capture.png` and `--evidence-provenance /path/to/provenance.json`. Provenance must contain its expected `sha256`, a relative `sourceArtifactPath`, and `status: verification-evidence-only-not-for-store-upload`. Mismatched or missing inputs fail before output. The capture keeps its original CI identity and does not become a store screenshot merely by being copied.

The official-source research date remains **26 November 2026** in `sources.json`. Local hashing records its own date in `source-snapshot.json`; rerunning a script does not recheck Google policy or grant independent approval. Generated listing metadata returns to draft status. Review changed source, claims or publisher choices before submission.

After content and artwork checks, create a checksum-bearing ZIP:

```sh
python3 package_kit.py --kit . --zip ../BMD-Android-Publication-Kit-Draft.zip
```

## Remaining work

Supply the nine publisher inputs, obtain final accepted-binary screenshots, verify the final merged manifest and Data safety answers, host the publisher-complete privacy policy, sign the chosen release and complete the required Play track/review process. No publisher identity, contact, rating, credential, benchmark or approval has been invented.

[P08]: https://developer.android.com/google/play/requirements/target-sdk
