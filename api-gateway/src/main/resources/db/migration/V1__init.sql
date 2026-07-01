CREATE TABLE idempotency_keys (
    key             VARCHAR PRIMARY KEY,
    payment_id      UUID NOT NULL,
    request_hash    VARCHAR NOT NULL,
    response_status INT NOT NULL,
    response_body   JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,          -- paymentId
    event_id     UUID NOT NULL UNIQUE,
    event_type   VARCHAR NOT NULL,
    headers      JSONB NOT NULL,         -- includes correlationId
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ             -- NULL until relayed
);
