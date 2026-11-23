#!/usr/bin/env bash
# INC-02: upstream sends yesterday's exact file again.
cd "$(dirname "$0")/.."
DATE=$(date +%F)
bash scripts/drop_file.sh --rows 200 --date "$DATE"
FILE=$(ls -t outbox/*.csv | head -1)
echo ">>> First run (normal):"
bash scripts/run_job.sh "$DATE"
echo; echo ">>> Upstream re-sends the same file and the job runs again:"
bash scripts/drop_file.sh --resend "$FILE" --date "$DATE"
bash scripts/run_job.sh "$DATE"
echo ">>> Ticket: 'ELIG_DAILY_LOAD ended RC=3. Is eligibility missing?' Follow RUNBOOK.md INC-02."
