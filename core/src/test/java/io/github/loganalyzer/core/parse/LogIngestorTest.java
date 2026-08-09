package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Потоковый разбор источника логов")
class LogIngestorTest {

    private LogIngestor ingestor() {
        return new LogIngestor(new ParseOptions()
                .setZone(ZoneOffset.UTC)
                .setDefaultDate(LocalDate.of(2026, 8, 9)));
    }

    @Test
    @DisplayName("Стек-трейс присоединяется к своей записи, а не становится отдельным событием")
    void attachesStackTraceToOwningEvent() {
        String log = """
                2026-08-09T10:00:01 INFO  com.example.Service - Обработка начата
                2026-08-09T10:00:02 ERROR com.example.Service - Unexpected error
                java.lang.NullPointerException: profile is null
                \tat com.example.Service.process(Service.java:50)
                \tat com.example.App.main(App.java:10)
                2026-08-09T10:00:03 INFO  com.example.Service - Следующая запись
                """;

        List<LogEvent> events = ingestor().ingestText(log, "test.log").getEvents();

        assertThat(events).hasSize(3);
        LogEvent error = events.get(1);
        assertThat(error.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(error.getKind()).isEqualTo(EventKind.EXCEPTION);
        assertThat(error.getMessage()).isEqualTo("Unexpected error");
        assertThat(error.getException()).isNotNull();
        assertThat(error.getException().simpleType()).isEqualTo("NullPointerException");
        assertThat(error.getException().getFrames()).hasSize(2);
    }

    @Test
    @DisplayName("Заголовок исключения в самой строке сообщения тоже распознаётся")
    void parsesExceptionHeaderInsideMessage() {
        String log = """
                2026-08-09T10:00:02 ERROR com.example.Service - java.lang.IllegalStateException: сломалось
                \tat com.example.Service.process(Service.java:50)
                """;

        LogEvent event = ingestor().ingestText(log, "test.log").getEvents().get(0);

        assertThat(event.getException()).isNotNull();
        assertThat(event.getException().simpleType()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("Многострочное сообщение без стека остаётся одним событием")
    void keepsMultilineMessageTogether() {
        String log = """
                2026-08-09T10:00:01 INFO com.example.Sql - Выполняется запрос:
                SELECT *
                  FROM orders
                 WHERE id = 42
                2026-08-09T10:00:02 INFO com.example.Sql - Готово
                """;

        List<LogEvent> events = ingestor().ingestText(log, "test.log").getEvents();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage()).contains("SELECT *").contains("WHERE id = 42");
        assertThat(events.get(0).getException()).isNull();
    }

    @Test
    @DisplayName("В одном источнике смешиваются текстовые и JSON-записи")
    void handlesMixedFormats() {
        String log = """
                2026-08-09T10:00:01 INFO com.example.Service - текстовая запись
                {"@timestamp":"2026-08-09T10:00:02Z","level":"ERROR","logger_name":"com.example.Json",\
                "message":"json-запись","traceId":"abc123"}
                2026-08-09T10:00:03 INFO com.example.Service - снова текст
                """;

        List<LogEvent> events = ingestor().ingestText(log, "test.log").getEvents();

        assertThat(events).hasSize(3);
        assertThat(events.get(1).getLogger()).isEqualTo("com.example.Json");
        assertThat(events.get(1).getTraceId()).isEqualTo("abc123");
        assertThat(events.get(1).getLevel()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    @DisplayName("HTTP-дамп превращается в событие с методом, URL и статусом")
    void parsesHttpDump() {
        String log = """
                GET /api/user?id=abc123 HTTP/1.1
                Host: example.com
                Accept: application/json

                HTTP/1.1 400 Bad Request
                Content-Type: application/json

                {"status":400,"message":"Number format exception"}
                """;

        List<LogEvent> events = ingestor().ingestText(log, "dump.log").getEvents();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getHttp().getMethod()).isEqualTo("GET");
        assertThat(events.get(0).getHttp().getUrl()).isEqualTo("/api/user?id=abc123");
        assertThat(events.get(0).getHttp().getHost()).isEqualTo("example.com");
        assertThat(events.get(1).getHttp().getStatus()).isEqualTo(400);
        assertThat(events.get(1).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(events.get(1).getHttp().getBody()).contains("Number format exception");
    }

    @Test
    @DisplayName("Стек-трейс без предшествующей записи становится самостоятельным событием")
    void handlesOrphanStackTrace() {
        String log = """
                java.lang.OutOfMemoryError: Java heap space
                \tat com.example.Cache.load(Cache.java:88)
                """;

        List<LogEvent> events = ingestor().ingestText(log, "test.log").getEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(events.get(0).getException().simpleType()).isEqualTo("OutOfMemoryError");
    }

    @Test
    @DisplayName("Идентификаторы из текста сообщения попадают в поля события")
    void extractsIdentifiersFromMessage() {
        String log = "2026-08-09T10:00:01 INFO com.example.Service - "
                + "Обработка traceId=8f3c2a1b sessionId=S-9021 userId=4417 duration=1500ms\n";

        LogEvent event = ingestor().ingestText(log, "test.log").getEvents().get(0);

        assertThat(event.getTraceId()).isEqualTo("8f3c2a1b");
        assertThat(event.getSessionId()).isEqualTo("S-9021");
        assertThat(event.getUserId()).isEqualTo("4417");
        assertThat(event.getAttributes()).containsEntry("durationMs", "1500");
    }

    @Test
    @DisplayName("Нераспознанные строки считаются и сохраняются как есть")
    void countsUnparsedLines() {
        LogIngestor.Result result = ingestor().ingestText("совершенно посторонняя строка\n", "test.log");

        assertThat(result.getUnparsedLines()).isEqualTo(1);
        assertThat(result.getEvents()).hasSize(1);
        assertThat(result.getEvents().get(0).getKind()).isEqualTo(EventKind.UNPARSED);
    }

    @Test
    @DisplayName("Блок без своей метки времени относится к моменту предыдущей записи")
    void inheritsTimestampFromPreviousEvent() {
        String log = """
                2026-08-09T10:00:01 INFO com.example.Filter - Входящий запрос
                GET /api/user?id=abc123 HTTP/1.1
                Host: example.com
                """;

        List<LogEvent> events = ingestor().ingestText(log, "test.log").getEvents();

        assertThat(events).hasSize(2);
        assertThat(events.get(1).getTimestamp()).isEqualTo(events.get(0).getTimestamp());
        assertThat(events.get(1).getAttributes()).containsEntry("timestampInherited", "true");
    }

    @Test
    @DisplayName("Позиция записи в файле сохраняется для перехода к строке")
    void recordsSourcePosition() {
        String log = """
                2026-08-09T10:00:01 INFO com.example.A - первая
                2026-08-09T10:00:02 INFO com.example.B - вторая
                """;

        List<LogEvent> events = ingestor().ingestText(log, "app.log").getEvents();

        assertThat(events.get(0).getSourceFile()).isEqualTo("app.log");
        assertThat(events.get(0).getSourceLine()).isEqualTo(1);
        assertThat(events.get(1).getSourceLine()).isEqualTo(2);
    }
}
