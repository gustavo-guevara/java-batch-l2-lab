#!/usr/bin/env bash
# Open psql on the eligibility database. Extra arguments go to psql.
#   bash scripts/sql.sh                       interactive (\q to exit)
#   bash scripts/sql.sh -f sql/triage.sql     run a file
#   bash scripts/sql.sh -c "SELECT ..."       run one query
PGPASSWORD="${DB_PASSWORD:-batch}" exec psql -h 127.0.0.1 -U "${DB_USER:-batch}" -d eligibility "$@"
