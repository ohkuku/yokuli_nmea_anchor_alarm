#!/usr/bin/env python3
"""Loopback-only browser console for safe Anchor Watch GitHub releases."""

from __future__ import annotations

import argparse
import json
import re
import secrets
import subprocess
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
STATIC_DIR = Path(__file__).resolve().parent / "console"
RELEASE_MANAGER = Path(__file__).resolve().parent / "manage-release.sh"
LOGO_FILE = REPO_ROOT / "docs" / "images" / "anchor-watch-logo.png"
REPOSITORY_URL = "https://github.com/ohkuku/yokuli_nmea_anchor_alarm"
TAG_PATTERN = re.compile(
    r"^v(?P<major>0|[1-9][0-9]*)\."
    r"(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)"
    r"(?:-(?P<channel>alpha|beta)\.(?P<number>[1-9][0-9]*))?$"
)


def run_git(*args: str, check: bool = True, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        check=check,
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def parse_tag(tag: str) -> dict[str, Any] | None:
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        return None
    values = match.groupdict()
    channel = values["channel"] or "stable"
    return {
        "tag": tag,
        "major": int(values["major"]),
        "minor": int(values["minor"]),
        "patch": int(values["patch"]),
        "channel": channel,
        "number": int(values["number"] or 0),
    }


def allowed_channels(branch: str) -> list[str]:
    if branch == "codex/develop":
        return ["alpha"]
    if branch.startswith("codex/release/"):
        return ["alpha", "beta"]
    if branch == "main":
        return ["beta", "stable"]
    return []


def suggested_tags(branch: str, tag_names: list[str]) -> dict[str, str]:
    parsed = [item for tag in tag_names if (item := parse_tag(tag)) is not None]
    branch_version = None
    if branch.startswith("codex/release/"):
        candidate = branch.removeprefix("codex/release/")
        if re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", candidate):
            branch_version = tuple(int(part) for part in candidate.split("."))

    if branch_version is not None:
        base = branch_version
    elif parsed:
        base = max((item["major"], item["minor"], item["patch"]) for item in parsed)
    else:
        base = (1, 0, 0)

    base_text = ".".join(str(part) for part in base)
    suggestions: dict[str, str] = {}
    for channel in ("alpha", "beta"):
        existing_numbers = [
            item["number"]
            for item in parsed
            if (item["major"], item["minor"], item["patch"]) == base and item["channel"] == channel
        ]
        suggestions[channel] = f"v{base_text}-{channel}.{max(existing_numbers, default=0) + 1}"
    suggestions["stable"] = f"v{base_text}"
    return suggestions


def repository_status() -> dict[str, Any]:
    branch = run_git("branch", "--show-current").stdout.strip()
    commit = run_git("rev-parse", "--short", "HEAD").stdout.strip()
    dirty_entries = [line for line in run_git("status", "--porcelain").stdout.splitlines() if line]
    upstream_result = run_git(
        "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check=False
    )
    upstream = upstream_result.stdout.strip() if upstream_result.returncode == 0 else ""
    ahead = behind = None
    if upstream:
        counts = run_git("rev-list", "--left-right", "--count", f"HEAD...{upstream}").stdout.split()
        if len(counts) == 2:
            ahead, behind = (int(counts[0]), int(counts[1]))

    tag_names = run_git("tag", "--list").stdout.splitlines()
    channels = allowed_channels(branch)
    clean = not dirty_entries
    synchronized = bool(upstream) and ahead == 0 and behind == 0
    return {
        "repository": "ohkuku/yokuli_nmea_anchor_alarm",
        "repositoryUrl": REPOSITORY_URL,
        "actionsUrl": f"{REPOSITORY_URL}/actions/workflows/release.yml",
        "releasesUrl": f"{REPOSITORY_URL}/releases",
        "branch": branch or "DETACHED",
        "commit": commit,
        "clean": clean,
        "changedCount": len(dirty_entries),
        "upstream": upstream or "Not configured",
        "ahead": ahead,
        "behind": behind,
        "synchronized": synchronized,
        "allowedChannels": channels,
        "suggestions": suggested_tags(branch, tag_names),
        "ready": clean and synchronized and bool(channels),
        "signingNote": "GitHub Secrets are checked by the online Release workflow before signing.",
    }


class ReleaseConsoleServer(ThreadingHTTPServer):
    csrf_token: str
    publish_lock: threading.Lock


class ReleaseConsoleHandler(BaseHTTPRequestHandler):
    server: ReleaseConsoleServer

    def log_message(self, format_string: str, *args: Any) -> None:
        print(f"Release Console: {format_string % args}")

    def send_bytes(self, content: bytes, content_type: str, status: HTTPStatus = HTTPStatus.OK) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(content)

    def send_json(self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        self.send_bytes(
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            "application/json; charset=utf-8",
            status,
        )

    def valid_loopback_host(self) -> bool:
        host = self.headers.get("Host", "")
        port = self.server.server_address[1]
        return host in {f"127.0.0.1:{port}", f"localhost:{port}"}

    def reject_non_loopback_host(self) -> bool:
        if self.valid_loopback_host():
            return False
        self.send_json({"ok": False, "error": "Invalid local console host."}, HTTPStatus.FORBIDDEN)
        return True

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_json({"ok": False, "error": "Cross-origin requests are not allowed."}, HTTPStatus.METHOD_NOT_ALLOWED)

    def do_GET(self) -> None:  # noqa: N802
        if self.reject_non_loopback_host():
            return
        route = self.path.split("?", 1)[0]
        if route == "/api/status":
            try:
                self.send_json({"ok": True, "status": repository_status()})
            except (OSError, subprocess.SubprocessError, ValueError) as error:
                self.send_json({"ok": False, "error": str(error)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if route == "/config.js":
            config = json.dumps({"token": self.server.csrf_token})
            self.send_bytes(f"window.RELEASE_CONSOLE={config};\n".encode(), "text/javascript; charset=utf-8")
            return
        if route == "/logo.png":
            self.send_bytes(LOGO_FILE.read_bytes(), "image/png")
            return

        static_routes = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "text/javascript; charset=utf-8"),
            "/styles.css": ("styles.css", "text/css; charset=utf-8"),
        }
        target = static_routes.get(route)
        if target is None:
            self.send_json({"ok": False, "error": "Not found"}, HTTPStatus.NOT_FOUND)
            return
        file_name, content_type = target
        self.send_bytes((STATIC_DIR / file_name).read_bytes(), content_type)

    def do_POST(self) -> None:  # noqa: N802
        if self.reject_non_loopback_host():
            return
        if self.path != "/api/publish":
            self.send_json({"ok": False, "error": "Not found"}, HTTPStatus.NOT_FOUND)
            return
        if self.headers.get("X-Release-Console-Token") != self.server.csrf_token:
            self.send_json({"ok": False, "error": "Invalid console token."}, HTTPStatus.FORBIDDEN)
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            content_length = 0
        if content_length <= 0 or content_length > 4096:
            self.send_json({"ok": False, "error": "Invalid request size."}, HTTPStatus.BAD_REQUEST)
            return
        try:
            payload = json.loads(self.rfile.read(content_length))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_json({"ok": False, "error": "Invalid JSON request."}, HTTPStatus.BAD_REQUEST)
            return
        tag = payload.get("tag") if isinstance(payload, dict) else None
        if not isinstance(tag, str) or parse_tag(tag) is None:
            self.send_json({"ok": False, "error": "Invalid release tag."}, HTTPStatus.BAD_REQUEST)
            return
        if not self.server.publish_lock.acquire(blocking=False):
            self.send_json({"ok": False, "error": "Another release request is already running."}, HTTPStatus.CONFLICT)
            return
        try:
            result = subprocess.run(
                [str(RELEASE_MANAGER), "publish", tag],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=180,
            )
        except subprocess.TimeoutExpired:
            self.send_json(
                {"ok": False, "error": "The publish operation timed out. Refresh status before retrying."},
                HTTPStatus.GATEWAY_TIMEOUT,
            )
            return
        except OSError as error:
            self.send_json({"ok": False, "error": str(error)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        finally:
            self.server.publish_lock.release()

        output = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        if result.returncode != 0:
            self.send_json({"ok": False, "error": output or "Release publication failed."}, HTTPStatus.CONFLICT)
            return
        self.send_json({"ok": True, "message": output, "actionsUrl": f"{REPOSITORY_URL}/actions/workflows/release.yml"})


def main() -> int:
    parser = argparse.ArgumentParser(description="Open the loopback-only Anchor Watch Release Console.")
    parser.add_argument("--port", type=int, default=8765, help="Local port (default: 8765)")
    parser.add_argument("--no-open", action="store_true", help="Do not open the browser automatically")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")

    server = ReleaseConsoleServer(("127.0.0.1", args.port), ReleaseConsoleHandler)
    server.csrf_token = secrets.token_urlsafe(32)
    server.publish_lock = threading.Lock()
    url = f"http://127.0.0.1:{args.port}"
    print(f"Anchor Watch Release Console: {url}")
    print("This console is available only on this Mac. Press Control-C to stop it.")
    if not args.no_open:
        threading.Timer(0.4, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nRelease Console stopped.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
