package lab.batch;

/**
 * Exit codes the scheduler (cron, systemd, Workload Automation...) sees.
 * Each one maps to a section in RUNBOOK.md.
 */
public enum ExitCode {
    SUCCESS(0, "Loaded (check rows_rejected for partial rejects)"),
    UNEXPECTED(1, "Unexpected error, see stack trace in the log"),
    FILE_NOT_FOUND(2, "Inbound file not found for the business date"),
    DUPLICATE_FILE(3, "Same file already loaded, run skipped"),
    REJECT_THRESHOLD(4, "Too many invalid rows, load rolled back"),
    DB_UNAVAILABLE(5, "Cannot connect to the database"),
    S3_UNAVAILABLE(6, "Cannot reach S3 / AWS Emulator");
    // 7 = already running: raised by scripts/run_job.sh (lock), never by Java.

    public final int code;
    public final String meaning;

    ExitCode(int code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }
}