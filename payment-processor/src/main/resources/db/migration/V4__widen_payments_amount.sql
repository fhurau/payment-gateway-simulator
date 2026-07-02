-- payments records what a request *asked for*, including requests rejected as
-- INVALID_AMOUNT_SCALE for exceeding NUMERIC(19,4)'s 15 integer digits - the FAILED row
-- must be able to store that amount. Money that actually moves stays constrained:
-- ledger_entries.amount and accounts.balance remain NUMERIC(19,4), and only amounts that
-- pass validation (§4) ever reach them.
ALTER TABLE payments ALTER COLUMN amount TYPE NUMERIC;
