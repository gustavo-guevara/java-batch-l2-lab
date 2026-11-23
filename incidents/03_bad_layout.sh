#!/usr/bin/env bash
# INC-03: upstream changed the delimiter from comma to semicolon without notice.
cd "$(dirname "$0")/.."
DATE=$(date +%F)
bash scripts/drop_file.sh --rows 200 --layout semicolon --date "$DATE"
bash scripts/run_job.sh "$DATE"
echo ">>> Ticket: 'ELIG_DAILY_LOAD failed RC=4.' Follow RUNBOOK.md INC-03."
