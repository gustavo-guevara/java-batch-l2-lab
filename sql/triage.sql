-- L2 triage queries. Run all:   ./scripts/sql.sh -f sql/triage.sql
-- Or copy one into:              ./scripts/sql.sh

-- Q1. Last 10 runs: what happened, how long, how many rows.
SELECT run_id, business_date, status, exit_code,
       rows_read, rows_loaded, rows_rejected,
       to_char(started_at, 'MM-DD HH24:MI:SS') AS started,
       COALESCE(round(extract(epoch FROM ended_at - started_at))::text, '-') AS secs,
       left(message, 60) AS message
FROM batch_run
ORDER BY run_id DESC
LIMIT 10;

-- Q2. Did today's load succeed? (the question every morning starts with)
SELECT business_date,
       bool_or(status IN ('SUCCESS','SUCCESS_WITH_ERRORS')) AS loaded_ok,
       count(*) AS attempts,
       max(started_at) AS last_attempt
FROM batch_run
WHERE business_date = current_date
GROUP BY business_date;

-- Q3. Runs stuck in RUNNING for more than 10 minutes (killed / hung job).
SELECT run_id, business_date, host, pid, started_at,
       now() - started_at AS running_for
FROM batch_run
WHERE status = 'RUNNING' AND started_at < now() - interval '10 minutes';

-- Q4. Why were rows rejected? Grouped, worst first.
SELECT e.run_id, regexp_replace(e.reason, '''.*''', '''...''') AS reason_type,
       count(*) AS rows
FROM load_error e
GROUP BY e.run_id, reason_type
ORDER BY e.run_id DESC, rows DESC
LIMIT 20;

-- Q5. Failure history by exit code over the last 7 days.
SELECT exit_code, status, count(*) AS runs, max(started_at) AS last_seen
FROM batch_run
WHERE started_at > now() - interval '7 days' AND status <> 'SUCCESS'
GROUP BY exit_code, status
ORDER BY runs DESC;

-- Q6. Member lookup (ticket: "member says they have no coverage").
--     Replace MBR-000000 with the member from the ticket.
SELECT 'LOADED' AS source, m.member_id, m.plan_code, m.coverage_start, m.coverage_end,
       m.last_run_id AS run_id, NULL AS reason
FROM member_eligibility m
WHERE m.member_id = 'MBR-000000'
UNION ALL
SELECT 'REJECTED', split_part(e.raw_line, ',', 1), NULL, NULL, NULL, e.run_id, e.reason
FROM load_error e
WHERE e.raw_line LIKE 'MBR-000000,%';

-- Q7. Data sanity: members whose coverage already ended.
SELECT count(*) FILTER (WHERE coverage_end < current_date) AS expired,
       count(*) AS total
FROM member_eligibility;
