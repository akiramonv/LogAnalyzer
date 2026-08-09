package io.github.loganalyzer.core.rules;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.loganalyzer.core.model.AnnotationType;

import java.util.ArrayList;
import java.util.List;

/**
 * Правило анализа: условие + что сделать при срабатывании (пометить событие и/или
 * выдвинуть гипотезу о причине).
 *
 * <p>Пример:
 * <pre>
 * - name: hikari-pool-timeout
 *   description: Исчерпан пул соединений HikariCP
 *   when:
 *     messageRegex: "Connection is not available, request timed out after"
 *   annotate:
 *     - type: DATABASE
 *       label: Пул соединений исчерпан
 *   cause:
 *     title: Исчерпан пул соединений к БД
 *     confidence: 0.85
 *     recommendation: Проверьте долгие транзакции и размер пула (maximumPoolSize)
 * </pre>
 */
public final class Rule {

    /** Что навесить на событие при срабатывании. */
    public static final class AnnotationSpec {
        private AnnotationType type = AnnotationType.INFO;
        private String label;

        public AnnotationType getType() {
            return type;
        }

        public void setType(AnnotationType v) {
            this.type = v == null ? AnnotationType.INFO : v;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String v) {
            this.label = v;
        }
    }

    /** Гипотеза о причине, выдвигаемая правилом. */
    public static final class CauseSpec {
        private String title;
        private String description;
        private String category;
        private double confidence = 0.7;
        private String recommendation;

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            this.title = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String v) {
            this.category = v;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double v) {
            this.confidence = v;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public void setRecommendation(String v) {
            this.recommendation = v;
        }
    }

    /** Дополнительное условие «раньше в этом же таймлайне было событие X». */
    public static final class PrecededBy {
        private Condition when = new Condition();
        /** Окно поиска предшествующего события в секундах (0 — весь таймлайн). */
        private long withinSeconds;

        public Condition getWhen() {
            return when;
        }

        public void setWhen(Condition v) {
            this.when = v == null ? new Condition() : v;
        }

        public long getWithinSeconds() {
            return withinSeconds;
        }

        public void setWithinSeconds(long v) {
            this.withinSeconds = Math.max(0, v);
        }
    }

    private String name;
    private String description;
    private boolean enabled = true;
    /** Больший приоритет — правило применяется раньше и его гипотеза весомее при равной уверенности. */
    private int priority;
    private Condition when = new Condition();
    private PrecededBy precededBy;
    private List<AnnotationSpec> annotate = new ArrayList<>();
    private CauseSpec cause;
    /** Не применять последующие правила к этому событию. */
    private boolean stopOnMatch;

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int v) {
        this.priority = v;
    }

    public Condition getWhen() {
        return when;
    }

    public void setWhen(Condition v) {
        this.when = v == null ? new Condition() : v;
    }

    public PrecededBy getPrecededBy() {
        return precededBy;
    }

    public void setPrecededBy(PrecededBy v) {
        this.precededBy = v;
    }

    public List<AnnotationSpec> getAnnotate() {
        return annotate;
    }

    public void setAnnotate(List<AnnotationSpec> v) {
        this.annotate = v == null ? new ArrayList<>() : v;
    }

    public CauseSpec getCause() {
        return cause;
    }

    public void setCause(CauseSpec v) {
        this.cause = v;
    }

    public boolean isStopOnMatch() {
        return stopOnMatch;
    }

    public void setStopOnMatch(boolean v) {
        this.stopOnMatch = v;
    }

    /** @return {@code true} если правило корректно (есть имя и непустое условие). */
    @JsonIgnore
    public boolean isValid() {
        return name != null && !name.isBlank() && when != null && !when.isEmpty();
    }

    @Override
    public String toString() {
        return name;
    }
}
