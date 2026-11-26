# Store assets and capture plan

Official requirements were checked on **26 November 2026**. Source IDs resolve in `sources.json`.

## Limits used for the draft

| Field / asset | Official limit used | This kit |
| --- | --- | --- |
| App title | 30 characters | Counted in listing JSON [P01] |
| Short description | 80 characters | Counted in listing JSON [P01] |
| Full description | 4,000 characters | Counted in listing JSON [P01] |
| App icon | 512×512; 32-bit PNG; sRGB; at most 1024 KB; full square without added outer shadow/mask | Faithful exported artwork [P02], [P03] |
| Feature graphic | Required; 1024×500; JPEG or 24-bit PNG without alpha | Original SVG and RGB PNG [P02] |
| Phone screenshots | 2–8; JPEG or 24-bit PNG without alpha; at most 8 MB each; sides 320–3840 px | Captures pending [P02] |

Use actual 1080×1920 portrait phone captures. Four or more meet Google's minimum-resolution recommendation for screenshot-based promotional formats; promotion is not promised. Standard screenshots must also meet the maximum 2.3:1 long-side ratio. Other device categories have separate asset requirements. [P02]

## Supplied artwork

- `black-marlin-store-icon.svg` / `.png`: all three paths and colors from the original Android launcher vector. The PNG is RGBA with opaque alpha. No mask, border or shadow is added. Alt text: “A white marlin on a teal square.”
- `black-marlin-feature-graphic-draft.svg` / `.png`: original mint/teal layout, a small faithful launcher mark, and the product copy “Your downloads. Your device. Direct links, clear controls.” It is promotional artwork, not a screen capture.
- `asset-provenance.json`: source commit/XML hash, path identity, dimensions, modes, renderer and output hashes. `build_assets.py` reproduces the export.

No Mobbin reference image, another app's logo or recorded motion is included. The graphics reuse Black Marlin's own vector; earlier Mobbin references informed interaction planning only. Graphics still require publisher brand approval.

## Actual listing capture plan

| Order | State to capture from the accepted binary | Optional caption | Readiness |
| --- | --- | --- | --- |
| 1 | Review of a public HTTPS file and its actual destination | Review before you start | Pending |
| 2 | Real active transfer with progress and Pause | Keep downloads under control | Pending |
| 3 | Files containing a genuinely published public fixture | Saved and ready | Pending |
| 4 | Reproducible save recovery with retained downloaded bytes | Retry saving without another download | Pending |
| 5 | Network settings showing Unmetered and actual limits | Choose how your network is used | Pending |
| 6 | Dark-theme transfer or file detail | Light or dark, your choice | Pending |

Use safe public content and deliberate filenames. Keep credentials, signed links and private information out of captures. Prepare a clean device status bar; never invent progress, completion or recovery state. Export screenshots to RGB PNG without alpha while retaining the original visible pixels and capture. Record binary identity and descriptive alt text. [P02]

## Verification captures are a separate input

The earlier CI empty-state image was a diagnostic relaunch after a low-memory kill. It was never a passing test result or a current store screenshot. That obsolete image is not recreated here. Current passing debug-suite evidence and future minified-release evidence must retain their exact run, binary and test scope. They are verification evidence until suitable listing captures are selected and reviewed. No listing screenshot is marked ready in this kit.

[P01]: https://support.google.com/googleplay/android-developer/answer/9859152?hl=en
[P02]: https://support.google.com/googleplay/android-developer/answer/9866151?hl=en
[P03]: https://developer.android.com/distribute/google-play/resources/icon-design-specifications
