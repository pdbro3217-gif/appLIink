# Clipboard Sync MVP (PC + Android)

Simple prototype of clipboard synchronization over WebSocket.
Now with auto mode: desktop app starts everything automatically.

## What is included

- `server/server.py` - FastAPI WebSocket relay server
- `pc-client/pc_client.py` - Python desktop client (`pyperclip`)
- `android-client/` - Android Kotlin app with one **Connect** button

## Message format

All clients use JSON:

```json
{
  "type": "clipboard",
  "data": "text"
}
```

## One app control center (recommended)

Run:

```bash
run_control_center.bat
```

For tray minimize support, install these packages in the Python environment used for `desktop_app.py`:

```bash
pip install pystray pillow
```

In one window you can:
- auto setup network URL
- auto start server + PC client in background
- show pairing QR for phone
- keep logs visible

## Manual mode (optional)

### 1) Run server (manual Python)

```bash
cd server
pip install -r requirements.txt
python server.py
```

Use exactly `python server.py` (not `server.pс`).

After start you should see in console:

```text
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8765 (Press CTRL+C to quit)
```

Important:
- `http://0.0.0.0:8765` is server bind address shown by Uvicorn.
- WebSocket endpoint for clients is: `ws://<SERVER_IP>:8765/ws`
- For same PC testing use: `ws://127.0.0.1:8765/ws`

### 2) Run PC client (manual Python)

Edit server address in `sync_config.json`:

```json
"pc_server_url": "ws://127.0.0.1:8765/ws"
```

Then run:

```bash
cd pc-client
pip install -r requirements.txt
python pc_client.py
```

## 3) Run Android client (Kotlin)

1. Open `android-client` in Android Studio.
2. Wait for Gradle sync.
3. Set server IP in `ClipboardSyncService.kt` (copy from `sync_config.json` -> `android_server_url`):

```kotlin
private val serverUrl = "ws://192.168.1.100:8765/ws"
```

4. Run app on device (same Wi-Fi as server).
5. Tap **Start Sync**.

## Where to set server IP

- Main config: `sync_config.json`
  - `server_host`, `server_port`
  - `pc_server_url`
  - `android_server_url`
- Android code: `android-client/app/src/main/java/com/example/clipboardsync/ClipboardSyncService.kt` -> `serverUrl`

## Build APK (Android Studio)

1. Open `android-client` in Android Studio.
2. Menu: `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
3. After build click `locate` and install APK on Samsung.

## Background mode on Samsung

- App uses `Foreground Service` with persistent notification, so sync keeps running in background.
- Open app and tap `Start Sync` once.
- In Samsung settings disable battery optimization for this app:
  - `Settings -> Apps -> ClipboardSync -> Battery -> Unrestricted`
  - Also allow autostart/background activity if your firmware has this option.

## Notes

- Server supports multiple clients.
- On message from one client, server forwards to all other clients.
- Basic disconnect handling is included.
- Logging:
  - Python: `print(...)`
  - Android: `Log.d / Log.e`
