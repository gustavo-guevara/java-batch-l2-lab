package lab.batch;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One line per event, easy to grep: "2026-11-23 10:00:00 INFO  message key=value". */
final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {}

    static void info(String msg, Object... args)  { print("INFO ", msg, args); }
    static void warn(String msg, Object... args)  { print("WARN ", msg, args); }
    static void error(String msg, Object... args) { print("ERROR", msg, args); }

    private static void print(String level, String msg, Object... args) {
        System.out.println(LocalDateTime.now().format(TS) + " " + level + " " + String.format(msg, args));
        System.out.flush();
    }
}
