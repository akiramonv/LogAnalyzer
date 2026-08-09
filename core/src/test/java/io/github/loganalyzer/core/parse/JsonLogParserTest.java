package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Разбор структурированных JSON-логов")
class JsonLogParserTest {

    private final JsonLogParser parser =
            new JsonLogParser(new TimestampParser(ZoneOffset.UTC, LocalDate.of(2026, 8, 9)));

    @Test
    @DisplayName("Формат logstash-logback-encoder")
    void parsesLogstashFormat() {
        LogEvent event = parser.parse("""
                {"@timestamp":"2026-08-09T10:05:03.100Z","level":"ERROR","logger_name":"com.example.Service",
                 "thread_name":"http-1","message":"Failed to process data","traceId":"XYZ","spanId":"SPAN1",
                 "service":"order-service"}
                """).build();

        assertThat(event.getTimestamp()).isEqualTo(Instant.parse("2026-08-09T10:05:03.100Z"));
        assertThat(event.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(event.getLogger()).isEqualTo("com.example.Service");
        assertThat(event.getThread()).isEqualTo("http-1");
        assertThat(event.getTraceId()).isEqualTo("XYZ");
        assertThat(event.getSpanId()).isEqualTo("SPAN1");
        assertThat(event.getService()).isEqualTo("order-service");
        assertThat(event.getMessage()).isEqualTo("Failed to process data");
    }

    @Test
    @DisplayName("Схема Elastic Common Schema с вложенными полями")
    void parsesEcsFormat() {
        LogEvent event = parser.parse("""
                {"@timestamp":"2026-08-09T10:05:03Z","log":{"level":"WARN","logger":"com.example.Ecs"},
                 "message":"almost broken","trace":{"id":"ecs-trace-1"},"service":{"name":"payments"}}
                """).build();

        assertThat(event.getLevel()).isEqualTo(LogLevel.WARN);
        assertThat(event.getLogger()).isEqualTo("com.example.Ecs");
        assertThat(event.getTraceId()).isEqualTo("ecs-trace-1");
        assertThat(event.getService()).isEqualTo("payments");
    }

    @Test
    @DisplayName("Стек-трейс из текстового поля stack_trace")
    void parsesStackTraceField() {
        LogEvent event = parser.parse("""
                {"@timestamp":"2026-08-09T10:05:03Z","level":"ERROR","message":"boom",
                 "stack_trace":"org.springframework.web.client.ResourceAccessException: I/O error\\n\\tat com.example.Client.call(Client.java:74)\\nCaused by: java.net.SocketTimeoutException: Read timed out\\n\\tat java.base/java.net.Socket.read(Socket.java:966)"}
                """).build();

        assertThat(event.getException()).isNotNull();
        assertThat(event.getException().simpleType()).isEqualTo("ResourceAccessException");
        assertThat(event.getException().rootCause().simpleType()).isEqualTo("SocketTimeoutException");
    }

    @Test
    @DisplayName("Исключение, заданное объектом, а не строкой")
    void parsesExceptionObject() {
        LogEvent event = parser.parse("""
                {"timestamp":"2026-08-09T10:05:03Z","level":"ERROR","message":"boom",
                 "exception":{"exception_class":"java.lang.IllegalArgumentException","exception_message":"Invalid ID"}}
                """).build();

        assertThat(event.getException().getType()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(event.getException().getMessage()).isEqualTo("Invalid ID");
    }

    @Test
    @DisplayName("Поля HTTP и MDC переносятся в событие")
    void parsesHttpAndMdc() {
        LogEvent event = parser.parse("""
                {"@timestamp":"2026-08-09T10:05:03Z","level":"INFO","message":"ответ отправлен",
                 "method":"POST","uri":"/api/v1/payments","status":502,"duration":43630,
                 "mdc":{"sessionId":"S-77102","userId":"4417","tenant":"acme"}}
                """).build();

        assertThat(event.getHttp()).isNotNull();
        assertThat(event.getHttp().getMethod()).isEqualTo("POST");
        assertThat(event.getHttp().getStatus()).isEqualTo(502);
        assertThat(event.getHttp().getDurationMs()).isEqualTo(43630L);
        assertThat(event.getHttp().isServerError()).isTrue();
        assertThat(event.getSessionId()).isEqualTo("S-77102");
        assertThat(event.getUserId()).isEqualTo("4417");
        assertThat(event.getAttributes()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("Формат Log4j2 c объектом instant")
    void parsesLog4j2Instant() {
        LogEvent event = parser.parse("""
                {"instant":{"epochSecond":1786312800,"nanoOfSecond":123000000},"level":"INFO",
                 "loggerName":"com.example.L4j","message":"ok","thread":"main"}
                """).build();

        assertThat(event.getTimestamp()).isEqualTo(Instant.ofEpochSecond(1786312800, 123000000));
        assertThat(event.getLogger()).isEqualTo("com.example.L4j");
    }

    @Test
    @DisplayName("Некорректный JSON не приводит к исключению")
    void returnsNullForBrokenJson() {
        assertThat(parser.parse("{это не json}")).isNull();
        assertThat(JsonLogParser.looksLikeJson("обычная строка")).isFalse();
        assertThat(JsonLogParser.looksLikeJson("{\"a\":1}")).isTrue();
    }
}
