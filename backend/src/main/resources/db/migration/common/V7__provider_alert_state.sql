-- The provider alert evaluator's state (ProviderAlertStatePort): the instant of its last
-- evaluation, so the first evaluation after a restart starts there instead of raising again an
-- alert for every failed call of the last day. One row (id 'evaluator'); last_evaluated_ns is the
-- exact instant, last_evaluated_at the same to the microsecond for people reading the table.
CREATE TABLE provider_alert_state (
    id                 VARCHAR(40) PRIMARY KEY,
    last_evaluated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_evaluated_ns  BIGINT NOT NULL
);
