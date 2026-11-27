# Publisher inputs still needed

The application and draft assets can be made concrete before these inputs are supplied. The table names the finite publisher choices needed for actual signing and Play submission. None has been invented, and this document does not request that secrets be pasted into chat.

| ID | Input | Why it is needed / current state |
| --- | --- | --- |
| I01 | Actual Play developer account, account type, legal owner and authorized access | Account verification and release permissions belong to the publisher. No account or legal identity has been assumed. |
| I02 | Public developer name, support email and privacy contact mechanism | Complete the listing, support and privacy policy. The source project name is not a substitute for a verified contact. |
| I03 | Confirmation of package control and publishing brand | Source uses `com.blackmarlin.downloader`; title draft is “Black Marlin Downloader.” No package availability or trademark clearance is claimed. |
| I04 | Play App Signing and upload-key arrangement | Choose/control the signing identity and protected secret delivery. CI test certificates are not production signing keys. |
| I05 | Price model, launch countries, listing languages and release choices | Tools is a proposed category. No pricing, market or locale decision has been made. |
| I06 | Target audience/age groups and completed content-rating questionnaire | The publisher must answer for the shipped experience. No rating has been assigned. |
| I07 | Final Data safety decisions and publisher-complete public privacy URL | Use the actual data paths and declarations; privacy hosting/contact is still required. |
| I08 | Stable public review fixture and declaration-video hosting/access | Play reviewers need accessible evidence. CI loopback URLs are unsuitable. There is no BMD login; any source-only access must be supplied without sharing production secrets. |
| I09 | Account-specific testing eligibility and test cohort, if applicable | New personal accounts can require a closed-test period before applying for production access. Check the actual account's requirements. |

## Signing handoff

The build reads `BMD_ANDROID_KEYSTORE`, `BMD_ANDROID_STORE_PASSWORD`, `BMD_ANDROID_KEY_ALIAS` and `BMD_ANDROID_KEY_PASSWORD`. Supply them through an approved secret mechanism at release time. Keep the upload key and Play-distributed app-signing role distinct. Record the chosen certificate fingerprints and signed artifact hashes. Do not distribute a CI test key or treat an ephemerally test-signed APK as the production identity. [P11]

Package names are permanent identifiers in Play; verify control before the first application record. A publisher support email is needed. The listing's name, description, contact and pricing choices are actual Console inputs. [P01]

For personal developer accounts created after 13 November 2023, the documented production-access requirement includes at least 12 continuously opted-in closed testers for 14 days before applying. That condition is account-specific; it is not a claim that this publisher meets or owes it. [P10]

## Engineering and submission gates

The missing publisher choices do not replace technical work. Before distribution, finish the exact candidate's device and minified-release runtime checks, verify signing and package provenance, confirm the final merged manifest and native ABI/page-size packaging, and capture real listing screenshots. Complete foreground-service/UIDT declarations and the privacy/Data safety forms with matching evidence.

Use internal/closed testing before first production availability as required by the account and release plan. Percentage-based staged rollout belongs to updates; do not promise a first-release percentage rollout. Play processing and review time are external and have not been estimated as a guarantee.

The source targets API 36. The checked official phone-app submission floor is API 36 from 31 August 2026; recheck at the actual submission date. Target level alone is not store approval. [P08]

No account operation, invitation, message, upload, signing action or Play submission was performed to prepare this kit. See the repository's [build and release guide](https://github.com/DecoyINDIA/BMD/blob/android/native-production/docs/android/BUILD_AND_RELEASE.md).

[P01]: https://support.google.com/googleplay/android-developer/answer/9859152?hl=en
[P08]: https://developer.android.com/google/play/requirements/target-sdk
[P10]: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
[P11]: https://developer.android.com/studio/publish/app-signing
