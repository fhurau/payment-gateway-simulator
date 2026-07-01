-- POSTGRES_DB env var creates the "gateway" database; the other two logical
-- databases for the remaining services are created here (one Postgres
-- container, three logical databases per DESIGN.md §2).
CREATE DATABASE processor;
CREATE DATABASE notification;
