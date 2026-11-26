# Native Android design

Black Marlin's Android application uses Kotlin, Jetpack Compose and Material 3 for its interface, with the Rust `bmd-engine` behind a JNI boundary. It accepts direct HTTP and HTTPS file links, keeps transfer state on the device, and publishes completed files through Android storage APIs. It opens at Transfers without an account or sign-in screen.

This document describes the implemented Android surfaces and the references that informed them. It does not establish that a build has passed device acceptance, performance testing or store review. See [Build and release](BUILD_AND_RELEASE.md) for the reproducible build, execution gates and release requirements, [Native contract](NATIVE_CONTRACT.md) for the engine boundary, and [Privacy](PRIVACY.md) for data handling.

## Navigation and screen inventory

The three main destinations are **Transfers**, **Files** and **Settings**. Transfers contains requested work and its history. Files indexes documents that Black Marlin has successfully published. Removing a transfer from history and deleting a saved file are separate actions.

Phones use a single content pane and bottom navigation on the main destinations. At a window width of 840 dp or greater, the interface uses a navigation rail; transfer and file details can appear beside their lists. Android's document picker, notification permission prompt, notification settings, file chooser and share sheet remain system-owned interfaces.

The original route IDs remain useful for reviews and acceptance scenarios. R01 is the empty state of Transfers, and R10 is a selection mode over a list; they do not need independent navigation stacks. The actual routes and dispatch are defined in [BmdViewModel](../../android/app/src/main/java/com/blackmarlin/downloader/ui/BmdViewModel.kt) and [BmdApp](../../android/app/src/main/java/com/blackmarlin/downloader/ui/BmdApp.kt).

| ID | Surface | Implemented behavior |
| --- | --- | --- |
| R01 | First run / empty Transfers | Explains direct-link downloads and offers Add a download and help. A shared link can enter the editor immediately. |
| R02 | Transfers | All, Active and Needs attention filters; stage and byte progress; inline pause/resume where permitted; add, search and selection actions. |
| R03 | Add link | Multiline URL field, explicit Paste, clear and Review. Unsupported schemes receive a local error. Multiple shared links open a choice. |
| R04 | Review download | Parsed source origin, editable filename, known or unknown size, destination, network policy, source credentials and optional expected SHA-256. Replacement-link reviews carry the complete confirmed form into restart. |
| R05 | Destination | Current folder, provider-reported space when available, public Downloads on API 29+, and Android's folder picker. A prepared separate copy has an explicit Retry save action. |
| R06 | Transfer detail | Network, verification and saving stages; their applicable progress and actions; source origin, destination, checksum state and expandable technical details. Recovery choices depend on durable task state. |
| R07 | Files | Search, type filters, newest/oldest/name/size sorting and file rows with metadata and access state. |
| R08 | File detail | Native Open and Share, saved metadata, Remove from list and separately confirmed Delete file. Missing access and unavailable handlers produce recoverable feedback. |
| R09 | Search | Local search across transfer and saved-file metadata. Selecting a result opens its detail. |
| R10 | Multi-select | Select visible items; apply supported transfer pause/resume/history actions or file-list removal/deletion. Batch failures identify affected items. |
| R11 | Settings | Entry points for network, storage, appearance, notifications, source credentials, help and about. |
| R12 | Network and performance | Default network choice, bounded concurrency controls and a speed limit per active transfer. Changes apply to the next admitted run. |
| R13 | Storage and cleanup | Private staged data, retained partials and indexed saved-file totals; default destination; explicit selection and confirmation of eligible cleanup candidates. |
| R14 | Appearance | Follow system, Light and Dark, plus reduced motion for unknown-length transfer progress. |
| R15 | Notifications | Permission/channel state, privacy preference, request permission and links to Android notification settings. |
| R16 | Source credentials | Exact HTTPS origin; None, HTTP Basic or Bearer; masked secret with reveal control; task-only use or explicit Remember; Forget for remembered entries. |
| R17 | Help and diagnostics | Offline explanations, a locally generated redacted report preview, explicit Share report and a project-support link. |
| R18 | About, privacy and licenses | App version and source revision, local-data explanation, project source, GPL text and bundled Rust/Android dependency notices. |

## Download and recovery flows

### Review before admission

An in-app download begins with Add a download, typing or explicit Paste, and Review. Sharing text from another app uses the same editor. Black Marlin reads clipboard text only after Paste; it does not monitor the clipboard or dereference a clipboard content URI to find a link.

Local parsing runs before the source probe. An HTTP URL needs task-specific consent before that probe or the download can send a request. Network cost is also checked before probing: a task restricted to unmetered networks needs a separate one-task allowance to use a metered connection. Choosing Any network is an explicit alternative. The repository and native boundary revalidate these choices; a screen preview is not an authorization token.

Review shows metadata when available and preserves unknown sizes as unknown. The user can edit the filename, choose a destination and supply a trusted 64-character SHA-256 value. The checksum is optional. A successful self-calculated digest alone does not produce the expected-checksum badge.

Start persists a request identified by a stable submission ID. Repeated taps, rotation or an acknowledgement failure reuse that identity. A later independent download has its own identity. Duplicate detection offers the existing task or an explicit additional copy; it does not silently overwrite a saved document.

Pending consent and asynchronous inspection belong to the captured draft. A new share creates a new ordinary draft, and a late probe cannot populate it with the earlier link's metadata. Draft fields survive rotation and Android saved-state restoration. Unsaved credential input stays in ViewModel memory, and a process-restored draft asks for HTTP and metered consent again.

### Transfer, verify, save

The UI renders repository state rather than simulated progress. Downloading reports confirmed received bytes. A known total permits a percentage; an unknown total keeps the byte count and uses an indeterminate indicator. A displayed ETA is an estimate from remaining bytes and the currently reported receive rate, not a measured guarantee of completion time.

Verification and saving are separate stages. Saving reports copied bytes from the publication operation, so a network transfer reaching its total does not imply that the file is already available in Files. The Files index is updated with the durable publication result. See [TransferCoordinator](../../android/app/src/main/java/com/blackmarlin/downloader/platform/TransferCoordinator.kt) and [PublicationManager](../../android/app/src/main/java/com/blackmarlin/downloader/storage/PublicationManager.kt).

Pause retains downloaded data. Resume validates whether that data can still be combined with the source. A changed representation leads to a restart decision. Replacing the URL is also restart-only: the old partial remains until confirmation, and the complete reviewed replacement settings and scoped credential are carried into the restart command.

Android owns execution windows. Waiting for a network, waiting for Android, an interrupted run, missing source authentication and insufficient space are explicit states. Opening the app or refreshing permissions does not silently clear a previous stop. The available buttons reflect the repository's permitted actions for the task's current generation.

### Recover a local save

An unavailable destination or failed publication can leave valid downloaded bytes in private staging. Retry save works from those bytes; it does not create a second network transfer. A generic document provider may be slow or unseekable, so the application uses a visible Retry save action when local work needs the foreground save service.

On API 26–29, publication supports regular file descriptors from local folders. If a provider instead returns a pipe or another streaming descriptor, Black Marlin closes it before reading or writing and holds the save. The exact known document URI and private downloaded bytes remain available for recovery. The message directs the user to Save a separate copy and a local device folder. API 30+ can admit streaming descriptors with nonblocking I/O; this does not guarantee that every provider will complete a save.

If a provider may already have created or finalized a document, the detail screen offers these distinct decisions:

- **Locate the saved file:** open Android's document picker and compare the selected document's complete bytes with the retained download. A matching filename or size is insufficient.
- **Save a separate copy:** warn that the earlier provider document may remain, archive its attempt identity, and open Destination. The user can choose a different folder before explicitly retrying the new save.
- **Stop saving, keep data:** hold the retained bytes and publication information for later recovery.
- **Discard downloaded data:** explicitly end recovery and remove private downloaded bytes. Provider files are left untouched and must be managed separately.

Cancellation is serialized with provider finalization. Before commit begins, cancellation prevents the publication attempt from advancing. Once finalization has begun or its outcome is uncertain, the app retains the evidence needed to reconcile it. A proven saved file is preserved and can be labelled Saved before cancellation. Deleting a published file always uses the separate Delete file action.

Picker results keep the initiating draft or task identity and returned grant flags. MainActivity waits until it has resumed before delivering actions that require visible admission. Canceling the picker leaves the current destination or recovery attempt intact.

## Source authentication

There is no Black Marlin account flow. Source credentials authenticate a request to the server supplying a file. The supported forms are HTTP Basic and a manually supplied Bearer token for one exact HTTPS origin: scheme, host and effective port.

The screen masks secrets, provides an explicit reveal action and protects the credential window from ordinary screenshots. A task-only credential is encrypted for recovery across process loss. Remember for this origin is a separate choice for future tasks. Credentials do not follow cross-origin redirects, and an HTTPS request cannot downgrade to HTTP. See [Privacy](PRIVACY.md) for retention, encryption and Forget semantics.

## Visual system and native behavior

The implemented palette and typography live in [BmdTheme](../../android/app/src/main/java/com/blackmarlin/downloader/ui/BmdTheme.kt), and reusable controls live in [BmdComponents](../../android/app/src/main/java/com/blackmarlin/downloader/ui/BmdComponents.kt).

| Element | Light | Dark / behavior |
| --- | --- | --- |
| Background | `#F7F8FA` | `#081B20` |
| Card surface | `#FFFFFF` | `#10272B` |
| Primary action | `#006B61` | `#79D9C7` |
| Main text | `#102A2B` | `#E0F0EB` |
| Secondary text | `#4A605D` | `#B5CAC5` |
| Type | Native Material typography; 26 sp page headline, 16 sp body and file title, 14 sp secondary text | Uses scalable text; system/light/dark preference also updates system bars. |
| Controls | Material buttons, text fields, chips, switches and dialogs | Primary actions and custom stepper controls use at least 48 dp targets. |
| Progress | Confirmed bytes and text state | Saving uses its own copy count; unknown totals never receive a fabricated percentage. |

Transfer rows keep the filename, status, progress and reversible action together. File rows keep the name and useful secondary metadata together. Forms use persistent labels and inline errors. Recovery panels explain the blocked stage and expose only applicable actions. Destructive dialogs name the object and the effect.

Motion comes from the app's Material components, including press feedback and standard progress behavior. There is no custom reproduction of another product's recorded animation and no claim that a static reference establishes timing or easing. The implemented Reduce motion preference replaces the moving unknown-length transfer indicator with a still track and readable state. The planning document's proposed transition timings are not measurements or guarantees of this implementation.

The UI includes heading semantics, explicit icon-action descriptions, selection labels and consolidated progress state descriptions. It collects application state with lifecycle awareness. These implementation details still require device verification with TalkBack, large fonts, landscape, narrow/wide windows, keyboard or switch navigation, denied permissions and disabled animations. English is the current interface language; resource-backed labels and RTL support do not constitute a completed localization or accessibility audit.

## Mobbin references and adaptation

The selected Mobbin references were inspected during planning on 26 November 2026. **All are iOS references.** The available screenshots and sampled flow stages support observations about layout, wording hierarchy and visible states. They do not establish unobserved gestures, accessibility behavior or animation duration. The Android interface is built from native Compose controls and original Black Marlin styling; another product's branding or screen image is not shipped as the app UI.

| Reference | Inspected scope | Pattern used in Black Marlin |
| --- | --- | --- |
| [M1 · Skillshare Downloads](https://mobbin.com/screens/c5d27450-a0dc-48c5-bb97-f40087d76433?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | Progress, received size and a pause action close to the download row; distinguish work in progress from saved output. |
| [M2 · Speechify link entry](https://mobbin.com/screens/702c8759-ee34-4836-a8ac-2d0aa1f92d07?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | A focused link-entry form with one main continuation action and clear empty/input states. |
| [M3 · Slack Files](https://mobbin.com/screens/4d9c0cde-4783-4eba-929b-8dd7e62be1f2?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | Compact file rows, secondary metadata and visible filter/sort controls. |
| [M4 · Google TV Network](https://mobbin.com/screens/32425797-fb81-4cd2-9851-67780e8935ba?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | An explicit network choice, expressed in Android as Unmetered only or Any network because Wi-Fi can also be metered. |
| [M5 · TikTok Offline videos](https://mobbin.com/screens/213e14e2-e561-4fac-8d09-eea6ac32f243?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | Activity totals, an accessible pause action and storage/network context near downloaded content. |
| [M6 · Notion Offline pages](https://mobbin.com/screens/e9e94681-2120-4697-aadd-814c1f9464c1?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | One iOS screen | Readable per-item state when a meaningful percentage is unavailable. |
| [M7 · Linktree file download](https://mobbin.com/flows/1857d99a-510b-4994-b4cf-47c3292c533a?tab=screens&utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | Sampled iOS stages 1, 3 and 4 of a four-stage flow | A stable action area across preparation, progress and completion. No claim about motion between the sampled stages. |
| [M8 · Google Drive onboarding](https://mobbin.com/flows/465b0cca-5b2e-4313-a7e7-545fbeb9254d?tab=screens&utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) | Final iOS stage 10 only | A useful empty file area with one clear next action; the complete onboarding flow was not adopted. |

The references are design inputs, not endorsements or evidence that Black Marlin has passed a usability test. Current device evidence, screenshots, performance measurements and release status belong to the build under review, as described in [Build and release](BUILD_AND_RELEASE.md).
