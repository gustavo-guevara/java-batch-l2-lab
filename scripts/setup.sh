#!/usr/bin/env bash
# Create the database, tables and S3 bucket. Safe to run more than once.
# Re-run after every emulator restart (S3 is in-memory; PostgreSQL keeps its data).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${AWS_ENDPOINT_URL:?run: source scripts/env.sh}"

echo "==> PostgreSQL role and database"
sudo systemctl start postgresql
sudo -u postgres psql -q -tc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASSWORD';"
sudo -u postgres psql -q -tc "SELECT 1 FROM pg_database WHERE datname='eligibility'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE DATABASE eligibility OWNER $DB_USER;"

echo "==> Tables"
PGPASSWORD="$DB_PASSWORD" psql -q -h 127.0.0.1 -U "$DB_USER" -d eligibility \
  -v ON_ERROR_STOP=1 -c "SET client_min_messages=warning;" -f sql/schema.sql

echo "==> S3 bucket"
aws s3api head-bucket --bucket "$INBOUND_BUCKET" 2>/dev/null \
  || aws s3api create-bucket --bucket "$INBOUND_BUCKET" >/dev/null
echo "Setup complete."
