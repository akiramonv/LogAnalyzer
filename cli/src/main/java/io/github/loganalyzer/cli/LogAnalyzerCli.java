package io.github.loganalyzer.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Точка входа консольного анализатора логов.
 *
 * <pre>
 * log-analyzer ui
 * log-analyzer analyze -i app.log
 * log-analyzer analyze -i logs/ -f html -o report.html --only-failed
 * log-analyzer rules --list
 * log-analyzer patterns --test "2026-08-09T10:00:00.123  INFO 1 --- [main] c.e.App : started"
 * log-analyzer prompt -i app.log --trace 8f3c2a
 * log-analyzer feedback
 * </pre>
 */
@Command(
        name = "log-analyzer",
        mixinStandardHelpOptions = true,
        version = "log-analyzer 0.1.0",
        description = "Анализ логов Java/Spring-приложений: таймлайн инцидента и вероятная причина ошибки.",
        subcommands = {
                UiCommand.class,
                AnalyzeCommand.class,
                RulesCommand.class,
                PatternsCommand.class,
                PromptCommand.class,
                FeedbackCommand.class,
                CommandLine.HelpCommand.class
        })
public final class LogAnalyzerCli implements Runnable {

    /** Общие коды возврата: удобны для использования в CI. */
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 1;
    static final int EXIT_FAILURE = 2;
    /** Возвращается с ключом {@code --fail-on-error}, если найдены инциденты с ошибками. */
    static final int EXIT_INCIDENTS_FOUND = 3;

    @Option(names = {"-v", "--verbose"}, description = "Печатать стек-трейсы собственных ошибок анализатора.")
    boolean verbose;

    @Override
    public void run() {
        // Без подкоманды показываем справку
        CommandLine.usage(this, System.out);
    }

    /**
     * Собирает настроенный разборщик команд.
     * Используется и точкой входа, и тестами — чтобы поведение при ошибках совпадало.
     */
    static CommandLine createCommandLine() {
        LogAnalyzerCli app = new LogAnalyzerCli();
        return new CommandLine(app)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("Ошибка: " + describe(ex));
                    cmd.getErr().flush();
                    if (app.verbose) {
                        ex.printStackTrace(cmd.getErr());
                    }
                    return EXIT_FAILURE;
                })
                .setCaseInsensitiveEnumValuesAllowed(true);
    }

    public static void main(String[] args) {
        System.exit(createCommandLine().execute(args));
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
