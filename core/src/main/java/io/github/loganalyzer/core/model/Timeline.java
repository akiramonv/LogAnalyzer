package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Хронологическая цепочка событий одного инцидента (одного traceId, запроса, сессии или потока)
 * вместе с выводом анализатора о причине.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class Timeline {

    private final String correlationId;
    private final CorrelationKind correlationKind;
    private final List<TimelineEntry> entries = new ArrayList<>();

    private RootCause rootCause;
    private final List<RootCause> alternatives = new ArrayList<>();

    public Timeline(String correlationId, CorrelationKind correlationKind) {
        this.correlationId = correlationId;
        this.correlationKind = correlationKind;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public CorrelationKind getCorrelationKind() {
        return correlationKind;
    }

    public List<TimelineEntry> getEntries() {
        return entries;
    }

    public RootCause getRootCause() {
        return rootCause;
    }

    public void setRootCause(RootCause v) {
        this.rootCause = v;
    }

    public List<RootCause> getAlternatives() {
        return alternatives;
    }

    public Instant getStart() {
        return entries.stream()
                .map(e -> e.getEvent().getTimestamp())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public Instant getEnd() {
        Instant last = null;
        for (TimelineEntry e : entries) {
            if (e.getLastRepeatAt() != null) {
                last = e.getLastRepeatAt();
            } else if (e.getEvent().getTimestamp() != null) {
                last = e.getEvent().getTimestamp();
            }
        }
        return last;
    }

    /** @return длительность инцидента в миллисекундах или {@code null}, если меток времени нет. */
    public Long getDurationMs() {
        Instant s = getStart();
        Instant e = getEnd();
        return (s == null || e == null) ? null : Duration.between(s, e).toMillis();
    }

    public int getEventCount() {
        return entries.stream().mapToInt(TimelineEntry::getRepeatCount).sum();
    }

    public int getErrorCount() {
        return (int) entries.stream().filter(TimelineEntry::isError).count();
    }

    public int getWarningCount() {
        return (int) entries.stream()
                .filter(e -> e.getEvent().getLevel() == LogLevel.WARN)
                .count();
    }

    /** @return {@code true} если в цепочке есть ошибки — такие таймлайны интересны в первую очередь. */
    public boolean isFailed() {
        return getErrorCount() > 0;
    }

    /** @return набор сервисов, участвовавших в инциденте (если в логах указано имя приложения). */
    public Set<String> getServices() {
        Set<String> services = new LinkedHashSet<>();
        for (TimelineEntry e : entries) {
            if (e.getEvent().getService() != null) {
                services.add(e.getEvent().getService());
            }
        }
        return services;
    }

    /** @return первое событие-ошибка в цепочке. */
    @JsonIgnore
    public Optional<TimelineEntry> firstError() {
        return entries.stream().filter(TimelineEntry::isError).findFirst();
    }

    /** @return последнее событие-ошибка в цепочке. */
    @JsonIgnore
    public Optional<TimelineEntry> lastError() {
        TimelineEntry found = null;
        for (TimelineEntry e : entries) {
            if (e.isError()) {
                found = e;
            }
        }
        return Optional.ofNullable(found);
    }

    @JsonIgnore
    public Optional<TimelineEntry> entryById(String id) {
        return entries.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    @Override
    public String toString() {
        return correlationKind + ":" + correlationId + " (" + entries.size() + " событий)";
    }
}
