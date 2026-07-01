-- Supports the §12 reconciliation checks, which need the original seed
-- balance (accounts.balance mutates in place) and a payment reference on
-- consumed_events (to detect a consumed event with no payments row).
ALTER TABLE accounts ADD COLUMN opening_balance NUMERIC(19,4);
UPDATE accounts SET opening_balance = balance WHERE opening_balance IS NULL;
ALTER TABLE accounts ALTER COLUMN opening_balance SET NOT NULL;

ALTER TABLE consumed_events ADD COLUMN payment_id UUID;
