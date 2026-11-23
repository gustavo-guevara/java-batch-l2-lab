#!/usr/bin/env bash
# Play the upstream system: generate an eligibility file and drop it in S3.
#   bash scripts/drop_file.sh                       200 good rows, today
#   bash scripts/drop_file.sh --rows 500 --bad 10
#   bash scripts/drop_file.sh --layout semicolon    wrong delimiter
#   bash scripts/drop_file.sh --date 2026-11-24
#   bash scripts/drop_file.sh --resend FILE         upload an existing file again
set -euo pipefail
cd "$(dirname "$0")/.."
: "${AWS_ENDPOINT_URL:?run: source scripts/env.sh}"

ROWS=200 BAD=0 LAYOUT=comma DATE="$(date +%F)" RESEND=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rows) ROWS="$2"; shift 2 ;;   --bad) BAD="$2"; shift 2 ;;
    --layout) LAYOUT="$2"; shift 2 ;; --date) DATE="$2"; shift 2 ;;
    --resend) RESEND="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 1 ;;
  esac
done

KEY="inbound/eligibility_${DATE//-/}.csv"
mkdir -p outbox
if [ -n "$RESEND" ]; then
  FILE="$RESEND"
else
  FILE="outbox/eligibility_${DATE//-/}_$(date +%H%M%S).csv"
  java -jar target/eligibility-loader.jar generate --rows "$ROWS" --bad "$BAD" --layout "$LAYOUT" --out "$FILE"
fi
aws s3 cp "$FILE" "s3://$INBOUND_BUCKET/$KEY" >/dev/null
echo "dropped $FILE -> s3://$INBOUND_BUCKET/$KEY"
