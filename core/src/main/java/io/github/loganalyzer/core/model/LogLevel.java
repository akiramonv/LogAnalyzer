package io.github.loganalyzer.core.model;

import java.util.Locale;

/**
 * Уровень логирования. Значения упорядочены по возрастанию серьёзности,
 * поэтому сравнение через {@link #isAtLeast(LogLevel)} работает по порядковому номеру.
 */
public enum LogLevel {
    UNKNOWN,
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL;

    /**
     * Разбирает уровень из строки лога. Поддерживает синонимы разных фреймворков:
     * {@code WARNING} (JUL), {@code SEVERE} (JUL), {@code CRIT/CRITICAL}, {@code FINE/FINER/FINEST} (JUL),
     * {@code NOTICE}, {@code VERBOSE}, а также числовые уровни syslog.
     *
     * @param raw исходная строка (может быть null)
     * @return распознанный уровень или {@link #UNKNOWN}
     */
    public static LogLevel parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) {
            return UNKNOWN;
        }
        return switch (v) {
            case "TRACE", "FINEST", "VERBOSE" -> TRACE;
            case "DEBUG", "FINE", "FINER" -> DEBUG;
            case "INFO", "INFORMATION", "NOTICE", "CONFIG" -> INFO;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "SEVERE", "ERR" -> ERROR;
            case "FATAL", "CRIT", "CRITICAL", "EMERG", "ALERT", "PANIC" -> FATAL;
            default -> UNKNOWN;
        };
    }

    /** @return {@code true} если уровень не ниже указанного (UNKNOWN не проходит ни один порог кроме UNKNOWN). */
    public boolean isAtLeast(LogLevel other) {
        if (other == null || other == UNKNOWN) {
            return true;
        }
        return this != UNKNOWN && this.ordinal() >= other.ordinal();
    }

    /** @return {@code true} для ERROR и FATAL. */
    public boolean isError() {
        return this == ERROR || this == FATAL;
    }
}
