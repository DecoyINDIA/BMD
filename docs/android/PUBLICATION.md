# Reproduce the Android publication kit

The native application has a source-based English listing, original launcher/feature artwork, a permission and data-handling map, nine publisher inputs and a real screenshot capture plan. The generators and prose templates live in `tools/android/publication`. Generated PNGs, ZIPs and review captures belong outside the repository.

Requirements: Python 3, Git, Pillow, Inkscape and DejaVu Sans. Choose an exact application source commit and a new or empty external output directory:

```sh
python3 tools/android/publication/build_kit.py \
  --repo . \
  --ref EXACT_COMMIT \
  --out /path/outside/BMD/android-publication-kit \
  --zip /path/outside/BMD/BMD-Android-Publication-Kit-Draft.zip
```

The command snapshots source with `git show`, exports the actual launcher paths, checks text/asset dimensions and hashes, and packages the draft. It does not sign, upload, submit or award an independent review. An existing nonempty output is refused so prepared work is not overwritten. The fixed official-source research date remains 26 November 2026; recheck policy at actual submission.

An actual release merged manifest can be passed with `--merged-manifest`; verify its build provenance separately. Without one, current source permissions and historical merged-manifest evidence are explicitly distinguished. Optional `--evidence-image` and `--evidence-provenance` must be supplied together; the image must match its recorded hash and verification-only classification. Diagnostic images never become store screenshots automatically.

Before publication, supply the actual publisher/account/contact information, signing arrangement, markets/pricing/languages, audience/rating answers, final Data safety/privacy hosting, reviewer access and account-specific test eligibility. The generated `PUBLISHER_INPUTS.md` details the nine inputs without inventing any values.

Review the generated listing and graphics independently. Then obtain actual accepted-binary screenshots, complete the exact release's device/minified-runtime gates, verify its final manifest and signing identity, and complete the applicable Play track and declarations. The first release and later update rollouts have different availability controls. See [Build and release](BUILD_AND_RELEASE.md), [Privacy](PRIVACY.md) and [Design](DESIGN.md).

Any regenerated kit starts as a draft. Keep the resulting ZIP and its SHA-256 with the review receipt and selected source/binary identity. Never transfer an old review result to changed files without review.
