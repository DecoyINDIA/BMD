# Verification evidence intake

No screenshot is currently included or marked ready for store upload in this reconstructed kit. Screenshots must come from the actual selected binary, retain their original capture identity, and be reviewed for visible state and private data. A debug test PASS, an empty-state launch and a minified-release runtime PASS are different evidence scopes.

The previous kit contained an earlier CI diagnostic relaunch after a low-memory kill. That image was verification-only and is deliberately not fabricated or replaced with a mock screenshot after the workspace restart.

To add an exact CI image with `prepare_metadata.py`, supply the image and a JSON provenance file together. Required keys are `status` (exactly `verification-evidence-only-not-for-store-upload`), `sha256` and a relative `sourceArtifactPath`. Also record the actual run ID, binary/source identity, device/API/ABI/page size and capture circumstances. Do not claim a test outcome from a screenshot alone. Check the source hash before copying; no source URL or credential may be exposed.

Final listing captures are prepared separately under the plan in `STORE_ASSETS.md`. Do not put this evidence folder in an automatic store upload set.
