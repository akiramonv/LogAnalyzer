package io.github.loganalyzer.core.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Разбор меток времени")
class TimestampParserTest {

    private final TimestampParser utc = new TimestampParser(ZoneOffset.UTC, LocalDate.of(2026, 8, 9));

    @Test
    @DisplayName("ISO-8601 со смещением часового пояса")
    void parsesIsoWithOffset() {
        assertThat(utc.parse("2026-08-09T10:00:00.123+03:00"))
                .isEqualTo(Instant.parse("2026-08-09T07:00:00.123Z"));
    }

    @Test
    @DisplayName("ISO-8601 в UTC")
    void parsesIsoUtc() {
        assertThat(utc.parse("2026-08-09T10:00:00.123Z"))
                .isEqualTo(Instant.parse("2026-08-09T10:00:00.123Z"));
    }

    @Test
    @DisplayName("Формат Logback с запятой перед миллисекундами")
    void parsesLogbackComma() {
        assertThat(utc.parse("2026-08-09 10:00:00,123"))
                .isEqualTo(Instant.parse("2026-08-09T10:00:00.123Z"));
    }

    @Test
    @DisplayName("Только время — дата подставляется из настроек")
    void parsesTimeOnly() {
        assertThat(utc.parse("10:00:00.123"))
                .isEqualTo(Instant.parse("2026-08-09T10:00:00.123Z"));
    }

    @Test
    @DisplayName("Европейский формат даты dd.MM.yyyy")
    void parsesEuropeanDate() {
        assertThat(utc.parse("09.08.2026 10:00:00"))
                .isEqualTo(Instant.parse("2026-08-09T10:00:00Z"));
    }

    @Test
    @DisplayName("Epoch в миллисекундах")
    void parsesEpochMillis() {
        assertThat(utc.parse("1786312800000")).isEqualTo(Instant.ofEpochMilli(1786312800000L));
    }

    @Test
    @DisplayName("Форматы без смещения читаются в заданном часовом поясе")
    void appliesConfiguredZone() {
        TimestampParser moscow = new TimestampParser(ZoneId.of("Europe/Moscow"), LocalDate.of(2026, 8, 9));
        assertThat(moscow.parse("2026-08-09 10:00:00"))
                .isEqualTo(Instant.parse("2026-08-09T07:00:00Z"));
    }

    @Test
    @DisplayName("Нераспознанное значение даёт null, а не исключение")
    void returnsNullForGarbage() {
        assertThat(utc.parse("не время")).isNull();
        assertThat(utc.parse("")).isNull();
        assertThat(utc.parse(null)).isNull();
    }
}
