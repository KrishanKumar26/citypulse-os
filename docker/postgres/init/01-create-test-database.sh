#!/bin/bash
# Creates the database used by the backend integration test suite.
#
# Integration tests run against real PostgreSQL rather than an in-memory
# substitute, because the schema relies on partial unique indexes, check
# constraints and TIMESTAMPTZ semantics that H2 does not reproduce — a test
# passing on H2 would prove nothing about production behaviour.
#
# Runs only on first initialisation of an empty data volume.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE citypulse_test OWNER $POSTGRES_USER;
EOSQL

echo "Created citypulse_test database for the integration test suite."
