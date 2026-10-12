import os
import sys
import socket
import threading
import asyncio
import json
import math
import shutil
import logging
import ctypes
import webbrowser
import re
from datetime import datetime
import tkinter as tk
from tkinter import filedialog, messagebox
from pathlib import Path
import subprocess
import time


FROZEN = getattr(sys, "frozen", False)


def _fix_stdio_for_gui() -> None:
    import io
    if sys.stdout is None: sys.stdout = io.StringIO()
    if sys.stderr is None: sys.stderr = io.StringIO()


def _relaunch_as_pythonw_if_needed() -> None:
    if FROZEN or __name__ != "__main__" or sys.platform != "win32": return
    if os.environ.get("LINKAPP_GUI") == "1": return
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    if not pythonw.is_file(): return
    env = os.environ.copy(); env["LINKAPP_GUI"] = "1"
    subprocess.Popen([str(pythonw), str(Path(__file__).resolve()), *sys.argv[1:]], env=env, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), close_fds=True)
    raise SystemExit(0)

_relaunch_as_pythonw_if_needed()
_fix_stdio_for_gui()

LOG_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "LinkApp" / "logs"

def log_server(message: str) -> None:
    try:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {message}\n"
        (LOG_DIR / "server.log").open("a", encoding="utf-8").write(line)
    except: pass

def silence_background_loggers() -> None:
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "pyftpdlib", "linkapp.ftp", "zeroconf"):
        logging.getLogger(name).setLevel(logging.CRITICAL)

UVICORN_LOG_CONFIG = {
    "version": 1, "disable_existing_loggers": False,
    "handlers": {"null": {"class": "logging.NullHandler"}},
    "loggers": {"uvicorn": {"handlers": ["null"], "level": "INFO", "propagate": False}}
}

REQUIRED_PACKAGES = ["pyperclip", "uvicorn", "fastapi", "python-multipart", "pyftpdlib", "win10toast", "qrcode", "Pillow", "pystray", "pywin32", "zeroconf", "psutil"]

def ensure_libs():
    if FROZEN: return False
    _import_name = {"Pillow": "PIL", "python-multipart": "multipart", "pywin32": "win32api"}
    missing = []
    for package in REQUIRED_PACKAGES:
        lib_name = _import_name.get(package, package)
        try: __import__(lib_name)
        except ImportError: missing.append(package)
    if missing:
        try: subprocess.check_call([sys.executable, "-m", "pip", "install"] + missing); return True
        except: pass
    return False

if not FROZEN and ensure_libs(): os.execv(sys.executable, [sys.executable] + sys.argv)

import pyperclip
import uvicorn
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
import base64
import io
from urllib.parse import unquote
from linkapp_ftp import run_ftp_server_blocking, get_lan_ip, get_default_ftp_root
from win10toast import ToastNotifier
from PIL import ImageTk, Image, ImageDraw
import qrcode
import pystray
from pystray import MenuItem as item
from zeroconf import ServiceInfo, Zeroconf
import psutil

APP_NAME = "LinkApp"
TOAST_APP_ID = "LinkApp.LinkApp"
CLR_BG = "#F2F2F7"; CLR_CARD = "#FFFFFF"; CLR_ACCENT = "#007AFF"; CLR_ACCENT_SOFT = "#E8F2FF"; CLR_TEXT_MAIN = "#1D1D1F"; CLR_TEXT_SEC = "#86868B"; CLR_BORDER = "#E5E5EA"


def _set_shortcut_app_user_model_id(lnk_path: str, app_id: str) -> None:
    import pythoncom
    from win32com.propsys import propsys
    if not hasattr(propsys, "PROPKEY"):
        return
    pk = propsys.PROPKEY(pythoncom.IID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"), 5)
    store = propsys.SHGetPropertyStoreFromParsingName(
        lnk_path, None, propsys.GPS_READWRITE, propsys.IID_IPropertyStore
    )
    store.SetValue(pk, app_id)
    store.Commit()


def _xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def notify_pc_windows(body: str, tag: str = "general") -> bool:
    """Современный toast Windows 10/11 — одна строка, с тегом для замены старых."""
    if sys.platform != "win32" or not body.strip():
        return False
    safe = _xml_escape(body.strip())
    # Если тег один и тот же (например "music"), новое уведомление заменит старое
    tag_id = f"LinkApp_{tag}"
    ps = f"""
$ErrorActionPreference = 'Stop'
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
$xml = @"
<toast duration="short">
  <visual>
    <binding template="ToastText01">
      <text id="1">{safe}</text>
    </binding>
  </visual>
</toast>
"@
$doc = New-Object Windows.Data.Xml.Dom.XmlDocument
$doc.LoadXml($xml)
$toast = [Windows.UI.Notifications.ToastNotification]::new($doc)
$toast.Tag = "{tag_id}"
$toast.Group = "LinkApp"
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("{TOAST_APP_ID}").Show($toast)
"""
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            timeout=10,
            check=True,
        )
        return True
    except Exception as e:
        log_server(f"win toast: {e}")
        return False


class LinkAppToastNotifier(ToastNotifier):
    """Balloon fallback; не отбрасывает уведомления, пока показывается предыдущее."""

    def notification_active(self) -> bool:
        return False

    def show_body(self, body: str, duration: int = 3, threaded: bool = True) -> bool:
        body = (body or "").strip()
        if not body:
            return False
        return self.show_toast("", body, icon_path=None, duration=duration, threaded=threaded)


def create_toast_notifier() -> ToastNotifier:
    return LinkAppToastNotifier()


def setup_windows_toast_identity() -> None:
    """Чтобы в центре уведомлений было «LinkApp», а не Python."""
    if sys.platform != "win32":
        return
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(TOAST_APP_ID)
    except Exception as e:
        log_server(f"AppUserModelID: {e}")
    try:
        import win32com.client
        start_menu = Path(os.environ["APPDATA"]) / "Microsoft" / "Windows" / "Start Menu" / "Programs"
        start_menu.mkdir(parents=True, exist_ok=True)
        lnk_path = start_menu / "LinkApp.lnk"
        script = Path(__file__).resolve()
        if FROZEN:
            target, args, workdir = sys.executable, "", str(Path(sys.executable).parent)
        else:
            pythonw = Path(sys.executable).with_name("pythonw.exe")
            target = str(pythonw if pythonw.is_file() else sys.executable)
            args = f'"{script}"'
            workdir = str(script.parent)
        shell = win32com.client.Dispatch("WScript.Shell")
        sc = shell.CreateShortCut(str(lnk_path))
        sc.Targetpath = target
        sc.Arguments = args
        sc.WorkingDirectory = workdir
        sc.Description = APP_NAME
        sc.save()
        _set_shortcut_app_user_model_id(str(lnk_path), TOAST_APP_ID)
    except Exception as e:
        log_server(f"toast shortcut: {e}")


setup_windows_toast_identity()


def send_image_to_clipboard(base64_str):
    try:
        import win32clipboard
        image_data = base64.b64decode(base64_str); image = Image.open(io.BytesIO(image_data))
        output = io.BytesIO(); image.convert("RGB").save(output, "BMP"); data = output.getvalue()[14:]
        win32clipboard.OpenClipboard(); win32clipboard.EmptyClipboard(); win32clipboard.SetClipboardData(win32clipboard.CF_DIB, data); win32clipboard.CloseClipboard()
        return True
    except: return False

class LinkAppGUI:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_NAME); self.root.geometry("400x750"); self.root.configure(bg=CLR_BG); self.root.resizable(False, False)
        self.toaster = create_toast_notifier(); self.clients = set(); self.lock = threading.Lock(); self.is_running = True
        self.qr_visible = False; self.last_rcvd = ""; self.last_rcvd_img_hash = None; self.ws_loop = None
        self._ui_connected = None; self._qr_btn_packed = True; self._disconnect_ticks = 0
        self._last_toast_key = ""; self._last_toast_at = 0.0
        self.ftp_root = get_default_ftp_root(); self.ftp_root.mkdir(parents=True, exist_ok=True)
        self.zeroconf = None
        self._make_assets(); self.setup_ui(); self.start_services(); self.animate_loop(); self.setup_tray(); self.refresh_ip()

    def _draw_rounded_rect(self, canvas, x1, y1, x2, y2, r, color, outline="", width=0):
        points = [x1+r, y1, x2-r, y1, x2, y1, x2, y1+r, x2, y2-r, x2, y2, x2-r, y2, x1+r, y2, x1, y2, x1, y2-r, x1, y1+r, x1, y1]
        return canvas.create_polygon(points, fill=color, smooth=True, outline=outline, width=width)

    def _make_assets(self):
        size = (180, 180)
        img_wait = Image.new("RGBA", size, (0, 0, 0, 0)); draw = ImageDraw.Draw(img_wait)
        draw.ellipse((45, 45, 135, 135), fill="#F8F8F9"); draw.line((86, 86, 94, 94), fill="#D1D1D6", width=2); draw.line((94, 86, 86, 94), fill="#D1D1D6", width=2)
        self.icon_wait = ImageTk.PhotoImage(img_wait)
        img_ok = Image.new("RGBA", size, (0, 0, 0, 0)); draw = ImageDraw.Draw(img_ok)
        draw.ellipse((45, 45, 135, 135), fill="#F2F9F4"); draw.line((80, 92, 88, 100, 100, 88), fill="#A8D5BA", width=3)
        self.icon_ok = ImageTk.PhotoImage(img_ok)

    def setup_ui(self):
        header = tk.Frame(self.root, bg=CLR_BG); header.pack(fill=tk.X, pady=(40, 10))
        tk.Label(header, text=APP_NAME, font=("Segoe UI Variable Display", 38, "bold"), bg=CLR_BG, fg=CLR_TEXT_MAIN).pack()
        self.status_label = tk.Label(header, text="Ожидание устройства...", font=("Segoe UI Variable Small", 11), bg=CLR_BG, fg=CLR_TEXT_SEC); self.status_label.pack(pady=5)
        self.card_canvas = tk.Canvas(self.root, width=320, height=320, bg=CLR_BG, highlightthickness=0); self.card_canvas.pack(pady=10)
        self._draw_rounded_rect(self.card_canvas, 5, 5, 315, 315, 50, CLR_CARD, outline=CLR_BORDER, width=1.5)
        self.status_img_id = self.card_canvas.create_image(160, 160, image=self.icon_wait)
        self.qr_container = tk.Label(self.root, bg=CLR_CARD); self.qr_id = self.card_canvas.create_window(160, 160, window=self.qr_container, state="hidden")
        self.controls = tk.Frame(self.root, bg=CLR_BG); self.controls.pack(pady=(10, 0), padx=50)
        self.btn_qr = tk.Canvas(self.controls, width=300, height=56, bg=CLR_BG, highlightthickness=0); self.btn_qr.pack(pady=5); self._draw_pill_btn(self.btn_qr, "Показать QR-код", self.toggle_qr, primary=True)
        self.btn_send = tk.Canvas(self.controls, width=300, height=56, bg=CLR_BG, highlightthickness=0); self.btn_send.pack(pady=5); self._draw_pill_btn(self.btn_send, "Отправить файл на телефон", self.send_file_to_phone, primary=True)
        self.btn_files = tk.Canvas(self.controls, width=300, height=56, bg=CLR_BG, highlightthickness=0); self.btn_files.pack(pady=5); self._draw_pill_btn(self.btn_files, "Открыть папку", self.open_folder, primary=False)
        self.footer = tk.Label(self.root, text="IP: ...", font=("Segoe UI Variable", 9), bg=CLR_BG, fg="#D1D1D6"); self.footer.pack(side=tk.BOTTOM, pady=8)

    def _draw_pill_btn(self, canv, text, cmd, primary=True):
        w, h = 300, 56; bg_color = CLR_ACCENT if primary else CLR_BG; text_color = "white" if primary else CLR_ACCENT
        rect = self._draw_rounded_rect(canv, 2, 2, w-2, h-8, 24, bg_color, outline="" if primary else CLR_ACCENT, width=1.5)
        txt = canv.create_text(w/2, 24, text=text, fill=text_color, font=("Segoe UI Variable Small", 11, "bold"))
        def on_click(e): cmd()
        canv.tag_bind(rect, "<Button-1>", on_click); canv.tag_bind(txt, "<Button-1>", on_click)

    def show_qr(self):
        self.qr_visible = True
        self.card_canvas.itemconfig(self.status_img_id, state="hidden")
        self.card_canvas.itemconfig(self.qr_id, state="normal")

    def hide_qr(self):
        if not self.qr_visible:
            return
        self.qr_visible = False
        self.card_canvas.itemconfig(self.qr_id, state="hidden")
        self.card_canvas.itemconfig(self.status_img_id, state="normal")

    def toggle_qr(self):
        if self.qr_visible:
            self.hide_qr()
        else:
            with self.lock:
                connected = len(self.clients) > 0
            if connected:
                return
            self.show_qr()

    def _clean_notification_text(self, text: str) -> str:
        t = text.strip()
        for prefix in (f"[{APP_NAME}]", f"[{APP_NAME}] ", "[LinkApp]", "[LinkApp] "):
            if t.startswith(prefix):
                t = t[len(prefix):].strip()
        return t

    def _should_show_toast(self, text: str, cooldown_sec: float = 2.0) -> bool:
        key = text.strip().lower()
        now = time.time()
        if key == self._last_toast_key and (now - self._last_toast_at) < cooldown_sec:
            return False
        self._last_toast_key = key
        self._last_toast_at = now
        return True

    def show_pc_notification(self, text: str, tag: str = "general") -> None:
        text = self._clean_notification_text(text)
        if not text or not self._should_show_toast(text):
            return
        try:
            if not notify_pc_windows(text, tag=tag):
                toaster = self.toaster
                duration = 3 if tag == "music" else 5
                if isinstance(toaster, LinkAppToastNotifier):
                    ok = toaster.show_body(text, duration=duration)
                else:
                    ok = toaster.show_toast("", text, icon_path=None, duration=duration, threaded=True)
                if not ok:
                    log_server(f"toast not shown: {text[:120]}")
        except Exception as e:
            log_server(f"toast: {e}")

    def show_pc_music_notification(self, app: str, title: str, artist: str) -> None:
        line = f"{title} — {artist}" if title and artist else (title or artist)
        if not line:
            return
        app = self._clean_notification_text(app)
        if app.lower() == APP_NAME.lower():
            app = ""
        prefix = f"🎵 {app}: " if app else "🎵 "
        self.show_pc_notification(f"{prefix}{line}".strip(), tag="music")


    def _apply_connection_layout(self, connected: bool) -> None:
        if connected == self._ui_connected:
            return
        self._ui_connected = connected
        if connected:
            self.hide_qr()
            if self._qr_btn_packed:
                self.btn_qr.pack_forget()
                self._qr_btn_packed = False
        else:
            if not self._qr_btn_packed:
                self.btn_qr.pack(pady=5, before=self.btn_send)
                self._qr_btn_packed = True

    def update_ui_status(self):
        try:
            with self.lock:
                count = len(self.clients)
            connected = count > 0
            if connected:
                self._disconnect_ticks = 0
                self.status_label.config(text=f"Подключено: {count}", fg="#75B68A")
                self.card_canvas.itemconfig(self.status_img_id, image=self.icon_ok)
                self._apply_connection_layout(True)
            else:
                self._disconnect_ticks += 1
                self.status_label.config(text="Ожидание устройства...", fg=CLR_TEXT_SEC)
                self.card_canvas.itemconfig(self.status_img_id, image=self.icon_wait)
                if self._disconnect_ticks >= 2:
                    self._apply_connection_layout(False)
        except: pass

    def animate_loop(self):
        if self.is_running: self.update_ui_status(); self.root.after(1000, self.animate_loop)

    def refresh_ip(self):
        try: self.current_ip = get_lan_ip()
        except: self.current_ip = "127.0.0.1"
        self.footer.config(text=f"Локальный адрес: {self.current_ip}")
        qr = qrcode.QRCode(box_size=5, border=2); qr.add_data(f"ws://{self.current_ip}:8765/ws")
        img = qr.make_image(fill_color=CLR_ACCENT, back_color="white").convert("RGB").resize((180, 180))
        self.qr_photo = ImageTk.PhotoImage(img); self.qr_container.config(image=self.qr_photo)
        self._start_mDNS()

    def _start_mDNS(self):
        try:
            if self.zeroconf: self.zeroconf.close()
            self.zeroconf = Zeroconf(); host_c = re.sub(r'[^a-zA-Z0-9-]', '', socket.gethostname())
            info = ServiceInfo("_clipboardsync._tcp.local.", f"LinkApp-{host_c}._clipboardsync._tcp.local.", addresses=[socket.inet_aton(self.current_ip)], port=8765, properties={b"path": b"/ws"}, server=f"{host_c}.local.")
            self.zeroconf.register_service(info)
        except Exception as e: log_server(f"mDNS error: {e}")

    def setup_tray(self):
        img = Image.new('RGB', (64, 64), CLR_ACCENT); draw = ImageDraw.Draw(img); draw.ellipse((10, 10, 54, 54), fill="white")
        self.tray = pystray.Icon(APP_NAME, img, menu=pystray.Menu(item('Развернуть', self.show_window, default=True), item('Выход', self.quit_app)))
        self.root.protocol('WM_DELETE_WINDOW', self.hide_window); threading.Thread(target=self.tray.run, daemon=True).start()

    def hide_window(self): self.root.withdraw()
    def show_window(self, _=None): self.root.deiconify(); self.root.attributes("-topmost", True); self.root.focus_force()
    def quit_app(self):
        self.is_running = False
        if self.zeroconf: self.zeroconf.close()
        os._exit(0)

    def start_services(self):
        threading.Thread(target=self.run_ws, daemon=True).start()
        threading.Thread(target=self.run_ftp, daemon=True).start()

    def run_ws(self):
        try:
            app = FastAPI()
            app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

            async def watcher():
                last_txt = ""; last_img_h = None
                while self.is_running:
                    try:
                        raw = await asyncio.get_event_loop().run_in_executor(None, pyperclip.paste); curr = str(raw) if raw else ""
                        if curr and curr != last_txt and curr != self.last_rcvd:
                            last_txt = curr
                            with self.lock:
                                for ws in list(self.clients): await ws.send_json({"type": "clipboard", "data": curr})
                        from PIL import ImageGrab
                        img = await asyncio.get_event_loop().run_in_executor(None, ImageGrab.grabclipboard)
                        if isinstance(img, Image.Image):
                            buf = io.BytesIO(); img.save(buf, format='PNG'); data = buf.getvalue(); cur_h = hash(data)
                            if cur_h != last_img_h and cur_h != self.last_rcvd_img_hash:
                                last_img_h = cur_h; b64 = base64.b64encode(data).decode('utf-8')
                                with self.lock:
                                    for ws in list(self.clients): await ws.send_json({"type": "image", "data": b64})
                        else: last_img_h = None
                    except: pass
                    await asyncio.sleep(1)

            async def status():
                while self.is_running:
                    try:
                        bat = psutil.sensors_battery()
                        if bat:
                            with self.lock:
                                for ws in list(self.clients): await ws.send_json({"type": "pc_status", "battery": int(bat.percent), "plugged": bat.power_plugged})
                    except: pass
                    await asyncio.sleep(10)

            @app.on_event("startup")
            async def startup():
                self.ws_loop = asyncio.get_running_loop()
                asyncio.create_task(watcher()); asyncio.create_task(status())

            @app.get("/files/{filename}")
            async def http_download_file(filename: str):
                path = self.ftp_root / Path(unquote(filename)).name
                if not path.is_file(): raise HTTPException(status_code=404)
                return FileResponse(path, filename=path.name)

            @app.post("/files")
            async def http_upload_file(file: UploadFile = File(...)):
                dest = self.ftp_root / Path(file.filename).name
                with dest.open("wb") as buffer: shutil.copyfileobj(file.file, buffer)
                return {"filename": dest.name}

            @app.websocket("/ws")
            async def websocket_endpoint(ws: WebSocket):
                await ws.accept();
                with self.lock: self.clients.add(ws)
                self.root.after(0, self.update_ui_status)
                try:
                    while True:
                        data = await ws.receive_text(); msg = json.loads(data); mt = msg.get("type")
                        if mt == "clipboard":
                            self.last_rcvd = msg.get("data")
                            if self.last_rcvd: await asyncio.get_event_loop().run_in_executor(None, pyperclip.copy, self.last_rcvd)
                        elif mt == "image":
                            b64 = msg.get("data")
                            if b64 and send_image_to_clipboard(b64):
                                try:
                                    img_d = base64.b64decode(b64); img_t = Image.open(io.BytesIO(img_d))
                                    buf = io.BytesIO(); img_t.save(buf, format='PNG'); self.last_rcvd_img_hash = hash(buf.getvalue())
                                except: pass
                        elif mt == "notification":
                            text = msg.get("data")
                            if text:
                                self.root.after(0, lambda t=text: self.show_pc_notification(t))
                        elif mt == "music_now":
                            song = msg.get("title", "")
                            artist = msg.get("artist", "")
                            app = msg.get("app", "Телефон")
                            self.root.after(0, lambda s=song, a=artist, p=app: self.show_pc_music_notification(p, s, a))
                        elif mt == "media":
                            import win32api, win32con; act = msg.get("action"); vk_map = {"play_pause":0xB3,"next":0xB0,"prev":0xB1,"vol_up":0xAF,"vol_down":0xAE,"mute":0xAD,"seek_forward":0x27,"seek_backward":0x25}
                            if act == "lock_pc": ctypes.windll.user32.LockWorkStation()
                            elif act == "open_calc": subprocess.Popen('calc.exe')
                            elif act == "open_pc": subprocess.Popen('explorer.exe')
                            elif act == "open_browser": webbrowser.open('https://google.com')
                            elif act in vk_map:
                                vk = vk_map[act]
                                win32api.keybd_event(vk,0,0,0); win32api.keybd_event(vk,0,win32con.KEYEVENTF_KEYUP,0)
                        elif mt == "file_notify":
                            fn = msg.get("filename")
                            if fn:
                                with self.lock:
                                    for peer in list(self.clients):
                                        if peer is not ws: await peer.send_json({"type": "file_push", "filename": fn, "size": msg.get("size", 0)})
                except: pass
                finally:
                    with self.lock: self.clients.discard(ws)
                    self.root.after(0, self.update_ui_status)

            uvicorn.run(app, host="0.0.0.0", port=8765, log_config=UVICORN_LOG_CONFIG)
        except Exception as e: log_server(str(e))

    def run_ftp(self):
        try: silence_background_loggers(); run_ftp_server_blocking(self.ftp_root, port=2121, masquerade=get_lan_ip())
        except Exception as e: log_server(f"FTP error: {e}")

    def open_folder(self): os.startfile(self.ftp_root)
    def send_file_to_phone(self):
        path = filedialog.askopenfilename()
        if path:
            dest = self.ftp_root / Path(path).name; shutil.copy2(path, dest)
            if self.ws_loop: asyncio.run_coroutine_threadsafe(self._broadcast_file(dest.name, dest.stat().st_size), self.ws_loop)

    async def _broadcast_file(self, name, size):
        with self.lock: targets = list(self.clients)
        for ws in targets:
            try: await ws.send_json({"type": "file_push", "filename": name, "size": size})
            except: pass

if __name__ == "__main__":
    root = tk.Tk(); app = LinkAppGUI(root)
    root.lift(); root.attributes('-topmost', True); root.after_idle(root.attributes, '-topmost', False); root.focus_force()
    root.mainloop()
