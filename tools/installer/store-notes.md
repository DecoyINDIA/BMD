# Microsoft Store submission notes (EXE/MSI app)

BMD is submitted as an **EXE or MSI app**: Partner Center takes the address of the signed setup
(`BMD-<version>-setup.exe`, plus `-setup-arm64.exe`), not an MSIX. Nothing here changes the
downloads on the releases page.

## Setup values (Partner Center → Packages)

| Field | Value |
|---|---|
| Installer type | EXE (Inno Setup) |
| Package URL (x64) | `https://github.com/DecoyINDIA/BMD/releases/download/v<version>/BMD-<version>-setup.exe` |
| Package URL (ARM64) | `https://github.com/DecoyINDIA/BMD/releases/download/v<version>/BMD-<version>-setup-arm64.exe` |
| Silent switches | `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /STORE=1 /TASKS="browser"` |
| Install scope | Per user (the setup's default when silent) |
| Languages | Every language of `i18n/languages.tsv` |

Always use the **versioned** release address, never a "latest" link, and never replace a file
after submitting it: a new version is a new submission.

## Why these switches

- `/STORE=1`: the setup leaves `store.marker` in the program's folder. BMD then treats the copy as
  the Store's (`bmd_tools::update::Install::Store`): **Update now** and **Download** are hidden in
  Help → Check for updates, with "update it there", so the Store and BMD never both update it. A
  setup run without `/STORE=1` (Update now from a normal copy, or a download from the releases
  page) removes the marker, and the copy is BMD's to update again.
- `/TASKS="browser"`: a silent setup applies every task that is ticked by default. Without this,
  the Store install would also start BMD with Windows (`startup`) and take over `.torrent` files and
  `magnet:` links (`torrents`) without the user choosing. With it, only the browser integration
  (the native messaging host, per user) is set up. The user turns the others on in Options.

## Before submitting

1. Sign the setups (release.yml signs them when the `PFX` secret is set; the certificate's
   subject should match the publisher name in Partner Center).
2. On a clean Windows 11 machine, run each setup with the switches above and check: no window,
   exit code 0, BMD in Apps → Installed apps, `store.marker` in the program's folder, **Help →
   Check for updates** with no Update now button, silent uninstall (`unins000.exe /VERYSILENT`)
   removes the program, the host's registry keys and `store.marker`.
3. Publisher and copyright: the setup and the program say `DecoyINDIA` in both; make sure
   that is your legal owner (or the name on your Partner Center account).
4. Privacy policy URL: the site's privacy page (`website/content/privacy.md`, published at
   `/privacy`).
5. Certification notes: say that BMD downloads yt-dlp and ffmpeg when it first needs them, checks
   them (yt-dlp against its developers' signing key, ffmpeg against published checksums) before
   running them, and installs no service or driver.
6. Listing text: do not use "IDM" or "Internet Download Manager" in the title, description or
   keywords.
