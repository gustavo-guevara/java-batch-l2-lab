# Runbook: ELIG_DAILY_LOAD

Daily job that loads the member eligibility file from S3 into PostgreSQL.
Scheduled at 02:00 (`scheduler/crontab.example`). Entry point: `scripts/run_job.sh`.

## Exit codes

| RC | Meaning | Data changed? | First action |
|---|---|---|---|
| 0 | Loaded (status `SUCCESS` or `SUCCESS_WITH_ERRORS`) | Yes | If rows were rejected, see INC-06 |
| 1 | Unexpected error | No (rolled back) | Read the stack trace in the log, escalate to dev |
| 2 | File not found | No | INC-01 |
| 3 | Duplicate file, skipped | No | INC-02 |
| 4 | Too many bad rows, rolled back | No | INC-03 |
| 5 | Database unavailable | No | INC-04 |
| 6 | S3 / emulator unavailable | No | `bash scripts/healthcheck.sh` |
| 7 | Another run holds the lock | No | INC-05 |
| 137 | Process killed (`kill -9`, out of memory) | No (rolled back) | INC-05 |

## Triage order

```bash
bash scripts/healthcheck.sh               # 1. are S3, DB and the jar OK?
bash scripts/sql.sh -f sql/triage.sql     # 2. last runs, stuck runs, reject reasons
ls -t logs/ | head -3                     # 3. newest job logs
grep -E "ERROR|WARN|WRAPPER" logs/elig_load_*.log | tail -20
```

The single most useful query:

```sql
SELECT run_id, status, exit_code, rows_read, rows_loaded, rows_rejected, message
FROM batch_run ORDER BY run_id DESC LIMIT 5;
```

---

## INC-01: file not found (RC=2)

**Check:** is the file really missing, or is it under another name?

```bash
aws s3 ls s3://inbound-files/inbound/
aws s3 ls s3://inbound-files/archive/ | tail -3     # already processed earlier?
```

**Root cause:** upstream did not deliver (or used a different name/date).
**Fix:** contact the upstream team with the expected key `inbound/eligibility_YYYYMMDD.csv`.
When the file arrives, rerun: `bash scripts/run_job.sh`.
**Don't:** create an empty file to make the job green.

## INC-02: duplicate file (RC=3)

**Check:**

```sql
SELECT run_id, status, exit_code, file_checksum, message
FROM batch_run ORDER BY run_id DESC LIMIT 3;
```

The `SKIPPED` run points to the earlier run that loaded the identical file (same SHA-256).
**Root cause:** upstream resent a file that was already processed.
**Fix:** nothing to reload; the data is already there. Confirm with upstream whether a
*new* file was expected. Close as "no impact". This check is what prevents double loads.

## INC-03: reject threshold exceeded (RC=4)

**Check:**

```sql
SELECT reason, count(*) FROM load_error
WHERE run_id = (SELECT max(run_id) FROM batch_run)
GROUP BY reason ORDER BY 2 DESC;
```
```bash
aws s3 cp s3://inbound-files/inbound/eligibility_$(date +%Y%m%d).csv - | head -3
```

**Root cause:** every row fails with `expected 7 columns, found 1`: the file uses `;`
instead of `,`. A layout change upstream.
**Why nothing loaded:** over 10% bad rows means the file is not trustworthy, so the
transaction is rolled back. Partial loads of a broken file are worse than none.
**Fix:** upstream resends a correct file, then rerun:

```bash
bash scripts/drop_file.sh --rows 200
bash scripts/run_job.sh
```

## INC-04: database unavailable (RC=5)

**Check:**

```bash
pg_isready -h 127.0.0.1 -p 5432
sudo systemctl status postgresql --no-pager | head -5
sudo journalctl -u postgresql --since "1 hour ago" --no-pager | tail -20
df -h /var/lib/postgresql     # full disk is a classic cause
```

**Fix:** bring the database back (in production: DBA team), then rerun.
The file is still in `inbound/` because the job never got that far.

```bash
sudo systemctl start postgresql
bash scripts/healthcheck.sh
bash scripts/run_job.sh
```

## INC-05: stuck RUNNING / lock / killed job (RC=7, RC=137)

**Check:**

```sql
SELECT run_id, status, pid, host, started_at, now() - started_at AS age
FROM batch_run WHERE status = 'RUNNING';
```
```bash
ps -p <pid> -o pid,etime,cmd || echo "process is gone"
grep WRAPPER logs/elig_load_*.log | tail -5
```

**Root cause:** the first run was slow, the scheduler started it again (RC=7, the lock
protected us), then someone killed the first run with `kill -9` (RC=137).
The process died before it could update `batch_run`, so the row says RUNNING forever.
**What happened to the data:** nothing. The rows were inside an uncommitted
transaction, and PostgreSQL rolls it back when the connection dies.
**Fix:** confirm the PID is gone, close the orphan row, rerun:

```sql
UPDATE batch_run
SET status = 'FAILED', exit_code = 137, ended_at = now(),
    message = 'killed; closed manually by L2, ticket INC-05'
WHERE status = 'RUNNING' AND run_id = <run_id>;
```
```bash
bash scripts/run_job.sh
```

**Never** mark a row FAILED while its PID is still alive.

## INC-06: member says coverage is missing, job was green (RC=0)

**Check:** use the member ID from the ticket (query Q6 in `sql/triage.sql`):

```sql
SELECT * FROM member_eligibility WHERE member_id = 'MBR-123456';
SELECT run_id, line_no, reason, raw_line FROM load_error
WHERE raw_line LIKE 'MBR-123456,%';
```

**Root cause:** the row was rejected (bad date, unknown plan...). The job is still green
because 3% bad rows is under the 10% threshold.
**Fix:** not L2's to change: send the business/upstream team the line number, reason
and raw line. When they correct it, it arrives in the next file.
**Prevent:** alert when `rows_rejected > 0`, not only when RC is non-zero.
A green job is not the same as good data.

---

## Closing a ticket

Impact (which run, how many rows/members, how long) · root cause · fix · prevention.
