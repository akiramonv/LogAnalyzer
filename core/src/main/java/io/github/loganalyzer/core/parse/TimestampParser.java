package io.github.loganalyzer.core.parse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Разбор меток времени из логов. Поддерживает форматы, которые реально встречаются
 * в Java/Spring-приложениях: ISO-8601 (Spring Boot 3), Logback с запятой, только время
 * (дефолтный logback-паттерн {@code %d{HH:mm:ss.SSS}}), дату в европейском формате,
 * Common Log Format, syslog и epoch-миллисекунды.
 *
 * <p>Для форматов без часового пояса используется зона, переданная в конструктор;
 * для форматов без даты — {@code defaultDate} (по умолчанию — сегодняшняя дата),
 * что позволяет корректно строить таймлайн даже по логам вида {@code 10:00:00.123 [main] INFO ...}.
 */
public final class TimestampParser {

    /** Регулярка «начало строки похоже на метку времени» — быстрый предфильтр. */
    public static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^\\s*(?:\\[)?(" +
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?" +
            "|\\d{2}[./-]\\d{2}[./-]\\d{4}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?" +
            "|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?" +
            "|\\d{13}" +
            ")");

    private static final DateTimeFormatter ISO_LOCAL_FLEX = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .appendLiteral('T')
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter SPACE_SEPARATED = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter EURO_DATE = new DateTimeFormatterBuilder()
            .appendPattern("dd.MM.yyyy")
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    /** Европейская дата через дефис: {@code 10-08-2026 00:00:00.118} (встречается в paylogic). */
    private static final DateTimeFormatter DASH_DATE = new DateTimeFormatterBuilder()
            .appendPattern("dd-MM-yyyy")
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter SLASH_DATE = new DateTimeFormatterBuilder()
            .appendPattern("yyyy/MM/dd")
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TIME_ONLY = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    /** Common Log Format: {@code 09/Aug/2026:10:00:00 +0300}. */
    private static final DateTimeFormatter CLF = DateTimeFormatter
            .ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** Syslog: {@code Aug  9 10:00:00} (год отсутствует). */
    private static final DateTimeFormatter SYSLOG = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMM")
            .appendLiteral(' ')
            .padNext(2)
            .appendPattern("d HH:mm:ss")
            .toFormatter(Locale.ENGLISH);

    private final ZoneId zone;
    private final LocalDate defaultDate;

    public TimestampParser() {
        this(ZoneId.systemDefault(), LocalDate.now());
    }

    public TimestampParser(ZoneId zone, LocalDate defaultDate) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.defaultDate = defaultDate == null ? LocalDate.now() : defaultDate;
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * Пытается разобрать строку как метку времени.
     *
     * @param raw строка, например {@code 2026-08-09T10:00:00.123+03:00}
     * @return момент времени или {@code null}, если формат не распознан
     */
    public Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Logback пишет миллисекунды через запятую: 2026-08-09 10:00:00,123
        s = normalizeFractionSeparator(s);

        // epoch millis / seconds
        if (s.matches("\\d{10}|\\d{13}")) {
            try {
                long v = Long.parseLong(s);
                return s.length() == 13 ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        // ISO с зоной/смещением
        if (s.matches(".*(?:Z|[+-]\\d{2}:?\\d{2})$") && s.contains("-") && s.length() >= 20) {
            try {
                return java.time.OffsetDateTime.parse(fixOffset(s)).toInstant();
            } catch (Exception ignored) {
                // ниже пробуем остальные варианты
            }
        }

        for (DateTimeFormatter f : List.of(ISO_LOCAL_FLEX, SPACE_SEPARATED, EURO_DATE, DASH_DATE, SLASH_DATE)) {
            try {
                return LocalDateTime.parse(s, f).atZone(zone).toInstant();
            } catch (Exception ignored) {
                // пробуем следующий формат
            }
        }

        try {
            return java.time.OffsetDateTime.parse(s, CLF).toInstant();
        } catch (Exception ignored) {
            // не CLF
        }

        try {
            // Syslog не содержит года — подставляем год из defaultDate
            java.time.temporal.TemporalAccessor ta = SYSLOG.parse(s);
            LocalDateTime dt = LocalDateTime.of(
                    defaultDate.getYear(),
                    ta.get(ChronoField.MONTH_OF_YEAR),
                    ta.get(ChronoField.DAY_OF_MONTH),
                    ta.get(ChronoField.HOUR_OF_DAY),
                    ta.get(ChronoField.MINUTE_OF_HOUR),
                    ta.get(ChronoField.SECOND_OF_MINUTE));
            return dt.atZone(zone).toInstant();
        } catch (Exception ignored) {
            // не syslog
        }

        try {
            LocalTime time = LocalTime.parse(s, TIME_ONLY);
            return LocalDateTime.of(defaultDate, time).atZone(zone).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Заменяет разделитель дробной части «,» на «.» (Logback пишет {@code 10:00:00,123}). */
    private static String normalizeFractionSeparator(String s) {
        int comma = s.lastIndexOf(',');
        if (comma > 0 && comma + 1 < s.length() && Character.isDigit(s.charAt(comma + 1))
                && s.indexOf(':') >= 0 && s.indexOf(':') < comma) {
            return s.substring(0, comma) + '.' + s.substring(comma + 1);
        }
        return s;
    }

    /** {@code +0300} -> {@code +03:00}; пробел перед смещением убирается. */
    private static String fixOffset(String s) {
        return s.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
    }
}
