package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Встроенные шаблоны строк лога")
class LogPatternTest {

    private final TimestampParser timestamps =
            new TimestampParser(ZoneOffset.UTC, LocalDate.of(2026, 8, 9));

    /** Прогоняет строку через встроенные шаблоны так же, как это делает ингестор. */
    private LogEvent parse(String line) {
        for (LogPattern pattern : LogPattern.builtins()) {
            Optional<LogEvent.Builder> hit = pattern.match(line, timestamps);
            if (hit.isPresent()) {
                return hit.get().build();
            }
        }
        return null;
    }

    private String matchedPatternName(String line) {
        for (LogPattern pattern : LogPattern.builtins()) {
            if (pattern.match(line, timestamps).isPresent()) {
                return pattern.name();
            }
        }
        return null;
    }

    @Test
    @DisplayName("Spring Boot 3 с блоком traceId и именем приложения")
    void parsesSpringBoot3WithTracing() {
        LogEvent event = parse("2026-08-09T10:04:11.219+03:00 DEBUG 12345 --- [order-service] "
                + "[nio-8080-exec-3] [8f3c2a1b4d5e6f70,4b2a9c1d3e5f7a80] c.e.o.service.OrderService   : Валидация");

        assertThat(event).isNotNull();
        assertThat(event.getLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(event.getThread()).isEqualTo("nio-8080-exec-3");
        assertThat(event.getLogger()).isEqualTo("c.e.o.service.OrderService");
        assertThat(event.getTraceId()).isEqualTo("8f3c2a1b4d5e6f70");
        assertThat(event.getSpanId()).isEqualTo("4b2a9c1d3e5f7a80");
        assertThat(event.getService()).isEqualTo("order-service");
        assertThat(event.getMessage()).isEqualTo("Валидация");
    }

    @Test
    @DisplayName("Spring Boot 3 с пустым блоком трассировки")
    void parsesSpringBoot3WithEmptyTraceBlock() {
        LogEvent event = parse("2026-08-09T10:00:00.101+03:00  INFO 12345 --- [order-service] "
                + "[           main] [                    ] c.e.o.App                : Starting");

        assertThat(event).isNotNull();
        assertThat(event.getThread()).isEqualTo("main");
        assertThat(event.getLogger()).isEqualTo("c.e.o.App");
        assertThat(event.getTraceId()).isNull();
        assertThat(event.getMessage()).isEqualTo("Starting");
    }

    @Test
    @DisplayName("Spring Boot 3 без имени приложения и без трассировки")
    void parsesPlainSpringBoot3() {
        LogEvent event = parse("2026-08-09T10:00:00.101+03:00  WARN 999 --- [           main] "
                + "c.e.Config                               : Свойство не задано");

        assertThat(event).isNotNull();
        assertThat(event.getLevel()).isEqualTo(LogLevel.WARN);
        assertThat(event.getThread()).isEqualTo("main");
        assertThat(event.getLogger()).isEqualTo("c.e.Config");
    }

    @Test
    @DisplayName("Spring Boot 2 со Sleuth: [app,traceId,spanId,exportable]")
    void parsesSleuthFormat() {
        LogEvent event = parse("2026-08-09 11:20:14.331  INFO "
                + "[billing-service,5e6f7a8b9c0d1e2f,3a4b5c6d7e8f9a0b,true] 4821 --- [nio-8082-exec-4] "
                + "c.e.b.web.InvoiceController              : Получен запрос");

        assertThat(matchedPatternName("2026-08-09 11:20:14.331  INFO "
                + "[billing-service,5e6f7a8b9c0d1e2f,3a4b5c6d7e8f9a0b,true] 4821 --- [nio-8082-exec-4] c.e.X : m"))
                .isEqualTo("spring-boot-sleuth");
        assertThat(event).isNotNull();
        assertThat(event.getService()).isEqualTo("billing-service");
        assertThat(event.getTraceId()).isEqualTo("5e6f7a8b9c0d1e2f");
        assertThat(event.getSpanId()).isEqualTo("3a4b5c6d7e8f9a0b");
        assertThat(event.getThread()).isEqualTo("nio-8082-exec-4");
    }

    @Test
    @DisplayName("Стандартный шаблон Logback: время [поток] УРОВЕНЬ логгер - сообщение")
    void parsesDefaultLogback() {
        LogEvent event = parse("10:00:00.123 [main] INFO  com.example.Service - Process started");

        assertThat(event).isNotNull();
        assertThat(event.getThread()).isEqualTo("main");
        assertThat(event.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(event.getLogger()).isEqualTo("com.example.Service");
        assertThat(event.getMessage()).isEqualTo("Process started");
    }

    @Test
    @DisplayName("Минимальный формат: время УРОВЕНЬ логгер - сообщение")
    void parsesMinimalFormat() {
        LogEvent event = parse("2026-08-09T10:00:02 ERROR com.example.Service - Unexpected error");

        assertThat(event).isNotNull();
        assertThat(event.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(event.getLogger()).isEqualTo("com.example.Service");
        assertThat(event.getMessage()).isEqualTo("Unexpected error");
    }

    @Test
    @DisplayName("Пользовательский шаблон подключается одной строкой regex")
    void supportsCustomPattern() {
        LogPattern custom = LogPattern.of("custom",
                "^(?<ts>\\S+)\\|(?<level>\\w+)\\|(?<logger>[^|]+)\\|(?<msg>.*)$");

        LogEvent event = custom.match("2026-08-09T10:00:00Z|ERROR|billing|Сбой оплаты", timestamps)
                .orElseThrow()
                .build();

        assertThat(event.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(event.getLogger()).isEqualTo("billing");
        assertThat(event.getMessage()).isEqualTo("Сбой оплаты");
    }

    @Test
    @DisplayName("Лог платёжного API: дата через дефис, поток в скобках, логгер сокращён")
    void parsesDashDateWithThread() {
        LogEvent event = parse("10-08-2026 00:02:28.750 [ajp-nio-8809-exec-23] ERROR "
                + "r.s.p.a.s.s.p.PaymentServiceImpl - Не найдено в очереди external 92df4dcc");

        assertThat(event).isNotNull();
        assertThat(event.getTimestamp()).isEqualTo("2026-08-10T00:02:28.750Z");
        assertThat(event.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(event.getThread()).isEqualTo("ajp-nio-8809-exec-23");
        assertThat(event.getLogger()).isEqualTo("r.s.p.a.s.s.p.PaymentServiceImpl");
        assertThat(event.getMessage()).startsWith("Не найдено в очереди");
    }

    @Test
    @DisplayName("Лог воркера: поле в скобках становится ключом корреляции")
    void parsesWorkerLineWithCorrelationField() {
        LogEvent event = parse("00:00:25,564  INFO RMI TCP Connection(21133)-10.122.114.6 "
                + "[996997565647] - Make onlineVerify: Payment{id=172500367}");

        assertThat(event).isNotNull();
        assertThat(matchedPatternName("00:00:25,564  INFO RMI TCP Connection(21133)-10.122.114.6 "
                + "[996997565647] - Make onlineVerify")).isEqualTo("level-thread-ref");
        assertThat(event.getThread()).isEqualTo("RMI TCP Connection(21133)-10.122.114.6");
        // Идентификатор в скобках — то, по чему собирается цепочка одной операции
        assertThat(event.getTraceId()).isEqualTo("996997565647");
        assertThat(event.getMessage()).isEqualTo("Make onlineVerify: Payment{id=172500367}");
    }

    @Test
    @DisplayName("Лог воркера с пустым полем корреляции разбирается как обычная запись")
    void parsesWorkerLineWithEmptyReference() {
        LogEvent event = parse("00:00:00,124  INFO UniversalChildWorkerV3-&33:0/1 [] - "
                + "Next payment: Payment{id=173497766}");

        assertThat(event).isNotNull();
        assertThat(event.getThread()).isEqualTo("UniversalChildWorkerV3-&33:0/1");
        assertThat(event.getTraceId()).isNull();
        assertThat(event.getMessage()).startsWith("Next payment");
    }

    @Test
    @DisplayName("Шаблоны применяются в порядке от специфичных к общим")
    void appliesSpecificPatternsFirst() {
        List<String> names = LogPattern.builtins().stream().map(LogPattern::name).toList();
        assertThat(names.indexOf("spring-boot")).isLessThan(names.indexOf("ts-level"));
        assertThat(names.indexOf("logback")).isLessThan(names.indexOf("ts-only"));
    }
}
