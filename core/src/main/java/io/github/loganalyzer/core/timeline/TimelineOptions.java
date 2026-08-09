package io.github.loganalyzer.core.timeline;

import java.time.Duration;

/** Настройки построения таймлайна. */
public final class TimelineOptions {

    /** Схлопывать идущие подряд одинаковые события в одно с указанием числа повторов. */
    private boolean dedupEnabled = true;

    /** При сравнении событий заменять числа, UUID и hex-идентификаторы на плейсхолдеры. */
    private boolean dedupNormalize = true;

    /** Максимальный интервал между дублями, при котором они схлопываются. */
    private Duration dedupWindow = Duration.ofMinutes(5);

    /** Порог, выше которого операция помечается как медленная. */
    private long slowMs = 1000;

    /** Связывать HTTP-запрос с последующим ответом внутри таймлайна. */
    private boolean linkHttp = true;

    /** Пауза между соседними событиями, которая сама по себе считается подозрительной. */
    private Duration suspiciousGap = Duration.ofSeconds(5);

    public boolean isDedupEnabled() {
        return dedupEnabled;
    }

    public TimelineOptions setDedupEnabled(boolean v) {
        this.dedupEnabled = v;
        return this;
    }

    public boolean isDedupNormalize() {
        return dedupNormalize;
    }

    public TimelineOptions setDedupNormalize(boolean v) {
        this.dedupNormalize = v;
        return this;
    }

    public Duration getDedupWindow() {
        return dedupWindow;
    }

    public TimelineOptions setDedupWindow(Duration v) {
        if (v != null && !v.isNegative()) {
            this.dedupWindow = v;
        }
        return this;
    }

    public long getSlowMs() {
        return slowMs;
    }

    public TimelineOptions setSlowMs(long v) {
        this.slowMs = Math.max(0, v);
        return this;
    }

    public boolean isLinkHttp() {
        return linkHttp;
    }

    public TimelineOptions setLinkHttp(boolean v) {
        this.linkHttp = v;
        return this;
    }

    public Duration getSuspiciousGap() {
        return suspiciousGap;
    }

    public TimelineOptions setSuspiciousGap(Duration v) {
        if (v != null && !v.isNegative()) {
            this.suspiciousGap = v;
        }
        return this;
    }
}
