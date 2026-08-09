package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Нормализованная запись лога — единая модель для всех источников
 * (текстовый лог, JSON-лог, стек-трейс, HTTP-дамп).
 *
 * <p>Объект неизменяемый, создаётся через {@link #builder()}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class LogEvent {

    /** Порядковый номер чтения — обеспечивает стабильную сортировку событий с одинаковой меткой времени. */
    private final long sequence;
    private final Instant timestamp;
    private final LogLevel level;
    private final String logger;
    private final String thread;
    private final String message;

    private final String traceId;
    private final String spanId;
    private final String sessionId;
    private final String requestId;
    private final String userId;
    private final String service;

    private final ExceptionInfo exception;
    private final HttpExchange http;
    private final EventKind kind;

    /** Дополнительные поля: MDC, произвольные ключи JSON-лога и т.п. */
    private final Map<String, String> attributes;

    /** Файл-источник и номер строки в нём — для перехода к месту в логе. */
    private final String sourceFile;
    private final int sourceLine;
    /** Исходный текст записи (включая многострочный хвост). */
    private final String raw;

    private LogEvent(Builder b) {
        this.sequence = b.sequence;
        this.timestamp = b.timestamp;
        this.level = b.level == null ? LogLevel.UNKNOWN : b.level;
        this.logger = b.logger;
        this.thread = b.thread;
        this.message = b.message == null ? "" : b.message;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.sessionId = b.sessionId;
        this.requestId = b.requestId;
        this.userId = b.userId;
        this.service = b.service;
        this.exception = b.exception;
        this.http = b.http;
        this.kind = b.kind == null ? defaultKind(b) : b.kind;
        this.attributes = b.attributes.isEmpty() ? Map.of() : Map.copyOf(b.attributes);
        this.sourceFile = b.sourceFile;
        this.sourceLine = b.sourceLine;
        this.raw = b.raw;
    }

    private static EventKind defaultKind(Builder b) {
        if (b.exception != null) {
            return EventKind.EXCEPTION;
        }
        if (b.http != null) {
            return b.http.getStatus() != null ? EventKind.HTTP_RESPONSE : EventKind.HTTP_REQUEST;
        }
        return EventKind.LOG;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return копия-строитель на основе текущего события (для обогащения при постобработке). */
    public Builder toBuilder() {
        return new Builder()
                .sequence(sequence)
                .timestamp(timestamp)
                .level(level)
                .logger(logger)
                .thread(thread)
                .message(message)
                .traceId(traceId)
                .spanId(spanId)
                .sessionId(sessionId)
                .requestId(requestId)
                .userId(userId)
                .service(service)
                .exception(exception)
                .http(http)
                .kind(kind)
                .attributes(attributes)
                .source(sourceFile, sourceLine)
                .raw(raw);
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getLogger() {
        return logger;
    }

    public String getThread() {
        return thread;
    }

    public String getMessage() {
        return message;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getUserId() {
        return userId;
    }

    public String getService() {
        return service;
    }

    public ExceptionInfo getException() {
        return exception;
    }

    public HttpExchange getHttp() {
        return http;
    }

    public EventKind getKind() {
        return kind;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public int getSourceLine() {
        return sourceLine;
    }

    public String getRaw() {
        return raw;
    }

    @JsonIgnore
    public boolean hasException() {
        return exception != null;
    }

    @JsonIgnore
    public boolean isError() {
        return level.isError() || exception != null || (http != null && http.isServerError());
    }

    /**
     * Текст, по которому работают правила: сообщение + заголовок исключения.
     * Позволяет писать одно правило вместо двух (по message и по exception).
     */
    @JsonIgnore
    public String searchableText() {
        StringBuilder sb = new StringBuilder(message);
        if (exception != null) {
            for (ExceptionInfo e : exception.chain()) {
                sb.append('\n').append(e.header());
            }
        }
        if (http != null) {
            sb.append('\n').append(http.summary());
        }
        return sb.toString();
    }

    /** @return краткая однострочная сводка для текстового отчёта. */
    @JsonIgnore
    public String summary() {
        String text = message;
        if ((text == null || text.isBlank()) && http != null) {
            text = http.summary();
        }
        if ((text == null || text.isBlank()) && exception != null) {
            text = exception.header();
        }
        return text == null ? "" : text;
    }

    @Override
    public String toString() {
        return (timestamp == null ? "?" : timestamp.toString()) + " " + level + " " + summary();
    }

    /** Builder для {@link LogEvent}. */
    public static final class Builder {
        private long sequence;
        private Instant timestamp;
        private LogLevel level;
        private String logger;
        private String thread;
        private String message;
        private String traceId;
        private String spanId;
        private String sessionId;
        private String requestId;
        private String userId;
        private String service;
        private ExceptionInfo exception;
        private HttpExchange http;
        private EventKind kind;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String sourceFile;
        private int sourceLine;
        private String raw;

        public Builder sequence(long v) {
            this.sequence = v;
            return this;
        }

        public Builder timestamp(Instant v) {
            this.timestamp = v;
            return this;
        }

        public Builder level(LogLevel v) {
            this.level = v;
            return this;
        }

        public Builder logger(String v) {
            this.logger = emptyToNull(v);
            return this;
        }

        public Builder thread(String v) {
            this.thread = emptyToNull(v);
            return this;
        }

        public Builder message(String v) {
            this.message = v;
            return this;
        }

        public Builder appendMessage(String v) {
            this.message = this.message == null ? v : this.message + "\n" + v;
            return this;
        }

        public Builder traceId(String v) {
            this.traceId = emptyToNull(v);
            return this;
        }

        public Builder spanId(String v) {
            this.spanId = emptyToNull(v);
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = emptyToNull(v);
            return this;
        }

        public Builder requestId(String v) {
            this.requestId = emptyToNull(v);
            return this;
        }

        public Builder userId(String v) {
            this.userId = emptyToNull(v);
            return this;
        }

        public Builder service(String v) {
            this.service = emptyToNull(v);
            return this;
        }

        public Builder exception(ExceptionInfo v) {
            this.exception = v;
            return this;
        }

        public Builder http(HttpExchange v) {
            this.http = v;
            return this;
        }

        public Builder kind(EventKind v) {
            this.kind = v;
            return this;
        }

        public Builder attribute(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, String> v) {
            if (v != null) {
                v.forEach(this::attribute);
            }
            return this;
        }

        public Builder source(String file, int line) {
            this.sourceFile = file;
            this.sourceLine = line;
            return this;
        }

        public Builder raw(String v) {
            this.raw = v;
            return this;
        }

        public Builder appendRaw(String v) {
            this.raw = this.raw == null ? v : this.raw + "\n" + v;
            return this;
        }

        /** @return значение поля traceId, уже установленное в строителе (нужно парсерам). */
        public String currentTraceId() {
            return traceId;
        }

        public LogEvent build() {
            return new LogEvent(this);
        }

        private static String emptyToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LogEvent other)) {
            return false;
        }
        return sequence == other.sequence
                && Objects.equals(timestamp, other.timestamp)
                && Objects.equals(message, other.message)
                && level == other.level;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, timestamp, message, level);
    }
}
