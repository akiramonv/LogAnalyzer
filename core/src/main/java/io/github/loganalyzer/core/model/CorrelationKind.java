package io.github.loganalyzer.core.model;

/** По какому признаку события были объединены в один таймлайн. */
public enum CorrelationKind {
    /** По traceId (Micrometer Tracing / Sleuth / OpenTelemetry) — самый надёжный вариант. */
    TRACE,
    /** По идентификатору запроса (X-Request-Id, requestId в MDC). */
    REQUEST,
    /** По идентификатору сессии. */
    SESSION,
    /** По имени потока с разрезанием на инциденты по паузам. */
    THREAD,
    /** Все события файла (когда корреляционных признаков нет вообще). */
    FILE;

    /** @return насколько признаку можно доверять (влияет на итоговую уверенность гипотезы). */
    public double reliability() {
        return switch (this) {
            case TRACE -> 1.0;
            case REQUEST -> 0.95;
            case SESSION -> 0.8;
            case THREAD -> 0.65;
            case FILE -> 0.4;
        };
    }
}
