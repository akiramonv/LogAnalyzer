package io.github.loganalyzer.core.timeline;

import io.github.loganalyzer.core.correlate.EventGroup;
import io.github.loganalyzer.core.model.AnnotationType;
import io.github.loganalyzer.core.model.EventAnnotation;
import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Строит таймлайн инцидента из группы событий: упорядочивает по времени, схлопывает повторы,
 * навешивает базовые аннотации и связывает парные события (HTTP-запрос ↔ ответ).
 *
 * <p>Правила из rule-engine применяются позже — здесь ставятся только те пометки,
 * которые следуют непосредственно из структуры события (исключение, ошибка, внешний вызов,
 * медленная операция, подозрительная пауза).
 */
public final class TimelineBuilder {

    private final TimelineOptions options;

    public TimelineBuilder() {
        this(new TimelineOptions());
    }

    public TimelineBuilder(TimelineOptions options) {
        this.options = options == null ? new TimelineOptions() : options;
    }

    /** Строит таймлайн из группы событий. */
    public Timeline build(EventGroup group) {
        Timeline timeline = new Timeline(group.getKey(), group.getKind());

        List<LogEvent> ordered = new ArrayList<>(group.getEvents());
        ordered.sort(Comparator
                .comparing(LogEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(LogEvent::getSequence));

        int index = 0;
        TimelineEntry previous = null;
        String previousFingerprint = null;

        for (LogEvent event : ordered) {
            String fingerprint = EventFingerprint.of(event, options.isDedupNormalize());
            if (options.isDedupEnabled()
                    && previous != null
                    && fingerprint.equals(previousFingerprint)
                    && withinDedupWindow(previous, event)) {
                previous.addRepeat(event);
                continue;
            }
            TimelineEntry entry = new TimelineEntry("e" + (++index), event);
            annotate(entry);
            timeline.getEntries().add(entry);
            previous = entry;
            previousFingerprint = fingerprint;
        }

        computeOffsets(timeline);
        if (options.isLinkHttp()) {
            linkHttpPairs(timeline);
        }
        annotateRepeats(timeline);
        return timeline;
    }

    private boolean withinDedupWindow(TimelineEntry previous, LogEvent candidate) {
        Instant last = previous.getLastRepeatAt() != null
                ? previous.getLastRepeatAt()
                : previous.getEvent().getTimestamp();
        Instant current = candidate.getTimestamp();
        if (last == null || current == null) {
            return true;
        }
        return Duration.between(last, current).abs().compareTo(options.getDedupWindow()) <= 0;
    }

    /** Базовые аннотации, вытекающие из самой структуры события. */
    private void annotate(TimelineEntry entry) {
        LogEvent event = entry.getEvent();

        if (event.getException() != null) {
            String type = event.getException().simpleType();
            entry.addAnnotation(EventAnnotation.of(AnnotationType.EXCEPTION, type));
            var root = event.getException().rootCause();
            if (root != event.getException()) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.EXCEPTION,
                        "первопричина: " + root.simpleType()));
            }
        } else if (event.getLevel().isError()) {
            entry.addAnnotation(EventAnnotation.of(AnnotationType.ERROR, "ошибка"));
        } else if (event.getLevel() == LogLevel.WARN) {
            entry.addAnnotation(EventAnnotation.of(AnnotationType.WARNING, "предупреждение"));
        }

        HttpExchange http = event.getHttp();
        if (http != null) {
            // Внешним считаем вызов, у которого есть признак чужой системы: явное направление,
            // абсолютный URL или указанный хост. Иначе это входящий запрос к нам.
            boolean outbound = http.getDirection() == HttpExchange.Direction.OUTBOUND
                    || http.getHost() != null
                    || (http.getUrl() != null && http.getUrl().startsWith("http"));
            if (outbound) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.EXTERNAL_CALL, http.summary()));
            }
            if (http.isServerError()) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.HTTP_SERVER_ERROR,
                        "HTTP " + http.getStatus()));
            } else if (http.isClientError()) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.HTTP_CLIENT_ERROR,
                        "HTTP " + http.getStatus()));
            }
            if (http.getDurationMs() != null && http.getDurationMs() >= options.getSlowMs()) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.SLOW,
                        "длительность " + http.getDurationMs() + " мс"));
            }
        }

        String durationAttr = event.getAttributes().get("durationMs");
        if (durationAttr != null && !entry.hasAnnotation(AnnotationType.SLOW)) {
            try {
                long ms = Long.parseLong(durationAttr);
                if (ms >= options.getSlowMs()) {
                    entry.addAnnotation(EventAnnotation.of(AnnotationType.SLOW, "длительность " + ms + " мс"));
                }
            } catch (NumberFormatException ignored) {
                // некорректное значение — пропускаем
            }
        }
    }

    /** Проставляет смещения от начала таймлайна и паузы между событиями. */
    private void computeOffsets(Timeline timeline) {
        Instant start = timeline.getStart();
        Instant previousAt = null;
        for (TimelineEntry entry : timeline.getEntries()) {
            Instant at = entry.getEvent().getTimestamp();
            if (at == null) {
                continue;
            }
            if (start != null) {
                entry.setSinceStart(Duration.between(start, at));
            }
            if (previousAt != null) {
                Duration gap = Duration.between(previousAt, at);
                entry.setSincePrevious(gap);
                if (gap.compareTo(options.getSuspiciousGap()) >= 0) {
                    entry.addAnnotation(EventAnnotation.of(AnnotationType.SLOW,
                            "пауза " + formatDuration(gap) + " перед событием"));
                }
            }
            previousAt = entry.getLastRepeatAt() != null ? entry.getLastRepeatAt() : at;
        }
    }

    /** Связывает HTTP-запрос со следующим ответом в этом же таймлайне. */
    private void linkHttpPairs(Timeline timeline) {
        List<TimelineEntry> entries = timeline.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            TimelineEntry request = entries.get(i);
            if (request.getEvent().getKind() != EventKind.HTTP_REQUEST) {
                continue;
            }
            for (int j = i + 1; j < entries.size(); j++) {
                TimelineEntry candidate = entries.get(j);
                if (candidate.getEvent().getKind() == EventKind.HTTP_RESPONSE) {
                    request.relateTo(candidate.getId());
                    candidate.relateTo(request.getId());
                    break;
                }
            }
        }
    }

    /** Помечает схлопнутые серии повторов как ретраи. */
    private void annotateRepeats(Timeline timeline) {
        for (TimelineEntry entry : timeline.getEntries()) {
            if (entry.getRepeatCount() > 1) {
                entry.addAnnotation(EventAnnotation.of(AnnotationType.RETRY,
                        "повторов: " + entry.getRepeatCount()));
            }
        }
    }

    /** Человекочитаемая длительность: {@code 1,2 с} / {@code 350 мс}. */
    public static String formatDuration(Duration d) {
        if (d == null) {
            return "";
        }
        long millis = d.toMillis();
        if (millis < 1000) {
            return millis + " мс";
        }
        if (millis < 60_000) {
            return String.format(java.util.Locale.ROOT, "%.1f с", millis / 1000.0);
        }
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1000;
        return minutes + " мин " + seconds + " с";
    }
}
