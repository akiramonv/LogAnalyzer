package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Именованный шаблон строки текстового лога — регулярное выражение с именованными группами.
 *
 * <p>Распознаваемые группы (все необязательные, кроме {@code msg}):
 * <ul>
 *   <li>{@code ts} — метка времени;</li>
 *   <li>{@code level} — уровень;</li>
 *   <li>{@code thread} — имя потока;</li>
 *   <li>{@code logger} — категория/класс;</li>
 *   <li>{@code msg} — текст сообщения;</li>
 *   <li>{@code trace} — блок трассировки: {@code traceId} либо {@code traceId,spanId};</li>
 *   <li>{@code sleuth} — блок Spring Cloud Sleuth: {@code app,traceId,spanId,exportable};</li>
 *   <li>{@code app} — имя приложения/сервиса;</li>
 *   <li>{@code pid} — идентификатор процесса.</li>
 * </ul>
 *
 * <p>Пользовательские шаблоны задаются в конфиге теми же именами групп, поэтому
 * поддержка нестандартного формата логов сводится к одной строке regex.
 */
public final class LogPattern {

    /** Фрагмент regex для метки времени — используется во встроенных шаблонах. */
    private static final String TS =
            "(?:\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?"
            + "|\\d{2}[./-]\\d{2}[./-]\\d{4}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?"
            + "|\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?"
            + "|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)";

    private static final String LEVEL = "(?:TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|FINE|FINEST|CRITICAL)";

    private final String name;
    private final Pattern pattern;

    private LogPattern(String name, Pattern pattern) {
        this.name = name;
        this.pattern = pattern;
    }

    /**
     * Создаёт шаблон из «сырого» regex с именованными группами.
     *
     * @throws java.util.regex.PatternSyntaxException если regex некорректен
     */
    public static LogPattern of(String name, String regex) {
        return new LogPattern(name, Pattern.compile(regex));
    }

    public String name() {
        return name;
    }

    public Pattern pattern() {
        return pattern;
    }

    /**
     * @return {@code true}, если шаблон может начать разбор только со строки, первый символ
     *         которой — цифра (метка времени) или {@code '['}.
     *
     * <p>Нужно для быстрого пропуска строк-продолжений: в больших логах их большинство
     * (стек-трейсы, XML- и JSON-тела, многострочные дампы), и прогонять по ним весь список
     * регулярных выражений — самая дорогая часть разбора. Проверка первого символа заменяет
     * этот прогон, но только если так устроены **все** шаблоны, включая пользовательские.
     */
    public boolean startsWithTimestampOrBracket() {
        String regex = pattern.pattern();
        return regex.startsWith("^(?<ts>") || regex.startsWith("^\\[");
    }

    /**
     * Пробует разобрать строку.
     *
     * @return заполненный строитель события (без {@code sequence}/{@code source}) либо {@code Optional.empty()}
     */
    public Optional<LogEvent.Builder> match(String line, TimestampParser timestamps) {
        Matcher m = pattern.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        LogEvent.Builder b = LogEvent.builder();
        String ts = group(m, "ts");
        if (ts != null) {
            b.timestamp(timestamps.parse(ts));
        }
        String level = group(m, "level");
        if (level != null) {
            b.level(LogLevel.parse(level));
        }
        b.thread(group(m, "thread"));
        b.logger(group(m, "logger"));
        b.service(group(m, "app"));
        String pid = group(m, "pid");
        if (pid != null) {
            b.attribute("pid", pid);
        }

        // Spring Cloud Sleuth: [appName,traceId,spanId,exportable]
        String sleuth = group(m, "sleuth");
        if (sleuth != null && sleuth.contains(",")) {
            String[] parts = sleuth.split(",", -1);
            if (parts.length >= 3) {
                if (!parts[0].isBlank()) {
                    b.service(parts[0]);
                }
                b.traceId(parts[1]);
                b.spanId(parts[2]);
            }
        }
        // Micrometer Tracing (Spring Boot 3): [traceId,spanId]; блок бывает пустым
        String trace = group(m, "trace");
        if (trace != null && !trace.isBlank() && !trace.startsWith(",")) {
            String[] parts = trace.split(",", -1);
            b.traceId(parts[0]);
            if (parts.length > 1) {
                b.spanId(parts[1]);
            }
        }
        String msg = group(m, "msg");
        b.message(msg == null ? line : msg);
        return Optional.of(b);
    }

    private static String group(Matcher m, String name) {
        try {
            String v = m.group(name);
            return v == null || v.isBlank() ? null : v.trim();
        } catch (IllegalArgumentException e) {
            // такой группы в шаблоне нет
            return null;
        }
    }

    @Override
    public String toString() {
        return name + " -> " + pattern.pattern();
    }

    /**
     * Встроенные шаблоны в порядке применения: от самых специфичных к самым общим.
     * Первый подошедший выигрывает.
     */
    public static List<LogPattern> builtins() {
        return List.of(
                // Spring Boot 2.x + Sleuth: 2020-01-01 10:00:00.000  INFO [app,trace,span,true] 1234 --- [thread] c.e.Cls : msg
                of("spring-boot-sleuth",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+"
                        + "\\[(?<sleuth>[^\\]]*,[^\\]]*)\\]\\s+"
                        + "(?<pid>\\d+)?\\s*---\\s*"
                        + "\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s+"
                        + "(?<logger>[^\\s:]+)\\s*:\\s?(?<msg>.*)$"),

                // Spring Boot 3.x (с опциональными блоками имени приложения и traceId,spanId)
                of("spring-boot",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+"
                        + "(?<pid>\\d+)?\\s*---\\s*"
                        + "(?:\\[(?<app>[^\\]\\s][^\\]]*)\\]\\s*)?"
                        + "\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s*"
                        // Блок трассировки может быть пустым (Spring Boot дополняет его пробелами)
                        + "(?:\\[(?<trace>[0-9a-fA-F,\\-\\s]*)\\]\\s*)?"
                        + "(?<logger>[^\\s:]+)\\s*:\\s?(?<msg>.*)$"),

                // Logback/Log4j2 по умолчанию: 10:00:00.123 [main] INFO  com.example.Cls - msg
                // (а также вариант с полной датой и с ':' вместо '-')
                of("logback",
                        "^(?<ts>" + TS + ")\\s+"
                        + "(?:\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s+)?"
                        + "(?<level>" + LEVEL + ")\\s+"
                        + "(?:\\[\\s*(?<trace>[0-9a-fA-F,\\-]{8,})\\s*\\]\\s+)?"
                        + "(?<logger>[\\w.$#\\[\\]-]+)\\s+[-:]\\s(?<msg>.*)$"),

                // Уровень перед потоком: 2026-08-09 10:00:00 INFO [main] com.example.Cls - msg
                of("level-first-thread",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+"
                        + "\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s+"
                        + "(?<logger>[\\w.$#-]+)\\s*[-:]\\s(?<msg>.*)$"),

                // Всё в скобках: [2026-08-09 10:00:00] [ERROR] [main] com.example.Cls: msg
                of("bracketed",
                        "^\\[(?<ts>" + TS + ")\\]\\s*"
                        + "\\[(?<level>" + LEVEL + ")\\]\\s*"
                        + "(?:\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s*)?"
                        + "(?:(?<logger>[\\w.$#-]+)\\s*[:-]\\s*)?(?<msg>.*)$"),

                // Воркер обработки платежей: 00:00:25,564  INFO RMI TCP Connection(21133)-10.1.1.6 [173497766] - msg
                // Имя потока содержит пробелы и спецсимволы, поэтому берётся «до скобки»;
                // в скобках — идентификатор обрабатываемого платежа или реквизит абонента,
                // он и становится ключом корреляции (цепочка одного платежа).
                of("level-thread-ref",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+"
                        + "(?<thread>\\S.*?)\\s+"
                        + "\\[(?<trace>[^\\]]*)\\]\\s+-\\s?(?<msg>.*)$"),

                // Минимальный формат: 2026-08-09T10:00:00 ERROR com.example.Cls - msg
                of("ts-level-logger",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+"
                        + "(?<logger>[\\w.$#-]+)\\s*[-:]\\s(?<msg>.*)$"),

                // Совсем простой: 2026-08-09T10:00:00 ERROR текст сообщения
                of("ts-level",
                        "^(?<ts>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+(?<msg>.*)$"),

                // Только метка времени (уровень будет определён по содержимому)
                of("ts-only",
                        "^(?<ts>" + TS + ")\\s+(?<msg>.*)$")
        );
    }
}
