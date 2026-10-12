import asyncio
import base64
import json
import socket
import sys
import threading
from datetime import datetime
from pathlib import Path

import pyperclip
import websockets

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from linkapp_ftp import get_lan_ip, resolve_ftp_root, run_ftp_server_blocking

CONFIG_PATH = ROOT / "sync_config.json"


def load_config() -> dict:
    defaults = {
        "pc_server_url": "ws://127.0.0.1:8765/ws",
        "ftp_port": 2121,
        "ftp_user": "user",
        "ftp_password": "password",
        "pc_ftp_root": "",
    }
    if CONFIG_PATH.is_file():
        try:
            with CONFIG_PATH.open(encoding="utf-8") as fh:
                defaults.update(json.load(fh))
        except (json.JSONDecodeError, OSError) as err:
            print(f"[Config] {err}, using defaults")
    return defaults


CONFIG = load_config()
FTP_ROOT = resolve_ftp_root(CONFIG.get("pc_ftp_root"), ROOT)
SERVER_URL = CONFIG.get("pc_server_url", "ws://127.0.0.1:8765/ws")
IMAGES_DIR = FTP_ROOT / "images"
IMAGES_DIR.mkdir(parents=True, exist_ok=True)


def run_ftp_server() -> None:
    port = int(CONFIG.get("ftp_port", 2121))
    print(f"[FTP] {get_lan_ip()}:{port} -> {FTP_ROOT}")
    run_ftp_server_blocking(
        FTP_ROOT,
        port=port,
        masquerade=get_lan_ip(),
        anonymous=True,
    )


async def listen_server(ws, state: dict) -> None:
    while True:
        try:
            message = await ws.recv()
            payload = json.loads(message)
            msg_type = payload.get("type")

            if msg_type == "clipboard":
                incoming_text = payload.get("data", "")
                state["skip_once_value"] = incoming_text
                pyperclip.copy(incoming_text)
                print(f"[PC] Clipboard from server: {incoming_text!r}")
            elif msg_type == "clipboard_image":
                base64_data = payload.get("data", "")
                mime = payload.get("mime", "image/jpeg")
                extension = "png" if "png" in mime else "jpg"
                file_name = f"clipboard_{datetime.now().strftime('%Y%m%d_%H%M%S')}.{extension}"
                target = IMAGES_DIR / file_name
                target.write_bytes(base64.b64decode(base64_data))
                path_text = str(target)
                state["skip_once_value"] = path_text
                pyperclip.copy(path_text)
                print(f"[PC] Image saved: {target}")
            elif msg_type == "file_notify":
                filename = payload.get("filename")
                print(f"[PC] File from client: {filename}")
            elif msg_type == "notification":
                print(f"\n🔔 [NOTIFICATION] {payload.get('data', '')}")
        except websockets.ConnectionClosed:
            print("[PC] Server connection closed (listener)")
            break
        except Exception as error:
            print(f"[PC] Listener error: {error}")


async def watch_clipboard(ws, state: dict) -> None:
    last_text = pyperclip.paste()
    print(f"[PC] Initial clipboard: {last_text!r}")

    while True:
        try:
            await asyncio.sleep(1)
            current_text = pyperclip.paste()
            if current_text == last_text:
                continue
            last_text = current_text

            try:
                path = Path(current_text).resolve()
                if path.is_file() and str(path).startswith(str(FTP_ROOT)):
                    rel_path = path.relative_to(FTP_ROOT)
                    payload = {
                        "type": "file_notify",
                        "filename": str(rel_path),
                    }
                    await ws.send(json.dumps(payload))
                    print(f"[PC] Shared file: {rel_path}")
            except Exception:
                pass

            if current_text == state.get("skip_once_value"):
                state["skip_once_value"] = None
                print("[PC] Skip echo of remote clipboard")
                continue

            await ws.send(json.dumps({"type": "clipboard", "data": current_text}))
            print(f"[PC] Sent clipboard: {current_text!r}")
        except websockets.ConnectionClosed:
            print("[PC] Server connection closed (watcher)")
            break
        except Exception as error:
            print(f"[PC] Watcher error: {error}")


async def main() -> None:
    threading.Thread(target=run_ftp_server, daemon=True).start()
    state = {"skip_once_value": None}
    current_url = SERVER_URL

    while True:
        try:
            print(f"[PC] Connecting to {current_url}")
            async with websockets.connect(current_url) as ws:
                print("[PC] Connected")
                await asyncio.gather(listen_server(ws, state), watch_clipboard(ws, state))
        except Exception as error:
            print(f"[PC] Connection error: {error}")
            print("[PC] Retry in 3 seconds...")
            current_url = SERVER_URL
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
