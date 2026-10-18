package provider

import (
	"errors"

	"go.temporal.io/sdk/temporal"
)

// The error classes of a broken contract: the call fails for good, whatever the step's retry
// policy says. A handler error that is not classified becomes the retryable class Unclassified.
const (
	UnknownAction  = "unknown_action"
	InvalidRequest = "invalid_request"
	InvalidInput   = "invalid_input"
	InvalidOutput  = "invalid_output"
	Unclassified   = "error"
)

// Error is a classified failure of an action. The worker reports it with its class: a retryable
// one may be retried by the step's retry policy, a fatal one fails the step at once.
type Error struct {
	Class     string
	Message   string
	Retryable bool
}

func (e *Error) Error() string { return e.Class + ": " + e.Message }

// Retryable is a failure a retry can fix: the system was busy, a lock was held, a dependency was
// down.
func Retryable(class, message string) error {
	return &Error{Class: class, Message: message, Retryable: true}
}

// Fatal is a failure no retry can fix: the input names something that does not exist, a quota is
// used up.
func Fatal(class, message string) error {
	return &Error{Class: class, Message: message}
}

// toTemporal is how a classified failure crosses to the platform: an ApplicationError whose type
// is the error class, non-retryable when the failure is fatal.
func toTemporal(err error) error {
	var e *Error
	if errors.As(err, &e) {
		return temporal.NewApplicationErrorWithOptions(e.Message, e.Class, temporal.ApplicationErrorOptions{NonRetryable: !e.Retryable})
	}
	return err
}
