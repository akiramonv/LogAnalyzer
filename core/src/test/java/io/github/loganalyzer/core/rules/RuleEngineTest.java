package io.github.loganalyzer.core.rules;

import io.github.loganalyzer.core.correlate.EventGroup;
import io.github.loganalyzer.core.model.AnnotationType;
import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.parse.LogIngestor;
import io.github.loganalyzer.core.parse.ParseOptions;
import io.github.loganalyzer.core.timeline.TimelineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Правила анализа")
class RuleEngineTest {

    private final AtomicLong seq = new AtomicLong();
    private final RuleSetLoader loader = new RuleSetLoader();

    private LogEvent event(String time, LogLevel level, String message) {
        return LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse(time))
                .level(level)
                .logger("com.example.Service")
                .message(message)
                .build();
    }

    private Timeline timelineOf(LogEvent... events) {
        EventGroup group = new EventGroup("trace-A", CorrelationKind.TRACE);
        for (LogEvent e : events) {
            group.add(e);
        }
        return new TimelineBuilder().build(group);
    }

    private Timeline timelineFromLog(String log) {
        LogIngestor ingestor = new LogIngestor(new ParseOptions()
                .setZone(ZoneOffset.UTC)
                .setDefaultDate(LocalDate.of(2026, 8, 9)));
        EventGroup group = new EventGroup("trace-A", CorrelationKind.TRACE);
        ingestor.ingestText(log, "test.log").getEvents().forEach(group::add);
        return new TimelineBuilder().build(group);
    }

    @Test
    @DisplayName("Пользовательское правило помечает событие и выдвигает причину")
    void appliesCustomRule() {
        RuleSet set = loader.parse("""
                name: test
                rules:
                  - name: payment-declined
                    description: Платёж отклонён банком
                    when:
                      messageRegex: 'declined by issuer'
                    annotate:
                      - type: EXTERNAL_CALL
                        label: Отказ банка
                    cause:
                      title: Банк отклонил операцию
                      confidence: 0.8
                """, "test");

        Timeline timeline = timelineOf(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "начало оплаты"),
                event("2026-08-09T10:00:01Z", LogLevel.ERROR, "Payment declined by issuer, code=51"));

        List<RuleEngine.RuleHit> hits = new RuleEngine(set).apply(timeline);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).rule().getName()).isEqualTo("payment-declined");
        assertThat(timeline.getEntries().get(1).hasAnnotation(AnnotationType.EXTERNAL_CALL)).isTrue();
    }

    @Test
    @DisplayName("Условие по типу исключения проверяет всю цепочку Caused by")
    void matchesExceptionAnywhereInChain() {
        Timeline timeline = timelineFromLog("""
                2026-08-09T10:00:02 ERROR com.example.Repo - Ошибка запроса
                org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
                \tat com.example.Repo.load(Repo.java:20)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30001ms
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """);

        List<RuleEngine.RuleHit> hits = new RuleEngine(loader.loadDefaults()).apply(timeline);

        assertThat(hits).extracting(h -> h.rule().getName()).contains("hikari-pool-timeout");
    }

    @Test
    @DisplayName("Правило с precededBy срабатывает только при наличии предшествующего события")
    void requiresPrecedingEvent() {
        RuleSet set = loader.parse("""
                name: test
                rules:
                  - name: error-after-retry
                    when:
                      levelAtLeast: ERROR
                    precededBy:
                      withinSeconds: 60
                      when:
                        messageRegex: 'Retry attempt'
                    annotate:
                      - type: RETRY
                        label: Ошибка после повторов
                """, "test");
        RuleEngine engine = new RuleEngine(set);

        Timeline withRetry = timelineOf(
                event("2026-08-09T10:00:00Z", LogLevel.WARN, "Retry attempt 1 of 3"),
                event("2026-08-09T10:00:05Z", LogLevel.ERROR, "Сдаёмся"));
        Timeline withoutRetry = timelineOf(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "обычная запись"),
                event("2026-08-09T10:00:05Z", LogLevel.ERROR, "Сдаёмся"));

        assertThat(engine.apply(withRetry)).hasSize(1);
        assertThat(engine.apply(withoutRetry)).isEmpty();
    }

    @Test
    @DisplayName("Правило по классу HTTP-статуса")
    void matchesHttpStatusClass() {
        Timeline timeline = timelineFromLog(
                "2026-08-09T10:00:02 ERROR com.example.Client - Внешний вызов завершился status=503\n");

        List<RuleEngine.RuleHit> hits = new RuleEngine(loader.loadDefaults()).apply(timeline);

        assertThat(hits).extracting(h -> h.rule().getName()).contains("http-server-error");
    }

    @Test
    @DisplayName("Встроенный набор правил загружается и проходит проверку")
    void loadsBuiltinRules() {
        RuleSet defaults = loader.loadDefaults();

        assertThat(defaults.size()).isGreaterThan(30);
        assertThat(defaults.getRules()).allMatch(Rule::isValid);
        assertThat(defaults.getRules()).extracting(Rule::getName)
                .contains("null-pointer", "socket-timeout", "hikari-pool-timeout", "out-of-memory-heap");
    }

    @Test
    @DisplayName("Опечатка в файле правил приводит к понятной ошибке")
    void reportsUnknownFields() {
        assertThatThrownBy(() -> loader.parse("""
                name: broken
                rules:
                  - name: typo
                    when:
                      mesageRegex: 'oops'
                """, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mesageRegex");
    }

    @Test
    @DisplayName("Правило без условий отклоняется при проверке набора")
    void rejectsRuleWithoutConditions() {
        RuleSet set = loader.parse("""
                name: broken
                rules:
                  - name: empty
                """, "test");

        assertThatThrownBy(() -> loader.validate(set, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
