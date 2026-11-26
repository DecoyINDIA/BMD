# Permissions and data-handling map

This map describes the source selected in `source-snapshot.json`. It supports preparation of the Play forms; it is not a submitted declaration or a guarantee of approval. The repository's `docs/android/PRIVACY.md` is the detailed behavior description.

## Manifest permissions

The eight Android permissions below are present in the current application source manifest. A previously inspected release merge at `0c89a5e88ec00ba6bb5eec740e18c5892817e581` also contained the generated signature permission listed last. That historical merge is recorded separately. Recheck the final release APK/AAB merge before submission; the restored kit does not have a newly generated merged manifest unless one is explicitly supplied to `prepare_metadata.py`.

| Permission / boundary | Implemented reason | User and publishing relevance |
| --- | --- | --- |
| `INTERNET` | Inspect and download user-provided HTTP(S) links | Review can contact the chosen source before a transfer starts. HTTP requires explicit task consent. |
| `ACCESS_NETWORK_STATE` | Observe connection changes and enforce network choice | No location permission is requested for this function. |
| `POST_NOTIFICATIONS` | Transfer, saved-file and recovery notices on supported Android versions | Contextual permission flow; privacy setting hides filenames by default. |
| `RUN_USER_INITIATED_JOBS` | Eligible user-requested network transfers on API 34+ | Use the UIDT declaration/policy for the actual download feature. [P09] |
| `FOREGROUND_SERVICE` | Foreground execution for eligible fallback transfers and explicit local saving | Must preserve visible notification and Android start/stop restrictions. |
| `FOREGROUND_SERVICE_DATA_SYNC` | The declared `dataSync` service type | Prepare the Play foreground-service justification and feature demonstration. [P05] |
| `WAKE_LOCK` | AndroidX WorkManager execution infrastructure | No separate manually managed wake lock was identified in app code; do not infer a battery result. |
| `RECEIVE_BOOT_COMPLETED` | Reconcile retained work after boot; receiver also handles package replacement | Recovery preserves stop/pause decisions instead of automatically overriding them. |
| `com.blackmarlin.downloader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX-generated signature boundary in the historical merged release | Not a runtime user-data permission; verify it in the final merge. |

`BIND_JOB_SERVICE` protects the exported job service; it is not an additional requested permission. The local save service and action receiver are not exported. MainActivity accepts `ACTION_SEND` text. The non-exported FileProvider grants scoped temporary access to generated diagnostic files; it does not expose arbitrary private paths. AndroidX can contribute protected components to the final merge, which must be inspected with the selected artifact.

The source does not request all-files access, broad media read, contacts, camera, microphone, location, advertising ID, exact alarms, full-screen intents or local-network discovery permission. Destination access comes from MediaStore or Android's system picker. `usesCleartextTraffic=true` enables the explicit HTTP feature; this is not a claim that all network traffic is encrypted.

## Data handling and recipients

| Data | Processing / recipient | Retention and control |
| --- | --- | --- |
| Draft URL, name, checksum and network/destination primitives | Private UI/ViewModel and Android saved state | Discard resets the draft. Consent is not silently carried into a new task. Unsaved credential input is not saved in instance state. |
| Full task URL, including query string | Private Room record; chosen source receives its request | Retained for active/paused/recoverable work. Removed after successful publication or safe cancellation cleanup. Origin/history can remain. |
| Request metadata and network address | Direct file source; DNS/network services as part of the connection | Governed by those services. HTTPS protects transport to the receiving server; HTTP is explicitly consented. |
| Optional Basic credential or Bearer token | Exact HTTPS scheme/host/effective-port origin | Encrypted in private preferences with Android Keystore AES-GCM. Task-only retention ends with safe task cleanup; remembering across tasks is separate opt-in. |
| Downloaded bytes and checkpoints | Private no-backup staging; then user-selected destination | Retained for resume/save recovery and removed after confirmed safe cleanup. An uncertain provider file may remain separately. |
| Destination URI, publication journal and file index | Private app storage; destination provider receives document operations | Journals support reconciliation. Removing a list/history item is distinct from deleting the actual file. |
| Published file | MediaStore/local or cloud document provider; selected receiving app when opened/shared | Provider files can remain after uninstall. Providers and receiving apps have their own transport, retention and privacy policies. |
| Preferences and credential summaries | Local app settings | User-controlled theme, network, notification privacy, destination and remembered-source choices. Summaries never contain the secret. |
| Diagnostics | Generated locally only on request; sent through the user's chosen share app | Preview omits full URLs, credentials, filenames and private paths. API/ABI, versions, task IDs, states, counts and error codes remain. |

Black Marlin has no own account, account-sync server, advertising, analytics tracking or automatic diagnostic-upload endpoint. The clipboard is read only on explicit Paste. Source URLs still leave the device when the user requests inspection or download; a cloud document provider may upload/sync files independently of Black Marlin's network preference.

Private application data is excluded from cloud backup and device transfer, and staging uses the no-backup directory. The database is protected by Android's private-storage boundary; it is not described as separately encrypted. Credential encryption does not imply that JVM memory can be permanently wiped. Logical deletion is not a forensic-erasure promise.

## Data safety preparation

Do not automatically answer “no data collected” or “no data shared.” Google's definitions distinguish off-device collection, local processing, and exemptions for certain user-initiated sharing. A sharing exemption does not automatically settle collection. Evaluate source requests, optional source credentials, provider operations, diagnostics and any publisher-added SDK against the actual current questionnaire. [P04]

Do not blanket-check “all data encrypted in transit” merely because HTTPS is supported. The app intentionally supports explicitly consented HTTP. Apply the current form's precise scope and third-party provisions to the actual data paths. Public claims and form answers must agree.

The final privacy policy needs the actual publisher/app identity, a contact mechanism and accurate retention/deletion practices. Host it at a publicly accessible, non-login, non-geofenced HTTPS web page; a PDF alone is unsuitable. Link it from the listing and the app's privacy path. No Black Marlin account exists in this version; do not invent an account-deletion workflow. [P06], [P07]

## Foreground-service and UIDT evidence to prepare

Draft feature description: “The user starts a direct file download. Eligible network transfer work uses Android's user-initiated jobs. Foreground data-sync execution supports older-version transfers and an explicit Retry save action that verifies and copies retained bytes to the chosen destination.”

Explain the user impact of delay/interruption and record a real demonstration of user initiation, ongoing notification, Pause/Stop, retained bytes and completed publication. The declaration must match the actual manifest types and paths. Do not treat WorkManager as a foreground-service quota exemption. Use UIDT only for the user-requested network transfer and only as long as necessary. [P05], [P09]

[P04]: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en-GB
[P05]: https://support.google.com/googleplay/android-developer/answer/13392821?hl=en
[P06]: https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-en
[P07]: https://support.google.com/googleplay/android-developer/answer/18265346?hl=en-GB
[P09]: https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-IN
