package io.github.loganalyzer.core.timeline;

import io.github.loganalyzer.core.correlate.EventGroup;
import io.github.loganalyzer.core.model.AnnotationType;
import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Построение таймлайна инцидента")
class TimelineBuilderTest {

    private final AtomicLong seq = new AtomicLong();

    private LogEvent event(String time, LogLevel level, String message) {
        return LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse(time))
                .level(level)
                .logger("com.example.Service")
                .message(message)
                .build();
    }

    private EventGroup group(LogEvent... events) {
        EventGroup group = new EventGroup("trace-A", CorrelationKind.TRACE);
        for (LogEvent e : events) {
            group.add(e);
        }
        return group;
    }

    @Test
    @DisplayName("События сортируются по времени независимо от порядка чтения")
    void sortsByTimestamp() {
        Timeline timeline = new TimelineBuilder().build(group(
                event("2026-08-09T10:00:02Z", LogLevel.INFO, "второе"),
                event("2026-08-09T10:00:01Z", LogLevel.INFO, "первое")));

        assertThat(timeline.getEntries()).hasSize(2);
        assertThat(timeline.getEntries().get(0).getEvent().getMessage()).isEqualTo("первое");
        assertThat(timeline.getEntries().get(0).getId()).isEqualTo("e1");
    }

    @Test
    @DisplayName("Серия одинаковых повторов схлопывается и помечается как ретрай")
    void collapsesRepeats() {
        Timeline timeline = new TimelineBuilder().build(group(
                event("2026-08-09T10:00:01Z", LogLevel.WARN, "Retry attempt 1 of 3"),
                event("2026-08-09T10:00:02Z", LogLevel.WARN, "Retry attempt 2 of 3"),
                event("2026-08-09T10:00:03Z", LogLevel.WARN, "Retry attempt 3 of 3"),
                event("2026-08-09T10:00:04Z", LogLevel.ERROR, "Всё пропало")));

        assertThat(timeline.getEntries()).hasSize(2);
        assertThat(timeline.getEntries().get(0).getRepeatCount()).isEqualTo(3);
        assertThat(timeline.getEntries().get(0).hasAnnotation(AnnotationType.RETRY)).isTrue();
        assertThat(timeline.getEventCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("Циклический опрос схлопывается, хотя сообщения чередуются")
    void collapsesRepeatingCycle() {
        // Зависший платёж опрашивается по кругу: запрос → ответ → снова запрос.
        // Повторы не идут подряд, но цепочка из тысяч таких шагов нечитаема.
        Timeline timeline = new TimelineBuilder().build(group(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "status request body"),
                event("2026-08-09T10:00:01Z", LogLevel.INFO, "status response: state=40"),
                event("2026-08-09T10:00:02Z", LogLevel.INFO, "status request body"),
                event("2026-08-09T10:00:03Z", LogLevel.INFO, "status response: state=40"),
                event("2026-08-09T10:00:04Z", LogLevel.INFO, "status request body"),
                event("2026-08-09T10:00:05Z", LogLevel.INFO, "status response: state=40")));

        assertThat(timeline.getEntries()).hasSize(2);
        assertThat(timeline.getEntries().get(0).getRepeatCount()).isEqualTo(3);
        assertThat(timeline.getEntries().get(1).getRepeatCount()).isEqualTo(3);
        assertThat(timeline.getEventCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("Повтор за пределами окна дедупликации остаётся отдельным событием")
    void keepsRepeatOutsideDedupWindow() {
        Timeline timeline = new TimelineBuilder(new TimelineOptions()
                .setDedupWindow(java.time.Duration.ofSeconds(10))).build(group(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "Опрос статуса"),
                event("2026-08-09T10:05:00Z", LogLevel.INFO, "Опрос статуса")));

        assertThat(timeline.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("Без нормализации разные номера попыток считаются разными событиями")
    void keepsDistinctEventsWhenNormalizationOff() {
        Timeline timeline = new TimelineBuilder(new TimelineOptions().setDedupNormalize(false)).build(group(
                event("2026-08-09T10:00:01Z", LogLevel.WARN, "Retry attempt 1 of 3"),
                event("2026-08-09T10:00:02Z", LogLevel.WARN, "Retry attempt 2 of 3")));

        assertThat(timeline.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("Смещения от начала и паузы между событиями вычисляются")
    void computesOffsets() {
        Timeline timeline = new TimelineBuilder().build(group(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "старт"),
                event("2026-08-09T10:00:10Z", LogLevel.INFO, "через десять секунд")));

        assertThat(timeline.getEntries().get(1).getSinceStart().toSeconds()).isEqualTo(10);
        assertThat(timeline.getEntries().get(1).getSincePrevious().toSeconds()).isEqualTo(10);
        assertThat(timeline.getDurationMs()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Долгая пауза перед событием помечается как подозрительная")
    void marksSuspiciousGap() {
        Timeline timeline = new TimelineBuilder().build(group(
                event("2026-08-09T10:00:00Z", LogLevel.INFO, "запрос ушёл"),
                event("2026-08-09T10:00:31Z", LogLevel.ERROR, "таймаут")));

        assertThat(timeline.getEntries().get(1).hasAnnotation(AnnotationType.SLOW)).isTrue();
    }

    @Test
    @DisplayName("HTTP-запрос связывается с последующим ответом")
    void linksHttpRequestAndResponse() {
        LogEvent request = LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse("2026-08-09T10:00:00Z"))
                .kind(EventKind.HTTP_REQUEST)
                .http(HttpExchange.builder().method("GET").url("/api/user").build())
                .message("GET /api/user")
                .build();
        LogEvent response = LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse("2026-08-09T10:00:01Z"))
                .kind(EventKind.HTTP_RESPONSE)
                .http(HttpExchange.builder().status(400).statusText("Bad Request").build())
                .message("400 Bad Request")
                .build();

        Timeline timeline = new TimelineBuilder().build(group(request, response));

        assertThat(timeline.getEntries().get(0).getRelatedIds()).contains("e2");
        assertThat(timeline.getEntries().get(1).getRelatedIds()).contains("e1");
        assertThat(timeline.getEntries().get(1).hasAnnotation(AnnotationType.HTTP_CLIENT_ERROR)).isTrue();
    }

    @Test
    @DisplayName("Входящий запрос не помечается как внешний вызов")
    void doesNotMarkInboundAsExternal() {
        LogEvent inbound = LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse("2026-08-09T10:00:00Z"))
                .http(HttpExchange.builder()
                        .direction(HttpExchange.Direction.INBOUND)
                        .method("POST").url("/api/v1/orders").build())
                .message("Получен запрос POST /api/v1/orders")
                .build();

        Timeline timeline = new TimelineBuilder().build(group(inbound));

        assertThat(timeline.getEntries().get(0).hasAnnotation(AnnotationType.EXTERNAL_CALL)).isFalse();
    }
}
