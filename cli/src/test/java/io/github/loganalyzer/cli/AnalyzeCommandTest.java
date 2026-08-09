package io.github.loganalyzer.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Команды консольного анализатора")
class AnalyzeCommandTest {

    private static final String LOG = """
            2026-08-09T10:04:11.204+03:00  INFO 1 --- [order-service] [nio-8080-exec-3] \
            [8f3c2a1b4d5e6f70,4b2a9c1d3e5f7a80] c.e.o.web.OrderController : Получен запрос POST /api/v1/orders
            2026-08-09T10:04:11.261+03:00 ERROR 1 --- [order-service] [nio-8080-exec-3] \
            [8f3c2a1b4d5e6f70,4b2a9c1d3e5f7a80] c.e.o.web.OrderController : Не удалось обработать заказ
            java.lang.NullPointerException: profile is null
            \tat com.example.orders.service.PricingService.applyDiscount(PricingService.java:88)
            """;

    /** Запускает CLI, перехватывая вывод. */
    private record Run(int exitCode, String out, String err) {
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = LogAnalyzerCli.createCommandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private Path writeLog(Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Files.writeString(file, LOG, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("analyze сохраняет JSON-отчёт в файл")
    void writesJsonReport(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir);
        Path report = dir.resolve("out/report.json");

        Run run = run("analyze", "-i", log.toString(), "-f", "json", "-o", report.toString());

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        assertThat(report).exists();
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(json).contains("\"correlationId\" : \"8f3c2a1b4d5e6f70\"");
        assertThat(json).contains("rootCause");
    }

    @Test
    @DisplayName("analyze --fail-on-error возвращает отдельный код для CI")
    void returnsSpecialCodeForCi(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir);

        Run withErrors = run("analyze", "-i", log.toString(), "-o",
                dir.resolve("r1.txt").toString(), "--fail-on-error");
        assertThat(withErrors.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_INCIDENTS_FOUND);

        Path clean = dir.resolve("clean.log");
        Files.writeString(clean, "2026-08-09T10:00:00 INFO com.example.App - всё хорошо\n", StandardCharsets.UTF_8);
        Run withoutErrors = run("analyze", "-i", clean.toString(), "-o",
                dir.resolve("r2.txt").toString(), "--fail-on-error");
        assertThat(withoutErrors.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
    }

    @Test
    @DisplayName("analyze --trace оставляет только нужный инцидент")
    void filtersByTrace(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir);
        Path report = dir.resolve("report.json");

        run("analyze", "-i", log.toString(), "-f", "json", "-o", report.toString(), "--trace", "8f3c2a");
        assertThat(Files.readString(report)).contains("8f3c2a1b4d5e6f70");

        run("analyze", "-i", log.toString(), "-f", "json", "-o", report.toString(), "--trace", "нет-такого");
        assertThat(Files.readString(report)).doesNotContain("8f3c2a1b4d5e6f70");
    }

    @Test
    @DisplayName("analyze -f html формирует самодостаточную страницу")
    void writesHtmlReport(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir);
        Path report = dir.resolve("report.html");

        run("analyze", "-i", log.toString(), "-f", "html", "-o", report.toString());

        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(html).startsWith("<!doctype html>").contains("NullPointerException");
    }

    @Test
    @DisplayName("Несуществующий путь приводит к понятной ошибке, а не к стеку")
    void reportsMissingInput(@TempDir Path dir) {
        Run run = run("analyze", "-i", dir.resolve("нет-такого.log").toString());

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_FAILURE);
        assertThat(run.err()).contains("Путь не найден");
    }

    @Test
    @DisplayName("analyze --text разбирает вставленный фрагмент лога")
    void analysesPastedSnippet() {
        Run run = run("analyze", "--text", LOG);

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        assertThat(run.out()).contains("Вероятная причина").contains("NullPointerException");
    }

    @Test
    @DisplayName("analyze --text разбирает голый стек-трейс без меток времени")
    void analysesBareStackTrace() {
        String snippet = """
                java.lang.IllegalStateException: Cannot open connection
                \tat com.example.Repo.load(Repo.java:20)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, \
                request timed out after 30001ms
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """;

        Run run = run("analyze", "--text", snippet);

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        assertThat(run.out()).contains("пул соединений");
    }

    @Test
    @DisplayName("Пустой фрагмент не анализируется")
    void rejectsEmptySnippet() {
        Run run = run("analyze", "--text", "   ");

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_USAGE);
        assertThat(run.err()).contains("Фрагмент пуст");
    }

    @Test
    @DisplayName("prompt --text работает с фрагментом без файлов")
    void buildsPromptFromSnippet() {
        Run run = run("prompt", "--text", LOG);

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        assertThat(run.out()).contains("Инцидент: TRACE 8f3c2a1b4d5e6f70");
    }

    @Test
    @DisplayName("rules --export выгружает встроенный набор правил")
    void exportsBuiltinRules(@TempDir Path dir) throws Exception {
        Path rules = dir.resolve("rules.yaml");

        Run run = run("rules", "--export", rules.toString());

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        assertThat(Files.readString(rules, StandardCharsets.UTF_8)).contains("null-pointer");
    }

    @Test
    @DisplayName("patterns --test показывает разбор строки")
    void testsPattern() {
        Run run = run("patterns", "--test",
                "2026-08-09T10:00:00.123+03:00  INFO 1 --- [app] [main] [aaaa,bbbb] c.e.App : Started");

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
    }

    @Test
    @DisplayName("prompt формирует запрос к языковой модели по инциденту")
    void buildsPrompt(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir);
        Path prompt = dir.resolve("prompt.txt");

        Run run = run("prompt", "-i", log.toString(), "-o", prompt.toString());

        assertThat(run.exitCode()).isEqualTo(LogAnalyzerCli.EXIT_OK);
        String text = Files.readString(prompt, StandardCharsets.UTF_8);
        assertThat(text).contains("Инцидент: TRACE 8f3c2a1b4d5e6f70");
        assertThat(text).contains("NullPointerException");
    }
}
