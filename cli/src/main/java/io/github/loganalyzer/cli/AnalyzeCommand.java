package io.github.loganalyzer.cli;

import io.github.loganalyzer.core.LogAnalyzer;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.report.HtmlReportWriter;
import io.github.loganalyzer.core.report.JsonReportWriter;
import io.github.loganalyzer.core.report.MermaidReportWriter;
import io.github.loganalyzer.core.report.ReportWriter;
import io.github.loganalyzer.core.report.TextReportWriter;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/** Основная команда: разбор логов, построение таймлайнов и вывод отчёта. */
@Command(
        name = "analyze",
        mixinStandardHelpOptions = true,
        description = "Разобрать логи, построить таймлайны инцидентов и определить вероятные причины.")
final class AnalyzeCommand implements Callable<Integer> {

    /** Формат вывода отчёта. */
    enum Format {
        text, json, html, mermaid
    }

    /** Источник данных: файлы/каталоги, поток или вставленный фрагмент. */
    static final class Source {
        @Option(names = {"-i", "--input"}, paramLabel = "PATH",
                description = "Файл, каталог или glob-шаблон с логами. Можно указывать несколько раз.")
        List<String> inputs = new ArrayList<>();

        @Option(names = "--stdin", description = "Читать логи со стандартного ввода (конвейер).")
        boolean stdin;

        @Option(names = {"-t", "--text"}, paramLabel = "TEXT",
                description = "Разобрать переданный фрагмент лога (одна или несколько строк, стек-трейс).")
        String text;

        @Option(names = {"-p", "--paste"},
                description = "Вставить фрагмент в консоль: ввод завершается Ctrl+Z и Enter (Windows) "
                        + "или Ctrl+D (Linux/macOS).")
        boolean paste;

        @Option(names = "--clipboard", description = "Взять фрагмент лога из буфера обмена.")
        boolean clipboard;
    }

    @ArgGroup(multiplicity = "1")
    Source source;

    @Option(names = "--include", paramLabel = "GLOB", split = ",",
            description = "Маски файлов при обходе каталога (по умолчанию: ${DEFAULT-VALUE}).")
    List<String> includes = new ArrayList<>(List.of("*.log", "*.txt", "*.json", "*.log.*"));

    @Option(names = {"-r", "--recursive"}, description = "Обходить подкаталоги.")
    boolean recursive;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE",
            description = "Файл для отчёта (по умолчанию — вывод в консоль).")
    Path output;

    @Option(names = {"-f", "--format"}, paramLabel = "FORMAT",
            description = "Формат отчёта: ${COMPLETION-CANDIDATES} (по умолчанию: ${DEFAULT-VALUE}).")
    Format format = Format.text;

    @Option(names = {"-c", "--config"}, paramLabel = "FILE",
            description = "Файл конфигурации анализа (YAML или JSON).")
    Path config;

    @Option(names = "--rules", paramLabel = "FILE", split = ",",
            description = "Дополнительные файлы правил (YAML или JSON).")
    List<Path> ruleFiles = new ArrayList<>();

    @Option(names = "--no-builtin-rules", description = "Не использовать встроенный набор правил.")
    boolean noBuiltinRules;

    @Option(names = "--trace", paramLabel = "ID",
            description = "Показать только инциденты с этим идентификатором корреляции (можно часть строки).")
    String trace;

    @Option(names = "--since", paramLabel = "TIME",
            description = "Отбросить события раньше указанного времени (ISO-8601, напр. 2026-08-09T10:00:00).")
    String since;

    @Option(names = "--until", paramLabel = "TIME", description = "Отбросить события позже указанного времени.")
    String until;

    @Option(names = "--min-level", paramLabel = "LEVEL",
            description = "Отбросить события ниже уровня: TRACE, DEBUG, INFO, WARN, ERROR, FATAL.")
    LogLevel minLevel;

    @Option(names = "--only-failed", description = "Показывать только инциденты, в которых были ошибки.")
    boolean onlyFailed;

    @Option(names = "--top", paramLabel = "N",
            description = "Оставить N самых «тяжёлых» инцидентов (по числу ошибок, затем по длительности).")
    int top;

    @Option(names = "--max-events", paramLabel = "N",
            description = "Максимум событий на инцидент в текстовом отчёте (0 — без ограничения).")
    int maxEvents;

    @Option(names = "--no-stacktraces", description = "Не печатать стек-трейсы в текстовом отчёте.")
    boolean noStackTraces;

    @Option(names = "--charset", paramLabel = "NAME",
            description = "Кодировка логов (по умолчанию из конфигурации или UTF-8).")
    String charset;

    @Option(names = "--zone", paramLabel = "ZONE",
            description = "Часовой пояс логов, например Europe/Moscow.")
    String zone;

    @Option(names = "--app-package", paramLabel = "PKG", split = ",",
            description = "Пакеты прикладного кода — повышают точность поиска места ошибки.")
    List<String> appPackages = new ArrayList<>();

    @Option(names = "--fail-on-error",
            description = "Вернуть код 3, если найдены инциденты с ошибками (для CI).")
    boolean failOnError;

    @Option(names = "--no-learning",
            description = "Не учитывать прошлые оценки причин (разбор «с чистого листа»).")
    boolean noLearning;

    @Option(names = "--memory", paramLabel = "FILE",
            description = "Файл памяти с оценками причин (по умолчанию ~/.log-analyzer/feedback.json).")
    Path memory;

    @Spec
    CommandLine.Model.CommandSpec spec;

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private PrintWriter err() {
        return spec.commandLine().getErr();
    }

    @Override
    public Integer call() throws Exception {
        AnalyzerConfig cfg = config == null ? AnalyzerConfig.defaults() : AnalyzerConfig.load(config);
        applyOverrides(cfg);

        LogAnalyzer analyzer = new LogAnalyzer(cfg);
        AnalysisReport report = new AnalysisReport();
        List<LogEvent> events;

        String snippet = readSnippet(cfg);
        if (source.stdin) {
            events = analyzer.parseStream(
                    new InputStreamReader(System.in, cfg.toParseOptions().getCharset()), "stdin", report);
        } else if (snippet != null) {
            if (snippet.isBlank()) {
                err().println("Фрагмент пуст — анализировать нечего.");
                return LogAnalyzerCli.EXIT_USAGE;
            }
            events = analyzer.parseText(snippet, "фрагмент", report);
            warnIfNothingRecognised(events);
        } else {
            List<Path> files = InputCollector.collect(source.inputs, includes, recursive);
            if (files.isEmpty()) {
                err().println("Не найдено ни одного файла логов по указанным путям.");
                return LogAnalyzerCli.EXIT_USAGE;
            }
            events = analyzer.parseFiles(files, report);
        }

        report = analyzer.analyzeEvents(filterEvents(events), report);
        filterTimelines(report);
        write(report, cfg);

        if (failOnError && report.getSummary().getFailedTimelines() > 0) {
            return LogAnalyzerCli.EXIT_INCIDENTS_FOUND;
        }
        return LogAnalyzerCli.EXIT_OK;
    }

    /**
     * Возвращает вставленный фрагмент лога либо {@code null}, если разбирать нужно файлы или поток.
     * Поддерживаются три способа вставки: аргументом, вводом в консоль и из буфера обмена.
     */
    private String readSnippet(AnalyzerConfig cfg) throws IOException {
        if (source.text != null) {
            return source.text;
        }
        if (source.clipboard) {
            return readClipboard();
        }
        if (source.paste) {
            out().println("Вставьте фрагмент лога и завершите ввод: "
                    + "Ctrl+Z и Enter (Windows) либо Ctrl+D (Linux/macOS).");
            out().flush();
            return new String(System.in.readAllBytes(), cfg.toParseOptions().getCharset());
        }
        return null;
    }

    /** Читает текст из системного буфера обмена. */
    private String readClipboard() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data == null ? "" : data.toString();
        } catch (java.awt.HeadlessException e) {
            throw new IllegalStateException(
                    "Буфер обмена недоступен в этой среде — используйте --paste или --text.");
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать буфер обмена: " + e.getMessage(), e);
        }
    }

    /** Подсказывает, что делать, если формат вставленного фрагмента не распознан. */
    private void warnIfNothingRecognised(List<LogEvent> events) {
        boolean recognised = events.stream()
                .anyMatch(e -> e.getKind() != io.github.loganalyzer.core.model.EventKind.UNPARSED);
        if (!recognised && !events.isEmpty()) {
            err().println("Формат фрагмента не распознан ни одним шаблоном.");
            err().println("Проверьте строку командой: log-analyzer patterns --test \"<строка>\"");
            err().println("и при необходимости добавьте свой шаблон в конфигурацию (parse.patterns).");
        }
    }

    /** Переносит ключи командной строки в конфигурацию. */
    private void applyOverrides(AnalyzerConfig cfg) {
        if (charset != null) {
            cfg.getParse().setCharset(charset);
        }
        if (zone != null) {
            cfg.getParse().setZone(zone);
        }
        if (noBuiltinRules) {
            cfg.getRules().setBuiltin(false);
        }
        for (Path file : ruleFiles) {
            cfg.getRules().getFiles().add(file.toString());
        }
        if (!appPackages.isEmpty()) {
            cfg.getAnalysis().getApplicationPackages().addAll(appPackages);
        }
        if (noLearning) {
            cfg.getLearning().setEnabled(false);
        }
        if (memory != null) {
            cfg.getLearning().setFile(memory.toString());
        }
    }

    /** Фильтрация событий до построения таймлайнов. */
    private List<LogEvent> filterEvents(List<LogEvent> events) {
        return ReportFilters.filterEvents(events,
                ReportFilters.parseTime(since), ReportFilters.parseTime(until), minLevel);
    }

    /** Фильтрация и ранжирование готовых таймлайнов. */
    private void filterTimelines(AnalysisReport report) {
        ReportFilters.filterTimelines(report, trace, onlyFailed, top);
    }

    private void write(AnalysisReport report, AnalyzerConfig cfg) throws IOException {
        ZoneId reportZone = cfg.getParse().getZone() == null || cfg.getParse().getZone().isBlank()
                ? ZoneId.systemDefault()
                : ZoneId.of(cfg.getParse().getZone());
        ReportWriter writer = switch (format) {
            case json -> new JsonReportWriter(true);
            case html -> new HtmlReportWriter(reportZone).setOnlyFailed(onlyFailed);
            case mermaid -> new MermaidReportWriter(reportZone).setOnlyFailed(onlyFailed);
            case text -> new TextReportWriter(reportZone)
                    .setShowStackTraces(!noStackTraces)
                    .setMaxEntriesPerTimeline(maxEvents)
                    .setOnlyFailed(onlyFailed);
        };

        if (output == null) {
            writer.write(report, out());
            out().flush();
        } else {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            try (BufferedWriter file = Files.newBufferedWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(report, file);
            }
            out().println("Отчёт сохранён: " + output.toAbsolutePath());
        }
    }

}
