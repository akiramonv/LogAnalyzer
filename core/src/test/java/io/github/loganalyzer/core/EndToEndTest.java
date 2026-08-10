package io.github.loganalyzer.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loganalyzer.core.analyze.IncidentPromptBuilder;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.report.HtmlReportWriter;
import io.github.loganalyzer.core.report.JsonReportWriter;
import io.github.loganalyzer.core.report.MermaidReportWriter;
import io.github.loganalyzer.core.report.TextReportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Полный конвейер анализа")
class EndToEndTest {

    private AnalysisReport analyze(String resource) throws IOException {
        try (InputStream in = EndToEndTest.class.getResourceAsStream("/logs/" + resource)) {
            assertThat(in).as("тестовый лог %s", resource).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            AnalyzerConfig config = AnalyzerConfig.defaults();
            config.getAnalysis().getApplicationPackages().add("com.example");
            // Проверяем сам разбор: память пользователя (~/.log-analyzer/feedback.json)
            // не должна влиять на ожидаемые причины — обучению посвящён LearningTest.
            config.getLearning().setEnabled(false);
            return new LogAnalyzer(config).analyzeText(text, resource);
        }
    }

    @Test
    @DisplayName("Смешанный лог: события одного запроса собираются по traceId, причина — NPE")
    void analysesMixedLog() throws IOException {
        AnalysisReport report = analyze("mixed-incident.log");

        assertThat(report.getSummary().getTotalEvents()).isEqualTo(7);
        assertThat(report.getTimelines()).hasSize(2);

        Timeline failed = report.getTimelines().stream().filter(Timeline::isFailed).findFirst().orElseThrow();
        assertThat(failed.getCorrelationKind()).isEqualTo(CorrelationKind.TRACE);
        assertThat(failed.getCorrelationId()).isEqualTo("8f3c2a1b4d5e6f70");
        assertThat(failed.getServices()).contains("order-service");
        assertThat(failed.getRootCause()).isNotNull();
        assertThat(failed.getRootCause().getTitle()).contains("null-ссылке");
        assertThat(failed.getRootCause().getConfidence()).isGreaterThan(0.7);

        // JSON-запись без формата Spring Boot тоже попала в ту же цепочку
        assertThat(failed.getEntries()).anyMatch(e ->
                e.getEvent().getMessage().contains("Профиль скидок не найден"));

        Timeline healthy = report.getTimelines().stream().filter(t -> !t.isFailed()).findFirst().orElseThrow();
        assertThat(healthy.getRootCause()).isNull();
    }

    @Test
    @DisplayName("Повторы схлопываются, причиной признаётся таймаут внешнего вызова")
    void analysesRetryTimeoutLog() throws IOException {
        AnalysisReport report = analyze("retry-timeout.log");

        assertThat(report.getTimelines()).hasSize(1);
        Timeline timeline = report.getTimelines().get(0);

        assertThat(timeline.getEventCount()).isEqualTo(6);
        assertThat(timeline.getEntries()).hasSize(4);
        assertThat(timeline.getEntries().get(1).getRepeatCount()).isEqualTo(3);
        assertThat(timeline.getRootCause().getTitle()).contains("не ответила за отведённое время");
        assertThat(timeline.getDurationMs()).isGreaterThan(40_000L);
    }

    @Test
    @DisplayName("JSON-отчёт содержит таймлайн, причину и доказательства")
    void producesJsonReport() throws IOException {
        AnalysisReport report = analyze("retry-timeout.log");

        String json = new JsonReportWriter(true).writeToString(report);
        JsonNode root = new ObjectMapper().readTree(json);

        assertThat(root.get("summary").get("totalEvents").asInt()).isEqualTo(6);
        JsonNode timeline = root.get("timelines").get(0);
        assertThat(timeline.get("correlationId").asText()).isEqualTo("a1b2c3d4e5f60718");
        assertThat(timeline.get("rootCause").get("confidence").asDouble()).isGreaterThan(0.5);
        assertThat(timeline.get("rootCause").get("evidence")).isNotEmpty();
        assertThat(timeline.get("entries")).isNotEmpty();
        assertThat(timeline.get("entries").get(0).get("event").get("timestamp").asText())
                .startsWith("2026-08-09T");
    }

    @Test
    @DisplayName("Текстовый отчёт читается и содержит вывод о причине")
    void producesTextReport() throws IOException {
        AnalysisReport report = analyze("mixed-incident.log");

        String text = new TextReportWriter(ZoneOffset.UTC).setOnlyFailed(true).writeToString(report);

        assertThat(text).contains("Анализ логов");
        assertThat(text).contains("traceId 8f3c2a1b4d5e6f70");
        assertThat(text).contains("Вероятная причина");
        assertThat(text).contains("NullPointerException");
    }

    @Test
    @DisplayName("HTML-отчёт самодостаточен и экранирует содержимое логов")
    void producesHtmlReport() throws IOException {
        AnalysisReport report = analyze("mixed-incident.log");

        String html = new HtmlReportWriter(ZoneOffset.UTC).writeToString(report);

        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("<style>").contains("<script>");
        assertThat(html).doesNotContain("http://").doesNotContain("https://cdn");
        assertThat(html).contains("&quot;profile&quot;");
        assertThat(html).contains("Вероятная причина");
    }

    @Test
    @DisplayName("Mermaid-диаграмма формируется для инцидентов с ошибками")
    void producesMermaidDiagram() throws IOException {
        AnalysisReport report = analyze("retry-timeout.log");

        String mermaid = new MermaidReportWriter(ZoneOffset.UTC).writeToString(report);

        assertThat(mermaid).contains("```mermaid").contains("gantt").contains("dateFormat");
        assertThat(mermaid).contains("crit");
    }

    @Test
    @DisplayName("Промпт для языковой модели описывает хронологию и задание")
    void producesLlmPrompt() throws IOException {
        AnalysisReport report = analyze("retry-timeout.log");
        Timeline timeline = report.getTimelines().get(0);

        IncidentPromptBuilder builder = new IncidentPromptBuilder(ZoneOffset.UTC);
        String user = builder.userPrompt(timeline);

        assertThat(builder.systemPrompt()).contains("Caused by");
        assertThat(user).contains("Инцидент: TRACE a1b2c3d4e5f60718");
        assertThat(user).contains("Retry attempt");
        assertThat(user).contains("SocketTimeoutException");
        assertThat(user).contains("уверенности");
    }
}
