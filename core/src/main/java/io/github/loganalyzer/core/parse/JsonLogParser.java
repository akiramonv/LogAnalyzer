package io.github.loganalyzer.core.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Разбор структурированных JSON-логов. Поддерживает распространённые схемы:
 * Logstash/logstash-logback-encoder, Elastic Common Schema (ECS), Log4j2 JSON layout,
 * GELF и произвольные плоские объекты.
 *
 * <p>Соответствие полей задаётся списками алиасов, поэтому новый формат обычно
 * подхватывается без изменений кода; всё, что не распознано, сохраняется в
 * {@code attributes} и остаётся доступно правилам.
 */
public final class JsonLogParser {

    private static final List<String> TIMESTAMP_KEYS = List.of(
            "@timestamp", "timestamp", "time", "ts", "eventTime", "event_time", "date", "@time");
    private static final List<String> LEVEL_KEYS = List.of(
            "level", "severity", "log.level", "loglevel", "levelStr", "level_name", "priority", "log_level");
    private static final List<String> MESSAGE_KEYS = List.of(
            "message", "msg", "@message", "short_message", "text", "log", "body", "event");
    private static final List<String> LOGGER_KEYS = List.of(
            "logger_name", "loggerName", "logger", "log.logger", "category", "class", "source", "log.origin.file.name");
    private static final List<String> THREAD_KEYS = List.of(
            "thread_name", "threadName", "thread", "log.origin.thread.name", "process.thread.name");
    private static final List<String> TRACE_KEYS = List.of(
            "traceId", "trace_id", "traceID", "trace.id", "dd.trace_id", "X-B3-TraceId", "traceid");
    private static final List<String> SPAN_KEYS = List.of(
            "spanId", "span_id", "spanID", "span.id", "dd.span_id", "X-B3-SpanId");
    private static final List<String> SESSION_KEYS = List.of(
            "sessionId", "session_id", "session.id", "sid", "JSESSIONID");
    private static final List<String> REQUEST_KEYS = List.of(
            "requestId", "request_id", "correlationId", "correlation_id", "x-request-id", "http.request.id");
    private static final List<String> USER_KEYS = List.of(
            "userId", "user_id", "user.id", "user.name", "principal", "username");
    private static final List<String> SERVICE_KEYS = List.of(
            "service", "service.name", "serviceName", "application", "app", "appName",
            "application_name", "spring.application.name", "kubernetes.container_name");
    private static final List<String> STACK_KEYS = List.of(
            "stack_trace", "stackTrace", "stacktrace", "exception", "throwable", "error.stack_trace",
            "error.stacktrace", "exception.stacktrace", "err", "error");
    private static final List<String> MDC_KEYS = List.of(
            "mdc", "contextMap", "context", "labels", "extra", "fields");

    /** Ключи, которые уже разобраны в поля события и не должны дублироваться в attributes. */
    private static final Set<String> CONSUMED = Set.of(
            "@timestamp", "timestamp", "time", "ts", "level", "severity", "message", "msg",
            "logger_name", "loggerName", "logger", "thread_name", "threadName", "thread",
            "traceId", "trace_id", "spanId", "span_id", "stack_trace", "stackTrace",
            "exception", "throwable", "mdc", "contextMap", "@version", "sessionId", "requestId");

    private final ObjectMapper mapper;
    private final TimestampParser timestamps;

    public JsonLogParser(TimestampParser timestamps) {
        this(new ObjectMapper(), timestamps);
    }

    public JsonLogParser(ObjectMapper mapper, TimestampParser timestamps) {
        this.mapper = mapper;
        this.timestamps = timestamps;
    }

    /** @return {@code true} если строка выглядит как самостоятельный JSON-объект. */
    public static boolean looksLikeJson(String line) {
        if (line == null) {
            return false;
        }
        String s = line.trim();
        return s.length() > 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}';
    }

    /**
     * Разбирает JSON-строку лога.
     *
     * @return строитель события или {@code null}, если строка не является корректным JSON-объектом
     */
    public LogEvent.Builder parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            return parse((ObjectNode) node);
        } catch (Exception e) {
            return null;
        }
    }

    /** Разбирает уже прочитанный JSON-объект. */
    public LogEvent.Builder parse(ObjectNode node) {
        LogEvent.Builder b = LogEvent.builder();

        Instant ts = readTimestamp(node);
        if (ts != null) {
            b.timestamp(ts);
        }
        String level = firstText(node, LEVEL_KEYS);
        b.level(LogLevel.parse(level));

        String message = firstText(node, MESSAGE_KEYS);
        b.message(message == null ? "" : message);
        b.logger(firstText(node, LOGGER_KEYS));
        b.thread(firstText(node, THREAD_KEYS));
        b.traceId(firstText(node, TRACE_KEYS));
        b.spanId(firstText(node, SPAN_KEYS));
        b.sessionId(firstText(node, SESSION_KEYS));
        b.requestId(firstText(node, REQUEST_KEYS));
        b.userId(firstText(node, USER_KEYS));
        b.service(firstText(node, SERVICE_KEYS));

        // MDC/контекст: плоско переносим в attributes и заодно ищем в нём корреляционные ключи
        for (String mdcKey : MDC_KEYS) {
            JsonNode mdc = node.get(mdcKey);
            if (mdc != null && mdc.isObject()) {
                mdc.properties().forEach(e -> {
                    if (e.getValue().isValueNode()) {
                        applyKnownKey(b, e.getKey(), e.getValue().asText());
                        b.attribute(e.getKey(), e.getValue().asText());
                    }
                });
            }
        }

        ExceptionInfo exception = readException(node);
        if (exception != null) {
            b.exception(exception);
            b.kind(EventKind.EXCEPTION);
        }

        HttpExchange http = readHttp(node);
        if (http != null) {
            b.http(http);
        }

        // Всё остальное — в attributes (скалярные значения и «точечные» пути глубиной до 3)
        collectAttributes(node, "", b, 0);

        // Дополняем то, что не нашлось в полях, разбором текста сообщения
        if (message != null) {
            AttributeExtractor.enrich(b, message);
        }
        return b;
    }

    private void applyKnownKey(LogEvent.Builder b, String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        if (TRACE_KEYS.stream().anyMatch(t -> t.equalsIgnoreCase(k))) {
            b.traceId(value);
        } else if (SPAN_KEYS.stream().anyMatch(t -> t.equalsIgnoreCase(k))) {
            b.spanId(value);
        } else if (SESSION_KEYS.stream().anyMatch(t -> t.equalsIgnoreCase(k))) {
            b.sessionId(value);
        } else if (REQUEST_KEYS.stream().anyMatch(t -> t.equalsIgnoreCase(k))) {
            b.requestId(value);
        } else if (USER_KEYS.stream().anyMatch(t -> t.equalsIgnoreCase(k))) {
            b.userId(value);
        }
    }

    private Instant readTimestamp(JsonNode node) {
        // Log4j2 JSON layout: {"instant":{"epochSecond":1699,"nanoOfSecond":123000000}}
        JsonNode instant = node.get("instant");
        if (instant != null && instant.isObject() && instant.has("epochSecond")) {
            long sec = instant.get("epochSecond").asLong();
            long nano = instant.has("nanoOfSecond") ? instant.get("nanoOfSecond").asLong() : 0L;
            return Instant.ofEpochSecond(sec, nano);
        }
        for (String key : TIMESTAMP_KEYS) {
            JsonNode v = resolve(node, key);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isNumber()) {
                long raw = v.asLong();
                return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
            }
            Instant parsed = timestamps.parse(v.asText());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private ExceptionInfo readException(JsonNode node) {
        for (String key : STACK_KEYS) {
            JsonNode v = resolve(node, key);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isTextual()) {
                String text = v.asText();
                if (text.isBlank()) {
                    continue;
                }
                ExceptionInfo parsed = StackTraceParser.parse(List.of(text.split("\\R")));
                if (parsed != null) {
                    return parsed;
                }
            } else if (v.isObject()) {
                // logstash-logback-encoder / ECS: {"exception":{"exception_class":..,"exception_message":..,"stacktrace":..}}
                String stack = firstText(v, List.of("stacktrace", "stack_trace", "stackTrace", "trace"));
                if (stack != null && !stack.isBlank()) {
                    ExceptionInfo parsed = StackTraceParser.parse(List.of(stack.split("\\R")));
                    if (parsed != null) {
                        return parsed;
                    }
                }
                String type = firstText(v, List.of("exception_class", "class", "type", "name"));
                String msg = firstText(v, List.of("exception_message", "message", "msg"));
                if (type != null || msg != null) {
                    return new ExceptionInfo(type, msg, List.of(), null, List.of(), 0);
                }
            }
        }
        return null;
    }

    private HttpExchange readHttp(JsonNode node) {
        String method = firstText(node, List.of("http.request.method", "method", "httpMethod",
                "http_method", "request.method", "verb"));
        String url = firstText(node, List.of("url.full", "url.path", "uri", "url", "path",
                "requestURI", "request.uri", "http.url", "request_uri"));
        String statusText = firstText(node, List.of("http.response.status_code", "status", "statusCode",
                "status_code", "response.status", "responseStatus", "http_status"));
        String durationText = firstText(node, List.of("event.duration", "duration", "durationMs",
                "duration_ms", "elapsed", "responseTime", "took"));

        if (method == null && url == null && statusText == null) {
            return null;
        }
        HttpExchange.Builder b = HttpExchange.builder()
                .method(method)
                .url(url);
        if (statusText != null) {
            try {
                b.status(Integer.valueOf(statusText.trim()));
            } catch (NumberFormatException ignored) {
                // статус в нечисловом виде игнорируем
            }
        }
        if (durationText != null) {
            try {
                b.durationMs((long) Double.parseDouble(durationText.replace(',', '.')));
            } catch (NumberFormatException ignored) {
                // длительность в непонятном формате
            }
        }
        String host = firstText(node, List.of("url.domain", "host", "hostname", "server", "target"));
        b.host(host);
        b.direction(url != null && url.startsWith("http")
                ? HttpExchange.Direction.OUTBOUND
                : HttpExchange.Direction.INBOUND);
        return b.build();
    }

    /** Плоско переносит скалярные поля в attributes, чтобы правила могли по ним работать. */
    private void collectAttributes(JsonNode node, String prefix, LogEvent.Builder b, int depth) {
        if (depth > 3) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode value = e.getValue();
            if (value.isObject()) {
                collectAttributes(value, key, b, depth + 1);
            } else if (value.isValueNode()) {
                if (CONSUMED.contains(e.getKey()) && prefix.isEmpty()) {
                    continue;
                }
                String text = value.asText();
                if (text != null && !text.isBlank() && text.length() <= 500) {
                    b.attribute(key, text);
                }
            }
        }
    }

    /** Читает первое непустое строковое значение по списку ключей (поддерживает «точечные» пути). */
    private static String firstText(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode v = resolve(node, key);
            if (v != null && !v.isNull() && !v.isContainerNode()) {
                String text = v.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Разрешает ключ: сначала как есть (поле может буквально называться {@code "log.level"}),
     * затем как путь по вложенным объектам ({@code log -> level}).
     */
    private static JsonNode resolve(JsonNode node, String key) {
        JsonNode direct = node.get(key);
        if (direct != null) {
            return direct;
        }
        if (!key.contains(".")) {
            return null;
        }
        JsonNode current = node;
        for (String part : key.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }
}
