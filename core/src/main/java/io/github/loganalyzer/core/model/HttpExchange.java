package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP-обмен, извлечённый из лога: либо из полей структурированного JSON-лога,
 * либо из «сырого» дампа запроса/ответа, либо из текстового сообщения access-лога.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class HttpExchange {

    /** Направление вызова относительно анализируемого сервиса. */
    public enum Direction {
        /** Входящий запрос (сервис обслуживает клиента). */
        INBOUND,
        /** Исходящий вызов (сервис ходит во внешнюю систему). */
        OUTBOUND,
        UNKNOWN
    }

    private final Direction direction;
    private final String method;
    private final String url;
    private final String host;
    private final Integer status;
    private final String statusText;
    private final Long durationMs;
    private final Map<String, String> headers;
    private final String body;

    private HttpExchange(Builder b) {
        this.direction = b.direction == null ? Direction.UNKNOWN : b.direction;
        this.method = b.method;
        this.url = b.url;
        this.host = b.host;
        this.status = b.status;
        this.statusText = b.statusText;
        this.durationMs = b.durationMs;
        this.headers = b.headers.isEmpty() ? Map.of() : Map.copyOf(b.headers);
        this.body = b.body;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Direction getDirection() {
        return direction;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getHost() {
        return host;
    }

    public Integer getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    @JsonIgnore
    public boolean isClientError() {
        return status != null && status >= 400 && status < 500;
    }

    @JsonIgnore
    public boolean isServerError() {
        return status != null && status >= 500;
    }

    @JsonIgnore
    public boolean isFailure() {
        return isClientError() || isServerError();
    }

    /** @return краткое описание вида {@code GET /api/user -> 500 (1203 ms)}. */
    @JsonIgnore
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (method != null) {
            sb.append(method).append(' ');
        }
        if (url != null) {
            sb.append(url);
        }
        if (status != null) {
            sb.append(" -> ").append(status);
            if (statusText != null && !statusText.isBlank()) {
                sb.append(' ').append(statusText);
            }
        }
        if (durationMs != null) {
            sb.append(" (").append(durationMs).append(" ms)");
        }
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return summary();
    }

    /** Builder для {@link HttpExchange}. */
    public static final class Builder {
        private Direction direction;
        private String method;
        private String url;
        private String host;
        private Integer status;
        private String statusText;
        private Long durationMs;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;

        public Builder direction(Direction v) {
            this.direction = v;
            return this;
        }

        public Builder method(String v) {
            this.method = v;
            return this;
        }

        public Builder url(String v) {
            this.url = v;
            return this;
        }

        public Builder host(String v) {
            this.host = v;
            return this;
        }

        public Builder status(Integer v) {
            this.status = v;
            return this;
        }

        public Builder statusText(String v) {
            this.statusText = v;
            return this;
        }

        public Builder durationMs(Long v) {
            this.durationMs = v;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> v) {
            if (v != null) {
                this.headers.putAll(v);
            }
            return this;
        }

        public Builder body(String v) {
            this.body = v;
            return this;
        }

        public HttpExchange build() {
            return new HttpExchange(this);
        }
    }
}
