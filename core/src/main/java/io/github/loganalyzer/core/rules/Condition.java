package io.github.loganalyzer.core.rules;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.TimelineEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Условие срабатывания правила. Все заданные поля объединяются по «И»;
 * незаполненные поля не проверяются. Списки внутри поля объединяются по «ИЛИ».
 *
 * <p>Пример в YAML:
 * <pre>
 * when:
 *   exceptionType: "SocketTimeoutException|ReadTimeoutException"
 *   messageRegex: "(?i)timed? ?out"
 *   levelAtLeast: WARN
 * </pre>
 */
public final class Condition {

    /** Регулярное выражение по тексту события (сообщение + заголовки исключений + HTTP-сводка). */
    private String messageRegex;
    /** Любое из выражений (ИЛИ). */
    private List<String> anyMessageRegex = new ArrayList<>();
    /** Регулярное выражение по имени класса исключения в любом звене цепочки причин. */
    private String exceptionType;
    /** Регулярное выражение по сообщению исключения. */
    private String exceptionMessageRegex;
    /** Регулярное выражение по имени логгера. */
    private String loggerRegex;
    /** Регулярное выражение по имени потока. */
    private String threadRegex;
    /** Минимальный уровень события. */
    private LogLevel levelAtLeast;
    /** Точный уровень события. */
    private LogLevel level;
    /** Тип события. */
    private EventKind kind;
    /** Конкретные HTTP-статусы. */
    private List<Integer> httpStatus = new ArrayList<>();
    /** Класс статусов: {@code 4xx} или {@code 5xx}. */
    private String httpStatusClass;
    /** Условия по атрибутам/MDC: ключ -> регулярное выражение значения. */
    private Map<String, String> attributes = new LinkedHashMap<>();
    /** Минимальная длительность операции в миллисекундах. */
    private Long minDurationMs;
    /** Учитывать регистр в регулярных выражениях (по умолчанию нет). */
    private boolean caseSensitive;

    @JsonIgnore
    private final Map<String, Pattern> patternCache = new LinkedHashMap<>();

    public String getMessageRegex() {
        return messageRegex;
    }

    public void setMessageRegex(String v) {
        this.messageRegex = v;
    }

    public List<String> getAnyMessageRegex() {
        return anyMessageRegex;
    }

    public void setAnyMessageRegex(List<String> v) {
        this.anyMessageRegex = v == null ? new ArrayList<>() : v;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String v) {
        this.exceptionType = v;
    }

    public String getExceptionMessageRegex() {
        return exceptionMessageRegex;
    }

    public void setExceptionMessageRegex(String v) {
        this.exceptionMessageRegex = v;
    }

    public String getLoggerRegex() {
        return loggerRegex;
    }

    public void setLoggerRegex(String v) {
        this.loggerRegex = v;
    }

    public String getThreadRegex() {
        return threadRegex;
    }

    public void setThreadRegex(String v) {
        this.threadRegex = v;
    }

    public LogLevel getLevelAtLeast() {
        return levelAtLeast;
    }

    public void setLevelAtLeast(LogLevel v) {
        this.levelAtLeast = v;
    }

    public LogLevel getLevel() {
        return level;
    }

    public void setLevel(LogLevel v) {
        this.level = v;
    }

    public EventKind getKind() {
        return kind;
    }

    public void setKind(EventKind v) {
        this.kind = v;
    }

    public List<Integer> getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(List<Integer> v) {
        this.httpStatus = v == null ? new ArrayList<>() : v;
    }

    public String getHttpStatusClass() {
        return httpStatusClass;
    }

    public void setHttpStatusClass(String v) {
        this.httpStatusClass = v;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> v) {
        this.attributes = v == null ? new LinkedHashMap<>() : v;
    }

    public Long getMinDurationMs() {
        return minDurationMs;
    }

    public void setMinDurationMs(Long v) {
        this.minDurationMs = v;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean v) {
        this.caseSensitive = v;
    }

    /** @return {@code true} если условие пустое (ничего не проверяет). */
    @JsonIgnore
    public boolean isEmpty() {
        return messageRegex == null && anyMessageRegex.isEmpty() && exceptionType == null
                && exceptionMessageRegex == null && loggerRegex == null && threadRegex == null
                && levelAtLeast == null && level == null && kind == null
                && httpStatus.isEmpty() && httpStatusClass == null && attributes.isEmpty()
                && minDurationMs == null;
    }

    /** Проверяет условие на событии таймлайна. */
    public boolean matches(TimelineEntry entry) {
        return entry != null && matches(entry.getEvent());
    }

    /** Проверяет условие на событии. */
    public boolean matches(LogEvent event) {
        if (event == null || isEmpty()) {
            return false;
        }
        if (level != null && event.getLevel() != level) {
            return false;
        }
        if (levelAtLeast != null && !event.getLevel().isAtLeast(levelAtLeast)) {
            return false;
        }
        if (kind != null && event.getKind() != kind) {
            return false;
        }
        String text = event.searchableText();
        if (messageRegex != null && !find(messageRegex, text)) {
            return false;
        }
        if (!anyMessageRegex.isEmpty()) {
            boolean any = anyMessageRegex.stream().anyMatch(r -> find(r, text));
            if (!any) {
                return false;
            }
        }
        if (exceptionType != null && !matchesExceptionType(event.getException())) {
            return false;
        }
        if (exceptionMessageRegex != null && !matchesExceptionMessage(event.getException())) {
            return false;
        }
        if (loggerRegex != null && !find(loggerRegex, event.getLogger())) {
            return false;
        }
        if (threadRegex != null && !find(threadRegex, event.getThread())) {
            return false;
        }
        if (!httpStatus.isEmpty() || httpStatusClass != null) {
            HttpExchange http = event.getHttp();
            Integer status = http == null ? null : http.getStatus();
            if (status == null) {
                return false;
            }
            if (!httpStatus.isEmpty() && !httpStatus.contains(status)) {
                return false;
            }
            if (httpStatusClass != null && !matchesStatusClass(status, httpStatusClass)) {
                return false;
            }
        }
        if (minDurationMs != null && durationOf(event) < minDurationMs) {
            return false;
        }
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String value = event.getAttributes().get(e.getKey());
            if (value == null || !find(e.getValue(), value)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesExceptionType(ExceptionInfo exception) {
        if (exception == null) {
            return false;
        }
        for (ExceptionInfo e : exception.chain()) {
            if (find(exceptionType, e.getType())) {
                return true;
            }
            for (ExceptionInfo s : e.getSuppressed()) {
                if (find(exceptionType, s.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesExceptionMessage(ExceptionInfo exception) {
        if (exception == null) {
            return false;
        }
        for (ExceptionInfo e : exception.chain()) {
            if (find(exceptionMessageRegex, e.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesStatusClass(int status, String statusClass) {
        String c = statusClass.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (c) {
            case "1xx" -> status < 200;
            case "2xx" -> status >= 200 && status < 300;
            case "3xx" -> status >= 300 && status < 400;
            case "4xx" -> status >= 400 && status < 500;
            case "5xx" -> status >= 500;
            default -> false;
        };
    }

    private static long durationOf(LogEvent event) {
        if (event.getHttp() != null && event.getHttp().getDurationMs() != null) {
            return event.getHttp().getDurationMs();
        }
        String attr = event.getAttributes().get("durationMs");
        if (attr != null) {
            try {
                return Long.parseLong(attr);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean find(String regex, String text) {
        if (regex == null || text == null) {
            return false;
        }
        return pattern(regex).matcher(text).find();
    }

    private Pattern pattern(String regex) {
        return patternCache.computeIfAbsent(regex, r -> Pattern.compile(
                r, caseSensitive ? Pattern.DOTALL : Pattern.DOTALL | Pattern.CASE_INSENSITIVE));
    }
}
