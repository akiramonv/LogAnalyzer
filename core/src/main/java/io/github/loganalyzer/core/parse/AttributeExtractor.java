package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт корреляционные идентификаторы и характеристики вызова прямо из текста сообщения.
 *
 * <p>Нужен для логов, где traceId не вынесен в отдельное поле формата, а печатается внутри
 * сообщения: {@code Processing order traceId=8f3c2a... sessionId=... duration=1203ms status=500}.
 * Такое встречается в большинстве приложений, где трассировку добавляли «руками».
 */
public final class AttributeExtractor {

    /** Ключи, которые считаем идентификатором трассировки. */
    private static final List<String> TRACE_KEYS =
            List.of("traceid", "trace_id", "trace-id", "x-b3-traceid", "x-trace-id", "traceparent");
    private static final List<String> SPAN_KEYS =
            List.of("spanid", "span_id", "span-id", "x-b3-spanid");
    private static final List<String> SESSION_KEYS =
            List.of("sessionid", "session_id", "session-id", "jsessionid", "sid");
    private static final List<String> REQUEST_KEYS =
            List.of("requestid", "request_id", "request-id", "x-request-id", "correlationid",
                    "correlation_id", "correlation-id", "x-correlation-id", "cid");
    private static final List<String> USER_KEYS =
            List.of("userid", "user_id", "user-id", "username", "principal");

    /** {@code key=value}, {@code key: value}, {@code [key=value]}; значение может быть в кавычках. */
    private static final Pattern KV = Pattern.compile(
            "(?<key>[A-Za-z][\\w.\\-]{1,40})\\s*[=:]\\s*(?<value>\"[^\"]{1,200}\"|'[^']{1,200}'|[^\\s,;)\\]}]{1,200})");

    /** Длительность: {@code 1203 ms}, {@code took 1203ms}, {@code elapsed=1.2s}. */
    private static final Pattern DURATION = Pattern.compile(
            "(?i)(?:took|elapsed|duration|latency|time)\\s*[=:]?\\s*(?<num>\\d+(?:[.,]\\d+)?)\\s*(?<unit>ms|s|sec|seconds|milliseconds)\\b"
            + "|\\b(?<num2>\\d{1,9})\\s?(?<unit2>ms)\\b");

    /** HTTP-метод и путь внутри сообщения: {@code GET /api/user?id=1}, {@code POST https://host/path}. */
    private static final Pattern HTTP_CALL = Pattern.compile(
            "\\b(?<method>GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(?<url>(?:https?://[^\\s\"']+|/[^\\s\"']*))");

    /** HTTP-статус: {@code status=500}, {@code 500 Internal Server Error}, {@code HTTP/1.1 404}. */
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "(?i)(?:status(?:code)?\\s*[=:]\\s*(?<s1>[1-5]\\d{2})"
            + "|HTTP/\\d(?:\\.\\d)?\\s+(?<s2>[1-5]\\d{2})"
            + "|\\bresponded\\s+with\\s+(?<s3>[1-5]\\d{2})\\b)");

    private AttributeExtractor() {
    }

    /**
     * Обогащает строитель события данными, найденными в тексте.
     * Уже установленные значения не перезаписываются — поля формата надёжнее, чем текст.
     */
    public static void enrich(LogEvent.Builder builder, String text) {
        if (builder == null || text == null || text.isEmpty()) {
            return;
        }
        Matcher kv = KV.matcher(text);
        while (kv.find()) {
            String key = kv.group("key").toLowerCase(Locale.ROOT);
            String value = unquote(kv.group("value"));
            if (value.isEmpty() || "null".equalsIgnoreCase(value) || "-".equals(value)) {
                continue;
            }
            if (TRACE_KEYS.contains(key)) {
                builder.traceId(normalizeTraceParent(key, value));
            } else if (SPAN_KEYS.contains(key)) {
                builder.spanId(value);
            } else if (SESSION_KEYS.contains(key)) {
                builder.sessionId(value);
            } else if (REQUEST_KEYS.contains(key)) {
                builder.requestId(value);
            } else if (USER_KEYS.contains(key)) {
                builder.userId(value);
            }
        }

        Long duration = extractDurationMs(text);
        if (duration != null) {
            builder.attribute("durationMs", duration.toString());
        }
    }

    /** @return длительность операции в миллисекундах, если она указана в тексте. */
    public static Long extractDurationMs(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DURATION.matcher(text);
        if (!m.find()) {
            return null;
        }
        String num = m.group("num") != null ? m.group("num") : m.group("num2");
        String unit = m.group("unit") != null ? m.group("unit") : m.group("unit2");
        if (num == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(num.replace(',', '.'));
            if (unit != null && !unit.toLowerCase(Locale.ROOT).startsWith("m")) {
                value *= 1000;
            }
            return Math.round(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Пытается собрать {@link HttpExchange} из текста сообщения
     * (для логов вида {@code Calling payment service: POST /v1/pay -> 504 (30012 ms)}).
     *
     * @return обмен или {@code null}, если HTTP-признаков в тексте нет
     */
    public static HttpExchange extractHttp(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher call = HTTP_CALL.matcher(text);
        Matcher status = HTTP_STATUS.matcher(text);
        boolean hasCall = call.find();
        boolean hasStatus = status.find();
        if (!hasCall && !hasStatus) {
            return null;
        }
        HttpExchange.Builder b = HttpExchange.builder();
        if (hasCall) {
            b.method(call.group("method"));
            String url = call.group("url");
            b.url(url);
            if (url.startsWith("http")) {
                b.direction(HttpExchange.Direction.OUTBOUND);
                try {
                    b.host(java.net.URI.create(url).getHost());
                } catch (RuntimeException ignored) {
                    // некорректный URL в логе — не критично
                }
            }
        }
        if (hasStatus) {
            String code = firstNonNull(status.group("s1"), status.group("s2"), status.group("s3"));
            if (code != null) {
                b.status(Integer.valueOf(code));
            }
        }
        Long duration = extractDurationMs(text);
        if (duration != null) {
            b.durationMs(duration);
        }
        return b.build();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** {@code traceparent} имеет вид {@code 00-<traceId>-<spanId>-01}. */
    private static String normalizeTraceParent(String key, String value) {
        if ("traceparent".equals(key)) {
            String[] parts = value.split("-");
            if (parts.length >= 3) {
                return parts[1];
            }
        }
        return value;
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
