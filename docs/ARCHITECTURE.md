# Architecture

How BMD's pieces fit together.

## Crates

| Path | Crate | Role |
|---|---|---|
| `crates/engine` | `bmd-engine` | Downloads one file over HTTP(S) or FTP(S): probe, segment planner, connection workers, resume state, per-host limits, speed limiter, checksums, proxy scripts |
| `crates/app` | `bmd-app` | The desktop app (`bmd.exe`, egui): list, categories, queues, scheduler, dialogs, tray, settings |
| `crates/cli` | `bmd-cli` | Headless downloads with progress (`get`, `resume`), videos, torrents and the automation API's commands: a library the app's program runs (`bmd --cli …`), and `bmd-cli`, a small launcher that starts it with the same arguments, terminal and exit code |
| `crates/mcp` | `bmd-mcp` | The MCP server (`bmd-mcp`): AI assistants list, add and control the running app's downloads through the automation API |
| `crates/remote` | `bmd-remote` | The remote control (#141): the HTTPS server a paired phone or computer on the local network uses (its page, pairing codes, device tokens, the self-signed certificate); every request becomes an automation API command the app answers |
| `crates/nmhost` | `bmd-nmhost` | The browser's native messaging host: takes captured downloads from the extension and hands them to the app |
| `crates/ipc` | `bmd-ipc` | The single-instance channel (a token-protected local socket), the data folder, and the capture rules shared by the app and the host |
| `crates/debrid` | `bmd-debrid` | Debrid accounts (#44): the services' official APIs (Real-Debrid, AllDebrid, Premiumize, TorBox, Mega-Debrid) turn a file host's link into a direct one the engine downloads |
| `crates/cnl` | `bmd-cnl` | Click'n'Load 2 (#37): the listener on 127.0.0.1:9666 that reads a link site's posts into packages for Link grabber, with limits on what it takes |
| `crates/extract` | `bmd-extract` | Archive extraction (#37): zip, tar, gz and xz in process; 7z and RAR through an installed 7-Zip, run as its own program; in place through HatchZip when asked |
| `crates/geo` | `bmd-geo` | Where a download's server is (#140): an IP-to-country range table built from DB-IP's Lite data (`tools/geo/build.py`), looked up on this computer only, and the countries' flags |
| `crates/tools` | `bmd-tools` | Video pages through yt-dlp and ffmpeg, image galleries through gallery-dl (run as separate programs); while a network adapter is chosen, their connections go through a proxy of BMD's on 127.0.0.1 that binds them to it (`relay.rs`); each runs in a folder it can write, also its TEMP (`workdir.rs`) |
| `crates/crash` | `bmd-crash` | Crash and freeze reports: the monitor process, out-of-process minidumps, symbolicated stacks, the report's text and the release logs ([crash-reports.md](crash-reports.md)) |
| `crates/torrent` | `bmd-torrent` | BitTorrent and magnet links through librqbit |
| `crates/labserver` | `bmd-labserver` | An HTTP Range server with a request log and fault injection, and an FTP/FTPS server with its own faults, for tests and benchmarks |
| `crates/android-bridge` | `bmd-android-bridge` | The Android app's native library (`libbmd_android_bridge.so`, JNI): runs `bmd-engine` for direct HTTP(S) with strict URL rules, private staging and a socket bound to the network Android assigned ([android/NATIVE_CONTRACT.md](android/NATIVE_CONTRACT.md)) |
| `android/` | — | The native Android app (Kotlin, Jetpack Compose, Room): screens, scheduling, storage publication and encrypted source credentials, over `bmd-android-bridge` ([android/BUILD_AND_RELEASE.md](android/BUILD_AND_RELEASE.md)) |
| `extension/` | — | The Chromium (MV3) and Firefox extension |
| `docs/` | — | The user guide and tutorials, this file, the benchmark data; the website makes the guide its help pages, which Help opens |
| `website/` | — | bmd.example.com's own pages (home, download, features, themes, FAQ); `tools/website/build.py` adds the help from `docs/` and the news from the CHANGELOG, in 22 languages, and GitHub Pages publishes it |

Dependencies point inward: `app`, `cli` and `remote` use `engine` and `ipc`; `mcp` and `nmhost` use
only `ipc` (they talk to the running app, never download themselves); `android-bridge` uses only
`engine`, and nothing uses it. The engine knows nothing of the UI. The app sees the engine only through its events (progress, segment table
snapshots, finished, failed).

## Engine

```
Download
 ├─ probe()              size, resumable?, validators (ETag / Last-Modified), file name
 ├─ Planner (pure)       segment table: when a connection is free, the largest remaining
 │                       part is split in half (planner.rs, tested by random simulation)
 ├─ Worker × N           one HTTP/1.1 connection each, reused across segments; slow ones
 │                       are dropped and their part re-split
 │    └─ writes at its offset into <name>.bmd.part (created sparse)
 ├─ HostLimits           learns how many connections each server accepts
 ├─ Limiter (shared)     per-download and global token buckets
 ├─ State                segment table saved atomically (craft-atomic) for crash-safe resume
 └─ events → channel     Progress, Done, Failed
```

When every segment is done, the part file is checked against the expected size and renamed into
place; the file's date can follow the server's.

A download's first request (`get`) follows redirects and the pages that lead on to a file by
themselves (a waiting page's refresh). Sites whose links aren't the file's address have a module
in `sites/` (Google Drive: `sites/gdrive.rs`; MediaFire: `sites/mediafire.rs`; Rapidgator:
`sites/rapidgator.rs`): it turns the link into the file's address, reads the site's own pages (a
confirmation form, a download button, an error page), names its refusals (`Error::Refused`) and,
where the file's addresses expire, the page that gives a fresh one (`sites::origin`, asked again
when a connection meets an expired address, at most once per expiry and never again at once for
an address it then refused; Rapidgator's page is the browser's tab the download came from,
`Options::page`), with pure functions tested without a network. The lab server plays such a site
on its real host names for the end-to-end tests (`labserver/src/drive.rs`,
`labserver/src/mediafire.rs`, `labserver/src/rapidgator.rs`).

FTP and FTPS (`ftp.rs`) go through the same planner, workers and state: a segment is `REST` +
`RETR` on a control session instead of a ranged `GET`, `SIZE` and `MDTM` stand in for the
headers, and sessions are pooled per download and reused between segments.

## App

One egui window painted to fixed pixel coordinates, plus one immediate viewport per dialog.
`app.rs` holds the state (downloads, queues, settings, the list's selection) and turns menu,
toolbar, key and IPC commands into actions. Each dialog is its own module and returns an
`Outcome` (open, closed, done with a value). Windows-only calls are confined to `win32.rs` and
their Linux and macOS counterparts to `unix.rs` (starting with the session, the browsers' host,
sounds, show in folder, the crash handler); every other file is safe, portable Rust. The interface is set
in IBM Plex Sans (text), IBM Plex Mono (code) and Space Grotesk Bold (titles), built in
(`crates/app/assets/fonts/`, `winui::install_fonts`), the same on every system. The scripts they don't draw
come from the system: Windows' Fonts folder, macOS's system fonts, fontconfig on Linux (`theme.rs`). The interface language's
script fonts load at start; text in another script gets its font the first time it shows: from BMD's
lists of the system's fonts for each script, then, on Windows and macOS, from the system's own fallback
(DirectWrite's, CoreText's, through fontique: `egui_system_font_provider`) for what the lists don't
name; Linux asks fontconfig for any letter. On Windows, Latin,
Greek, Cyrillic and single-codepoint CJK text is drawn by DirectWrite (`win32.rs`, through egui's glyph rasterizer hook;
the built-in fonts are written to files in the data folder for it, CJK uses the system's):
View → Text → Smooth in its natural mode, anti-aliased at the size asked for and placed at quarter
pixels with the font's own advances; Classic (the default) in GDI-classic mode, so each letter's
hinted shape and whole-pixel width come together. CJK uses the interface language's system face;
combining marks, multi-codepoint clusters and missing glyphs stay with egui, as do other scripts.

### Themes

Every colour and size a control is drawn with comes from the active theme (#45). A theme is a
light and a dark palette, metrics, control styles and an icon style (`palette.rs`):

- **Palette roles.** `palette.rs` declares each role with a stable key (`"button.hot"`,
  `"list.row"`, `"caption"`…), a description and the classic look's light and dark values,
  written out (the classic look isn't derived). The shared painters (`winui.rs`, `chrome.rs`,
  `list.rs`, `scrollbar.rs`) read them through `theme::colors()` and `theme::metrics()` and decide
  each control's states from them; no painting code holds a colour of its own.
- **Derived palettes.** `derive.rs` makes a whole palette from a few seeds (background, surface,
  text, accent…), mixing every other role in OKLab and keeping WCAG contrast (4.5:1 for text, 3:1
  for edges); High contrast strengthens every edge on top. `native.rs` seeds the Native theme from
  the system's accent and contrast (Windows' settings, the freedesktop portal on Linux, macOS's
  accent).
- **Control styles.** `palette::Controls` names each control's shape (text boxes, check boxes,
  buttons, progress bars, scroll bars, the focus mark, the title bar); the style themes (Metro,
  Adwaita, Breeze, Luna, Aero, Aqua, Emerald) pick theirs. On Windows BMD draws custom title bars
  for themes that select one (the `caption.*` roles).
- **Theme files.** `themefile.rs` reads TOML or JSON from the data folder's `themes` (one file,
  or `<id>/theme.toml` with icons beside it). A file names only what it changes; the rest comes
  from the theme it `inherits` (a built-in's id or another file, the classic look by default),
  down a chain in which a cycle is refused. Unknown keys are warnings, not errors. The built-in
  style themes are such files too, carried in the program (`crates/app/assets/themes`).
- **Icon sets.** `themeicons.rs` draws a theme's icons (named by what they're for: `tb-add-url`,
  `cat-music`…, SVG or PNG at 16, 24, 32 and 48 px, `-symbolic` ones inked in the text colour)
  where the classic look draws its own; a need the set lacks keeps the classic icon.

Emerald uses its own spacing and presentation in the main toolbar, categories and download list.
Themes share the platform icons before file names (`fileicons.rs`): the Windows shell's system image lists, macOS Finder's
(NSWorkspace), the Linux desktop's icon theme (`xdgicons.rs`, shared-mime-info and the Icon
Theme Specification). Each type is looked up once per pixel size, on a background thread. Tango's
icons (bundled) stand in until the system's icon is ready, and wherever the system has none.

### Drawing

The windows are drawn with OpenGL (eframe's glow) or with the system's own graphics API through
eframe's wgpu, with that one backend only: Direct3D 12 on Windows (the default there), Vulkan on
Linux, Metal on macOS (choices there; OpenGL stays their default until real graphics cards have
tried them, #206). View → Draw with picks one, from the next start. One code path does it on
every system (`crates/app/src/renderer.rs`): when the chosen one can't start, the other one is
tried, so the windows always open. A watched start with the system's API leaves a marker until
its first frame is drawn: a start that ends before it (a crash inside the driver), one whose
first frame doesn't come within 10 seconds (BMD then starts again with OpenGL), or a graphics
card lost twice in 10 minutes is remembered with its reason, which the menu shows.

View → Interface size (`scale.rs`, #262) is egui's zoom factor over the display's own scale:
every window is laid out in points, so it opens that much larger; one larger than the screen
scrolls.

### Data

The download list and settings are saved atomically to the data folder (`downloads.json`):
`%APPDATA%\bmd\` on Windows, `~/.local/share/bmd` on Linux,
`~/Library/Application Support/bmd` on macOS, a `data` folder beside a portable
copy, or `BMD_DATA_DIR` (`bmd_ipc::data_dir`). The browser host reads the same file for the
capture rules, so a change in Options applies to the next captured download.

Sign-in secrets (debrid accounts, Sites Logins' and the proxy's passwords) live in the system's
keychain (`secrets.rs`, #44): Windows Credential Manager (`win32.rs`), the Secret Service on Linux
or the macOS keychain (`unix.rs`). The settings keep a `keychain:<service>/<scope>/<key>`
reference in their place, written only once the keychain has confirmed the secret; without a
keychain (or with a locked one, never unlocked for BMD) the secret stays in the settings as
before.

A download's server location (#140) is the country of the address it connected to, looked up in
`bmd-geo`'s table on this computer (`countries.rs` names it in the interface language); the
release job builds the table again from the month's data (`tools/geo/build.py --release`).

## Android

The Android app is a second front end to the same engine, not a port of the desktop app. Kotlin
owns what Android owns: the screens, Room (tasks, history), scheduling (user-initiated data
transfer jobs on Android 14 and later, WorkManager before), the network Android assigns,
MediaStore and folder (SAF) publication, and source credentials sealed with an Android Keystore
key. Rust (`crates/android-bridge`, JNI) owns the bytes: `bmd-engine` downloads into private
staging through a socket bound to that network, and keeps the checkpoints that make a resume
safe. Its scope is direct HTTP(S) links; torrents, FTP, video pages, browser capture, PAC scripts
and queues are desktop-only (`cfg(target_os = "android")` keeps PAC and QuickJS out of its build).

Engine additions for it are off on the desktop: `strict_http` (exact origins, no downgrade, no
direct-socket fallback) and `network_completion` (a durable proof that a file is complete) are
only set by the bridge. The contract between the two halves is
[android/NATIVE_CONTRACT.md](android/NATIVE_CONTRACT.md); building, testing and releasing it,
[android/BUILD_AND_RELEASE.md](android/BUILD_AND_RELEASE.md). What the two apps do alike and
differently is in the feature matrix of [MASTER_EXECUTION_PLAN.md](MASTER_EXECUTION_PLAN.md).

## Browser integration

```
page ──► extension ──native messaging──► bmd-nmhost ──ipc──► app
```

The extension asks the host which file types to take, checks each download against them, and
cancels the browser's download only after the app has accepted it; otherwise the browser keeps
it, and the extension says why.

A message to the host is at most 1 MB (`bmd_ipc::MAX_MESSAGE`; the host refuses more, and the
browser lets a host answer no more than that either). A page's links past it go in parts, each
measured as the widest browser writes it (`wireSize` in `background.js`) and carrying the page's
referrer and cookies and its place (`bmd_ipc::LinksPart`); the host hands each over as it comes,
and the app keeps them (`linkparts.rs`) until the last is in, then opens Link grabber with the
whole list.

The host serves only BMD's own extension. Its IDs are in one place, `bmd_ipc::extension`
(`crates/ipc/src/extension.rs`): the unpacked copy's (`UNPACKED_ID`, from the manifest's public
key), the Chrome Web Store's (`STORE_ID`, signed with the store's own key: `pack.ps1` leaves the
manifest's key out of the store's copy), the Firefox add-on's (`FIREFOX_ID`) and its
addons.mozilla.org page; the Edge and Opera stores' are placeholders, never served, until the
extension is listed there. The host writes each browser's manifest with those IDs. The app opens
the Firefox add-on listing or the local Chromium setup guide and copies the bundled extension
folder path. The manual installation guide covers builds without the bundled folder.

## Remote control

```
phone ──HTTPS (paired token)──► bmd-remote ──ipc::Sink──► app (answer, remote_add)
```

`--headless` (`headless.rs`, #157) runs the same `App` without eframe: a loop calls its
`tick` (what `logic` runs at every frame) on a bare `egui::Context`, woken by the command
channel, and never its `ui`; commands are added as the remote control adds them (no window).
The `Dockerfile` and `docker/` (Compose, Unraid's template) run it on a server
([server.md](server.md)).

The server runs on the app's Tokio runtime, one listener per local network address. Each request
is checked (`Host`, `Origin`, token), turned into an `bmd_ipc::Command` marked `remote` (a field
no request line can set) and handed to the app through the same channel as the command line's;
the app answers it on its next frame, as it answers the automation API. Its page is built in
(`crates/remote/web`) and its texts come from the app's catalogs (`remote.rs`).

## Translations

Every text the UI shows goes through `crate::i18n` (craft-i18n catalogs, `i18n/<locale>.tsv`).
`tools/i18n/extract.py` rebuilds `i18n/en.tsv` from the source, with a note for translators on
each text (what it is, where it shows, its placeholders). `tools/i18n/translate.py` fills what
the other catalogs lack (rows marked `#, machine`), following each language's style guide
(`i18n/style/`) and the glossary; a row a person reviewed is never replaced, and CI
(`reviewed_guard.py`) fails a change that drops one. The website has its own catalogs
(`i18n/site/`, `tools/website/translate_site.py`).

## Pictures

The help pages' pictures (`docs/images/help/`) come from one list, `tools/shots/shots.toml`:
`tools/shots/make.py` opens each window through BMD's own `/show <window>` command line in a test
instance off the screen, crops and annotates it from the window's accessibility tree, and writes
lossless WebP; `make.py check` (CI) keeps the list, the pages and the files in step. The
published ones are taken on GitHub's Windows runner (`docs-shots.yml`).

## Contributors

`tools/contributors.py` reads who opened pull requests and issues, and who commented, from
GitHub's public API (the maintainer, Claude and bots left out) for every place that thanks them:
the website's home page, `/contributors.html` and each release on the News page (`build.py`, on
every build), each release's notes (`release.yml`, the people active since the release before)
and the README (`python tools/contributors.py readme` at each release's preparation: its round
photos are committed in `art/contributors/`, since GitHub can't round a picture in a README).

## Tests and benchmarks

`cargo test` runs unit tests and end-to-end downloads against `bmd-labserver` with injected
faults (slow, dropped and refused connections, servers without ranges, changing files).
`tools/bench` runs the published speed comparison; its raw data is in `docs/bench/`.
