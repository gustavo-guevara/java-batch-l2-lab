#!/usr/bin/env bash
# Is every dependency of ELIG_DAILY_LOAD up? Exit 0 = healthy, 1 = not.
set -uo pipefail
cd "$(dirname "$0")/.."
FAIL=0
ok()  { printf "  [ OK ] %s\n" "$1"; }
bad() { printf "  [FAIL] %s\n" "$1"; FAIL=1; }
q()   { PGPASSWORD="${DB_PASSWORD:-batch}" psql -h 127.0.0.1 -U "${DB_USER:-batch}" -d eligibility -tAc "$1" 2>/dev/null; }

[ -n "${AWS_ENDPOINT_URL:-}" ] || { echo "AWS_ENDPOINT_URL not set (source scripts/env.sh)"; exit 1; }

echo "1) S3 (AWS Emulator)"
if aws sts get-caller-identity >/dev/null 2>&1; then ok "emulator reachable"
  aws s3api head-bucket --bucket "$INBOUND_BUCKET" >/dev/null 2>&1 && ok "bucket $INBOUND_BUCKET" \
    || bad "bucket $INBOUND_BUCKET missing (bash scripts/setup.sh)"
  n=$(aws s3 ls "s3://$INBOUND_BUCKET/inbound/" 2>/dev/null | wc -l)
  echo "         files waiting in inbound/: $n"
else bad "emulator not responding (is aws-emulator running?)"; fi

echo "2) PostgreSQL"
if pg_isready -h 127.0.0.1 -p 5432 -q; then ok "server accepting connections"
  if [ "$(q "SELECT to_regclass('batch_run') IS NOT NULL")" = "t" ]; then ok "schema present"
  else bad "tables missing (bash scripts/setup.sh)"; fi
else bad "server down (sudo systemctl start postgresql)"; fi

echo "3) Jar"
[ -f target/eligibility-loader.jar ] && ok "target/eligibility-loader.jar" || bad "jar missing (mvn package)"

echo "4) Job state"
if [ $FAIL -eq 0 ]; then
  last=$(q "SELECT run_id||' '||status||' rc='||COALESCE(exit_code::text,'-')||' '||to_char(started_at,'MM-DD HH24:MI') FROM batch_run ORDER BY run_id DESC LIMIT 1")
  echo "         last run: ${last:-none}"
  stuck=$(q "SELECT count(*) FROM batch_run WHERE status='RUNNING' AND started_at < now() - interval '10 minutes'")
  [ "${stuck:-0}" -gt 0 ] && printf "  [WARN] %s run(s) stuck in RUNNING > 10 min (RUNBOOK INC-05)\n" "$stuck"
  flock -n /tmp/elig_daily_load.lock true 2>/dev/null && echo "         lock: free" || echo "         lock: HELD (a run is active)"
fi

echo
[ $FAIL -eq 0 ] && echo "HEALTHY" || echo "UNHEALTHY"
exit $FAIL
