#!/usr/bin/env python3
"""Simple LAN file transfer server for phones and computers."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parent
RESOURCE_ROOT = Path(getattr(sys, "_MEIPASS", SOURCE_ROOT))
DATA_ROOT = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else SOURCE_ROOT
WEB_DIR = RESOURCE_ROOT / "web"
SHARED_DIR = DATA_ROOT / "shared_files"
CHUNK_SIZE = 1024 * 1024
MAX_UPLOAD_BYTES = 50 * 1024 * 1024 * 1024
DISCOVERY_PORT = 8766
DISCOVERY_MESSAGE = b"FLASH_TRANSFER_DISCOVER"


def primary_local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def local_ipv4_addresses() -> list[str]:
    """Return usable local IPv4 addresses, with Windows hotspot addresses first."""
    addresses: set[str] = set()
    try:
        addresses.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass

    addresses.add(primary_local_ip())
    try:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        result = subprocess.run(
            ["ipconfig"],
            capture_output=True,
            text=True,
            errors="ignore",
            creationflags=flags,
            timeout=5,
            check=False,
        )
        for line in result.stdout.splitlines():
            if "IPv4" in line:
                addresses.update(re.findall(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])", line))
    except (OSError, subprocess.SubprocessError):
        pass

    usable = [
        address
        for address in addresses
        if address != "127.0.0.1"
        and not address.startswith("169.254.")
        and all(0 <= int(part) <= 255 for part in address.split("."))
    ]

    def priority(address: str) -> tuple[int, str]:
        if address == "192.168.137.1":
            return (0, address)
        if address.startswith(("192.168.", "10.", "172.")) and address.endswith(".1"):
            return (1, address)
        return (2, address)

    return sorted(set(usable), key=priority)


def safe_name(value: str) -> str:
    name = Path(value.replace("\\", "/")).name.strip().strip(".")
    forbidden = '<>:"/\\|?*\0'
    name = "".join("_" if char in forbidden or ord(char) < 32 else char for char in name)
    return name[:240] or "unnamed-file"


def safe_relative_path(value: str) -> Path:
    parts = [
        safe_name(part)
        for part in value.replace("\\", "/").split("/")
        if part.strip() not in ("", ".", "..")
    ]
    return Path(*parts[:32]) if parts else Path("unnamed-file")


def unique_path(name: str) -> Path:
    path = SHARED_DIR / safe_relative_path(name)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        return path
    stem, suffix = path.stem, path.suffix
    index = 1
    while True:
        candidate = path.with_name(f"{stem} ({index}){suffix}")
        if not candidate.exists():
            return candidate
        index += 1


def file_info(path: Path) -> dict[str, object]:
    stat = path.stat()
    return {
        "name": path.relative_to(SHARED_DIR).as_posix(),
        "size": stat.st_size,
        "modified": int(stat.st_mtime * 1000),
    }


def create_qr_image(url: str, token: str = "current") -> Path | None:
    """Create a scannable QR PNG when OpenCV is available."""
    try:
        import cv2
    except ImportError:
        return None
    matrix = cv2.QRCodeEncoder_create().encode(url)
    quiet_zone = 4
    matrix = cv2.copyMakeBorder(
        matrix,
        quiet_zone,
        quiet_zone,
        quiet_zone,
        quiet_zone,
        cv2.BORDER_CONSTANT,
        value=255,
    )
    image = cv2.resize(matrix, None, fx=12, fy=12, interpolation=cv2.INTER_NEAREST)
    path = DATA_ROOT / f"连接二维码-{safe_name(token)}.png"
    success, encoded = cv2.imencode(".png", image)
    if not success:
        return None
    path.write_bytes(encoded.tobytes())
    for old_path in DATA_ROOT.glob("连接二维码-*.png"):
        if old_path != path:
            try:
                old_path.unlink()
            except OSError:
                pass
    return path


def discovery_loop(token: str, port: int, stop_event: threading.Event) -> None:
    """Answer Android client discovery broadcasts with the current connection data."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
        sock.settimeout(1)
        payload = json.dumps({"service": "flash-transfer", "port": port, "token": token}).encode("utf-8")
        while not stop_event.is_set():
            try:
                data, address = sock.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                break
            if data.strip() == DISCOVERY_MESSAGE:
                try:
                    sock.sendto(payload, address)
                except OSError:
                    pass
    except OSError as exc:
        print(f"提示：自动发现服务未启动：{exc}")
    finally:
        sock.close()


class TransferServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], token: str):
        SHARED_DIR.mkdir(exist_ok=True)
        super().__init__(address, TransferHandler)
        self.token = token


class TransferHandler(BaseHTTPRequestHandler):
    server: TransferServer

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stdout.write(f"[{self.log_date_time_string()}] {self.client_address[0]} {fmt % args}\n")

    def do_GET(self) -> None:
        route, query = self.parse_request_target()
        if route == "/":
            return self.redirect(f"/{self.server.token}/")
        if not self.authorized(route):
            return self.send_error(HTTPStatus.NOT_FOUND)

        relative = route[len(self.server.token) + 2 :]
        if relative in ("", "/"):
            return self.send_static("index.html")
        if relative == "api/files":
            return self.list_files()
        if relative == "api/download":
            return self.download_file(query)
        if relative.startswith("static/"):
            return self.send_static(relative.removeprefix("static/"))
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        route, query = self.parse_request_target()
        if not self.authorized(route):
            return self.send_error(HTTPStatus.NOT_FOUND)
        relative = route[len(self.server.token) + 2 :]
        if relative == "api/upload":
            return self.upload_file(query)
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_DELETE(self) -> None:
        route, query = self.parse_request_target()
        if not self.authorized(route):
            return self.send_error(HTTPStatus.NOT_FOUND)
        relative = route[len(self.server.token) + 2 :]
        if relative == "api/file":
            return self.delete_file(query)
        self.send_error(HTTPStatus.NOT_FOUND)

    def parse_request_target(self) -> tuple[str, dict[str, list[str]]]:
        parsed = urllib.parse.urlsplit(self.path)
        return urllib.parse.unquote(parsed.path), urllib.parse.parse_qs(parsed.query)

    def authorized(self, route: str) -> bool:
        return route == f"/{self.server.token}" or route.startswith(f"/{self.server.token}/")

    def redirect(self, location: str) -> None:
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", location)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def send_json(self, payload: object, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_static(self, name: str) -> None:
        static_files = {
            "index.html": ("text/html; charset=utf-8", WEB_DIR / "index.html"),
            "app.css": ("text/css; charset=utf-8", WEB_DIR / "app.css"),
            "app.js": ("text/javascript; charset=utf-8", WEB_DIR / "app.js"),
        }
        item = static_files.get(name)
        if not item or not item[1].is_file():
            return self.send_error(HTTPStatus.NOT_FOUND)
        content_type, path = item
        data = path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def list_files(self) -> None:
        files = [
            file_info(path)
            for path in SHARED_DIR.rglob("*")
            if path.is_file() and path.name != ".gitkeep"
        ]
        files.sort(key=lambda item: item["modified"], reverse=True)
        self.send_json({"files": files})

    def upload_file(self, query: dict[str, list[str]]) -> None:
        length_text = self.headers.get("Content-Length")
        if not length_text:
            return self.send_json({"error": "缺少文件长度"}, HTTPStatus.LENGTH_REQUIRED)
        try:
            length = int(length_text)
        except ValueError:
            return self.send_json({"error": "无效的文件长度"}, HTTPStatus.BAD_REQUEST)
        if length < 0 or length > MAX_UPLOAD_BYTES:
            return self.send_json({"error": "文件超过 50 GB 限制"}, HTTPStatus.REQUEST_ENTITY_TOO_LARGE)

        requested_name = query.get("name", [""])[0]
        if not requested_name:
            return self.send_json({"error": "缺少文件名"}, HTTPStatus.BAD_REQUEST)

        destination = unique_path(requested_name)
        temporary = destination.with_name(f".{destination.name}.{secrets.token_hex(6)}.uploading")
        remaining = length
        try:
            with temporary.open("wb") as output:
                while remaining:
                    chunk = self.rfile.read(min(CHUNK_SIZE, remaining))
                    if not chunk:
                        raise ConnectionError("上传连接提前断开")
                    output.write(chunk)
                    remaining -= len(chunk)
            temporary.replace(destination)
        except Exception as exc:
            temporary.unlink(missing_ok=True)
            return self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
        self.send_json({"file": file_info(destination)}, HTTPStatus.CREATED)

    def requested_file(self, query: dict[str, list[str]]) -> Path | None:
        name = safe_relative_path(query.get("name", [""])[0])
        path = SHARED_DIR / name
        return path if path.is_file() else None

    def download_file(self, query: dict[str, list[str]]) -> None:
        path = self.requested_file(query)
        if not path:
            return self.send_error(HTTPStatus.NOT_FOUND)
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(path.stat().st_size))
        encoded = urllib.parse.quote(path.name)
        self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{encoded}")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            with path.open("rb") as source:
                while chunk := source.read(CHUNK_SIZE):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def delete_file(self, query: dict[str, list[str]]) -> None:
        path = self.requested_file(query)
        if not path:
            return self.send_json({"error": "文件不存在"}, HTTPStatus.NOT_FOUND)
        try:
            path.unlink()
            parent = path.parent
            while parent != SHARED_DIR:
                try:
                    parent.rmdir()
                except OSError:
                    break
                parent = parent.parent
        except OSError as exc:
            return self.send_json({"error": str(exc)}, HTTPStatus.INTERNAL_SERVER_ERROR)
        self.send_json({"deleted": str(path.relative_to(SHARED_DIR).as_posix())})


def main() -> None:
    parser = argparse.ArgumentParser(description="手机与电脑局域网文件传输")
    parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    parser.add_argument("--token", help="固定访问口令；默认每次随机生成")
    parser.add_argument("--hotspot", action="store_true", help="电脑移动热点模式")
    parser.add_argument("--show-qr", action="store_true", default=True, help="启动时打开连接二维码")
    parser.add_argument("--no-show-qr", action="store_false", dest="show_qr", help="不自动打开连接二维码")
    args = parser.parse_args()

    SHARED_DIR.mkdir(exist_ok=True)
    token = args.token or hashlib.sha256(secrets.token_bytes(32)).hexdigest()[:10]
    server = TransferServer(("0.0.0.0", args.port), token)
    discovery_stop = threading.Event()
    discovery_thread = threading.Thread(
        target=discovery_loop,
        args=(token, args.port, discovery_stop),
        daemon=True,
    )
    discovery_thread.start()
    addresses = local_ipv4_addresses()
    urls = [f"http://{address}:{args.port}/{token}/" for address in addresses]

    print("\n手机电脑文件传输已启动")
    if args.hotspot:
        print("热点模式：请让手机连接这台电脑开启的移动热点。")
        hotspot_found = "192.168.137.1" in addresses
        if not hotspot_found:
            print("提示：暂未检测到默认热点地址，请确认 Windows 移动热点已经开启。")
    else:
        print("请让手机与电脑连接同一 Wi-Fi。")
    print("\n请在手机浏览器打开以下地址：")
    if urls:
        for index, url in enumerate(urls, 1):
            label = "（推荐热点地址）" if url.startswith("http://192.168.137.1:") else ""
            print(f"  {index}. {url} {label}")
    else:
        urls = [f"http://192.168.137.1:{args.port}/{token}/"]
        print(f"  {urls[0]} （电脑热点常用地址）")
    qr_path = create_qr_image(urls[0], token)
    if qr_path:
        print(f"连接二维码：{qr_path}")
        if args.show_qr:
            try:
                os.startfile(qr_path)
            except OSError:
                pass
    else:
        print("提示：当前电脑缺少二维码组件，请使用上方地址连接。")
    print(f"共享文件夹：{SHARED_DIR}")
    print("按 Ctrl+C 停止服务\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")
    finally:
        discovery_stop.set()
        server.server_close()


if __name__ == "__main__":
    main()
