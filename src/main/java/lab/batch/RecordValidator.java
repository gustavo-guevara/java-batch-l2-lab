package lab.batch;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/** Business rules for one eligibility row. Returns null when valid, or the reason. */
final class RecordValidator {
    static final String HEADER =
            "member_id,first_name,last_name,dob,plan_code,coverage_start,coverage_end";
    static final int COLUMNS = 7;
    private static final Pattern MEMBER_ID = Pattern.compile("MBR-\\d{6}");
    static final Set<String> PLANS = Set.of("HMO01", "PPO01", "EPO01", "HDHP1");

    private RecordValidator() {}

    record Member(String memberId, String firstName, String lastName, LocalDate dob,
                  String planCode, LocalDate coverageStart, LocalDate coverageEnd) {}

    /** Result: either a member or a reject reason. */
    record Result(Member member, String reason) {
        boolean ok() { return member != null; }
    }

    static Result parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length != COLUMNS) {
            return reject("expected " + COLUMNS + " columns, found " + f.length);
        }
        for (int i = 0; i < f.length; i++) f[i] = f[i].trim();

        if (!MEMBER_ID.matcher(f[0]).matches()) return reject("invalid member_id '" + f[0] + "'");
        if (f[1].isEmpty() || f[2].isEmpty()) return reject("missing first or last name");
        if (!PLANS.contains(f[4])) return reject("unknown plan_code '" + f[4] + "'");

        LocalDate dob, start, end;
        try {
            dob = LocalDate.parse(f[3]);
            start = LocalDate.parse(f[5]);
            end = LocalDate.parse(f[6]);
        } catch (DateTimeParseException e) {
            return reject("bad date (expected YYYY-MM-DD): " + e.getParsedString());
        }
        if (dob.isAfter(LocalDate.now())) return reject("dob in the future");
        if (end.isBefore(start)) return reject("coverage_end before coverage_start");

        return new Result(new Member(f[0], f[1], f[2], dob, f[4], start, end), null);
    }

    private static Result reject(String reason) {
        return new Result(null, reason);
    }
}
