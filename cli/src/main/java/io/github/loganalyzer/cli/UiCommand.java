package io.github.loganalyzer.cli;

import io.github.loganalyzer.cli.ui.UiServer;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Запускает локальный веб-интерфейс: вставка отрывка, выбор файла, таймлайн и причина в браузере.
 * Сервер слушает только 127.0.0.1 — снаружи он недоступен.
 */
@Command(
        name = "ui",
        mixinStandardHelpOptions = true,
        description = "Открыть интерфейс анализатора в браузере (локальный сервер).")
final class UiCommand implements Callable<Integer> {

    /** Порт по умолчанию выбран так, чтобы редко пересекаться с рабочими сервисами. */
    private static final int DEFAULT_PORT = 8321;

    @Option(names = {"-p", "--port"}, paramLabel = "PORT",
            description = "Порт интерфейса (по умолчанию: ${DEFAULT-VALUE}). Если занят — будет выбран свободный.")
    int port = DEFAULT_PORT;

    @Option(names = "--no-browser", description = "Не открывать браузер автоматически.")
    boolean noBrowser;

    @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "Файл конфигурации анализа.")
    Path config;

    @Option(names = "--rules", paramLabel = "FILE", split = ",",
            description = "Дополнительные файлы правил.")
    List<Path> ruleFiles = new ArrayList<>();

    @Option(names = "--no-builtin-rules", description = "Не использовать встроенный набор правил.")
    boolean noBuiltinRules;

    @Option(names = "--app-package", paramLabel = "PKG", split = ",",
            description = "Пакеты прикладного кода — повышают точность поиска места ошибки.")
    List<String> appPackages = new ArrayList<>();

    @Option(names = "--zone", paramLabel = "ZONE", description = "Часовой пояс логов, например Europe/Moscow.")
    String zone;

    @Option(names = "--no-learning",
            description = "Не учитывать прошлые оценки причин и не предлагать оценивать разбор.")
    boolean noLearning;

    @Option(names = "--memory", paramLabel = "FILE",
            description = "Файл памяти с оценками причин (по умолчанию ~/.log-analyzer/feedback.json).")
    Path memory;

    @Spec
    CommandLine.Model.CommandSpec spec;

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    @Override
    public Integer call() throws Exception {
        AnalyzerConfig cfg = config == null ? AnalyzerConfig.defaults() : AnalyzerConfig.load(config);
        if (zone != null) {
            cfg.getParse().setZone(zone);
        }
        if (noBuiltinRules) {
            cfg.getRules().setBuiltin(false);
        }
        for (Path file : ruleFiles) {
            cfg.getRules().getFiles().add(file.toString());
        }
        cfg.getAnalysis().getApplicationPackages().addAll(appPackages);
        if (noLearning) {
            cfg.getLearning().setEnabled(false);
        }
        if (memory != null) {
            cfg.getLearning().setFile(memory.toString());
        }

        UiServer server = open(cfg);
        server.start();

        URI url = server.url();
        out().println("Интерфейс анализатора: " + url);
        out().println("Остановить: Ctrl+C");
        out().flush();

        if (!noBrowser) {
            openBrowser(url);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
        return LogAnalyzerCli.EXIT_OK;
    }

    /** Поднимает сервер на выбранном порту; если он занят — берёт любой свободный. */
    private UiServer open(AnalyzerConfig cfg) throws IOException {
        try {
            return new UiServer(cfg, port);
        } catch (BindException e) {
            out().println("Порт " + port + " занят — выбираю свободный.");
            return new UiServer(cfg, 0);
        }
    }

    /** Открывает браузер; если это не удалось, пользователь просто перейдёт по ссылке сам. */
    private void openBrowser(URI url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(url);
                return;
            }
        } catch (Exception ignored) {
            // ниже пробуем системную команду
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            List<String> command = os.contains("win")
                    ? List.of("rundll32", "url.dll,FileProtocolHandler", url.toString())
                    : os.contains("mac")
                    ? List.of("open", url.toString())
                    : List.of("xdg-open", url.toString());
            new ProcessBuilder(command).start();
        } catch (Exception ignored) {
            out().println("Откройте ссылку вручную: " + url);
        }
    }
}
