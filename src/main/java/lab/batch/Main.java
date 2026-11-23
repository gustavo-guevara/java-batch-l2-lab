package lab.batch;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Usage:
 *   java -jar eligibility-loader.jar load [--date YYYY-MM-DD]
 *   java -jar eligibility-loader.jar generate --rows N [--bad N] [--layout comma|semicolon] --out FILE
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";
        switch (cmd) {
            case "load" -> {
                String date = opt(args, "--date", LocalDate.now().toString());
                ExitCode code = new EligibilityLoader(date).run();
                Log.info("JOB END exit_code=%d (%s) meaning=\"%s\"", code.code, code, code.meaning);
                System.exit(code.code);
            }
            case "generate" -> FileGenerator.generate(
                    Integer.parseInt(opt(args, "--rows", "100")),
                    Integer.parseInt(opt(args, "--bad", "0")),
                    opt(args, "--layout", "comma"),
                    Path.of(opt(args, "--out", "eligibility.csv")));
            default -> {
                System.out.println("""
                        usage:
                          load [--date YYYY-MM-DD]
                          generate --rows N [--bad N] [--layout comma|semicolon] --out FILE""");
                System.exit(1);
            }
        }
    }

    private static String opt(String[] args, String name, String fallback) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return fallback;
    }
}
