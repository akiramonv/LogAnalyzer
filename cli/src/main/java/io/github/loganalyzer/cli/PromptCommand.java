package io.github.loganalyzer.cli;

import io.github.loganalyzer.core.LogAnalyzer;
import io.github.loganalyzer.core.analyze.IncidentPromptBuilder;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.Timeline;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Готовит запрос к языковой модели по конкретному инциденту.
 * Результат можно вставить в чат с моделью или передать в свой сервис —
 * сам анализатор при этом никуда не ходит по сети.
 */
@Command(
        name = "prompt",
        mixinStandardHelpOptions = true,
        description = "Сформировать запрос к языковой модели по выбранному инциденту.")
final class PromptCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, paramLabel = "PATH",
            description = "Файл, каталог или glob-шаблон с логами.")
    List<String> inputs = new ArrayList<>();

    @Option(names = {"-t", "--text"}, paramLabel = "TEXT",
            description = "Разобрать переданный фрагмент лога вместо файлов.")
    String text;

    @Option(names = "--include", paramLabel = "GLOB", split = ",",
            description = "Маски файлов при обходе каталога.")
    List<String> includes = new ArrayList<>(List.of("*.log", "*.txt", "*.json", "*.log.*"));

    @Option(names = {"-r", "--recursive"}, description = "Обходить подкаталоги.")
    boolean recursive;

    @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "Файл конфигурации анализа.")
    Path config;

    @Option(names = "--trace", paramLabel = "ID",
            description = "Идентификатор инцидента. По умолчанию берётся самый проблемный.")
    String trace;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Сохранить промпт в файл.")
    Path output;

    @Option(names = "--max-events", paramLabel = "N", description = "Максимум событий в описании инцидента.")
    int maxEvents = 60;

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
        LogAnalyzer analyzer = new LogAnalyzer(cfg);
        AnalysisReport report;

        if (text != null) {
            report = analyzer.analyzeText(text, "фрагмент");
        } else {
            if (inputs.isEmpty()) {
                err().println("Укажите источник логов: --input <путь> или --text \"<фрагмент>\".");
                return LogAnalyzerCli.EXIT_USAGE;
            }
            List<Path> files = InputCollector.collect(inputs, includes, recursive);
            if (files.isEmpty()) {
                err().println("Не найдено ни одного файла логов по указанным путям.");
                return LogAnalyzerCli.EXIT_USAGE;
            }
            report = analyzer.analyzeFiles(files);
        }

        Timeline timeline = selectTimeline(report);
        if (timeline == null) {
            err().println(trace == null
                    ? "Инцидентов с ошибками не найдено."
                    : "Инцидент с идентификатором, содержащим '" + trace + "', не найден.");
            return LogAnalyzerCli.EXIT_USAGE;
        }

        ZoneId zone = cfg.getParse().getZone() == null || cfg.getParse().getZone().isBlank()
                ? ZoneId.systemDefault()
                : ZoneId.of(cfg.getParse().getZone());
        IncidentPromptBuilder builder = new IncidentPromptBuilder(zone).setMaxEntries(maxEvents);

        String text = "=== Системная инструкция ===\n"
                + builder.systemPrompt()
                + "\n=== Запрос ===\n"
                + builder.userPrompt(timeline);

        if (output == null) {
            out().println(text);
        } else {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                out.write(text);
            }
            out().println("Промпт сохранён: " + output.toAbsolutePath());
        }
        return LogAnalyzerCli.EXIT_OK;
    }

    /** Выбирает инцидент: по идентификатору либо самый проблемный. */
    private Timeline selectTimeline(AnalysisReport report) {
        List<Timeline> timelines = report.getTimelines();
        if (trace != null && !trace.isBlank()) {
            return timelines.stream()
                    .filter(t -> t.getCorrelationId() != null && t.getCorrelationId().contains(trace))
                    .findFirst()
                    .orElse(null);
        }
        return timelines.stream()
                .filter(Timeline::isFailed)
                .max(Comparator.comparingInt(Timeline::getErrorCount))
                .orElse(null);
    }
}
