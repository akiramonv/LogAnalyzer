package io.github.loganalyzer.core.analyze;

import io.github.loganalyzer.core.model.AnnotationType;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.RootCause;
import io.github.loganalyzer.core.model.StackFrame;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;
import io.github.loganalyzer.core.rules.Rule;
import io.github.loganalyzer.core.rules.RuleEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Определяет первопричину инцидента без обращения к внешним сервисам.
 *
 * <p>Работает на трёх сигналах, каждый из которых даёт свои гипотезы:
 * <ol>
 *   <li><b>Цепочка {@code Caused by}.</b> В Java верхнее исключение почти всегда обёртка;
 *       настоящая причина — самое глубокое звено цепочки, поэтому именно оно становится
 *       основным кандидатом, а место в прикладном коде берётся из его стека.</li>
 *   <li><b>Сработавшие правила.</b> Правило приносит готовую формулировку, категорию,
 *       рекомендацию и базовую уверенность.</li>
 *   <li><b>Форма таймлайна.</b> Первая ошибка в цепочке важнее последующих (каскад),
 *       а таймауты и повторы перед ней объясняют, почему сбой произошёл.</li>
 * </ol>
 *
 * <p>Гипотезы от разных сигналов, указывающие на одно и то же событие, объединяются,
 * а итоговая уверенность корректируется надёжностью корреляции: вывод по цепочке,
 * собранной по traceId, весомее вывода по цепочке, собранной по имени потока.
 */
public final class HeuristicRootCauseAnalyzer implements RootCauseAnalyzer {

    private final AnalysisOptions options;

    public HeuristicRootCauseAnalyzer() {
        this(new AnalysisOptions());
    }

    public HeuristicRootCauseAnalyzer(AnalysisOptions options) {
        this.options = options == null ? new AnalysisOptions() : options;
    }

    @Override
    public List<RootCause> analyze(Timeline timeline, List<RuleEngine.RuleHit> hits) {
        if (timeline == null || timeline.getEntries().isEmpty()) {
            return List.of();
        }
        Optional<TimelineEntry> firstError = timeline.firstError();
        if (firstError.isEmpty()) {
            return List.of();
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();

        collectRuleCandidates(timeline, hits, candidates);
        collectExceptionCandidate(timeline, firstError.get(), candidates);
        collectCascadeCandidate(timeline, firstError.get(), candidates);

        double reliability = timeline.getCorrelationKind().reliability();
        List<RootCause> result = new ArrayList<>();
        candidates.values().stream()
                .sorted(Comparator.comparingDouble((Candidate c) -> c.confidence).reversed())
                .forEach(c -> {
                    double adjusted = c.confidence * (0.85 + 0.15 * reliability);
                    if (adjusted >= options.getMinConfidence()) {
                        result.add(c.toRootCause(adjusted, timeline));
                    }
                });
        return result;
    }

    /** Гипотезы, принесённые правилами. */
    private void collectRuleCandidates(Timeline timeline,
                                       List<RuleEngine.RuleHit> hits,
                                       Map<String, Candidate> candidates) {
        if (hits == null) {
            return;
        }
        String firstErrorId = timeline.firstError().map(TimelineEntry::getId).orElse(null);
        for (RuleEngine.RuleHit hit : hits) {
            Rule.CauseSpec spec = hit.rule().getCause();
            if (spec == null || spec.getTitle() == null) {
                continue;
            }
            TimelineEntry entry = hit.entry();
            double confidence = spec.getConfidence();
            // Правило, сработавшее на первой ошибке цепочки, объясняет инцидент лучше,
            // чем сработавшее на её последствиях.
            if (entry.getId().equals(firstErrorId)) {
                confidence += 0.07;
            }
            if (!entry.isError()) {
                confidence -= 0.15;
            }
            confidence += Math.min(0.05, hit.rule().getPriority() / 2000.0);

            String key = "rule:" + hit.rule().getName() + ":" + entry.getId();
            Candidate candidate = new Candidate(
                    spec.getTitle(),
                    spec.getDescription(),
                    spec.getCategory(),
                    RootCause.Source.RULE,
                    hit.rule().getName(),
                    spec.getRecommendation(),
                    clamp(confidence),
                    entry);
            candidates.merge(key, candidate, Candidate::best);
        }
    }

    /** Гипотеза по самому глубокому звену цепочки {@code Caused by}. */
    private void collectExceptionCandidate(Timeline timeline,
                                           TimelineEntry firstError,
                                           Map<String, Candidate> candidates) {
        TimelineEntry entry = firstErrorWithException(timeline).orElse(firstError);
        LogEvent event = entry.getEvent();
        ExceptionInfo exception = event.getException();
        if (exception == null) {
            return;
        }
        ExceptionInfo root = exception.rootCause();
        boolean wrapped = root != exception;

        double confidence = 0.6;
        if (wrapped) {
            // Развёрнутая цепочка причин — сильный сигнал: причина названа явно
            confidence += 0.1;
        }
        StackFrame appFrame = findApplicationFrame(root);
        if (appFrame == null && wrapped) {
            appFrame = findApplicationFrame(exception);
        }
        if (appFrame != null) {
            confidence += 0.1;
        }
        if (entry.getId().equals(firstError.getId())) {
            confidence += 0.05;
        }

        StringBuilder description = new StringBuilder();
        if (wrapped) {
            description.append("Цепочка исключений: ");
            List<ExceptionInfo> chain = exception.chain();
            for (int i = 0; i < chain.size(); i++) {
                description.append(chain.get(i).simpleType());
                if (i < chain.size() - 1) {
                    description.append(" -> ");
                }
            }
            description.append(". ");
        }
        if (root.getMessage() != null && !root.getMessage().isBlank()) {
            description.append("Сообщение: ").append(oneLine(root.getMessage())).append(". ");
        }
        if (appFrame != null) {
            description.append("Место в коде: ").append(appFrame).append('.');
        }

        String title = root.simpleType()
                + (root.getMessage() == null || root.getMessage().isBlank()
                   ? "" : ": " + oneLine(root.getMessage()));

        Candidate candidate = new Candidate(
                title,
                description.toString().trim(),
                "exception",
                RootCause.Source.HEURISTIC,
                null,
                appFrame == null
                        ? "Изучите стек-трейс: верхний кадр показывает место возникновения ошибки."
                        : "Начните разбор с " + appFrame + " — это ближайший кадр прикладного кода.",
                clamp(confidence),
                entry);
        candidates.merge("exception:" + entry.getId(), candidate, Candidate::best);
    }

    /**
     * Гипотеза о каскаде: первая ошибка объясняет последующие, а предшествующие ей
     * таймауты/повторы/внешние вызовы объясняют её саму.
     */
    private void collectCascadeCandidate(Timeline timeline,
                                         TimelineEntry firstError,
                                         Map<String, Candidate> candidates) {
        if (!options.isDetectCascade()) {
            return;
        }
        int errorCount = timeline.getErrorCount();
        TimelineEntry trigger = findTrigger(timeline, firstError);

        if (trigger == null && errorCount <= 1) {
            return;
        }
        if (trigger != null) {
            LogEvent event = trigger.getEvent();
            String what = describeTrigger(trigger);
            double confidence = 0.55;
            if (trigger.hasAnnotation(AnnotationType.TIMEOUT)) {
                confidence += 0.15;
            }
            if (trigger.hasAnnotation(AnnotationType.RETRY)) {
                confidence += 0.05;
            }
            if (event.getHttp() != null && event.getHttp().isServerError()) {
                confidence += 0.1;
            }
            Candidate candidate = new Candidate(
                    what,
                    "Ошибка возникла после этого события; оно наиболее вероятно является пусковым.",
                    "cascade",
                    RootCause.Source.HEURISTIC,
                    null,
                    "Проверьте состояние внешней системы или ресурса, задействованного в этом шаге.",
                    clamp(confidence),
                    trigger);
            candidate.extraEvidence.add(firstError);
            candidates.merge("trigger:" + trigger.getId(), candidate, Candidate::best);
        }

        if (errorCount > 1) {
            Candidate candidate = new Candidate(
                    "Каскад ошибок: " + errorCount + " сбоев в одной цепочке",
                    "Первая ошибка цепочки: " + oneLine(firstError.getEvent().summary())
                            + ". Последующие ошибки, вероятно, являются её следствием.",
                    "cascade",
                    RootCause.Source.HEURISTIC,
                    null,
                    "Разбирайте первую ошибку — остальные обычно исчезают вместе с ней.",
                    clamp(0.5),
                    firstError);
            timeline.lastError().ifPresent(candidate.extraEvidence::add);
            candidates.merge("cascade:" + firstError.getId(), candidate, Candidate::best);
        }
    }

    /** Ищет событие-триггер: таймаут, внешний вызов или предупреждение непосредственно перед ошибкой. */
    private TimelineEntry findTrigger(Timeline timeline, TimelineEntry firstError) {
        List<TimelineEntry> entries = timeline.getEntries();
        int errorIndex = entries.indexOf(firstError);
        for (int i = errorIndex - 1; i >= 0 && i >= errorIndex - 10; i--) {
            TimelineEntry candidate = entries.get(i);
            if (candidate.hasAnnotation(AnnotationType.TIMEOUT)
                    || candidate.hasAnnotation(AnnotationType.RETRY)
                    || candidate.hasAnnotation(AnnotationType.HTTP_SERVER_ERROR)
                    || candidate.hasAnnotation(AnnotationType.RESILIENCE)) {
                return candidate;
            }
        }
        return null;
    }

    private static String describeTrigger(TimelineEntry entry) {
        LogEvent event = entry.getEvent();
        HttpExchange http = event.getHttp();
        if (entry.hasAnnotation(AnnotationType.TIMEOUT)) {
            return "Таймаут перед сбоем: " + oneLine(event.summary());
        }
        if (http != null && http.isFailure()) {
            return "Неуспешный внешний вызов: " + http.summary();
        }
        if (entry.hasAnnotation(AnnotationType.RETRY)) {
            return "Серия повторов перед сбоем: " + oneLine(event.summary());
        }
        return "Пусковое событие: " + oneLine(event.summary());
    }

    private static Optional<TimelineEntry> firstErrorWithException(Timeline timeline) {
        return timeline.getEntries().stream()
                .filter(e -> e.getEvent().getException() != null)
                .findFirst();
    }

    /** Ищет ближайший кадр прикладного кода в стеке исключения. */
    private StackFrame findApplicationFrame(ExceptionInfo exception) {
        if (exception == null) {
            return null;
        }
        for (StackFrame frame : exception.getFrames()) {
            if (frame.isApplicationFrame(options.getApplicationPackages())) {
                return frame;
            }
        }
        return exception.getFrames().isEmpty() ? null : exception.getFrames().get(0);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Промежуточная гипотеза до преобразования в {@link RootCause}. */
    private static final class Candidate {
        private final String title;
        private final String description;
        private final String category;
        private final RootCause.Source source;
        private final String rule;
        private final String recommendation;
        private final double confidence;
        private final TimelineEntry anchor;
        private final List<TimelineEntry> extraEvidence = new ArrayList<>();

        Candidate(String title, String description, String category, RootCause.Source source,
                  String rule, String recommendation, double confidence, TimelineEntry anchor) {
            this.title = title;
            this.description = description;
            this.category = category;
            this.source = source;
            this.rule = rule;
            this.recommendation = recommendation;
            this.confidence = confidence;
            this.anchor = anchor;
        }

        Candidate best(Candidate other) {
            return other.confidence > this.confidence ? other : this;
        }

        RootCause toRootCause(double adjustedConfidence, Timeline timeline) {
            RootCause.Builder b = RootCause.builder()
                    .title(title)
                    .description(description)
                    .category(category)
                    .source(source)
                    .rule(rule)
                    .recommendation(recommendation)
                    .confidence(adjustedConfidence)
                    .evidence(anchor);
            for (TimelineEntry e : extraEvidence) {
                if (!e.getId().equals(anchor.getId())) {
                    b.evidence(e);
                }
            }
            // Добавляем контекст: предшествующее событие помогает понять обстоятельства
            int index = timeline.getEntries().indexOf(anchor);
            if (index > 0) {
                b.evidence(timeline.getEntries().get(index - 1));
            }
            return b.build();
        }
    }
}
