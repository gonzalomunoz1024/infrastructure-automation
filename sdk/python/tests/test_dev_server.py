"""The worker against a real Temporal dev server, the way the platform calls it.

Runs when TEMPORAL_CLI names a ``temporal`` CLI binary (the dev server it starts is in-memory and
gone after the test); skipped otherwise.
"""

from __future__ import annotations

import asyncio
import os
import socket
import uuid
from collections.abc import AsyncIterator
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from typing import Any

import pytest
from temporalio import workflow
from temporalio.api.workflowservice.v1 import DescribeNamespaceRequest
from temporalio.client import Client
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError
from temporalio.worker import UnsandboxedWorkflowRunner, Worker

from iaf_provider import envelope, new_worker

from inventory import Inventory

CLI = os.environ.get("TEMPORAL_CLI")
pytestmark = pytest.mark.skipif(not CLI, reason="set TEMPORAL_CLI to a temporal CLI binary to run against a dev server")

PLATFORM = "platform"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
async def dev_server() -> AsyncIterator[Client]:
    """A dev server of its own, waited for patiently: a shared machine can take more than the
    few seconds ``WorkflowEnvironment.start_local`` allows."""
    port = free_port()
    server = await asyncio.create_subprocess_exec(
        CLI,
        "server",
        "start-dev",
        "--headless",
        "--ip",
        "127.0.0.1",
        "--port",
        str(port),
        "--http-port",
        str(free_port()),
        "--metrics-port",
        str(free_port()),
        "--log-level",
        "warn",
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        client = None
        for _ in range(120):
            if server.returncode is not None:
                pytest.fail(f"the dev server exited with {server.returncode}")
            try:
                client = await Client.connect(f"127.0.0.1:{port}")
                # Ready once the default namespace exists, not merely once the port answers.
                await client.workflow_service.describe_namespace(DescribeNamespaceRequest(namespace="default"))
                break
            except Exception:
                client = None
                await asyncio.sleep(0.25)
        if client is None:
            pytest.fail("the dev server did not answer within 30s")
        yield client
    finally:
        if server.returncode is None:
            server.terminate()
            try:
                await asyncio.wait_for(server.wait(), 10)
            except asyncio.TimeoutError:
                server.kill()
                await server.wait()


@workflow.defn(name="PlatformCall")
class PlatformCall:
    """Calls an action like the platform's interpreter: on the provider's queue, no Temporal retries."""

    @workflow.run
    async def run(self, task_queue: str, action: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            out = await workflow.execute_activity(
                action,
                payload,
                task_queue=task_queue,
                start_to_close_timeout=timedelta(seconds=20),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
            return {"output": out}
        except ActivityError as e:
            if isinstance(e.cause, ApplicationError):
                return {"type": e.cause.type, "nonRetryable": e.cause.non_retryable, "message": e.cause.message}
            raise


async def test_the_worker_answers_the_platforms_calls_on_a_dev_server(dev_server: Client):
    provider = Inventory().provider()
    with ThreadPoolExecutor(max_workers=4) as executor:
        team = new_worker(dev_server, provider, activity_executor=executor, drain_timeout=timedelta(seconds=5))
        platform = Worker(dev_server, task_queue=PLATFORM, workflows=[PlatformCall], workflow_runner=UnsandboxedWorkflowRunner())
        async with team, platform:

            async def call(action: str, payload: dict[str, Any]) -> dict[str, Any]:
                return await dev_server.execute_workflow(
                    PlatformCall.run, args=[provider.task_queue, action, payload], id=f"call-{uuid.uuid4()}", task_queue=PLATFORM
                )

            ok = await call("item.get", envelope({"id": "it-1"}))
            missing = await call("item.get", envelope({"id": "it-404"}))
            unknown = await call("item.melt", envelope())
            bad = await call("item.get", envelope({"name": "no id"}))

    assert ok == {"output": {"id": "it-1", "name": "first", "state": "active"}}
    assert missing == {"type": "not_found", "nonRetryable": True, "message": "No item it-404"}
    assert unknown["type"] == "unknown_action" and unknown["nonRetryable"]
    assert bad["type"] == "invalid_input" and bad["nonRetryable"]
