"""Classified failures and the error classes of the contract."""

from __future__ import annotations

#: The error classes of a broken contract: the call fails for good, whatever the step's retry
#: policy says. A handler exception that is not classified becomes the retryable class ``error``.
UNKNOWN_ACTION = "unknown_action"
INVALID_REQUEST = "invalid_request"
INVALID_INPUT = "invalid_input"
INVALID_OUTPUT = "invalid_output"
UNCLASSIFIED = "error"


class ProviderError(Exception):
    """A classified failure of an action.

    The worker reports it with its error class: a retryable one may be retried by the step's
    retry policy, a fatal one fails the step at once.
    """

    def __init__(self, error_class: str, message: str, *, retryable: bool) -> None:
        if not error_class:
            raise ValueError("an error needs an error class")
        super().__init__(message or error_class)
        self.error_class = error_class
        self.message = message or error_class
        self.retryable = retryable

    def __repr__(self) -> str:
        return f"ProviderError({self.error_class!r}, {self.message!r}, retryable={self.retryable})"


def retryable(error_class: str, message: str) -> ProviderError:
    """A failure a retry can fix: the system was busy, a lock was held, a dependency was down."""
    return ProviderError(error_class, message, retryable=True)


def fatal(error_class: str, message: str) -> ProviderError:
    """A failure no retry can fix: the input names something that does not exist, a quota is used up."""
    return ProviderError(error_class, message, retryable=False)
