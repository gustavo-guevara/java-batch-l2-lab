#!/usr/bin/env bash
# INC-01: the upstream file did not arrive before the 02:00 run.
cd "$(dirname "$0")/.."
DATE=$(date +%F)
aws s3 rm "s3://$INBOUND_BUCKET/inbound/eligibility_${DATE//-/}.csv" >/dev/null 2>&1
echo ">>> Ticket: 'ELIG_DAILY_LOAD abended RC=2 at 02:00. Eligibility not updated.'"
bash scripts/run_job.sh "$DATE"
echo ">>> Now follow RUNBOOK.md INC-01."
