-- Lets the relay park a row that repeatedly fails to publish (§8): rows with
-- failed_attempts >= the relay's cap are excluded from the poll so one poison row
-- can't head-of-line-block every event created after it.
ALTER TABLE outbox ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;
