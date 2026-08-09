package io.github.loganalyzer.core.rules;

import java.util.ArrayList;
import java.util.List;

/** Набор правил — содержимое одного rule-файла. */
public final class RuleSet {

    private String name = "rules";
    private String description;
    private List<Rule> rules = new ArrayList<>();

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

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> v) {
        this.rules = v == null ? new ArrayList<>() : v;
    }

    /** Объединяет два набора: правила второго добавляются к правилам первого. */
    public RuleSet merge(RuleSet other) {
        if (other != null) {
            this.rules.addAll(other.getRules());
        }
        return this;
    }

    public int size() {
        return rules.size();
    }

    @Override
    public String toString() {
        return name + " (" + rules.size() + " правил)";
    }
}
