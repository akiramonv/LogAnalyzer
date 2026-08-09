package io.github.loganalyzer.core.analyze;

import io.github.loganalyzer.core.correlate.EventGroup;
import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.RootCause;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.parse.LogIngestor;
import io.github.loganalyzer.core.parse.ParseOptions;
import io.github.loganalyzer.core.rules.RuleEngine;
import io.github.loganalyzer.core.rules.RuleSetLoader;
import io.github.loganalyzer.core.timeline.TimelineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Определение первопричины")
class HeuristicRootCauseAnalyzerTest {

    private final RuleEngine ruleEngine = new RuleEngine(new RuleSetLoader().loadDefaults());

    private Timeline analyze(String log, CorrelationKind kind) {
        LogIngestor ingestor = new LogIngestor(new ParseOptions()
                .setZone(ZoneOffset.UTC)
                .setDefaultDate(LocalDate.of(2026, 8, 9)));
        EventGroup group = new EventGroup("trace-A", kind);
        ingestor.ingestText(log, "test.log").getEvents().forEach(group::add);

        Timeline timeline = new TimelineBuilder().build(group);
        List<RuleEngine.RuleHit> hits = ruleEngine.apply(timeline);
        List<RootCause> causes = new HeuristicRootCauseAnalyzer(
                new AnalysisOptions().addApplicationPackage("com.example"))
                .analyze(timeline, hits);
        if (!causes.isEmpty()) {
            timeline.setRootCause(causes.get(0));
            causes.stream().skip(1).limit(3).forEach(timeline.getAlternatives()::add);
        }
        return timeline;
    }

    @Test
    @DisplayName("NullPointerException распознаётся как причина с высокой уверенностью")
    void detectsNullPointer() {
        Timeline timeline = analyze("""
                2026-08-09T10:00:01 INFO  com.example.Service - Обработка начата
                2026-08-09T10:00:02 ERROR com.example.Service - Ошибка обработки
                java.lang.NullPointerException: Cannot invoke "DiscountProfile.rate()" because "profile" is null
                \tat com.example.PricingService.applyDiscount(PricingService.java:88)
                """, CorrelationKind.TRACE);

        RootCause cause = timeline.getRootCause();
        assertThat(cause).isNotNull();
        assertThat(cause.getTitle()).contains("null-ссылке");
        assertThat(cause.getConfidence()).isGreaterThan(0.8);
        assertThat(cause.getRule()).isEqualTo("null-pointer");
        assertThat(cause.getEvidence()).isNotEmpty();
    }

    @Test
    @DisplayName("Причиной считается самое глубокое звено цепочки Caused by")
    void prefersDeepestCause() {
        Timeline timeline = analyze("""
                2026-08-09T10:00:02 ERROR com.example.Repo - Ошибка запроса
                org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
                \tat com.example.Repo.load(Repo.java:20)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30001ms
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """, CorrelationKind.TRACE);

        assertThat(timeline.getRootCause().getTitle()).contains("пул соединений");
        // среди альтернатив должна быть формулировка по самому глубокому исключению
        List<String> titles = timeline.getAlternatives().stream().map(RootCause::getTitle).toList();
        assertThat(titles).anyMatch(t -> t.contains("SQLTransientConnectionException"));
    }

    @Test
    @DisplayName("Таймаут внешнего вызова важнее места падения")
    void detectsExternalTimeout() {
        Timeline timeline = analyze("""
                2026-08-09T10:00:00 INFO  com.example.Client - POST https://gw.example/v2/charge
                2026-08-09T10:00:30 ERROR com.example.Client - Вызов не удался
                org.springframework.web.client.ResourceAccessException: I/O error on POST request
                \tat com.example.Client.charge(Client.java:74)
                Caused by: java.net.SocketTimeoutException: Read timed out
                \tat java.base/java.net.Socket.read(Socket.java:966)
                """, CorrelationKind.TRACE);

        assertThat(timeline.getRootCause().getTitle()).contains("не ответила за отведённое время");
        assertThat(timeline.getRootCause().getRecommendation()).isNotBlank();
    }

    @Test
    @DisplayName("Без ошибок причина не выдумывается")
    void returnsNothingWithoutErrors() {
        Timeline timeline = analyze("""
                2026-08-09T10:00:01 INFO com.example.Service - всё хорошо
                2026-08-09T10:00:02 INFO com.example.Service - и дальше хорошо
                """, CorrelationKind.TRACE);

        assertThat(timeline.getRootCause()).isNull();
        assertThat(timeline.isFailed()).isFalse();
    }

    @Test
    @DisplayName("Уверенность ниже, если события собраны по потоку, а не по traceId")
    void lowersConfidenceForWeakCorrelation() {
        String log = """
                2026-08-09T10:00:02 ERROR com.example.Service - Ошибка обработки
                java.lang.NullPointerException: profile is null
                \tat com.example.PricingService.applyDiscount(PricingService.java:88)
                """;

        double byTrace = analyze(log, CorrelationKind.TRACE).getRootCause().getConfidence();
        double byThread = analyze(log, CorrelationKind.THREAD).getRootCause().getConfidence();

        assertThat(byThread).isLessThan(byTrace);
    }
}
