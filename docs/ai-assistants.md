# AI assistants (MCP)

[← Back to docs index](README.md)

BMD comes with an [MCP](https://modelcontextprotocol.io) server, `bmd-mcp`, so an AI assistant that supports MCP (Claude Desktop, Claude Code, Cursor, VS Code and others) can work with the downloads in your running BMD. You ask in your own words:

- "What is BMD downloading right now, and how long is left?"
- "Download https://example.com/big.iso to D:\ISOs tonight, don't start it yet."
- "Pause everything that's downloading."
- "Remove the finished downloads from the list but keep the files."

The assistant starts the server itself; you only tell the assistant where it is, once. The server passes each request to the app through its [automation API](command-line.md#the-automation-api), on this computer only.

## What the assistant can do

| Tool | What it does |
|---|---|
| `list_downloads` | Lists your downloads, newest first: file, address, folder, status, size, how much is done, speed. It can list only one id, only one status (Queued, Downloading, Stopped, Complete, Error), only names and addresses that contain some text, or only one category or queue; reports available disk space too. |
| `add_download` | Adds an http, https, ftp or ftps address and starts it, or only queues it (into the main queue by default, or a designated queue). It can say which folder, file name and category to use, the checksum the finished file must have, in the same form as the command line's `--checksum` (hex MD5, SHA-1, SHA-256, SHA-512 or CRC32, optionally named, e.g. `sha256:…`), and a custom Referer, User-Agent, or Cookie header. A magnet link opens **Add torrent** in BMD, for you to choose the files. |
| `pause_downloads` | Pauses running downloads. |
| `resume_downloads` | Starts or resumes stopped, queued or failed downloads. |
| `remove_downloads` | Removes downloads from the list and keeps their files, or deletes the files too when asked. A running download must be paused first. |
| `start_queue` | Starts a download queue (the main queue by default, or by name). |
| `stop_queue` | Stops a download queue (the main queue by default, or by name). |

The assistant can't change BMD's options, see your browser, or turn the automation API on by itself.

## Set it up

### 1. Turn on the automation API

Listing and acting on downloads needs BMD's automation API, which is off until you turn it on. With BMD running, run this once (it stays on):

```
bmd-cli api on
```

`bmd-cli api off` turns it off again. Without the API, the assistant can still add downloads, the same as BMD's [command line](command-line.md#the-apps-command-line).

### 2. Find the server

`bmd-mcp` is next to the app:

| You installed | The server |
|---|---|
| Windows, for you only (the installer's default) | `%LOCALAPPDATA%\Programs\BMD\bmd-mcp.exe` |
| Windows, for everyone | `C:\Program Files\BMD\bmd-mcp.exe` |
| Windows, a zip or [portable copy](portable.md) | `bmd-mcp.exe` in its folder |
| macOS | `/Applications/BMD.app/Contents/MacOS/bmd-mcp` |
| Linux, the .deb or .rpm | `/usr/bin/bmd-mcp` |
| Linux, the .tar.gz | `bmd-mcp` in its folder (the AppImage doesn't expose it: use the .tar.gz) |

A portable copy's server finds the portable copy's BMD by itself, from its own folder.

### 3. Tell your assistant

**Claude Desktop**: **Settings → Developer → Edit Config** opens `claude_desktop_config.json`. Add the server, then restart Claude Desktop:

```json
{
  "mcpServers": {
    "bmd": {
      "command": "C:\\Users\\you\\AppData\\Local\\Programs\\BMD\\bmd-mcp.exe"
    }
  }
}
```

**Claude Code**:

```
claude mcp add bmd -- "C:\Users\you\AppData\Local\Programs\BMD\bmd-mcp.exe"
```

**Cursor**: the same `mcpServers` entry as Claude Desktop's, in `~/.cursor/mcp.json` (or add it in Cursor's MCP settings).

**VS Code**: in `.vscode/mcp.json`, or **MCP: Add Server** in the command palette:

```json
{
  "servers": {
    "bmd": {
      "type": "stdio",
      "command": "C:\\Users\\you\\AppData\\Local\\Programs\\BMD\\bmd-mcp.exe"
    }
  }
}
```

Use the path from step 2 (in JSON, every `\` is written `\\`). Other assistants take the same thing: a command to start, no arguments.

## Privacy

BMD itself still sends nothing anywhere: the server talks only to the BMD on this computer, over the token-protected local channel. But what the assistant reads, it passes to its own AI service: when it lists your downloads, their file names, addresses and folders go to that service, under that service's privacy terms. Turn the automation API off, or remove the server from the assistant, when you don't want that.

## When it doesn't work

| The assistant says | What to do |
|---|---|
| BMD isn't running | Start BMD. The server can't start it for you. |
| The automation API is off | Run `bmd-cli api on` with BMD running. |
| BMD didn't answer in time | A window in BMD may be waiting for you (a question, **Add torrent**); answer it and ask again. |
| BMD refused the request | BMD's channel file is from an earlier start; restart BMD. |
| The tools don't show up at all | Check the path in the assistant's settings, and restart the assistant. Started by hand, `bmd-mcp --help` says what it is. |
