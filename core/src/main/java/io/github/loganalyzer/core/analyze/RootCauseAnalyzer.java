package io.github.loganalyzer.core.analyze;

import io.github.loganalyzer.core.model.RootCause;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.rules.RuleEngine;

import java.util.List;

/** Стратегия определения первопричины инцидента по построенному таймлайну. */
public interface RootCauseAnalyzer {

    /**
     * Анализирует таймлайн.
     *
     * @param timeline таймлайн с уже применёнными правилами
     * @param hits     сработки правил на событиях этого таймлайна
     * @return гипотезы, отсортированные по убыванию уверенности (может быть пустым)
     */
    List<RootCause> analyze(Timeline timeline, List<RuleEngine.RuleHit> hits);
}
