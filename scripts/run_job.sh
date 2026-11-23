#!/usr/bin/env bash
# Scheduler entry point for ELIG_DAILY_LOAD (what cron / Workload Automation calls).
#   bash scripts/run_job.sh               business date = today
#   bash scripts/run_job.sh 2026-11-23    a specific business date
# Exit code = the job's exit code (see RUNBOOK.md), or 7 if another run is active.
set -uo pipefail
cd "$(dirname "$0")/.."
: "${AWS_ENDPOINT_URL:?run: source scripts/env.sh}"

DATE="${1:-$(date +%F)}"
mkdir -p logs
LOG="logs/elig_load_${DATE//-/}_$(date +%H%M%S).log"
JAR=target/eligibility-loader.jar
ts() { date '+%F %T'; }

meaning() {
  case "$1" in
    0) echo "success" ;;           1) echo "unexpected error" ;;
    2) echo "file not found" ;;    3) echo "duplicate file, skipped" ;;
    4) echo "reject threshold exceeded, rolled back" ;;
    5) echo "database unavailable" ;; 6) echo "S3 / emulator unavailable" ;;
    7) echo "already running (lock held)" ;;
    137) echo "killed (SIGKILL, often out of memory or manual kill)" ;;
    *) echo "unknown" ;;
  esac
}

[ -f "$JAR" ] || { echo "$(ts) ERROR $JAR missing, run: mvn package"; exit 1; }

# Only one run at a time. The lock is released automatically if this script dies.
exec 9>/tmp/elig_daily_load.lock
if ! flock -n 9; then
  echo "$(ts) ERROR another ELIG_DAILY_LOAD run holds the lock" | tee -a "$LOG"
  echo "$(ts) WRAPPER rc=7 meaning=\"$(meaning 7)\"" | tee -a "$LOG"
  exit 7
fi

# Java needs the emulator's certificate in a truststore. The emulator creates a
# new certificate on every start, so rebuild the truststore when it changes.
TS="$HOME/.cache/batch-lab/emulator-truststore.p12"
if [ -f "$AWS_CA_BUNDLE" ] && { [ ! -f "$TS" ] || [ "$AWS_CA_BUNDLE" -nt "$TS" ]; }; then
  mkdir -p "$(dirname "$TS")" && rm -f "$TS"
  keytool -importcert -noprompt -alias aws-emulator -file "$AWS_CA_BUNDLE" \
    -keystore "$TS" -storetype PKCS12 -storepass changeit >/dev/null 2>&1
fi
JAVA_OPTS="-Xmx256m"
[ -f "$TS" ] && JAVA_OPTS+=" -Djavax.net.ssl.trustStore=$TS -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"

echo "$(ts) WRAPPER start job=ELIG_DAILY_LOAD date=$DATE log=$LOG" | tee -a "$LOG"
# 9>&- : the Java process must not inherit the lock.
java $JAVA_OPTS -jar "$JAR" load --date "$DATE" 9>&- 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
echo "$(ts) WRAPPER rc=$RC meaning=\"$(meaning "$RC")\"" | tee -a "$LOG"
exit "$RC"
