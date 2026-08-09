package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Событие в контексте таймлайна: исходная запись лога плюс всё, что о ней выяснил анализ —
 * аннотации, число схлопнутых повторов, задержка от предыдущего события, связи с другими событиями.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class TimelineEntry {

    /** Идентификатор внутри таймлайна, например {@code e12}. Используется в ссылках evidence/related. */
    private final String id;
    private final LogEvent event;
    private final List<EventAnnotation> annotations = new ArrayList<>();
    private final Set<String> relatedIds = new LinkedHashSet<>();

    /** Сколько одинаковых записей схлопнуто в эту (1 — повторов не было). */
    private int repeatCount = 1;
    /** Метка времени последнего из схлопнутых повторов. */
    private java.time.Instant lastRepeatAt;
    /** Время от начала таймлайна. */
    private Duration sinceStart;
    /** Пауза от предыдущего события таймлайна. */
    private Duration sincePrevious;

    public TimelineEntry(String id, LogEvent event) {
        this.id = id;
        this.event = event;
    }

    public String getId() {
        return id;
    }

    public LogEvent getEvent() {
        return event;
    }

    public List<EventAnnotation> getAnnotations() {
        return annotations;
    }

    public Set<String> getRelatedIds() {
        return relatedIds;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public java.time.Instant getLastRepeatAt() {
        return lastRepeatAt;
    }

    public Duration getSinceStart() {
        return sinceStart;
    }

    public Duration getSincePrevious() {
        return sincePrevious;
    }

    public void setSinceStart(Duration v) {
        this.sinceStart = v;
    }

    public void setSincePrevious(Duration v) {
        this.sincePrevious = v;
    }

    /** Учитывает ещё один дубликат этого события. */
    public void addRepeat(LogEvent duplicate) {
        this.repeatCount++;
        if (duplicate != null && duplicate.getTimestamp() != null) {
            this.lastRepeatAt = duplicate.getTimestamp();
        }
    }

    public void addAnnotation(EventAnnotation annotation) {
        if (annotation == null) {
            return;
        }
        boolean duplicate = annotations.stream()
                .anyMatch(a -> a.type() == annotation.type()
                        && java.util.Objects.equals(a.label(), annotation.label()));
        if (!duplicate) {
            annotations.add(annotation);
        }
    }

    public void relateTo(String otherId) {
        if (otherId != null && !otherId.equals(id)) {
            relatedIds.add(otherId);
        }
    }

    @JsonIgnore
    public boolean hasAnnotation(AnnotationType type) {
        return annotations.stream().anyMatch(a -> a.type() == type);
    }

    /**
     * По одной аннотации на тип — для отчётов, где важна читаемость.
     * Из нескольких пометок одного типа предпочитается пришедшая от правила:
     * она несёт осмысленную формулировку, а не общее «ошибка»/«предупреждение».
     */
    @JsonIgnore
    public List<EventAnnotation> distinctAnnotations() {
        java.util.Map<AnnotationType, EventAnnotation> best = new java.util.LinkedHashMap<>();
        for (EventAnnotation a : annotations) {
            best.merge(a.type(), a, (existing, candidate) -> {
                boolean existingFromRule = existing.rule() != null && !"builtin".equals(existing.rule());
                boolean candidateFromRule = candidate.rule() != null && !"builtin".equals(candidate.rule());
                if (existingFromRule != candidateFromRule) {
                    return existingFromRule ? existing : candidate;
                }
                return existing;
            });
        }
        return new ArrayList<>(best.values());
    }

    @JsonIgnore
    public boolean isError() {
        return event.isError();
    }

    @Override
    public String toString() {
        return id + " " + event;
    }
}
