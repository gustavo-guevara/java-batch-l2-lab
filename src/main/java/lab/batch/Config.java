package lab.batch;

import java.net.URI;

/** All settings come from environment variables (see scripts/env.sh). */
final class Config {
    static final String JOB_NAME = "ELIG_DAILY_LOAD";
    static final String BUCKET = env("INBOUND_BUCKET", "inbound-files");
    static final String DB_URL = env("DB_URL",
            "jdbc:postgresql://127.0.0.1:5432/eligibility?connectTimeout=5&socketTimeout=60");
    static final String DB_USER = env("DB_USER", "batch");
    static final String DB_PASSWORD = env("DB_PASSWORD", "batch");
    static final String REGION = env("AWS_REGION", "us-east-1");
    /** Fail the whole load if more than this share of rows is invalid. */
    static final double MAX_REJECT_RATIO = Double.parseDouble(env("MAX_REJECT_RATIO", "0.10"));
    /** Milliseconds to sleep per row; used only to simulate a slow / hung job. */
    static final long SLOW_MS = Long.parseLong(env("SLOW_MS", "0"));

    private Config() {}

    /**
     * Refuse to run unless S3 calls go to a local emulator, so a misconfigured
     * shell can never touch a real AWS account.
     */
    static URI localEndpointOrExit() {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint == null || endpoint.isBlank()) {
            Log.error("BLOCKED: AWS_ENDPOINT_URL is not set. Run: source scripts/env.sh");
            System.exit(ExitCode.S3_UNAVAILABLE.code);
        }
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            Log.error("BLOCKED: AWS_ENDPOINT_URL points to '%s', not a local emulator", host);
            System.exit(ExitCode.S3_UNAVAILABLE.code);
        }
        return uri;
    }

    static String fileKey(String businessDate) {
        return "inbound/eligibility_" + businessDate.replace("-", "") + ".csv";
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
