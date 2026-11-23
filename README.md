# Java Batch L2 Support Lab

A nightly batch job, the way production support sees it: a **Java** loader that reads a
member eligibility file from **S3**, validates it, loads it into **PostgreSQL**, and is
run by a **Linux** scheduler that only understands exit codes.

Then you break it in six realistic ways and fix it with a runbook and SQL.

Runs entirely on your machine: **AWS Emulator** (AWS's official local emulator) inside a
**Multipass** Ubuntu VM. No AWS account, no cloud bill.

> All data is synthetic. Names, member IDs and dates are randomly generated.

## How it works

```
 upstream system                 ELIG_DAILY_LOAD (02:00, cron)
 ───────────────                 ──────────────────────────────────────────────
 drop_file.sh ──► S3 inbound/ ──► run_job.sh (lock, logs, exit code)
                                    └─► Java loader
                                          1. connect to PostgreSQL
                                          2. download file, SHA-256 it
                                          3. already loaded? ─────────► RC=3 skip
                                          4. validate each row
                                          5. upsert valid rows  ┐ one
                                          6. >10% bad? rollback ┘ transaction ► RC=4
                                          7. commit, write audit rows
                                          8. move file to S3 archive/ ──► RC=0
```

| Table | Purpose |
|---|---|
| `batch_run` | One row per execution: status, exit code, counts, timing, PID. The audit trail. |
| `member_eligibility` | Current coverage per member (upsert by `member_id`). |
| `load_error` | Every rejected row with line number, raw text and reason. |

| Path | What it is |
|---|---|
| `src/main/java/lab/batch/` | Loader, validator and test-file generator (Java 17, AWS SDK v2, JDBC) |
| `sql/schema.sql` | Tables, constraints, partial unique index that blocks double loads |
| `sql/triage.sql` | The queries L2 runs first: last runs, stuck runs, reject reasons, member lookup |
| `scripts/run_job.sh` | Scheduler entry point: `flock` lock, per-run log file, exit code mapping |
| `scripts/healthcheck.sh` | S3, PostgreSQL, jar and job state in one command |
| `incidents/` | Six scripts that reproduce production incidents |
| `RUNBOOK.md` | Exit code table and the fix for each incident |

## Setup

Uses the same Multipass VM as [aws-emulator-l2-lab](https://github.com/gustavo-guevara/aws-emulator-l2-lab)
(any Ubuntu 24.04 VM works).

**On your Mac**, mount this folder into the VM:

```bash
multipass mount "$PWD" aws-lab:/home/ubuntu/batch
```

On macOS, a folder under Desktop, Documents or Downloads needs Full Disk Access
for `multipassd` (System Settings → Privacy & Security).

**Inside the VM** (`multipass shell aws-lab`):

```bash
cd ~/batch
bash scripts/install.sh          # Java 21, Maven, PostgreSQL; builds the jar
```

**Run it** with two terminals:

```bash
# Terminal 1
aws-emulator

# Terminal 2
cd ~/batch
source scripts/env.sh
bash scripts/setup.sh            # database, tables, S3 bucket
bash scripts/healthcheck.sh      # should end in HEALTHY
```

## Happy path

```bash
bash scripts/drop_file.sh --rows 200     # upstream delivers today's file
bash scripts/run_job.sh                  # the scheduler runs the job
bash scripts/sql.sh -f sql/triage.sql    # check the result like L2 would
```

## Incidents

| ID | Inject | Exit code | Ticket says |
|---|---|---|---|
| INC-01 | `bash incidents/01_file_not_arrived.sh` | 2 | "Job abended at 02:00" |
| INC-02 | `bash incidents/02_duplicate_resend.sh` | 3 | "Job ended RC=3, is data missing?" |
| INC-03 | `bash incidents/03_bad_layout.sh` | 4 | "Job failed, nothing loaded" |
| INC-04 | `bash incidents/04_db_down.sh` | 5 | "Job failed RC=5" |
| INC-05 | `bash incidents/05_hung_and_killed.sh` | 7, 137 | "Job shows RUNNING for hours" |
| INC-06 | `bash incidents/06_member_missing.sh` | 0 | "Member has no coverage" (job was green) |

Diagnose each one with `healthcheck.sh`, `triage.sql` and the logs before reading
[RUNBOOK.md](RUNBOOK.md).

## What this demonstrates

- Reading a batch failure from its **exit code**, the way a scheduler (cron, Control-M,
  Workload Automation) does
- **SQL triage** on an audit table: last runs, stuck runs, reject reasons, member lookup
- **Idempotency**: SHA-256 duplicate detection plus a partial unique index
- **Transactions**: all-or-nothing loads, and why a killed job leaves no partial data
- **Linux ops**: `flock` locking, per-run logs, `systemctl`, `journalctl`, `pg_isready`, `kill -9`
- Separating **data problems** (escalate to the source) from **technical problems** (fix and rerun)
- Knowing that a green job does not mean good data

## Notes

- The emulator keeps S3 in memory; after restarting it, run `source scripts/env.sh`
  and `bash scripts/setup.sh` again. PostgreSQL keeps its data.
- Java trusts the emulator's certificate through a truststore that `run_job.sh`
  rebuilds whenever the emulator certificate changes.
- AWS Emulator docs: https://docs.aws.amazon.com/emulator/
