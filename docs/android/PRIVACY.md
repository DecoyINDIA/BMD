# Black Marlin for Android: privacy

This document describes the native Android application's data handling in this source tree. Black Marlin downloads files from direct HTTP or HTTPS links that you provide. It does not require a Black Marlin account, run an account-sync service, or include advertising, analytics tracking or automatic diagnostic uploads.

Your device still makes requests to the file sources you choose. A document provider, another app you share with, or a website you open can handle data under its own policy. The sections below explain those boundaries and the information the app retains locally.

## Links and requests

You can type a link, share text into Black Marlin, or tap Paste. Clipboard text is read only when you tap Paste. The app does not watch the clipboard, import browser cookies, capture another app's login session, or browse your device for download links.

Review can contact the source to inspect the file before you start downloading. For unencrypted HTTP, Black Marlin asks for explicit consent for that task before any probe or download request. HTTP can expose the requested URL and content to the network. HTTPS certificate checks remain enabled, and redirects from HTTPS to HTTP are refused.

The source server receives the requests needed to inspect and download your file. Those requests expose the source URL, ordinary HTTP request information and your network address to that server. HTTPS protects request contents in transit; it does not hide a request from the server receiving it. If you supply a source credential, its intended source also receives that credential in the request's authorization header.

The default network policy is Unmetered only. Review and download traffic honor the task's admitted network and cost choice. You may choose Any network or explicitly allow one task to use a metered network. A link to a local server is used only because you entered or shared it; the app does not automatically discover local servers.

## Information kept on the device

Black Marlin uses private app storage for its task database, settings and recovery files. The database is protected by Android's app-storage boundary; it is not described as a separately encrypted database. Source credentials have additional encryption, explained below.

| Information | Why it is kept | Retention and control |
| --- | --- | --- |
| Current draft link, filename, destination and download options | Restore the editor through rotation or Android saved-state restoration | Discard the draft to clear its fields. Starting a successfully acknowledged task replaces the editor with a fresh draft. Unsaved credential input is not written into saved instance state. |
| Full task URL, including any query string | Inspect, download and recover the requested file | Retained while a task needs recovery. Cleared when successful publication cleanup or cancellation cleanup completes, and by eligible explicit private-data cleanup. Paused, failed or unresolved tasks may retain the URL until resolved or removed. |
| Task history | Show the name, source origin, state, byte counts, timestamps, selected network and destination, checksum result and recovery decisions | Remains locally until you remove the history entry. The origin can remain after the full URL has been cleared. |
| Partial and completed staging bytes | Resume interrupted network work and retry saving without downloading again | Kept in the app's private no-backup directory. Removed after confirmed publication cleanup, safe cancellation cleanup, or explicit eligible cleanup/discard. |
| Publication journal and destination URI | Reconcile an interrupted save without guessing which provider document belongs to the task | Retained for recovery and task history. An explicit separate-copy decision archives the earlier attempt's identity. |
| Saved-file index | List documents Black Marlin has published, with filename, content URI, type, size, date and checksum state | Remove from list clears the index entry. It does not delete the underlying document. |
| Preferences and remembered source origins | Keep your appearance, network, notification and destination choices, and show remembered credential entries | Stored locally. Remembered credentials can be forgotten from Source credentials. |

Raw signed links can themselves grant access to a file. They are treated as private task data, excluded from generated diagnostics and normal transfer presentation. Clearing a URL field or deleting a database record is logical deletion; this document does not promise forensic erasure from flash storage.

## Source credentials

Source authentication is separate from a Black Marlin account. The app supports manually entered HTTP Basic credentials and Bearer tokens for an exact HTTPS origin. Scope includes the scheme, host and effective port: a different subdomain or port does not inherit the credential, and credentials do not follow a redirect to a different origin. Source authentication is not sent over HTTP.

The credential record is encrypted with AES-GCM using a non-exportable key held in Android Keystore. Encrypted records are saved in private app preferences so an interrupted task can recover after process loss. Decryption is needed in memory while making an authorized request; the application does not claim that a JVM string can be permanently wiped from memory.

There are two retention choices:

- **Task-only use:** the encrypted credential belongs to that transfer and is removed after publication or safe cancellation/private-data cleanup. A paused or unresolved task can retain it for recovery.
- **Remember for this origin:** an explicit opt-in stores an additional encrypted record for future tasks using that exact origin. Forget removes that reusable record. Credentials already attached to active tasks remain until those tasks end or are removed; forgetting a remembered record does not silently cancel their requests.

The credential screen masks secrets and has an explicit reveal control. It uses Android's secure-window flag while visible. Unsaved credential forms stay in the ViewModel rather than saved instance state. Secret-bearing request objects redact their string representations, and generated diagnostics omit credential values. If the stored key or encrypted record cannot be used, the record is discarded and the app asks you to enter the credential again.

## Saved files and document providers

On API 29 and later, the default shared destination is `Downloads/BlackMarlin` through MediaStore. You can also choose a folder using Android's Storage Access Framework. On API 26–28, choose a folder with Android's picker before starting a download. The app persists the read/write access returned by that picker; it does not request broad access to every file on the device.

A destination can belong to a local file provider or a cloud provider. The provider controls its own storage, synchronization, transport, account and retention behavior. Black Marlin's download-network preference cannot control traffic that another provider performs while handling a document. Choose a local destination if you do not want that provider to upload or sync your saved file.

After publication, the destination controls access to the saved document. It can remain after you remove Black Marlin's history, remove it from the Files list, clear app data or uninstall the app. **Delete file** is a separate confirmed action that asks the provider to remove the document. If access is revoked or the provider refuses, the app reports the failure.

An uncertain save may already have produced a provider document. Black Marlin retains the downloaded bytes until the save can be checked or you explicitly discard them. Locate the saved file reads the document you select and compares its full contents locally. Save a separate copy may leave an earlier provider copy behind. Discard downloaded data removes the private recovery bytes; it does not guess at or delete a provider file.

Opening or sharing a saved file uses an Android chooser and a content URI with temporary read access for the receiving app. The selected app then handles the document under its own behavior and policy.

## Notifications, backup and Android permissions

Notifications show transfer status and saved-file or recovery information. The privacy preference hides filenames by default. You can change this preference or disable the notification channels in Android settings. Source credentials and full download URLs are not included in notifications.

Private application data is excluded from Android cloud backup and device transfer in the manifest and backup rules. Staging is also placed in the no-backup directory. These exclusions do not govern separately published files or a document provider's own synchronization and backup.

The manifest declares Internet and network-state access, notification permission, user-initiated jobs, foreground data-sync services, wake locks and boot/package-update recovery. These support requested transfers and local recovery; a boot callback does not override a previous stop decision. Folder access comes from the system picker. The app does not request contacts, location, camera, microphone, advertising ID or all-files access for its download workflow.

## Diagnostics and support

Help and diagnostics creates a report locally after you ask for it. You can read the preview before selecting Share this report. The Android share sheet sends it only through the app or recipient you choose; Black Marlin has no automatic reporting endpoint.

The generated report includes app/source version, Android API and CPU ABI information, notification-permission state, internal task IDs, task states, executor generation, durable byte counts, redacted error codes and publication phases. It omits full URLs, source credentials, filenames and private filesystem paths. Task IDs and byte counts are still information about your local activity, so the preview is provided before sharing.

Open project support and View project source open the project's GitHub pages in an external app. GitHub handles those visits under its own policy. If you post a report in a public issue, review the report and any additional text or screenshots before posting; Black Marlin does not create or submit an issue automatically.

## Implementation references

The behavior above can be checked against the current source:

- [Manifest](../../android/app/src/main/AndroidManifest.xml), [backup rules](../../android/app/src/main/res/xml/backup_rules.xml) and [device-transfer rules](../../android/app/src/main/res/xml/data_extraction_rules.xml).
- [CredentialVault](../../android/app/src/main/java/com/blackmarlin/downloader/data/CredentialVault.kt) for encryption, task credentials and remembered entries.
- [DownloadPolicy](../../android/app/src/main/java/com/blackmarlin/downloader/domain/DownloadPolicy.kt) for parsing, plaintext consent and credential scope.
- [BmdRepository](../../android/app/src/main/java/com/blackmarlin/downloader/data/BmdRepository.kt), [TransferCoordinator](../../android/app/src/main/java/com/blackmarlin/downloader/platform/TransferCoordinator.kt) and [PublicationManager](../../android/app/src/main/java/com/blackmarlin/downloader/storage/PublicationManager.kt) for persistence, cleanup, diagnostics and publication recovery.
- [MainActivity](../../android/app/src/main/java/com/blackmarlin/downloader/MainActivity.kt) and [BmdViewModel](../../android/app/src/main/java/com/blackmarlin/downloader/ui/BmdViewModel.kt) for explicit Paste, picker results, saved drafts and sharing.

See [Build and release](BUILD_AND_RELEASE.md) for the evidence required before a particular binary is distributed. This source description does not claim that the application is already published or that device and store acceptance have passed.
