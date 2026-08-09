package io.github.loganalyzer.core.rules;

import io.github.loganalyzer.core.model.EventAnnotation;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Применяет правила к таймлайну: навешивает аннотации и собирает сработки,
 * из которых анализатор причин строит гипотезы.
 *
 * <p>Правила проверяются в порядке убывания приоритета. Правило с {@code precededBy}
 * срабатывает только если раньше в этом же таймлайне (в пределах заданного окна)
 * встретилось событие, удовлетворяющее вложенному условию — так описываются сценарии
 * вида «ошибка после серии таймаутов».
 */
public final class RuleEngine {

    /** Факт срабатывания правила на конкретном событии. */
    public record RuleHit(Rule rule, TimelineEntry entry) {
    }

    private final List<Rule> rules;

    public RuleEngine(RuleSet ruleSet) {
        this(ruleSet == null ? List.of() : ruleSet.getRules());
    }

    public RuleEngine(List<Rule> rules) {
        List<Rule> active = new ArrayList<>();
        for (Rule rule : rules == null ? List.<Rule>of() : rules) {
            if (rule.isEnabled() && rule.isValid()) {
                active.add(rule);
            }
        }
        active.sort(Comparator.comparingInt(Rule::getPriority).reversed());
        this.rules = List.copyOf(active);
    }

    public List<Rule> getRules() {
        return rules;
    }

    /**
     * Применяет правила к таймлайну, изменяя его аннотации.
     *
     * @return список сработок в порядке следования событий
     */
    public List<RuleHit> apply(Timeline timeline) {
        List<RuleHit> hits = new ArrayList<>();
        if (timeline == null) {
            return hits;
        }
        List<TimelineEntry> entries = timeline.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            TimelineEntry entry = entries.get(i);
            for (Rule rule : rules) {
                if (!rule.getWhen().matches(entry)) {
                    continue;
                }
                if (rule.getPrecededBy() != null && !hasPreceding(entries, i, rule.getPrecededBy())) {
                    continue;
                }
                for (Rule.AnnotationSpec spec : rule.getAnnotate()) {
                    entry.addAnnotation(new EventAnnotation(
                            spec.getType(),
                            spec.getLabel() == null ? rule.getDescription() : spec.getLabel(),
                            rule.getName()));
                }
                hits.add(new RuleHit(rule, entry));
                if (rule.isStopOnMatch()) {
                    break;
                }
            }
        }
        return hits;
    }

    /** Проверяет наличие подходящего события до позиции {@code index}. */
    private static boolean hasPreceding(List<TimelineEntry> entries, int index, Rule.PrecededBy precededBy) {
        Instant current = entries.get(index).getEvent().getTimestamp();
        Duration window = precededBy.getWithinSeconds() > 0
                ? Duration.ofSeconds(precededBy.getWithinSeconds())
                : null;
        for (int i = index - 1; i >= 0; i--) {
            TimelineEntry candidate = entries.get(i);
            if (window != null && current != null && candidate.getEvent().getTimestamp() != null) {
                Duration distance = Duration.between(candidate.getEvent().getTimestamp(), current);
                if (distance.compareTo(window) > 0) {
                    return false;
                }
            }
            if (precededBy.getWhen().matches(candidate)) {
                return true;
            }
        }
        return false;
    }
}
