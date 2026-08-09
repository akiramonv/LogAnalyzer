package io.github.loganalyzer.cli;

import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.parse.LogPattern;
import io.github.loganalyzer.core.parse.TimestampParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Отладка распознавания формата логов: какой шаблон сработал и что из строки извлеклось.
 * Это первое, что стоит запустить, если логи вашего приложения разбираются неправильно.
 */
@Command(
        name = "patterns",
        mixinStandardHelpOptions = true,
        description = "Показать встроенные шаблоны строк лога и проверить их на конкретной строке.")
final class PatternsCommand implements Callable<Integer> {

    @Option(names = {"-l", "--list"}, description = "Показать встроенные шаблоны.")
    boolean list;

    @Option(names = {"-t", "--test"}, paramLabel = "LINE",
            description = "Проверить строку лога: какой шаблон подойдёт и как она разберётся.")
    String test;

    @Option(names = "--regex", paramLabel = "REGEX",
            description = "Проверить собственный шаблон (регулярное выражение с именованными группами).")
    String regex;

    @Option(names = "--zone", paramLabel = "ZONE", description = "Часовой пояс для разбора меток времени.")
    String zone;

    @Spec
    CommandLine.Model.CommandSpec spec;

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    @Override
    public Integer call() {
        if (test == null && regex == null) {
            list = true;
        }
        if (list) {
            out().println("Встроенные шаблоны (применяются сверху вниз):");
            out().println("-".repeat(78));
            for (LogPattern pattern : LogPattern.builtins()) {
                out().println(pattern.name());
                out().println("    " + pattern.pattern().pattern());
            }
            out().println();
            out().println("Свой формат добавляется в конфигурацию:");
            out().println("""
                    parse:
                      patterns:
                        - name: my-format
                          regex: '^(?<ts>\\S+) (?<level>\\w+) (?<logger>\\S+) - (?<msg>.*)$'
                    """);
        }

        if (test != null) {
            TimestampParser timestamps = new TimestampParser(
                    zone == null ? ZoneId.systemDefault() : ZoneId.of(zone), java.time.LocalDate.now());

            List<LogPattern> patterns = regex == null
                    ? LogPattern.builtins()
                    : List.of(LogPattern.of("custom", regex));

            boolean matched = false;
            for (LogPattern pattern : patterns) {
                Optional<LogEvent.Builder> result = pattern.match(test, timestamps);
                if (result.isPresent()) {
                    LogEvent event = result.get().build();
                    out().println("Сработал шаблон: " + pattern.name());
                    out().println("  время:    " + event.getTimestamp());
                    out().println("  уровень:  " + event.getLevel());
                    out().println("  поток:    " + event.getThread());
                    out().println("  логгер:   " + event.getLogger());
                    out().println("  traceId:  " + event.getTraceId());
                    out().println("  spanId:   " + event.getSpanId());
                    out().println("  сервис:   " + event.getService());
                    out().println("  сообщение: " + event.getMessage());
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out().println("Ни один шаблон не подошёл. Строка будет обработана как продолжение "
                        + "предыдущей записи или как нераспознанная.");
                out().println("Добавьте свой шаблон через конфигурацию (см. --list).");
                return LogAnalyzerCli.EXIT_USAGE;
            }
        }
        return LogAnalyzerCli.EXIT_OK;
    }
}
