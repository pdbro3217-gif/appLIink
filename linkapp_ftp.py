"""Shared FTP server setup for LinkApp (desktop + pc-client)."""
from __future__ import annotations

import logging
import os
import socket
import sys
from pathlib import Path
from typing import Iterable

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

logger = logging.getLogger("linkapp.ftp")

PASSIVE_PORTS = range(60000, 60051)
DEFAULT_FTP_PORT = 2121
LINKAPP_FOLDER = "LinkApp"
LEGACY_RELATIVE_ROOTS = {".", "./ftp_root", "ftp_root", "./ftp_root/"}


def get_downloads_dir() -> Path:
    """System Downloads folder (handles localized Windows paths like Загрузки)."""
    if sys.platform == "win32":
        try:
            import winreg

            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders",
            ) as key:
                for name in (
                    "{374DE290-123F-4565-9164-39C4925E467B}",
                    "Downloads",
                    "{7D83EE9B-2244-4E70-B1F4-2867039FC297}",
                ):
                    try:
                        value, _ = winreg.QueryValueEx(key, name)
                        path = Path(os.path.expandvars(value))
                        if path.is_dir():
                            return path
                    except OSError:
                        continue
        except OSError:
            pass
        home = Path.home()
        for folder in ("Downloads", "Загрузки", "Скачивания"):
            candidate = home / folder
            if candidate.is_dir():
                return candidate

    xdg = os.environ.get("XDG_DOWNLOAD_DIR")
    if xdg:
        path = Path(xdg).expanduser()
        if path.is_dir():
            return path

    return Path.home() / "Downloads"


def get_default_ftp_root() -> Path:
    return get_downloads_dir() / LINKAPP_FOLDER


def resolve_ftp_root(config_value: str | None, project_root: Path | None = None) -> Path:
    """
    FTP files directory. Default: Downloads/LinkApp (system Downloads).
    Legacy ./ftp_root in config is mapped to the same default.
    """
    raw = (config_value or "").strip()
    if not raw or raw.replace("\\", "/").rstrip("/") in LEGACY_RELATIVE_ROOTS:
        return get_default_ftp_root()

    path = Path(raw).expanduser()
    if not path.is_absolute() and project_root is not None:
        path = (project_root / path).resolve()
    elif not path.is_absolute():
        path = path.resolve()
    return path


def get_lan_ip() -> str:
    """Best-effort local IPv4 for PASV replies (not 127.0.0.1)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        pass
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


def create_ftp_server(
    root_dir: Path,
    *,
    host: str = "0.0.0.0",
    port: int = DEFAULT_FTP_PORT,
    masquerade: str | None = None,
    passive_ports: Iterable[int] = PASSIVE_PORTS,
    anonymous: bool = True,
    username: str = "user",
    password: str = "password",
) -> FTPServer:
    root_dir = root_dir.resolve()
    root_dir.mkdir(parents=True, exist_ok=True)

    authorizer = DummyAuthorizer()
    perms = "elradfmwMT"
    if anonymous:
        authorizer.add_anonymous(str(root_dir), perm=perms)
    else:
        authorizer.add_user(username, password, str(root_dir), perm=perms)

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.banner = "LinkApp FTP ready."
    handler.passive_ports = list(passive_ports)
    handler.masquerade_address = masquerade or get_lan_ip()

    logger.info(
        "FTP root=%s listen=%s:%s pasv_host=%s pasv_ports=%s-%s",
        root_dir,
        host,
        port,
        handler.masquerade_address,
        passive_ports[0],
        passive_ports[-1],
    )
    return FTPServer((host, port), handler)


def run_ftp_server_blocking(
    root_dir: Path,
    *,
    port: int = DEFAULT_FTP_PORT,
    masquerade: str | None = None,
    anonymous: bool = True,
    username: str = "user",
    password: str = "password",
) -> None:
    server = create_ftp_server(
        root_dir,
        port=port,
        masquerade=masquerade,
        anonymous=anonymous,
        username=username,
        password=password,
    )
    server.serve_forever()
