"""Write a custom-code provider for the platform in Python.

Declare the provider and its actions, serve them on the provider's Temporal task queue, and
publish the provider file the platform reads::

    from iaf_provider import Call, Effect, Provider, fatal, run_worker

    dns = Provider("dns-records", title="DNS records", owner="team-platform", version="1.0.0")
    dns.error("record_not_found", retryable=False, description="No record has that name")

    @dns.action("record.get", title="Get a record", effect=Effect.READ)
    def get_record(call: Call, ref: RecordRef) -> Record:
        ...

    asyncio.run(run_worker(dns))

The platform and the SDKs share one contract (the payload, the error classes, the heartbeat); see
"Custom-code providers" in docs/architecture.md.
"""

from ._errors import (
    INVALID_INPUT,
    INVALID_OUTPUT,
    INVALID_REQUEST,
    UNCLASSIFIED,
    UNKNOWN_ACTION,
    ProviderError,
    fatal,
    retryable,
)
from ._provider import Action, Effect, ErrorClass, Provider, format_duration
from ._publish import Submission, submit, submit_to, write_file
from ._schema import schema_of
from ._worker import (
    Call,
    Job,
    WorkerOptions,
    activities,
    activity_for,
    envelope,
    new_worker,
    run_worker,
)

__all__ = [
    "Action",
    "Call",
    "Effect",
    "ErrorClass",
    "INVALID_INPUT",
    "INVALID_OUTPUT",
    "INVALID_REQUEST",
    "Job",
    "Provider",
    "ProviderError",
    "Submission",
    "UNCLASSIFIED",
    "UNKNOWN_ACTION",
    "WorkerOptions",
    "activities",
    "activity_for",
    "envelope",
    "fatal",
    "format_duration",
    "new_worker",
    "retryable",
    "run_worker",
    "schema_of",
    "submit",
    "submit_to",
    "write_file",
]
