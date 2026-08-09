package io.github.loganalyzer.core.correlate;

import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Группировка событий в инциденты")
class CorrelatorTest {

    private final AtomicLong seq = new AtomicLong();

    private LogEvent event(String time, String thread, String traceId, String message) {
        return LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse(time))
                .thread(thread)
                .traceId(traceId)
                .message(message)
                .build();
    }

    @Test
    @DisplayName("События с одинаковым traceId попадают в один инцидент")
    void groupsByTraceId() {
        List<EventGroup> groups = new Correlator().correlate(List.of(
                event("2026-08-09T10:00:00Z", "http-1", "trace-A", "начало A"),
                event("2026-08-09T10:00:01Z", "http-2", "trace-B", "начало B"),
                event("2026-08-09T10:00:02Z", "http-1", "trace-A", "конец A")));

        assertThat(groups).hasSize(2);
        EventGroup a = groups.stream().filter(g -> g.getKey().equals("trace-A")).findFirst().orElseThrow();
        assertThat(a.getKind()).isEqualTo(CorrelationKind.TRACE);
        assertThat(a.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Строки без traceId наследуют активный трейс своего потока")
    void inheritsTraceFromThread() {
        List<EventGroup> groups = new Correlator().correlate(List.of(
                event("2026-08-09T10:00:00Z", "http-1", "trace-A", "с трейсом"),
                event("2026-08-09T10:00:01Z", "http-1", null, "без трейса, но тот же поток")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getKey()).isEqualTo("trace-A");
        assertThat(groups.get(0).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Наследование трейса не выходит за пределы временного окна")
    void doesNotInheritTraceAfterWindow() {
        CorrelationOptions options = new CorrelationOptions().setInheritWindow(Duration.ofSeconds(5));

        List<EventGroup> groups = new Correlator(options).correlate(List.of(
                event("2026-08-09T10:00:00Z", "http-1", "trace-A", "с трейсом"),
                event("2026-08-09T10:05:00Z", "http-1", null, "намного позже")));

        assertThat(groups).hasSize(2);
    }

    @Test
    @DisplayName("Один поток режется на разные инциденты по длинной паузе")
    void splitsThreadByGap() {
        CorrelationOptions options = new CorrelationOptions()
                .setThreadGap(Duration.ofSeconds(30))
                .setInheritTraceFromThread(false);

        List<EventGroup> groups = new Correlator(options).correlate(List.of(
                event("2026-08-09T10:00:00Z", "worker-1", null, "первый запрос"),
                event("2026-08-09T10:00:01Z", "worker-1", null, "первый запрос, шаг 2"),
                event("2026-08-09T10:10:00Z", "worker-1", null, "совсем другой запрос")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).getKind()).isEqualTo(CorrelationKind.THREAD);
        assertThat(groups.get(0).size()).isEqualTo(2);
        assertThat(groups.get(1).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Событие с sessionId не вырывается из активной цепочки своего потока")
    void keepsSessionEventInsideActiveTrace() {
        LogEvent withSession = LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse("2026-08-09T10:00:01Z"))
                .thread("http-1")
                .sessionId("S-1")
                .message("сессия известна, трейс ещё не проставлен")
                .build();

        List<EventGroup> groups = new Correlator().correlate(List.of(
                event("2026-08-09T10:00:00Z", "http-1", "trace-A", "начало"),
                withSession));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getKey()).isEqualTo("trace-A");
    }

    @Test
    @DisplayName("Без корреляционных признаков события собираются по источнику")
    void fallsBackToFile() {
        LogEvent orphan = LogEvent.builder()
                .sequence(seq.getAndIncrement())
                .timestamp(Instant.parse("2026-08-09T10:00:00Z"))
                .message("ни трейса, ни потока")
                .source("app.log", 1)
                .build();

        List<EventGroup> groups = new Correlator().correlate(List.of(orphan));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getKind()).isEqualTo(CorrelationKind.FILE);
        assertThat(groups.get(0).getKey()).isEqualTo("app.log");
    }

    @Test
    @DisplayName("Группы упорядочены по времени первого события")
    void ordersGroupsByFirstEvent() {
        List<EventGroup> groups = new Correlator().correlate(List.of(
                event("2026-08-09T10:00:05Z", "http-2", "trace-B", "позже"),
                event("2026-08-09T10:00:00Z", "http-1", "trace-A", "раньше")));

        assertThat(groups.get(0).getKey()).isEqualTo("trace-A");
        assertThat(groups.get(1).getKey()).isEqualTo("trace-B");
    }
}
