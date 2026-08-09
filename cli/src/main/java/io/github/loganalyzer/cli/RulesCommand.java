package io.github.loganalyzer.cli;

import io.github.loganalyzer.core.rules.Rule;
import io.github.loganalyzer.core.rules.RuleSet;
import io.github.loganalyzer.core.rules.RuleSetLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/** Работа с правилами: просмотр, проверка и выгрузка встроенного набора для правки. */
@Command(
        name = "rules",
        mixinStandardHelpOptions = true,
        description = "Просмотр, проверка и выгрузка правил анализа.")
final class RulesCommand implements Callable<Integer> {

    @Option(names = {"-l", "--list"}, description = "Показать список правил.")
    boolean list;

    @Option(names = "--validate", paramLabel = "FILE", description = "Проверить корректность файла правил.")
    Path validate;

    @Option(names = "--export", paramLabel = "FILE",
            description = "Сохранить встроенный набор правил в файл — удобно как основа для своего набора.")
    Path export;

    @Option(names = "--file", paramLabel = "FILE",
            description = "Показывать правила из указанного файла вместо встроенных.")
    Path file;

    @Option(names = "--details", description = "Печатать условия и рекомендации правил.")
    boolean details;

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
        RuleSetLoader loader = new RuleSetLoader();

        if (export != null) {
            try (InputStream in = RuleSetLoader.class.getResourceAsStream(RuleSetLoader.DEFAULT_RULES_RESOURCE)) {
                if (in == null) {
                    err().println("Встроенный набор правил не найден.");
                    return LogAnalyzerCli.EXIT_FAILURE;
                }
                if (export.getParent() != null) {
                    Files.createDirectories(export.getParent());
                }
                Files.write(export, in.readAllBytes());
            }
            out().println("Встроенные правила сохранены: " + export.toAbsolutePath());
            return LogAnalyzerCli.EXIT_OK;
        }

        if (validate != null) {
            RuleSet set = loader.load(validate);
            out().println("Файл корректен: " + validate + " — правил: " + set.size());
            return LogAnalyzerCli.EXIT_OK;
        }

        RuleSet set = file == null ? loader.loadDefaults() : loader.load(file);
        if (list || !details) {
            printList(set);
        } else {
            printDetails(set);
        }
        return LogAnalyzerCli.EXIT_OK;
    }

    private void printList(RuleSet set) {
        out().println("Набор: " + set.getName() + " (правил: " + set.size() + ")");
        out().println("-".repeat(78));
        List<Rule> rules = set.getRules().stream()
                .sorted(Comparator.comparingInt(Rule::getPriority).reversed())
                .toList();
        for (Rule rule : rules) {
            out().printf("%-26s prio=%-4d %s%s%n",
                    rule.getName(),
                    rule.getPriority(),
                    rule.getDescription() == null ? "" : rule.getDescription(),
                    rule.isEnabled() ? "" : "  [выключено]");
            if (details) {
                printRuleDetails(rule);
            }
        }
    }

    private void printDetails(RuleSet set) {
        out().println("Набор: " + set.getName() + " (правил: " + set.size() + ")");
        for (Rule rule : set.getRules()) {
            out().println();
            out().println("# " + rule.getName());
            if (rule.getDescription() != null) {
                out().println("  " + rule.getDescription());
            }
            printRuleDetails(rule);
        }
    }

    private void printRuleDetails(Rule rule) {
        var when = rule.getWhen();
        if (when.getExceptionType() != null) {
            out().println("    исключение: " + when.getExceptionType());
        }
        if (when.getMessageRegex() != null) {
            out().println("    сообщение:  " + when.getMessageRegex());
        }
        if (!when.getAnyMessageRegex().isEmpty()) {
            out().println("    сообщение (любое из): " + String.join(" | ", when.getAnyMessageRegex()));
        }
        if (when.getHttpStatusClass() != null) {
            out().println("    HTTP-статус: " + when.getHttpStatusClass());
        }
        if (when.getLevelAtLeast() != null) {
            out().println("    уровень >= " + when.getLevelAtLeast());
        }
        if (rule.getPrecededBy() != null) {
            out().println("    требует предшествующего события в пределах "
                    + rule.getPrecededBy().getWithinSeconds() + " с");
        }
        if (rule.getCause() != null) {
            out().println("    причина:    " + rule.getCause().getTitle()
                    + " (уверенность " + rule.getCause().getConfidence() + ")");
            if (rule.getCause().getRecommendation() != null) {
                out().println("    что делать: " + rule.getCause().getRecommendation().trim());
            }
        }
    }
}
