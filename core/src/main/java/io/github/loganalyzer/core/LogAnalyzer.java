package io.github.loganalyzer.core;

import io.github.loganalyzer.core.analyze.HeuristicRootCauseAnalyzer;
import io.github.loganalyzer.core.analyze.RootCauseAnalyzer;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import io.github.loganalyzer.core.correlate.Correlator;
import io.github.loganalyzer.core.correlate.EventGroup;
import io.github.loganalyzer.core.learn.FeedbackStore;
import io.github.loganalyzer.core.learn.IncidentSignature;
import io.github.loganalyzer.core.learn.LearningRootCauseAnalyzer;
import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.RootCause;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.parse.LogIngestor;
import io.github.loganalyzer.core.parse.ParseOptions;
import io.github.loganalyzer.core.search.RequisiteFilter;
import io.github.loganalyzer.core.rules.RuleEngine;
import io.github.loganalyzer.core.rules.RuleSet;
import io.github.loganalyzer.core.rules.RuleSetLoader;
import io.github.loganalyzer.core.timeline.TimelineBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Фасад анализа: принимает источники логов и возвращает готовый отчёт.
 *
 * <p>Конвейер: разбор → корреляция → построение таймлайнов → правила → анализ первопричины →
 * поправка на прошлые оценки пользователя (см. {@link io.github.loganalyzer.core.learn}).
 * Класс намеренно не зависит ни от CLI, ни от UI — его одинаково использует
 * консольная команда и плагин IDE.
 *
 * <pre>
 * AnalysisReport report = new LogAnalyzer(AnalyzerConfig.defaults())
 *         .analyzeFiles(List.of(Path.of("app.log")));
 * </pre>
 */
public final class LogAnalyzer {

    private final AnalyzerConfig config;
    private final ParseOptions parseOptions;
    private final Correlator correlator;
    private final TimelineBuilder timelineBuilder;
    private final RuleEngine ruleEngine;
    private final RootCauseAnalyzer rootCauseAnalyzer;
    private final FeedbackStore feedbackStore;
    private final int maxAlternatives;

    public LogAnalyzer() {
        this(AnalyzerConfig.defaults());
    }

    public LogAnalyzer(AnalyzerConfig config) {
        this(config, loadRules(config), null);
    }

    /**
     * @param config            конфигурация
     * @param ruleSet           набор правил (если {@code null} — берутся правила из конфигурации)
     * @param rootCauseAnalyzer анализатор причин (если {@code null} — эвристический)
     */
    public LogAnalyzer(AnalyzerConfig config, RuleSet ruleSet, RootCauseAnalyzer rootCauseAnalyzer) {
        this.config = config == null ? AnalyzerConfig.defaults() : config;
        this.parseOptions = this.config.toParseOptions();
        this.correlator = new Correlator(this.config.toCorrelationOptions());
        this.timelineBuilder = new TimelineBuilder(this.config.toTimelineOptions());
        this.ruleEngine = new RuleEngine(ruleSet == null ? loadRules(this.config) : ruleSet);
        RootCauseAnalyzer base = rootCauseAnalyzer == null
                ? new HeuristicRootCauseAnalyzer(this.config.toAnalysisOptions())
                : rootCauseAnalyzer;
        this.feedbackStore = this.config.getLearning().isEnabled()
                ? FeedbackStore.forFile(this.config.learningFile())
                : null;
        // Обучение — надстройка над любым анализатором причин: сначала обычный разбор,
        // затем поправка на то, что пользователь уже говорил о таких же инцидентах.
        this.rootCauseAnalyzer = feedbackStore == null
                ? base
                : new LearningRootCauseAnalyzer(base, feedbackStore,
                        this.config.getAnalysis().getMinConfidence());
        this.maxAlternatives = this.config.getAnalysis().getMaxAlternatives();
    }

    private static RuleSet loadRules(AnalyzerConfig config) {
        AnalyzerConfig effective = config == null ? AnalyzerConfig.defaults() : config;
        RuleSetLoader loader = new RuleSetLoader();
        List<Path> files = new ArrayList<>();
        for (String file : effective.getRules().getFiles()) {
            files.add(Path.of(file));
        }
        return loader.loadAll(files, effective.getRules().isBuiltin());
    }

    public RuleEngine getRuleEngine() {
        return ruleEngine;
    }

    /** @return память отзывов, применяемая при анализе, либо {@code null}, если обучение выключено. */
    public FeedbackStore getFeedbackStore() {
        return feedbackStore;
    }

    public AnalyzerConfig getConfig() {
        return config;
    }

    /** Анализирует список файлов логов. */
    public AnalysisReport analyzeFiles(List<Path> files) {
        AnalysisReport report = new AnalysisReport();
        List<LogEvent> events = parseFiles(files, report);
        return analyzeEvents(events, report);
    }

    /**
     * Только разбор файлов, без корреляции и анализа.
     * Нужен, когда события требуется отфильтровать (по времени, уровню, traceId)
     * перед построением таймлайнов.
     *
     * @param files  файлы логов
     * @param report отчёт, куда пишутся предупреждения и статистика разбора
     * @return разобранные события в порядке чтения
     */
    public List<LogEvent> parseFiles(List<Path> files, AnalysisReport report) {
        List<LogEvent> events = new ArrayList<>();
        LogIngestor ingestor = new LogIngestor(parseOptions);

        for (Path file : files) {
            report.getInputs().add(file.toString());
            try {
                LogIngestor.Result result = ingestor.ingest(file);
                events.addAll(result.getEvents());
                result.getWarnings().forEach(report::addWarning);
                report.getSummary().setUnparsedLines(
                        report.getSummary().getUnparsedLines() + result.getUnparsedLines());
                if (result.getEvents().isEmpty()) {
                    report.addWarning("Файл не содержит распознанных записей: " + file);
                }
            } catch (IOException e) {
                report.addWarning("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        report.getSummary().setFilesAnalyzed(files.size());
        return events;
    }

    /** Итог отбора строк по реквизиту: события и сколько строк пришлось просмотреть. */
    public record MatchedLines(List<LogEvent> events, int scannedLines, int keptLines) {
    }

    /**
     * Разбирает только те строки файлов, которые связаны с указанными значениями
     * (ИНН, идентификатор платежа, номер телефона, ФИО).
     *
     * <p>Отбор идёт по тексту, до разбора: дневной лог платёжного шлюза — десятки мегабайт,
     * и строить таймлайны по всему файлу ради одной операции незачем. Окончательный отбор
     * цепочек делает {@link io.github.loganalyzer.core.search.RequisiteSearch}.
     *
     * @param files  файлы логов
     * @param values искомые значения
     * @param report отчёт, куда пишутся предупреждения
     */
    public MatchedLines parseMatchingLines(List<Path> files, List<String> values, AnalysisReport report) {
        AnalysisReport target = report == null ? new AnalysisReport() : report;
        List<LogEvent> events = new ArrayList<>();
        int limit = RequisiteFilter.defaultMaxLines();
        int scanned = 0;
        int kept = 0;

        for (Path file : files) {
            RequisiteFilter.Result filtered;
            try {
                filtered = RequisiteFilter.of(
                        file, parseOptions.getCharset(), values, Math.max(0, limit - kept));
            } catch (IOException e) {
                target.addWarning("Не удалось прочитать " + file + ": " + e.getMessage());
                continue;
            }
            scanned += filtered.scannedLines();
            kept += filtered.keptLines();
            if (filtered.truncated()) {
                target.addWarning("Слишком много совпадений в " + file + " — взяты первые "
                        + limit + " строк. Уточните значение поиска.");
            }
            if (filtered.isEmpty()) {
                continue;
            }
            target.getInputs().add(file.toString());
            events.addAll(parseText(filtered.text(), file.toString(), target));
        }
        target.getSummary().setFilesAnalyzed(files.size());
        return new MatchedLines(events, scanned, kept);
    }

    /** Анализирует поток логов (используется для stdin). */
    public AnalysisReport analyzeStream(Reader reader, String sourceName) {
        AnalysisReport report = new AnalysisReport();
        return analyzeEvents(parseStream(reader, sourceName, report), report);
    }

    /** Только разбор потока — чтобы события можно было отфильтровать перед анализом. */
    public List<LogEvent> parseStream(Reader reader, String sourceName, AnalysisReport report) {
        AnalysisReport target = report == null ? new AnalysisReport() : report;
        target.getInputs().add(sourceName);
        target.getSummary().setFilesAnalyzed(1);
        try {
            LogIngestor.Result result = new LogIngestor(parseOptions).ingest(reader, sourceName);
            result.getWarnings().forEach(target::addWarning);
            target.getSummary().setUnparsedLines(result.getUnparsedLines());
            return result.getEvents();
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка чтения потока " + sourceName, e);
        }
    }

    /** Анализирует текст логов: вставленный фрагмент, содержимое буфера обмена, тело письма. */
    public AnalysisReport analyzeText(String text, String sourceName) {
        AnalysisReport report = new AnalysisReport();
        return analyzeEvents(parseText(text, sourceName, report), report);
    }

    /**
     * Только разбор текста, без корреляции и анализа — чтобы события можно было
     * отфильтровать перед построением таймлайнов.
     */
    public List<LogEvent> parseText(String text, String sourceName, AnalysisReport report) {
        AnalysisReport target = report == null ? new AnalysisReport() : report;
        target.getInputs().add(sourceName);
        target.getSummary().setFilesAnalyzed(1);
        LogIngestor.Result result = new LogIngestor(parseOptions).ingestText(text, sourceName);
        result.getWarnings().forEach(target::addWarning);
        target.getSummary().setUnparsedLines(result.getUnparsedLines());
        return result.getEvents();
    }

    /** Полный конвейер для уже разобранных событий. */
    public AnalysisReport analyzeEvents(List<LogEvent> events, AnalysisReport report) {
        AnalysisReport result = report == null ? new AnalysisReport() : report;
        fillEventSummary(events, result);
        if (feedbackStore != null && feedbackStore.getLoadWarning() != null) {
            result.addWarning(feedbackStore.getLoadWarning());
        }

        for (EventGroup group : correlator.correlate(events)) {
            Timeline timeline = timelineBuilder.build(group);
            // Сигнатура нужна и обучению, и интерфейсу: по ней отзыв привязывается к инциденту.
            timeline.setSignature(IncidentSignature.of(timeline));
            List<RuleEngine.RuleHit> hits = ruleEngine.apply(timeline);
            List<RootCause> causes = rootCauseAnalyzer.analyze(timeline, hits);
            if (!causes.isEmpty()) {
                timeline.setRootCause(causes.get(0));
                for (int i = 1; i < causes.size() && i <= maxAlternatives; i++) {
                    timeline.getAlternatives().add(causes.get(i));
                }
            }
            result.getTimelines().add(timeline);
        }

        result.getSummary().setTimelines(result.getTimelines().size());
        result.getSummary().setFailedTimelines(
                (int) result.getTimelines().stream().filter(Timeline::isFailed).count());
        countRuleMarkedErrors(result);
        return result;
    }

    /**
     * Добавляет в сводку события, которые признаны ошибками правилами ({@code markError}).
     * Иначе шапка отчёта показывала бы «ошибок: 0» рядом с цепочкой, где ошибка есть:
     * отказ внешней системы записан в лог как обычное сообщение.
     */
    private static void countRuleMarkedErrors(AnalysisReport report) {
        int marked = 0;
        for (Timeline timeline : report.getTimelines()) {
            for (io.github.loganalyzer.core.model.TimelineEntry entry : timeline.getEntries()) {
                if (entry.isError() && !entry.getEvent().isError()) {
                    marked += entry.getRepeatCount();
                }
            }
        }
        report.getSummary().setErrorEvents(report.getSummary().getErrorEvents() + marked);
    }

    private static void fillEventSummary(List<LogEvent> events, AnalysisReport report) {
        int errors = 0;
        int warnings = 0;
        int exceptions = 0;
        for (LogEvent event : events) {
            if (event.isError()) {
                errors++;
            } else if (event.getLevel() == LogLevel.WARN) {
                warnings++;
            }
            if (event.hasException()) {
                exceptions++;
            }
        }
        report.getSummary().setTotalEvents(events.size());
        report.getSummary().setErrorEvents(errors);
        report.getSummary().setWarningEvents(warnings);
        report.getSummary().setExceptionEvents(exceptions);
    }
}
