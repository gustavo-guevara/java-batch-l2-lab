package lab.batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Generates a synthetic eligibility file. No real people or data. */
final class FileGenerator {
    private static final String[] FIRST = {"Ana", "Luis", "Maria", "Jose", "Sofia", "Carlos",
            "Elena", "Diego", "Laura", "Miguel", "Paula", "Jorge"};
    private static final String[] LAST = {"Garcia", "Lopez", "Martinez", "Hernandez", "Perez",
            "Sanchez", "Ramirez", "Torres", "Flores", "Rivera"};
    private static final List<String> PLANS = new ArrayList<>(RecordValidator.PLANS);

    private FileGenerator() {}

    static void generate(int rows, int bad, String layout, Path out) throws IOException {
        Random rnd = new Random();
        Set<Integer> badSlots = new HashSet<>();
        while (badSlots.size() < Math.min(bad, rows)) badSlots.add(rnd.nextInt(rows));
        String sep = "semicolon".equals(layout) ? ";" : ",";

        List<String> lines = new ArrayList<>();
        lines.add(RecordValidator.HEADER.replace(",", sep));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            String id;
            do { id = String.format("MBR-%06d", rnd.nextInt(1_000_000)); } while (!ids.add(id));
            LocalDate dob = LocalDate.of(1950 + rnd.nextInt(55), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
            LocalDate start = LocalDate.now().withDayOfMonth(1);
            LocalDate end = start.plusYears(1).minusDays(1);
            String[] f = {id, FIRST[rnd.nextInt(FIRST.length)], LAST[rnd.nextInt(LAST.length)],
                    dob.toString(), PLANS.get(rnd.nextInt(PLANS.size())), start.toString(), end.toString()};
            if (badSlots.contains(i)) corrupt(f, rnd);
            lines.add(String.join(sep, f));
        }
        Files.write(out, lines);
        System.out.printf("generated %s rows=%d bad=%d layout=%s%n", out, rows, badSlots.size(), layout);
    }

    /** Inject one realistic data-quality problem. */
    private static void corrupt(String[] f, Random rnd) {
        switch (rnd.nextInt(4)) {
            case 0 -> f[0] = f[0].replace("MBR-", "M-");          // bad member id
            case 1 -> f[4] = "GOLD9";                              // unknown plan
            case 2 -> f[3] = "31/12/1980";                         // wrong date format
            default -> f[6] = LocalDate.parse(f[5]).minusDays(1).toString(); // end < start
        }
    }
}
