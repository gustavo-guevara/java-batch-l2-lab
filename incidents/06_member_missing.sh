#!/usr/bin/env bash
# INC-06: the job succeeded, but some rows were rejected. A member calls in.
cd "$(dirname "$0")/.."
DATE=$(date +%F)
bash scripts/drop_file.sh --rows 300 --bad 9 --date "$DATE"
bash scripts/run_job.sh "$DATE"
MEMBER=$(bash scripts/sql.sh -tAc "SELECT split_part(raw_line, ',', 1) FROM load_error
          WHERE raw_line LIKE 'MBR-%' ORDER BY error_id DESC LIMIT 1")
echo
echo ">>> Ticket: 'Member ${MEMBER:-<none, re-run this script>} says the portal shows no active coverage.'"
echo ">>> The job ended RC=0. Follow RUNBOOK.md INC-06."
