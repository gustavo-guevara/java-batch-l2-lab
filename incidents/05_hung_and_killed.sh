#!/usr/bin/env bash
# INC-05: a slow run overlaps the next one, then gets killed with kill -9.
cd "$(dirname "$0")/.."
DATE=$(date +%F)
bash scripts/drop_file.sh --rows 400 --date "$DATE"
echo ">>> Starting a slow run in the background (about 40 s)..."
SLOW_MS=100 bash scripts/run_job.sh "$DATE" > /tmp/inc05_first_run.out 2>&1 &
sleep 8
echo ">>> The scheduler starts the job again while the first is still running:"
bash scripts/run_job.sh "$DATE"
echo; echo ">>> Someone kills the stuck job with kill -9:"
pkill -9 -f "eligibility-loader.jar load" && sleep 2
tail -n 3 /tmp/inc05_first_run.out
echo ">>> Ticket: 'ELIG_DAILY_LOAD shows RUNNING for hours, nothing new loaded.' Follow RUNBOOK.md INC-05."
