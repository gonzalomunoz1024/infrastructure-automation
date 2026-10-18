"""Getting the provider file to the platform."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from ._provider import Provider

URL_ENV = "IAF_PLATFORM_URL"
TOKEN_ENV = "IAF_PLATFORM_TOKEN"
DEFAULT_URL = "http://localhost:8080"
_TIMEOUT = 30


@dataclass(frozen=True)
class Submission:
    """What the platform answered: the change request opened for the provider version."""

    change_request: str | None
    status: str | None


def write_file(provider: Provider, path: str | Path) -> Path:
    """Write the provider file, creating the folders; returns the path."""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(provider.validate().to_yaml(), encoding="utf-8")
    return p


def submit(provider: Provider, env: Mapping[str, str] | None = None) -> Submission:
    """Submit to ``IAF_PLATFORM_URL`` (default http://localhost:8080) as the service account whose
    token is in ``IAF_PLATFORM_TOKEN``."""
    env = os.environ if env is None else env
    token = (env.get(TOKEN_ENV) or "").strip()
    if not token:
        raise RuntimeError(f"Set {TOKEN_ENV} to a service-account token of the platform to submit the provider")
    url = (env.get(URL_ENV) or "").strip() or DEFAULT_URL
    return submit_to(provider, url, token)


def submit_to(provider: Provider, platform_url: str, token: str) -> Submission:
    """Check the provider file with the platform's provider checks, then publish it: the platform
    opens a change request for the version. Nothing is published when a check reports an error.
    The token is sent, never printed."""
    body = {"yaml": provider.validate().to_yaml()}
    base = platform_url.rstrip("/")
    checked = _post(base, "/api/v1/providers:validate", body, token, 200)
    errors = [
        f"{p.get('code')}{' at ' + p['field'] if p.get('field') else ''}: {p.get('message')}"
        for p in checked.get("problems", [])
        if p.get("level") == "error"
    ]
    if errors:
        raise RuntimeError(f"The platform's checks reject provider {provider.name}:\n  - " + "\n  - ".join(errors))
    accepted = _post(base, "/api/v1/providers:publish", body, token, 202)
    return Submission(accepted.get("id"), accepted.get("status"))


def _post(base: str, path: str, body: dict[str, Any], token: str, expected: int) -> dict[str, Any]:
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=_TIMEOUT) as response:
            status, text = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode("utf-8", errors="replace")
    data: Any = {}
    if text.strip():
        try:
            data = json.loads(text)
        except ValueError:
            data = {"error": text[:200]}
    if status != expected:
        why = data.get("error") if isinstance(data, dict) and data.get("error") else "unexpected answer"
        raise RuntimeError(f"POST {path} answered {status}: {why}")
    return data if isinstance(data, dict) else {}
