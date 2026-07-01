CREATE TABLE accounts (
    account_id VARCHAR PRIMARY KEY,
    balance    NUMERIC(19,4) NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payments (
    id             UUID PRIMARY KEY,
    status         VARCHAR NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    from_account   VARCHAR NOT NULL,
    to_account     VARCHAR NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    failure_reason VARCHAR,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    account_id VARCHAR NOT NULL,
    direction  VARCHAR NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount     NUMERIC(19,4) NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE consumed_events (
    event_id    UUID PRIMARY KEY,
    event_type  VARCHAR NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
