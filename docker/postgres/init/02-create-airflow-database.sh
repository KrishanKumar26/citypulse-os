#!/bin/bash
# Creates the database Airflow keeps its own metadata in.
#
# Separate from the application database on purpose: Airflow runs its own
# schema migrations on startup, and Flyway owns the application schema. Sharing
# one database would let the two migration systems collide over the same
# search_path, and an Airflow upgrade could then fail — or worse, succeed — in
# ways that touch application tables.
#
# Created here rather than by the Airflow container so it exists before the
# scheduler's first connection attempt, whichever order compose starts them in.
#
# Runs only on first initialisation of an empty data volume.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE citypulse_airflow OWNER $POSTGRES_USER;
EOSQL

echo "Created citypulse_airflow database for the orchestration profile."
