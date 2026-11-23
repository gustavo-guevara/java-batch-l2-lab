#!/usr/bin/env bash
# INC-04: the database is down (maintenance overran, disk full, crash...).
cd "$(dirname "$0")/.."
DATE=$(date +%F)
bash scripts/drop_file.sh --rows 200 --date "$DATE"
sudo systemctl stop postgresql
bash scripts/run_job.sh "$DATE"
echo ">>> Ticket: 'ELIG_DAILY_LOAD failed RC=5.' Follow RUNBOOK.md INC-04."
echo ">>> (PostgreSQL is now STOPPED on purpose.)"
