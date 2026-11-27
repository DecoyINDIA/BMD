# Android native bridge v1

This contract is implemented by `bmd-android-bridge` (`libbmd_android_bridge.so`). Kotlin owns Room, user consent, OS scheduling, URI publication and encrypted credentials; Rust owns byte checkpoints and private staging. There is no application login.

## JNI declarations

Package `com.blackmarlin.downloader.bridge`, Kotlin object `NativeEngine`:

```kotlin
@JvmStatic external fun nativeInitialize(applicationContext: android.content.Context): String
@JvmStatic external fun nativeCommand(requestJson: String): String
@JvmStatic external fun nativeSnapshot(handle: Long): String
```

Initialization calls Android's TLS verifier with the application Context, obtains `getNoBackupFilesDir()`, and creates one process runtime. Call on a background dispatcher before any network executor. Repeated initialization is idempotent. No Activity or callback is retained. All responses use `{ "apiVersion": 1, "ok": true, "data": ... }` or `{ "apiVersion": 1, "ok": false, "error": { "code": "...", "retryable": false } }`. Error text never contains a URL, token, credentials or path. JNI input is capped at 32 KiB; outputs at 64 KiB. Exceptions/panics are contained.

## Command JSON

`command` is a required string discriminator. Unknown fields are rejected. All task commands carry `taskId` (1–128 ASCII letters, digits, `_` or `-`) and a positive `generation` integer. Numbers are signed-64-bit-safe. `handle` is process-local; task identity is durable in Room. At most 64 retained handles and four active tasks. A new generation for an unreleased task is refused.

### probe / start

```json
{
  "command": "start",
  "taskId": "38d4a7d0-7267-48eb-8e02-abb5485742ab",
  "generation": 1,
  "url": "https://example.org/file.zip",
  "expectedOrigin": "https://example.org",
  "credentialOrigin": null,
  "privateDirectory": "/data/user/0/com.blackmarlin.downloader/no_backup/transfers/38d4a7d0-7267-48eb-8e02-abb5485742ab",
  "fileName": "file.zip",
  "resume": false,
  "httpConsent": false,
  "expectedSha256": null,
  "authorization": null,
  "network": { "handle": 123456789, "generation": 1 },
  "maxConnections": 4,
  "speedLimitBytesPerSecond": null
}
```

`probe` accepts the same request (directory/name may be omitted for probe). It returns a handle immediately and performs a bounded metadata request off the JNI thread. The task snapshot contains `fileName`, `totalBytes`, `sourceOrigin`, `webPage` and `phase=probed` on success. No probe payload is published. `start` requires privateDirectory exactly `<noBackupFilesDir>/transfers/<taskId>` (no symlink components) and a single safe file name; it returns `{handle}` immediately. `resume=true` accepts only the exact checkpoint source URL, destination and completed representation; a changed source URL returns `SOURCE_CHANGED_RESTART_REQUIRED`. New downloads never overwrite existing staging artifacts. Credential fields are supplied by Kotlin after decryption and are never written by Rust.

`expectedOrigin` is Kotlin's parsed source origin. Rust independently parses/canonicalizes it and requires equality with its parsed URL origin before admission. `credentialOrigin` is required whenever authorization is supplied, and must independently match that exact HTTPS origin.

A request supports only complete HTTP/HTTPS URLs, with no URL username/password, control characters or literal backslashes, and an 8,192-byte UTF-8 limit. Before URL parsing, the bridge rejects path segments equal to `.` or `..`, including mixed literal and case-insensitive `%2e` forms; these would otherwise be silently normalized. This check does not decode or rewrite the query. HTTP requires `httpConsent=true` before any network operation and never accepts authorization. `authorization`, if present, is a complete `Basic …` or `Bearer …` header (8 KiB maximum), sent only to the source's exact HTTPS origin including port. HTTPS→HTTP redirect is always refused; no Referer is synthesized. No browser cookie capture, FTP, site extractors, external helpers or system proxies are admitted by this bridge.

The network handle must come from the admitted Android `Network`; Kotlin must check current capabilities and policy immediately before admission. One authenticated loopback SOCKS5h gateway per executor resolves every upstream name with `android_getaddrinfofornetwork` and binds every upstream socket before connect with `android_setsocknetwork`. It never falls back to the process/default route. Loopback is transport inside the app, not a substitute for binding external sockets. Relay secrets never leave Rust. Gateway overhead must be measured on devices.

### Local verification / recovery

```json
{"command":"verify","taskId":"...","generation":2,"privateDirectory":"...","expectedSourceUrl":"https://example.org/file.zip"}
{"command":"reconcile","taskId":"...","generation":2,"privateDirectory":"...","expectedSourceUrl":"https://example.org/file.zip"}
```

Both return `{handle}` and do private disk work asynchronously, without network admission. `reconcile` validates the native proof/checkpoint and returns one of `network_complete`, `verified`, `interrupted`, `empty` or `failed`. `verify` requires a durable network completion proof, computes any user/server expected checksum with cooperative cancellation, then atomically moves the part to `verified.payload` and writes a durable verification marker. Successful snapshot `phase=verified` has `stagedPath`, `totalBytes`, `checkpointBytes`. Kotlin publishes that file by MediaStore/SAF; native verification does not mean the public copy was saved. Retrying publication reads the same verified file without redownload. Proof files contain private source identity and are never shown/logged by UI.

### Control

```json
{"command":"stop","handle":1}
{"command":"set_limit","handle":1,"speedLimitBytesPerSecond":1048576}
{"command":"release","handle":1}
{"command":"invalidate_network","network":{"handle":123456789,"generation":1}}
```

`stop` returns immediately after closing admission and signalling cancellation. Kotlin keeps its writer claim and executor alive until snapshot `terminal=true AND writerReleased=true`. This acknowledgment is set only after Rust's downloader, disk/hash worker, DNS work and all gateway relays have joined. `release` removes only a terminal acknowledged handle. Do not abort a task by discarding a handle. `invalidate_network` cancels all matching executors and blocks new connections; Kotlin creates a new admitted generation after a network or cost change. Speed limit null means unlimited.

## Snapshot data

```json
{
  "handle":1,"taskId":"...","generation":1,"phase":"downloading",
  "receivedBytes":1234,"checkpointBytes":0,"totalBytes":10000,
  "resumable":true,"fileName":"file.zip","sourceOrigin":"https://example.org",
  "webPage":false,"networkCompleteProofPath":null,"stagedPath":null,
  "errorCode":null,"retryable":false,"terminal":false,"writerReleased":false
}
```

Phases: `queued`, `probing`, `probed`, `downloading`, `stopping`, `cancelled`, `network_complete`, `verifying`, `verified`, `interrupted`, `empty`, `failed`. Snapshot returns only bounded summary data, no URL/query, header, event ring, raw native error or full segment list. `receivedBytes` is received/written progress, `checkpointBytes` is durable progress only; do not persist the former as resumable bytes. `network_complete` is emitted only after completed extents/EOF and staging bytes are flushed, all upstream handles are released, and the atomic `NetworkCompleteProof` is durable. It is a terminal result of the network executor; verification gets a later generation/handle. Hash failure preserves private bytes for explicit user action. No native phase means publicly saved.

## Integration responsibilities

Call initialization on every process entry path (including worker/service before Activity). Persist task intent and generation before start. Schedule only after the shared startup/user-stop barrier. Poll at 2–4 Hz only while visible/needed, throttle Room writes, retain terminal transitions. Before retry, release the previous acknowledged handle. Do not let a worker timeout or service `onDestroy` release writer ownership early. Never delete private files while a native handle is unacknowledged. Kotlin alone encrypts task credentials durably until completion/cancel; Remember permits exact-origin reuse across tasks. Native release does not delete staged data.

Android TLS uses `rustls-platform-verifier` 0.7.1 and its matching `org.rustls:rustls-platform-verifier:0.2.0` Android Maven artifact from `rustls-platform-verifier-android` 0.2.0. Use upstream's `https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/` repository and derive the support artifact version from Cargo.lock; do not substitute an obsolete verifier JAR or disable certificate checks. Keep JNI names and verifier classes in release shrinking rules.
