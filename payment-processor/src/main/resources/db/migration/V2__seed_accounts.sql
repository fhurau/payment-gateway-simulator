-- Demo accounts so payments work immediately, zero manual setup.
INSERT INTO accounts (account_id, balance, currency) VALUES
    ('jpy-funded',  1000000.0000, 'JPY'), -- well-funded JPY account
    ('jpy-low',           10.0000, 'JPY'), -- near-empty, demos insufficient funds
    ('usd-funded',    5000.0000, 'USD');
