package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Разобранное исключение Java вместе с цепочкой {@code Caused by} и подавленными исключениями.
 * Ключевой элемент анализа: настоящая причина сбоя почти всегда находится в конце цепочки причин,
 * поэтому {@link #rootCause()} используется анализатором как основной кандидат.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class ExceptionInfo {

    private final String type;
    private final String message;
    private final List<StackFrame> frames;
    private final ExceptionInfo cause;
    private final List<ExceptionInfo> suppressed;
    /** Количество кадров, свёрнутых JVM в «... 42 more». */
    private final int framesOmitted;

    public ExceptionInfo(String type,
                         String message,
                         List<StackFrame> frames,
                         ExceptionInfo cause,
                         List<ExceptionInfo> suppressed,
                         int framesOmitted) {
        this.type = type;
        this.message = message;
        this.frames = frames == null ? List.of() : List.copyOf(frames);
        this.cause = cause;
        this.suppressed = suppressed == null ? List.of() : List.copyOf(suppressed);
        this.framesOmitted = framesOmitted;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public List<StackFrame> getFrames() {
        return frames;
    }

    public ExceptionInfo getCause() {
        return cause;
    }

    public List<ExceptionInfo> getSuppressed() {
        return suppressed;
    }

    public int getFramesOmitted() {
        return framesOmitted;
    }

    /** @return самое глубокое исключение в цепочке {@code Caused by} — обычно это и есть первопричина. */
    @JsonIgnore
    public ExceptionInfo rootCause() {
        ExceptionInfo current = this;
        // Защита от циклов на случай странных логов
        int guard = 0;
        while (current.cause != null && guard++ < 64) {
            current = current.cause;
        }
        return current;
    }

    /** @return вся цепочка исключений от внешнего к самому глубокому. */
    @JsonIgnore
    public List<ExceptionInfo> chain() {
        List<ExceptionInfo> result = new ArrayList<>();
        ExceptionInfo current = this;
        int guard = 0;
        while (current != null && guard++ < 64) {
            result.add(current);
            current = current.cause;
        }
        return Collections.unmodifiableList(result);
    }

    /** @return короткое имя класса исключения без пакета, например {@code NullPointerException}. */
    @JsonIgnore
    public String simpleType() {
        if (type == null) {
            return null;
        }
        int idx = type.lastIndexOf('.');
        return idx >= 0 ? type.substring(idx + 1) : type;
    }

    /** @return {@code Type: message} — строка для отчётов. */
    @JsonIgnore
    public String header() {
        return message == null || message.isBlank() ? String.valueOf(type) : type + ": " + message;
    }

    /** @return первый кадр стека (место возникновения), либо {@code null}. */
    @JsonIgnore
    public StackFrame topFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /** @return текстовое представление всей цепочки, пригодное для сравнения/дедупликации. */
    @JsonIgnore
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (ExceptionInfo e : chain()) {
            sb.append(e.type).append('|');
            StackFrame top = e.topFrame();
            if (top != null) {
                sb.append(top.declaringClass()).append('#').append(top.methodName())
                        .append(':').append(top.lineNumber());
            }
            sb.append(">>");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExceptionInfo other)) {
            return false;
        }
        return framesOmitted == other.framesOmitted
                && Objects.equals(type, other.type)
                && Objects.equals(message, other.message)
                && Objects.equals(frames, other.frames)
                && Objects.equals(cause, other.cause)
                && Objects.equals(suppressed, other.suppressed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, message, frames, cause, suppressed, framesOmitted);
    }

    @Override
    public String toString() {
        return header();
    }
}
