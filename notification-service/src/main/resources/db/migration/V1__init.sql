CREATE TABLE consumed_events (
    event_id    UUID PRIMARY KEY,
    event_type  VARCHAR NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    event_id   UUID NOT NULL,
    channel    VARCHAR NOT NULL,
    status     VARCHAR NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
