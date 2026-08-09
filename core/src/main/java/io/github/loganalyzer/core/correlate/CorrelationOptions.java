package io.github.loganalyzer.core.correlate;

import io.github.loganalyzer.core.model.CorrelationKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Настройки группировки событий в инциденты. */
public final class CorrelationOptions {

    /** Порядок предпочтения корреляционных признаков. */
    private List<CorrelationKind> priority = new ArrayList<>(List.of(
            CorrelationKind.TRACE, CorrelationKind.REQUEST, CorrelationKind.SESSION, CorrelationKind.THREAD));

    /** Пауза, после которой события одного потока считаются разными инцидентами. */
    private Duration threadGap = Duration.ofSeconds(30);

    /**
     * Приписывать события без traceId к активному трейсу того же потока.
     * Помогает, когда MDC заполняется не с первой строки обработки запроса.
     */
    private boolean inheritTraceFromThread = true;

    /** Максимальный разрыв, в пределах которого работает наследование трейса по потоку. */
    private Duration inheritWindow = Duration.ofSeconds(10);

    /** Дополнительные ключи из attributes/MDC, которые тоже считаются корреляционными (напр. {@code orderId}). */
    private final List<String> additionalKeys = new ArrayList<>();

    /** Если корреляционных признаков нет вообще — объединять события источника в один таймлайн. */
    private boolean fallbackToFile = true;

    /** Отбрасывать таймлайны короче указанного числа событий (0 — не отбрасывать). */
    private int minEventsPerTimeline;

    public List<CorrelationKind> getPriority() {
        return priority;
    }

    public CorrelationOptions setPriority(List<CorrelationKind> v) {
        if (v != null && !v.isEmpty()) {
            this.priority = new ArrayList<>(v);
        }
        return this;
    }

    public Duration getThreadGap() {
        return threadGap;
    }

    public CorrelationOptions setThreadGap(Duration v) {
        if (v != null && !v.isNegative()) {
            this.threadGap = v;
        }
        return this;
    }

    public boolean isInheritTraceFromThread() {
        return inheritTraceFromThread;
    }

    public CorrelationOptions setInheritTraceFromThread(boolean v) {
        this.inheritTraceFromThread = v;
        return this;
    }

    public Duration getInheritWindow() {
        return inheritWindow;
    }

    public CorrelationOptions setInheritWindow(Duration v) {
        if (v != null && !v.isNegative()) {
            this.inheritWindow = v;
        }
        return this;
    }

    public List<String> getAdditionalKeys() {
        return additionalKeys;
    }

    public CorrelationOptions addAdditionalKey(String key) {
        if (key != null && !key.isBlank()) {
            additionalKeys.add(key.trim());
        }
        return this;
    }

    public boolean isFallbackToFile() {
        return fallbackToFile;
    }

    public CorrelationOptions setFallbackToFile(boolean v) {
        this.fallbackToFile = v;
        return this;
    }

    public int getMinEventsPerTimeline() {
        return minEventsPerTimeline;
    }

    public CorrelationOptions setMinEventsPerTimeline(int v) {
        this.minEventsPerTimeline = Math.max(0, v);
        return this;
    }
}
