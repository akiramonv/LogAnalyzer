package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Гипотеза о первопричине инцидента с оценкой достоверности.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class RootCause {

    /** Откуда взялась гипотеза. */
    public enum Source {
        /** Встроенные эвристики (цепочка Caused by, каскад ошибок, таймауты). */
        HEURISTIC,
        /** Пользовательское или встроенное правило из rule-файла. */
        RULE,
        /** Языковая модель. */
        LLM,
        /** Формулировка, которую сам пользователь дал такому же инциденту раньше. */
        FEEDBACK
    }

    /** Ссылка на событие-доказательство. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(String entryId, String timestamp, String summary) {
    }

    /**
     * Отметка о том, что версия уже оценивалась человеком: сколько раз её подтверждали
     * и отвергали и не является ли она ответом самого пользователя.
     *
     * <p>Именно эта отметка отличает «анализатор так думает» от «это уже проверяли» —
     * в отчёте она показывается рядом с уверенностью.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Learned(int confirmations, int rejections, boolean taught) {
    }

    private final String title;
    private final String description;
    private final String category;
    private final double confidence;
    private final Source source;
    private final String rule;
    private final String recommendation;
    /** Конкретные шаги проверки и устранения — по порядку. */
    private final List<String> steps;
    private final List<Evidence> evidence;
    private final Learned learned;

    private RootCause(Builder b) {
        this.title = b.title;
        this.description = b.description;
        this.category = b.category;
        this.confidence = Math.max(0.0, Math.min(1.0, b.confidence));
        this.source = b.source == null ? Source.HEURISTIC : b.source;
        this.rule = b.rule;
        this.recommendation = b.recommendation;
        this.steps = List.copyOf(b.steps);
        this.evidence = List.copyOf(b.evidence);
        this.learned = b.learned;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public double getConfidence() {
        return confidence;
    }

    public Source getSource() {
        return source;
    }

    public String getRule() {
        return rule;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public List<String> getSteps() {
        return steps;
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }

    /** @return оценка человека для этой версии либо {@code null}, если её ещё не оценивали. */
    public Learned getLearned() {
        return learned;
    }

    /** @return строитель, заполненный полями этой гипотезы — для правки уверенности и пометок. */
    public Builder toBuilder() {
        return new Builder()
                .title(title)
                .description(description)
                .category(category)
                .confidence(confidence)
                .source(source)
                .rule(rule)
                .recommendation(recommendation)
                .steps(steps)
                .evidence(evidence)
                .learned(learned);
    }

    @Override
    public String toString() {
        return String.format("%s (%.2f)", title, confidence);
    }

    /** Builder для {@link RootCause}. */
    public static final class Builder {
        private String title;
        private String description;
        private String category;
        private double confidence;
        private Source source;
        private String rule;
        private String recommendation;
        private final List<String> steps = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private Learned learned;

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder category(String v) {
            this.category = v;
            return this;
        }

        public Builder confidence(double v) {
            this.confidence = v;
            return this;
        }

        public Builder source(Source v) {
            this.source = v;
            return this;
        }

        public Builder rule(String v) {
            this.rule = v;
            return this;
        }

        public Builder recommendation(String v) {
            this.recommendation = v;
            return this;
        }

        public Builder step(String v) {
            if (v != null && !v.isBlank()) {
                this.steps.add(v.trim());
            }
            return this;
        }

        public Builder steps(List<String> v) {
            if (v != null) {
                v.forEach(this::step);
            }
            return this;
        }

        public Builder evidence(TimelineEntry entry) {
            if (entry != null) {
                String ts = entry.getEvent().getTimestamp() == null
                        ? null : entry.getEvent().getTimestamp().toString();
                this.evidence.add(new Evidence(entry.getId(), ts, trim(entry.getEvent().summary())));
            }
            return this;
        }

        public Builder evidence(Evidence e) {
            if (e != null) {
                this.evidence.add(e);
            }
            return this;
        }

        public Builder evidence(List<Evidence> list) {
            if (list != null) {
                list.forEach(this::evidence);
            }
            return this;
        }

        public Builder learned(Learned v) {
            this.learned = v;
            return this;
        }

        private static String trim(String s) {
            if (s == null) {
                return null;
            }
            String oneLine = s.replace('\n', ' ').trim();
            return oneLine.length() > 200 ? oneLine.substring(0, 197) + "..." : oneLine;
        }

        public RootCause build() {
            return new RootCause(this);
        }
    }
}
