# MCServerM

A Kotlin/Ktor app to manage local Minecraft servers from a web UI and HTTP API.

## Features

- Discover servers in the `servers/` folder automatically
- Create and delete server instances (Vanilla, Paper, Forge, Fabric, NeoForge)
- Start / stop servers
- Send console commands
- Accept EULA
- Read and update `server.properties`
- Browse, read, upload, and download files inside each server folder
- Stream server console output in real-time over WebSocket

---

> [!WARNING]
> **MCServerM has no authentication or authorisation.**
> Anyone who can reach the web interface or API has full control over your servers — including starting/stopping, editing files, and running console commands.
> **Do not expose this app to the public internet or any untrusted network.**
> Run it on `localhost` or behind a firewall/VPN only.

---

## Requirements

- Java 17+
- Linux / macOS (Windows works with `gradlew.bat`)

---

## Build & Run

```bash
./gradlew clean build
./gradlew run
```

Then open **http://localhost:8080** in your browser.

---

## Quick Start

### 1. List existing servers

```bash
curl http://localhost:8080/servers
```

### 2. Create a new server

```bash
curl -X POST http://localhost:8080/server/create \
  -H "Content-Type: application/json" \
  -d '{"name":"myserver","type":"VANILLA","running":false,"port":25565}'
```

Supported types: `VANILLA`, `PAPER`, `FORGE`, `FABRIC`, `NEOFORGE`

> The server jar is downloaded automatically on creation.

### 3. Accept the EULA

```bash
curl -X POST http://localhost:8080/servers/myserver/eula \
  -H "Content-Type: application/json" \
  -d '{"accepted":true}'
```

### 4. Start the server

```bash
curl -X POST http://localhost:8080/servers/myserver/start
```

### 5. Send a command

```bash
curl -X POST http://localhost:8080/servers/myserver/sendCommand \
  -H "Content-Type: application/json" \
  -d '{"command":"say Hello!"}'
```

### 6. Stop the server

```bash
curl -X POST http://localhost:8080/server/myserver/stop
```

### 7. Delete a server

```bash
curl -X POST http://localhost:8080/server/delete/myserver
```

---

## Server Properties (`server.properties`)

Get all properties:

```bash
curl http://localhost:8080/servers/myserver/config
```

Update a property:

```bash
curl -X POST http://localhost:8080/servers/myserver/config \
  -H "Content-Type: application/json" \
  -d '{"key":"motd","value":"My Minecraft Server"}'
```

---

## File Manager

List files/folders (leave `path` empty for the root):

```bash
curl "http://localhost:8080/servers/myserver/files?path="
curl "http://localhost:8080/servers/myserver/files?path=world"
```

Read a file:

```bash
curl "http://localhost:8080/servers/myserver/file?path=server.properties"
```

Upload a file into a folder:

```bash
curl -X POST "http://localhost:8080/servers/myserver/upload?path=mods" \
  -F "file=@./MyMod.jar"
```

Download a file:

```bash
curl -L "http://localhost:8080/servers/myserver/download?path=server.properties" \
  -o server.properties
```

---

## Live Console (WebSocket)

Connect while the server is running:

```bash
websocat ws://localhost:8080/servers/myserver
```

> The WebSocket closes immediately if the server is not running.

---

## Status Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/servers` | List all servers |
| GET | `/isRunning/{name}` | `true` / `false` |
| GET | `/server/{name}/info` | Server details (name, type, port, running) |

---

## Web Interface

Open **http://localhost:8080** in your browser after starting the app.

The UI is split into two main areas:

### Left panel — Servers

- **Create server form** — enter a name, pick a type (Vanilla, Paper, Forge, Fabric, NeoForge) and a port, then click **Create Server**.
- **Server list** — each discovered server shows its name, type, port, and running status with **Start** / **Stop** and **Delete** buttons.
- **Refresh button** (top-right of the header) — re-fetches the server list at any time.

### Right panel — Console + Config / File Manager

Switch between the two tabs at the top of the right panel.

#### Console + Config tab

- **Status bar** — shows whether the selected server is running or stopped.
- **Log output** — live console output streamed over WebSocket while the server is running. Auto-scrolls to the bottom.
- **Command input** — type any server command and press **Send** (only active while the server is running).
- **EULA checkbox** — tick and click **Save EULA** to accept the Minecraft EULA for the selected server before first start.
- **Vanilla Server Properties** — lists all current `server.properties` key/value pairs. Click any row to load it into the edit fields, change the value, and click **Save Property**. Use **Reload Properties** to refresh the list from disk.

#### File Manager tab

- **Breadcrumb path** — shows your current location inside the server folder.
- **File / folder list** — click a folder to navigate into it, click a file to open it in the editor.
- **Toolbar buttons:**
  - **Up** — go up one directory level.
  - **Refresh** — reload the current directory listing.
  - **Upload** — pick a local file to upload into the current directory.
  - **Download** — download the currently selected file to your computer.
  - **Save** — save edits made in the editor back to the server file (button is highlighted when unsaved changes exist).
- **Monaco editor** — full syntax-highlighted editor (supports `.json`, `.yml`, `.toml`, `.properties`, `.java`, `.kt`, `.sh`, `.bat`, and many more). Read-only until a file is selected; shows an unsaved-changes indicator when dirty.

---

## Common Issues

| Problem | Fix |
|---------|-----|
| `Server not found` | Check the exact name with `GET /servers` |
| Server won't start | Make sure EULA is accepted and Java version is compatible |
| EULA error on first start | Call the `/eula` endpoint before starting |
| File path error | Paths must stay inside the server's own folder |
| NeoForge / Forge first run | Installer runs automatically; wait for it to finish before starting |

