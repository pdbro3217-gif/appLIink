import json
import socket
import asyncio
import psutil
from pathlib import Path
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
import uvicorn
from zeroconf import IPVersion, ServiceInfo, Zeroconf
from pyngrok import ngrok

app = FastAPI()
connected_clients: list[WebSocket] = []
global_url = ""

def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # doesn't even have to be reachable
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

def start_ngrok(port):
    global global_url
    try:
        config = load_config()
        token = config.get("ngrok_token")
        if token:
            ngrok.set_auth_token(token)

        # Open a tunnel on the given port
        # Using 'http' tunnel for websocket (it works)
        public_url = ngrok.connect(port, "http").public_url
        global_url = public_url.replace("https://", "wss://") + "/ws"
        print(f"[SERVER] Ngrok tunnel established: {global_url}")
        return True
    except Exception as e:
        print(f"[SERVER] Failed to start Ngrok: {e}")
        return False

def register_service(port):
    desc = {'path': '/ws', 'global_url': global_url}
    info = ServiceInfo(
        "_clipboard-sync._tcp.local.",
        "ClipboardSyncServer._clipboard-sync._tcp.local.",
        addresses=[socket.inet_aton(get_ip())],
        port=port,
        properties=desc,
        server="clipboard-sync.local.",
    )
    zeroconf = Zeroconf(ip_version=IPVersion.All)
    zeroconf.register_service(info)
    return zeroconf, info

def load_config() -> dict:
    config_path = Path(__file__).resolve().parent.parent / "sync_config.json"
    if not config_path.exists():
        print("[SERVER] sync_config.json not found, using defaults")
        return {"server_host": "0.0.0.0", "server_port": 8765}
    with config_path.open("r", encoding="utf-8") as file:
        return json.load(file)


async def broadcast_battery():
    while True:
        try:
            battery = psutil.sensors_battery()
            if battery is not None:
                percent = battery.percent
                power_plugged = battery.power_plugged
                msg = json.dumps({
                    "type": "pc_status",
                    "battery": percent,
                    "plugged": power_plugged
                })
                for client in connected_clients[:]:
                    try:
                        await client.send_text(msg)
                    except:
                        pass
        except Exception as e:
            print(f"[SERVER] Battery broadcast error: {e}")
        await asyncio.sleep(60) # Обновляем раз в минуту

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(broadcast_battery())

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    connected_clients.append(websocket)
    print(f"[SERVER] Client connected. Total: {len(connected_clients)}")

    # Send current config (including global URL) to the newly connected client
    if global_url:
        try:
            config_msg = json.dumps({
                "type": "sync_config",
                "global_url": global_url
            })
            await websocket.send_text(config_msg)
        except Exception as e:
            print(f"[SERVER] Failed to send config: {e}")

    try:
        while True:
            raw_message = await websocket.receive_text()
            print(f"[SERVER] Incoming: {raw_message}")

            # Validate JSON and basic message format.
            try:
                payload = json.loads(raw_message)
                msg_type = payload.get("type")
                if msg_type not in ["clipboard", "clipboard_image", "file_notify", "notification"]:
                    print(f"[SERVER] Skip message with unknown type: {msg_type}")
                    continue
                if "data" not in payload and msg_type != "file_notify":
                    print("[SERVER] Skip message without data")
                    continue
            except json.JSONDecodeError:
                print("[SERVER] Skip invalid JSON")
                continue

            # Broadcast to everyone except the sender.
            disconnected: list[WebSocket] = []
            for client in connected_clients:
                if client is websocket:
                    continue
                try:
                    await client.send_text(raw_message)
                except Exception as send_error:
                    print(f"[SERVER] Send failed: {send_error}")
                    disconnected.append(client)

            # Remove broken sockets.
            for dead_client in disconnected:
                if dead_client in connected_clients:
                    connected_clients.remove(dead_client)
                    print(f"[SERVER] Removed dead client. Total: {len(connected_clients)}")

    except WebSocketDisconnect:
        print("[SERVER] Client disconnected")
    except Exception as loop_error:
        print(f"[SERVER] Unexpected error: {loop_error}")
    finally:
        if websocket in connected_clients:
            connected_clients.remove(websocket)
        print(f"[SERVER] Active clients: {len(connected_clients)}")


if __name__ == "__main__":
    config = load_config()
    host = config.get("server_host", "0.0.0.0")
    port = int(config.get("server_port", 8765))

    if config.get("use_ngrok", True):
        start_ngrok(port)

    zc, info = register_service(port)
    print(f"[SERVER] Service registered via mDNS: {get_ip()}:{port}")

    try:
        print(f"[SERVER] Starting on {host}:{port}")
        uvicorn.run("server:app", host=host, port=port, reload=False)
    finally:
        zc.unregister_service(info)
        zc.close()
        if config.get("use_ngrok", True):
            try:
                ngrok.disconnect(global_url.replace("wss://", "https://").replace("/ws", ""))
            except:
                pass
