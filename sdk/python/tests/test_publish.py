from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

from iaf_provider import Submission, submit, submit_to, write_file

from inventory import Inventory

TOKEN = "iaf_test-token"


class Platform:
    """Answers the provider API the way the platform does and records what it got."""

    def __init__(self, answers: dict[str, tuple]) -> None:
        self.calls: list[str] = []
        self.bodies: list[dict] = []
        platform = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                self.answer()

            def do_GET(self) -> None:  # noqa: N802
                self.answer()

            def answer(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                platform.bodies.append(json.loads(self.rfile.read(length) or b"{}"))
                platform.calls.append(f"{self.command} {self.path} {self.headers.get('Authorization')}")
                status, body, *headers = answers.get(self.path, (404, '{"error":"not found"}'))
                data = body.encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                for name, value in (headers[0] if headers else {}).items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args) -> None:
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def platform_with():
    started: list[Platform] = []

    def start(answers: dict[str, tuple]) -> Platform:
        p = Platform(answers)
        started.append(p)
        return p

    yield start
    for p in started:
        p.close()


def test_write_file_creates_the_folders_and_the_provider_file(tmp_path: Path):
    p = Inventory().provider()

    file = write_file(p, tmp_path / "providers" / "team-platform" / "inventory.yaml")

    assert file.read_text(encoding="utf-8") == p.to_yaml()


def test_submit_checks_the_file_then_publishes_it_as_the_service_account(platform_with):
    p = Inventory().provider()
    srv = platform_with(
        {
            "/api/v1/providers:validate": (200, '{"checks":[],"problems":[{"level":"warning","code":"alerts.channel","message":"Set it"}]}'),
            "/api/v1/providers:publish": (202, '{"id":"cr-42","status":"open","links":{}}'),
        }
    )

    s = submit(p, {"IAF_PLATFORM_URL": srv.url, "IAF_PLATFORM_TOKEN": TOKEN})

    assert s == Submission("cr-42", "open")
    assert srv.calls == [
        f"POST /api/v1/providers:validate Bearer {TOKEN}",
        f"POST /api/v1/providers:publish Bearer {TOKEN}",
    ]
    assert all(b["yaml"] == p.to_yaml() for b in srv.bodies)


def test_a_check_error_stops_the_submit_before_anything_is_published(platform_with):
    srv = platform_with(
        {
            "/api/v1/providers:validate": (
                200,
                '{"checks":[],"problems":[{"level":"error","code":"metadata.owner","field":"metadata.owner","message":"owner is a team"}]}',
            ),
            "/api/v1/providers:publish": (202, '{"id":"cr-1"}'),
        }
    )

    with pytest.raises(RuntimeError, match="metadata.owner at metadata.owner: owner is a team"):
        submit_to(Inventory().provider(), srv.url, TOKEN)
    assert len(srv.calls) == 1


def test_a_refused_publish_says_why_without_repeating_the_token(platform_with):
    srv = platform_with(
        {
            "/api/v1/providers:validate": (200, '{"checks":[],"problems":[]}'),
            "/api/v1/providers:publish": (403, '{"error":"Only team-platform may publish provider inventory"}'),
        }
    )

    with pytest.raises(RuntimeError) as e:
        submit_to(Inventory().provider(), srv.url + "/", TOKEN)

    assert "403" in str(e.value)
    assert "Only team-platform may publish provider inventory" in str(e.value)
    assert TOKEN not in str(e.value)


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
def test_a_redirect_fails_the_submit_and_the_token_never_reaches_the_other_host(platform_with, status):
    elsewhere = platform_with({"/api/v1/providers:validate": (200, '{"checks":[],"problems":[]}')})
    srv = platform_with(
        {"/api/v1/providers:validate": (status, "", {"Location": elsewhere.url + "/api/v1/providers:validate"})}
    )

    with pytest.raises(RuntimeError) as e:
        submit_to(Inventory().provider(), srv.url, TOKEN)

    assert f"POST /api/v1/providers:validate answered {status}, a redirect to {elsewhere.url}" in str(e.value)
    assert "IAF_PLATFORM_URL" in str(e.value)
    assert TOKEN not in str(e.value)
    assert len(srv.calls) == 1
    assert elsewhere.calls == []


def test_submitting_needs_a_service_account_token_from_the_environment():
    with pytest.raises(RuntimeError, match="IAF_PLATFORM_TOKEN"):
        submit(Inventory().provider(), {"IAF_PLATFORM_URL": "http://127.0.0.1:1"})
