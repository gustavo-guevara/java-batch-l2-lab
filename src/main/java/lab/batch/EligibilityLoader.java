package lab.batch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Daily eligibility load:
 * S3 inbound file -> validate each row -> upsert into PostgreSQL -> archive file.
 * Every run is recorded in batch_run, every bad row in load_error.
 */
final class EligibilityLoader {

    private static final String UPSERT = """
            INSERT INTO member_eligibility
              (member_id, first_name, last_name, dob, plan_code, coverage_start, coverage_end,
               last_run_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (member_id) DO UPDATE SET
              first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
              dob = EXCLUDED.dob, plan_code = EXCLUDED.plan_code,
              coverage_start = EXCLUDED.coverage_start, coverage_end = EXCLUDED.coverage_end,
              last_run_id = EXCLUDED.last_run_id, updated_at = now()
            """;

    private record Reject(int lineNo, String raw, String reason) {}

    private final String businessDate;
    private final String key;
    private Connection db;
    private long runId = -1;

    EligibilityLoader(String businessDate) {
        this.businessDate = businessDate;
        this.key = Config.fileKey(businessDate);
    }

    ExitCode run() {
        Log.info("JOB START job=%s business_date=%s file=s3://%s/%s",
                Config.JOB_NAME, businessDate, Config.BUCKET, key);

        // 1. Database first: without it we cannot even record the run.
        try {
            db = DriverManager.getConnection(Config.DB_URL, Config.DB_USER, Config.DB_PASSWORD);
        } catch (SQLException e) {
            Log.error("DB connection failed sqlstate=%s msg=%s", e.getSQLState(), e.getMessage());
            return ExitCode.DB_UNAVAILABLE;
        }

        // Not try-with-resources: the catch blocks below still need the open
        // connection to mark the run FAILED, so we close in finally.
        S3Client s3 = null;
        try {
            s3 = s3Client();
            // 2. Fetch the file.
            byte[] content;
            try {
                content = s3.getObjectAsBytes(b -> b.bucket(Config.BUCKET).key(key)).asByteArray();
            } catch (NoSuchKeyException e) {
                return finish(recordRun("FAILED", null), ExitCode.FILE_NOT_FOUND,
                        "file not found: s3://" + Config.BUCKET + "/" + key);
            } catch (S3Exception e) {
                return finish(recordRun("FAILED", null), ExitCode.S3_UNAVAILABLE,
                        "S3 error " + e.awsErrorDetails().errorCode() + ": " + e.getMessage());
            } catch (SdkClientException e) {
                return finish(recordRun("FAILED", null), ExitCode.S3_UNAVAILABLE,
                        "cannot reach S3 endpoint: " + e.getMessage());
            }
            String checksum = sha256(content);
            Log.info("file downloaded bytes=%d sha256=%s", content.length, checksum.substring(0, 12));

            // 3. Same content already loaded? Then this is a resend: skip it.
            Long previous = previousSuccessfulRun(checksum);
            if (previous != null) {
                return finish(recordRun("SKIPPED", checksum), ExitCode.DUPLICATE_FILE,
                        "identical file already loaded by run_id=" + previous);
            }

            // 4. Load inside one transaction.
            recordRun("RUNNING", checksum);
            Log.info("run_id=%d status=RUNNING", runId);
            return load(new String(content, StandardCharsets.UTF_8), s3);

        } catch (SQLException e) {
            ExitCode code = e.getSQLState() != null && e.getSQLState().startsWith("08")
                    ? ExitCode.DB_UNAVAILABLE : ExitCode.UNEXPECTED;
            Log.error("SQL error sqlstate=%s msg=%s", e.getSQLState(), e.getMessage());
            safeMarkFailed(code, e.getMessage());
            return code;
        } catch (Exception e) {
            Log.error("unexpected %s: %s", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace(System.out);
            safeMarkFailed(ExitCode.UNEXPECTED, e.toString());
            return ExitCode.UNEXPECTED;
        } finally {
            if (s3 != null) s3.close();
            try { db.close(); } catch (SQLException ignored) { }
        }
    }

    private ExitCode load(String text, S3Client s3) throws Exception {
        String[] lines = text.split("\\R");
        int start = 0;
        if (lines.length > 0 && lines[0].trim().equals(RecordValidator.HEADER)) {
            start = 1;
        } else {
            Log.warn("header missing or unexpected: '%s'", lines.length > 0 ? lines[0] : "");
        }

        db.setAutoCommit(false);
        List<Reject> rejects = new ArrayList<>();
        int read = 0, loaded = 0;
        try (PreparedStatement up = db.prepareStatement(UPSERT)) {
            for (int i = start; i < lines.length; i++) {
                String line = lines[i];
                if (line.isBlank()) continue;
                read++;
                RecordValidator.Result r = RecordValidator.parse(line);
                if (!r.ok()) {
                    rejects.add(new Reject(i + 1, line, r.reason()));
                    continue;
                }
                var m = r.member();
                up.setString(1, m.memberId());
                up.setString(2, m.firstName());
                up.setString(3, m.lastName());
                up.setDate(4, Date.valueOf(m.dob()));
                up.setString(5, m.planCode());
                up.setDate(6, Date.valueOf(m.coverageStart()));
                up.setDate(7, Date.valueOf(m.coverageEnd()));
                up.setLong(8, runId);
                up.addBatch();
                loaded++;
                if (loaded % 500 == 0) {
                    up.executeBatch();
                    Log.info("progress rows_read=%d rows_loaded=%d", read, loaded);
                }
                if (Config.SLOW_MS > 0) Thread.sleep(Config.SLOW_MS);
            }
            up.executeBatch();
        }

        double ratio = read == 0 ? 1.0 : (double) rejects.size() / read;
        Log.info("validation rows_read=%d rows_valid=%d rows_rejected=%d reject_ratio=%.1f%% limit=%.0f%%",
                read, loaded, rejects.size(), ratio * 100, Config.MAX_REJECT_RATIO * 100);

        if (read == 0 || ratio > Config.MAX_REJECT_RATIO) {
            db.rollback();
            db.setAutoCommit(true);
            saveRejects(rejects);
            updateCounts(read, 0, rejects.size());
            return finish(runId, ExitCode.REJECT_THRESHOLD, read == 0
                    ? "file has no data rows"
                    : String.format("reject ratio %.1f%% over limit, nothing loaded", ratio * 100));
        }

        saveRejects(rejects);
        updateCounts(read, loaded, rejects.size());
        String status = rejects.isEmpty() ? "SUCCESS" : "SUCCESS_WITH_ERRORS";
        setStatus(status, ExitCode.SUCCESS.code, rejects.isEmpty()
                ? "all rows loaded" : rejects.size() + " rows rejected, see load_error");
        db.commit();
        db.setAutoCommit(true);
        Log.info("run_id=%d status=%s committed", runId, status);
        if (!rejects.isEmpty()) {
            Log.warn("%d rows rejected -> SELECT * FROM load_error WHERE run_id=%d;", rejects.size(), runId);
        }

        archive(s3);
        return ExitCode.SUCCESS;
    }

    /** Move the processed file to archive/ so tomorrow starts clean. */
    private void archive(S3Client s3) {
        String target = key.replace("inbound/", "archive/run" + runId + "_");
        try {
            s3.copyObject(b -> b.sourceBucket(Config.BUCKET).sourceKey(key)
                    .destinationBucket(Config.BUCKET).destinationKey(target));
            s3.deleteObject(b -> b.bucket(Config.BUCKET).key(key));
            Log.info("file archived to s3://%s/%s", Config.BUCKET, target);
        } catch (Exception e) {
            // Data is already committed; archiving can be redone by hand.
            Log.warn("archive failed (data is committed, move the file manually): %s", e.getMessage());
        }
    }

    // ---------- batch_run bookkeeping ----------

    private long recordRun(String status, String checksum) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO batch_run (job_name, business_date, file_key, file_checksum, status, host, pid)
                VALUES (?, ?::date, ?, ?, ?, ?, ?) RETURNING run_id""")) {
            ps.setString(1, Config.JOB_NAME);
            ps.setString(2, businessDate);
            ps.setString(3, key);
            ps.setString(4, checksum);
            ps.setString(5, status);
            ps.setString(6, System.getenv().getOrDefault("HOSTNAME", "unknown"));
            ps.setLong(7, ProcessHandle.current().pid());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                runId = rs.getLong(1);
            }
        }
        return runId;
    }

    private Long previousSuccessfulRun(String checksum) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT run_id FROM batch_run
                WHERE file_checksum = ? AND status IN ('SUCCESS','SUCCESS_WITH_ERRORS')
                ORDER BY run_id DESC LIMIT 1""")) {
            ps.setString(1, checksum);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void saveRejects(List<Reject> rejects) throws SQLException {
        if (rejects.isEmpty()) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO load_error (run_id, line_no, raw_line, reason) VALUES (?, ?, ?, ?)")) {
            for (Reject r : rejects) {
                ps.setLong(1, runId);
                ps.setInt(2, r.lineNo());
                ps.setString(3, r.raw());
                ps.setString(4, r.reason());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void updateCounts(int read, int loaded, int rejected) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE batch_run SET rows_read=?, rows_loaded=?, rows_rejected=? WHERE run_id=?")) {
            ps.setInt(1, read);
            ps.setInt(2, loaded);
            ps.setInt(3, rejected);
            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }

    private void setStatus(String status, int exitCode, String message) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                UPDATE batch_run SET status=?, exit_code=?, message=?, ended_at=now()
                WHERE run_id=?""")) {
            ps.setString(1, status);
            ps.setInt(2, exitCode);
            ps.setString(3, message);
            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }

    /** Close out a run that ended without loading data. */
    private ExitCode finish(long id, ExitCode code, String message) throws SQLException {
        runId = id;
        String status = code == ExitCode.DUPLICATE_FILE ? "SKIPPED" : "FAILED";
        setStatus(status, code.code, message);
        if (code == ExitCode.DUPLICATE_FILE) Log.warn("%s", message);
        else Log.error("%s", message);
        return code;
    }

    private void safeMarkFailed(ExitCode code, String message) {
        if (runId < 0 || db == null) return;
        try {
            if (!db.getAutoCommit()) {
                db.rollback();
                db.setAutoCommit(true);
            }
            setStatus("FAILED", code.code, message);
        } catch (SQLException ignored) {
            Log.warn("could not update batch_run run_id=%d; it may show RUNNING", runId);
        }
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(Config.localEndpointOrExit())
                .forcePathStyle(true)
                .region(Region.of(Config.REGION))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
